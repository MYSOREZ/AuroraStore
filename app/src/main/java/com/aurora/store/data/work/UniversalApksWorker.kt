/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import com.aurora.extensions.isPAndAbove
import com.aurora.extensions.isQAndAbove
import com.aurora.extensions.isRAndAbove
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.network.IHttpClient
import com.aurora.Constants
import com.aurora.store.R
import com.aurora.store.data.AccountRepository
import com.aurora.store.data.helper.DownloadHelper
import com.aurora.store.data.model.AccountType
import com.aurora.store.data.model.DownloadStatus
import com.aurora.store.data.network.HttpClient
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.data.providers.NativeDeviceInfoProvider
import com.aurora.store.data.room.download.Download
import com.aurora.store.data.room.download.DownloadDao
import com.aurora.store.util.CertUtil
import com.aurora.store.util.PackageUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Worker that downloads ALL split APK variants (all ABIs and screen densities) for an app,
 * then bundles them into a single universal `.apks` ZIP file installable on any device.
 *
 * Strategy: Makes up to 10 purchase requests with different virtual device configurations
 * (varying `Platforms` / ABI and `Screen.Density`), deduplicates the returned files by name,
 * downloads everything, then compresses into `.apks` with maximum ZIP compression.
 *
 * Rate-limiting mitigation: the first purchase reuses the existing saved AuthData (no extra
 * network call). Subsequent purchases build a new AuthData; for anonymous accounts this calls
 * the dispenser — failures are caught and logged rather than aborting the whole job.
 *
 * The worker inserts a [Download] row into Room at startup so the app icon shows a progress
 * ring and the entry appears in the downloads list, matching the existing download UX.
 */
@HiltWorker
class UniversalApksWorker @AssistedInject constructor(
    private val authProvider: AuthProvider,
    private val accountRepository: AccountRepository,
    private val httpClient: IHttpClient,
    private val downloadDao: DownloadDao,
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UniversalApksWorker"

        private const val PACKAGE_NAME = "PACKAGE_NAME"
        private const val VERSION_CODE = "VERSION_CODE"
        private const val OFFER_TYPE = "OFFER_TYPE"
        private const val ACCOUNT_ID = "ACCOUNT_ID"
        private const val DISPLAY_NAME = "DISPLAY_NAME"
        private const val ICON_URL = "ICON_URL"
        // User-selected config (comma-separated strings / boolean)
        private const val SELECTED_ABIS = "SELECTED_ABIS"
        private const val SELECTED_DENSITIES = "SELECTED_DENSITIES"
        private const val SELECTED_LOCALES = "SELECTED_LOCALES"
        private const val INCLUDE_DFS = "INCLUDE_DFS"

        private const val NOTIFICATION_ID_FGS = 601
        private const val NOTIFICATION_ID_RESULT = 602
        private const val NOTIFICATION_ID_PROGRESS = 603

        private const val BUFFER_SIZE = 256 * 1024

        // Delay between dispenser calls to avoid rate-limiting (anonymous accounts only)
        private const val DISPENSER_DELAY_MS = 2000L

        // Retry a file download up to this many times on transient errors (timeout, reset).
        // Each retry resumes from the existing .tmp file via Range: bytes=N-, so no bytes are
        // re-downloaded; the delay just gives the server time to recover.
        private const val DOWNLOAD_MAX_RETRIES = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 4000L

        // Full lists used when building configs for each ABI / density sweep.
        // Only the user-selected subset is actually attempted.
        private val ALL_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "armeabi")
        private val ALL_DENSITIES = listOf(640, 480, 320, 240, 160, 120, 213)

        // Mapping from density dpi to the label used in split file names
        // (e.g. 640 → "xxxhdpi" → "config.xxxhdpi.apk")
        private val DENSITY_LABEL = mapOf(
            640 to "xxxhdpi", 480 to "xxhdpi", 320 to "xhdpi",
            240 to "hdpi", 160 to "mdpi", 120 to "ldpi", 213 to "tvdpi"
        )

        fun enqueue(
            context: Context,
            app: App,
            accountId: String,
            selectedAbis: Set<String> = setOf("arm64-v8a", "armeabi-v7a"),
            selectedDensities: Set<Int> = ALL_DENSITIES.toSet(),
            selectedLocales: Set<String> = setOf("en"),
            includeDynamicFeatures: Boolean = true
        ) {
            val data = Data.Builder()
                .putString(PACKAGE_NAME, app.packageName)
                .putLong(VERSION_CODE, app.versionCode)
                .putInt(OFFER_TYPE, app.offerType)
                .putString(ACCOUNT_ID, accountId)
                .putString(DISPLAY_NAME, app.displayName)
                .putString(ICON_URL, app.iconArtwork.url)
                .putString(SELECTED_ABIS, selectedAbis.joinToString(","))
                .putString(SELECTED_DENSITIES, selectedDensities.joinToString(","))
                .putString(SELECTED_LOCALES, selectedLocales.joinToString(","))
                .putBoolean(INCLUDE_DFS, includeDynamicFeatures)
                .build()

            val request = OneTimeWorkRequestBuilder<UniversalApksWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("${DownloadHelper.PACKAGE_NAME}:${app.packageName}")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG/${app.packageName}",
                ExistingWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Enqueued universal APKS download for ${app.packageName}")
        }
    }

    private val notificationManager by lazy { context.getSystemService<NotificationManager>()!! }
    private val packageName by lazy { inputData.getString(PACKAGE_NAME)!! }
    private val versionCode by lazy { inputData.getLong(VERSION_CODE, 0L) }
    private val offerType by lazy { inputData.getInt(OFFER_TYPE, 0) }
    private val accountId by lazy { inputData.getString(ACCOUNT_ID)!! }
    private val displayName by lazy { inputData.getString(DISPLAY_NAME) ?: packageName }
    private val iconUrl by lazy { inputData.getString(ICON_URL).orEmpty() }
    // User's selected config (falls back to sensible defaults if not present)
    private val selectedAbis by lazy {
        inputData.getString(SELECTED_ABIS)?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("arm64-v8a", "armeabi-v7a")
    }
    private val selectedDensities by lazy {
        inputData.getString(SELECTED_DENSITIES)?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: ALL_DENSITIES.toSet()
    }
    private val selectedLocales by lazy {
        inputData.getString(SELECTED_LOCALES)?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
    }
    private val includeDfs by lazy { inputData.getBoolean(INCLUDE_DFS, true) }

    override suspend fun doWork(): Result {
        return try {
            runJob()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Never die silently: surface the reason so the user knows what happened.
            Log.e(TAG, "Universal APKS job crashed for $packageName", exception)
            finalizeDownloadRow(success = false)
            notifyResult(success = false, errorMessage = exception.message)
            Result.failure()
        }
    }

    private suspend fun runJob(): Result {
        // Register with the downloads system so the progress ring appears and the entry
        // shows up in the downloads list, matching the existing download UX.
        insertDownloadRow()

        enterForeground(context.getString(R.string.universal_apks_gathering))
        notifyProgress(context.getString(R.string.universal_apks_gathering))

        // Step 1: Collect unique files across all device configs
        val collectedFiles = LinkedHashMap<String, PlayFile>()

        // Config 0: use the SPECIFIC account's saved AuthData — no dispenser call needed.
        // Match DownloadWorker: pass the cert hash on Android P+ for installed apps so
        // key-rotation apps return valid download URLs (403 without it on some titles).
        runCatching {
            val savedAuth = authProvider.getAuthData(accountId)
                ?: throw IllegalStateException("No auth data for account $accountId")
            val purchaseHelper = PurchaseHelper(savedAuth).using(httpClient)
            val files = if (isPAndAbove && PackageUtil.isInstalled(context, packageName)) {
                purchaseHelper.purchase(
                    packageName, versionCode, offerType,
                    CertUtil.getEncodedCertificateHashes(context, packageName).lastOrNull() ?: ""
                )
            } else {
                purchaseHelper.purchase(packageName, versionCode, offerType)
            }
            files.filter { it.url.isNotBlank() }
                .filter { includeDfs || !it.name.startsWith("df_") }
                .forEach { collectedFiles[it.name] = it }
            Log.i(TAG, "Config 0 (saved): got ${files.size} files, ${collectedFiles.size} collected")
        }.onFailure { Log.w(TAG, "Config 0 (saved) failed: ${it.message}") }

        // Subsequent purchases: one per selected ABI (at max density), then density sweep.
        val baseProps = NativeDeviceInfoProvider.getNativeDeviceProperties(context)
        val isAnonymous = accountRepository.getById(accountId)?.type == AccountType.ANONYMOUS

        for (abi in ALL_ABIS.filter { it in selectedAbis }) {
            if (isStopped) break
            collectForConfig(collectedFiles, baseProps, abi = abi, density = 640, isAnonymous = isAnonymous)
            if (isAnonymous) delay(DISPENSER_DELAY_MS)
        }

        // Density sweep: use first selected ABI at each non-640 selected density
        val sweepAbi = selectedAbis.firstOrNull { it in ALL_ABIS } ?: "arm64-v8a"
        for (density in ALL_DENSITIES.filter { it in selectedDensities && it != 640 }) {
            if (isStopped) break
            collectForConfig(collectedFiles, baseProps, abi = sweepAbi, density = density, isAnonymous = isAnonymous)
            if (isAnonymous) delay(DISPENSER_DELAY_MS)
        }

        if (collectedFiles.isEmpty()) {
            Log.e(TAG, "No files collected for $packageName — all purchases failed")
            finalizeDownloadRow(success = false)
            notifyResult(success = false)
            return Result.failure()
        }

        Log.i(TAG, "Collected ${collectedFiles.size} unique files for $packageName")

        // Step 2: Download all unique files
        val downloadDir = File(context.cacheDir, "Downloads/$packageName/$versionCode/universal")
            .also { it.mkdirs() }

        enterForeground(context.getString(R.string.universal_apks_downloading))
        runCatching { downloadDao.updateStatus(packageName, DownloadStatus.DOWNLOADING) }

        val downloadedFiles = mutableListOf<File>()
        val total = collectedFiles.size
        val totalBytes = collectedFiles.values.sumOf { it.size }
        var index = 0
        var completedBytes = 0L
        for (playFile in collectedFiles.values) {
            if (isStopped) break
            index++
            notifyProgress(context.getString(R.string.universal_apks_downloading) + " ($index/$total)")
            runCatching {
                val localFile = downloadFile(playFile, downloadDir, totalBytes, completedBytes)
                if (localFile != null) {
                    downloadedFiles.add(localFile)
                    completedBytes += playFile.size
                }
            }.onFailure {
                Log.w(TAG, "Failed to download ${playFile.name}: ${it.message}")
            }
        }

        if (downloadedFiles.isEmpty()) {
            Log.e(TAG, "No files downloaded for $packageName")
            finalizeDownloadRow(success = false)
            notifyResult(success = false)
            return Result.failure()
        }

        // Step 3: Bundle into .apks ZIP
        enterForeground(context.getString(R.string.universal_apks_bundling))
        notifyProgress(context.getString(R.string.universal_apks_bundling))
        runCatching { downloadDao.updateProgress(packageName, 95, 0L, 0L) }

        val outputDir = resolveOutputDir()?.also { it.mkdirs() }
            ?: run {
                Log.e(TAG, "Cannot access output directory")
                finalizeDownloadRow(success = false)
                notifyResult(success = false)
                return Result.failure()
            }

        val outputFile = File(outputDir, "${packageName}_${versionCode}_universal.apks")

        runCatching {
            bundleIntoApks(downloadedFiles, outputFile)
        }.onFailure {
            Log.e(TAG, "Failed to bundle APKS for $packageName", it)
            outputFile.delete()
            finalizeDownloadRow(success = false)
            notifyResult(success = false)
            return Result.failure()
        }

        Log.i(TAG, "Universal APKS ready: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
        finalizeDownloadRow(success = true)
        notifyResult(success = true, outputFile = outputFile)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(context.getString(R.string.universal_apks_gathering))

    /**
     * Inserts a [Download] row so the app-icon progress ring and downloads list show this job.
     * If an active (running/purchasing/verifying) download already exists for the same package
     * we leave it untouched — the Universal APKS job still proceeds but won't overwrite the
     * in-flight record.
     */
    private suspend fun insertDownloadRow() {
        runCatching {
            val existing = runCatching { downloadDao.getDownload(packageName) }.getOrNull()
            if (existing?.isActive == true) {
                Log.i(TAG, "Active download exists for $packageName, skipping row insert")
                return
            }
            downloadDao.insert(
                Download(
                    packageName = packageName,
                    versionCode = versionCode,
                    offerType = offerType,
                    isInstalled = false,
                    displayName = displayName,
                    iconURL = iconUrl,
                    size = 0L,
                    id = 0,
                    status = DownloadStatus.PURCHASING,
                    progress = 0,
                    speed = 0L,
                    timeRemaining = -1L,
                    totalFiles = 0,
                    downloadedFiles = 0,
                    fileList = emptyList(),
                    sharedLibs = emptyList(),
                    downloadedAt = Date().time
                )
            )
        }.onFailure { Log.w(TAG, "Could not insert download row: ${it.message}") }
    }

    /**
     * Updates the [Download] row to its final state. Uses [DownloadStatus.COMPLETED] on success
     * so the row stays in history with a green checkmark. COMPLETED is correct — nothing is
     * installed; the .apks bundle is a download artifact, not an installed app.
     */
    private suspend fun finalizeDownloadRow(success: Boolean) {
        runCatching {
            val status = if (success) DownloadStatus.COMPLETED else DownloadStatus.FAILED
            downloadDao.updateStatus(packageName, status)
        }.onFailure { Log.w(TAG, "Could not finalize download row: ${it.message}") }
    }

    /**
     * Returns the best available output directory for the .apks file.
     * Prefers public Downloads/AuroraStore/ when storage permission is granted,
     * falling back to the app-private external files directory.
     */
    private fun resolveOutputDir(): File? {
        val canWritePublic = if (isRAndAbove) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (canWritePublic) {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AuroraStore"
            )
            if (publicDir.mkdirs() || publicDir.exists()) return publicDir
        }
        return context.getExternalFilesDir("UniversalApks")
    }

    /**
     * Builds an AuthData for [abi] + [density] device config and purchases splits.
     * Results are merged into [collected] (deduplicated by file name).
     */
    private suspend fun collectForConfig(
        collected: LinkedHashMap<String, PlayFile>,
        baseProps: Properties,
        abi: String,
        density: Int,
        isAnonymous: Boolean
    ) {
        val label = "$abi @ ${density}dpi"
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val props = (baseProps.clone() as Properties).apply {
                setProperty("Platforms", abi)
                setProperty("Screen.Density", density.toString())
                // Augment the locale list with user-requested locales so the purchase
                // response includes locale-specific splits for those languages.
                if (selectedLocales.isNotEmpty()) {
                    val existing = getProperty("Locales", "")
                        .split(",").filter { it.isNotBlank() }.toMutableSet()
                    existing.addAll(selectedLocales)
                    setProperty("Locales", existing.joinToString(","))
                }
            }
            val authData = authProvider.buildAuthDataWithProperties(accountId, props)
            val purchaseHelper = PurchaseHelper(authData).using(httpClient)
            val files = if (isPAndAbove && PackageUtil.isInstalled(context, packageName)) {
                purchaseHelper.purchase(
                    packageName, versionCode, offerType,
                    CertUtil.getEncodedCertificateHashes(context, packageName).lastOrNull() ?: ""
                )
            } else {
                purchaseHelper.purchase(packageName, versionCode, offerType)
            }
            val newFiles = files.filter { it.url.isNotBlank() }
                .filter { includeDfs || !it.name.startsWith("df_") }
                .filterNot { collected.containsKey(it.name) }
            newFiles.forEach { collected[it.name] = it }
            Log.i(TAG, "Config $label: got ${files.size} files, ${newFiles.size} new")
        }.onFailure {
            Log.w(TAG, "Config $label failed: ${it.message}")
        }
    }

    /**
     * Downloads [playFile] to [dir] with up to [DOWNLOAD_MAX_RETRIES] attempts.
     * Resumes partial `.tmp` files via `Range: bytes=N-` so transient timeouts (e.g. on
     * large ABI splits) don't require re-downloading from the start.
     * Returns the completed [File] or null if all attempts fail or the URL returns an
     * unrecoverable HTTP error (403/404/410).
     */
    private suspend fun downloadFile(
        playFile: PlayFile, dir: File,
        totalJobBytes: Long = 0L, completedJobBytes: Long = 0L
    ): File? =
        withContext(Dispatchers.IO) {
            val targetFile = File(dir, playFile.name)
            if (targetFile.exists() && playFile.size > 0 && targetFile.length() == playFile.size) {
                Log.i(TAG, "${playFile.name} already complete, skipping")
                return@withContext targetFile
            }

            val tmpFile = File(dir, "${playFile.name}.tmp")

            for (attempt in 1..DOWNLOAD_MAX_RETRIES) {
                val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L

                try {
                    val headers = mutableMapOf<String, String>()
                    if (existingBytes > 0) headers["Range"] = "bytes=$existingBytes-"

                    val response = (httpClient as HttpClient).call(playFile.url, headers)
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} for ${playFile.name} (attempt $attempt)")
                        response.close()
                        // 403/410 = expired URL; 404 = missing. No point retrying.
                        break
                    }

                    val resuming = existingBytes > 0 && response.code == 206
                    var speedBytes = 0L
                    var totalWritten = existingBytes
                    var speedWindowStart = System.currentTimeMillis()

                    FileOutputStream(tmpFile, resuming).use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        response.body.byteStream().use { input ->
                            var read = input.read(buffer)
                            while (read >= 0) {
                                out.write(buffer, 0, read)
                                speedBytes += read
                                totalWritten += read
                                val now = System.currentTimeMillis()
                                val elapsed = now - speedWindowStart
                                if (elapsed >= 1000L) {
                                    val speed = speedBytes * 1000L / elapsed
                                    val overallWritten = completedJobBytes + totalWritten
                                    val progress = if (totalJobBytes > 0) {
                                        (overallWritten * 100L / totalJobBytes).toInt().coerceIn(1, 99)
                                    } else 1
                                    val remaining = (totalJobBytes - overallWritten).coerceAtLeast(0L)
                                    val eta = if (speed > 0L) remaining * 1000L / speed else -1L
                                    runCatching {
                                        downloadDao.updateProgress(packageName, progress, speed, eta)
                                    }
                                    speedBytes = 0L
                                    speedWindowStart = now
                                }
                                read = input.read(buffer)
                            }
                        }
                    }

                    if (!tmpFile.renameTo(targetFile)) {
                        Log.w(TAG, "Could not rename ${tmpFile.name} → ${targetFile.name}")
                        return@withContext null
                    }

                    Log.i(TAG, "Downloaded ${playFile.name} (${targetFile.length()} bytes)")
                    return@withContext targetFile
                } catch (e: Exception) {
                    if (attempt < DOWNLOAD_MAX_RETRIES) {
                        Log.w(TAG, "Attempt $attempt/$DOWNLOAD_MAX_RETRIES failed for ${playFile.name}: ${e.message}, resuming in ${DOWNLOAD_RETRY_DELAY_MS}ms")
                        delay(DOWNLOAD_RETRY_DELAY_MS)
                    } else {
                        Log.e(TAG, "Download failed for ${playFile.name}", e)
                    }
                }
            }
            null
        }

    /**
     * Packs all [files] into a ZIP at [output] using maximum compression.
     * Flat layout — all APKs at the archive root for SAI / bundletool compatibility.
     */
    private suspend fun bundleIntoApks(files: List<File>, output: File) =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            ZipOutputStream(FileOutputStream(output).buffered(BUFFER_SIZE)).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().buffered(BUFFER_SIZE).use { input ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            zip.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

    /**
     * Best-effort foreground promotion. Starting a foreground service from the background is
     * restricted on Android 12+, and an expedited job downgraded to regular background work
     * (see [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]) may hit that restriction.
     * A failure here is non-fatal — the job keeps running and still produces a result.
     */
    private suspend fun enterForeground(message: String) {
        runCatching { setForeground(buildForegroundInfo(message)) }
            .onFailure { Log.w(TAG, "Could not enter foreground: ${it.message}") }
    }

    private fun buildForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setContentTitle(displayName)
            .setContentText(message)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (isQAndAbove) {
            ForegroundInfo(NOTIFICATION_ID_FGS, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID_FGS, notification)
        }
    }

    /**
     * Posts a visible, ongoing progress notification on its own id. Independent of the silent
     * foreground notification so the user gets clear feedback even if foreground promotion failed.
     */
    private fun notifyProgress(message: String) {
        val notification = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setContentTitle(displayName)
            .setContentText(message)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun notifyResult(success: Boolean, outputFile: File? = null, errorMessage: String? = null) {
        notificationManager.cancel(NOTIFICATION_ID_FGS)
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setContentTitle(displayName)
            .setAutoCancel(true)

        if (success && outputFile != null) {
            val savedTo = outputFile.parentFile?.absolutePath ?: outputFile.absolutePath
            val completedText = context.getString(R.string.universal_apks_notification_complete)
            builder.setContentText(completedText)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$completedText\n$savedTo"))

            // Share action so the user can open / send the .apks file
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileProvider",
                outputFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingShare = PendingIntent.getActivity(
                context,
                outputFile.hashCode(),
                Intent.createChooser(shareIntent, outputFile.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_share,
                context.getString(R.string.action_share),
                pendingShare
            )
        } else {
            val failed = context.getString(R.string.universal_apks_notification_failed)
            val text = if (!errorMessage.isNullOrBlank()) "$failed: $errorMessage" else failed
            builder.setContentText(text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        notificationManager.notify(NOTIFICATION_ID_RESULT, builder.build())
    }
}
