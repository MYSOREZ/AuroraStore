/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
import com.aurora.extensions.isPAndAbove
import com.aurora.extensions.isQAndAbove
import com.aurora.extensions.isRAndAbove
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
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
        private const val METADATA_FILENAME = "bundle_info.txt"

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

        // Density sweep: always use arm64-v8a so density splits are collected regardless of
        // which ABI the user selected.
        val sweepAbi = "arm64-v8a"
        for (density in ALL_DENSITIES.filter { it in selectedDensities && it != 640 }) {
            if (isStopped) break
            collectForConfig(collectedFiles, baseProps, abi = sweepAbi, density = density, isAnonymous = isAnonymous)
            if (isAnonymous) delay(DISPENSER_DELAY_MS)
        }

        // Locale sweep: Play returns at most ONE locale split per purchase call.
        // Make a dedicated call per language so every selected locale is collected.
        //
        // To avoid N dispenser calls (one per locale) and the rate-limiting that entails:
        // build ONE base auth for the locale sweep (one dispenser call for anonymous accounts),
        // then reuse its token for all per-locale purchases via buildAuthDataReusingToken().
        // For Google accounts, buildAuthDataWithProperties() is already free (no dispenser).
        if (selectedLocales.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val localeBaseProps = (baseProps.clone() as Properties).apply {
                setProperty("Platforms", sweepAbi)
                setProperty("Screen.Density", "640")
            }
            val localeBaseAuth = runCatching {
                if (isAnonymous) delay(DISPENSER_DELAY_MS)  // cooldown before locale dispenser call
                authProvider.buildAuthDataWithProperties(accountId, localeBaseProps)
            }.getOrElse {
                Log.w(TAG, "Locale base auth failed, will fall back to per-locale dispenser: ${it.message}")
                null
            }

            for (locale in selectedLocales.filter { it.isNotBlank() }) {
                if (isStopped) break
                val alreadyHave = collectedFiles.keys.any { name ->
                    val id = configIdFromName(name) ?: return@any false
                    id == locale || id.startsWith("${locale}_")
                }
                if (alreadyHave) continue
                collectForConfig(
                    collectedFiles, baseProps,
                    abi = sweepAbi, density = 640,
                    isAnonymous = isAnonymous,
                    localeOverride = locale,
                    reuseAuth = localeBaseAuth
                )
                // Delay only when falling back to per-locale dispenser calls
                if (isAnonymous && localeBaseAuth == null) delay(DISPENSER_DELAY_MS)
            }
        }

        if (collectedFiles.isEmpty()) {
            Log.e(TAG, "No files collected for $packageName — all purchases failed")
            finalizeDownloadRow(success = false)
            notifyResult(success = false)
            return Result.failure()
        }

        // Remove density/locale splits that weren't selected by the user
        filterCollectedFiles(collectedFiles)
        Log.i(TAG, "After filter: ${collectedFiles.size} files for $packageName (${collectedFiles.keys.joinToString()})")

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

        // Step 3: Bundle into .apks ZIP (temp file in cache, then publish to public storage)
        enterForeground(context.getString(R.string.universal_apks_bundling))
        notifyProgress(context.getString(R.string.universal_apks_bundling))
        runCatching { downloadDao.updateProgress(packageName, 95, 0L, 0L) }

        val tempDir = File(context.cacheDir, "universal_apks_temp").also { it.mkdirs() }
        val tempFile = File(tempDir, "${packageName}_${versionCode}_universal.apks")

        val bundleMetadata = buildBundleMetadata(downloadedFiles)

        runCatching {
            bundleIntoApks(downloadedFiles, tempFile, bundleMetadata)
        }.onFailure {
            Log.e(TAG, "Failed to bundle APKS for $packageName", it)
            tempFile.delete()
            finalizeDownloadRow(success = false)
            notifyResult(success = false)
            return Result.failure()
        }

        Log.i(TAG, "Bundle ready: ${tempFile.length()} bytes — publishing to storage")
        val (savedPath, shareUri) = publishBundledFile(tempFile)
        tempFile.delete()

        Log.i(TAG, "Universal APKS published: $savedPath")
        finalizeDownloadRow(success = true)
        notifyResult(success = true, savedPath = savedPath, shareUri = shareUri)
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
                    downloadedAt = Date().time,
                    isUniversalApks = true
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
     * Removes config splits for ABI, density, or locale that the user did NOT select.
     *
     * ABI: always filtered strictly — only user-selected architectures are kept.
     *   Arm64 collected incidentally during the density sweep is removed if not selected.
     *   If no selected ABI exists for this app, no ABI split is included (base.apk only).
     *
     * Density/Locale: smart fallback — only filtered when at least one requested item was
     *   actually collected; otherwise whatever exists is kept so the bundle remains usable.
     *
     * Keeps base.apk, df_*.apk, and any unrecognised split type unchanged.
     */
    private fun filterCollectedFiles(files: LinkedHashMap<String, PlayFile>) {
        val wantedDensityLabels = selectedDensities.mapNotNull { DENSITY_LABEL[it] }.toSet()
        val allAbiFileIds = ALL_ABIS.map { it.replace("-", "_") }.toSet()
        val selectedAbiFileIds = selectedAbis.map { it.replace("-", "_") }.toSet()

        // Pre-scan to see which requested split types we actually got (used for density/locale
        // smart-fallback: keep whatever exists if none of the requested type was returned)
        val gotRequestedDensity = files.keys.any { name ->
            val id = configIdFromName(name) ?: return@any false
            id in wantedDensityLabels
        }
        val gotRequestedLocale = files.keys.any { name ->
            val id = configIdFromName(name) ?: return@any false
            !allAbiFileIds.any { id.equals(it, ignoreCase = true) } &&
                !DENSITY_LABEL.values.contains(id) &&
                selectedLocales.any { loc -> id == loc || id.startsWith("${loc}_") }
        }

        Log.i(TAG, "Filter state: gotDensity=$gotRequestedDensity gotLocale=$gotRequestedLocale")

        files.entries.removeIf { (name, _) ->
            val configId = configIdFromName(name) ?: return@removeIf false

            when {
                // ABI split: always filter strictly to user selection — no fallback.
                // If the user didn't select arm64, arm64 must not appear even if it was
                // collected incidentally during the density sweep.
                allAbiFileIds.any { configId.equals(it, ignoreCase = true) } ->
                    selectedAbiFileIds.none { configId.equals(it, ignoreCase = true) }

                // Density split: filter only when we got at least one requested density
                DENSITY_LABEL.values.any { it == configId } ->
                    gotRequestedDensity && configId !in wantedDensityLabels

                // Locale split: filter only when we got at least one requested locale
                selectedLocales.isNotEmpty() ->
                    gotRequestedLocale && selectedLocales.none { locale ->
                        configId == locale || configId.startsWith("${locale}_")
                    }

                else -> false
            }
        }
    }

    /** Extracts the config identifier from "config.XYZ.apk" or "split_config.XYZ.apk". */
    private fun configIdFromName(name: String): String? =
        Regex("config\\.([^.]+)\\.apk").find(name)?.groupValues?.get(1)

    /**
     * Copies [source] to a user-visible location and returns the display path and a
     * URI suitable for the share intent.
     *
     * Strategy (in priority order):
     *  1. Android Q+ → MediaStore.Downloads ("Download/AuroraStore/") — no extra permission
     *  2. Android R+ with MANAGE_EXTERNAL_STORAGE → raw file in public Downloads
     *  3. Pre-Q with WRITE_EXTERNAL_STORAGE → raw file in public Downloads
     *  4. Fallback → app-private external dir (always accessible, no permission needed)
     */
    private suspend fun publishBundledFile(source: File): Pair<String, Uri?> =
        withContext(Dispatchers.IO) {
            // Strategy 1: MediaStore.Downloads (Android Q+, no permission needed)
            if (isQAndAbove) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/AuroraStore")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            source.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        Log.i(TAG, "Published via MediaStore: $uri")
                        return@withContext Pair("Download/AuroraStore/${source.name}", uri)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore publish failed: ${e.message}")
                }
            }

            // Strategy 2/3: direct file in public Downloads
            val canWriteDirect = if (isRAndAbove) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
            if (canWriteDirect) {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AuroraStore"
                )
                if (publicDir.mkdirs() || publicDir.exists()) {
                    val dest = File(publicDir, source.name)
                    try {
                        source.copyTo(dest, overwrite = true)
                        val fpUri = runCatching {
                            FileProvider.getUriForFile(
                                context, "${context.packageName}.fileProvider", dest
                            )
                        }.getOrNull()
                        return@withContext Pair(dest.absolutePath, fpUri)
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct copy to public Downloads failed: ${e.message}")
                    }
                }
            }

            // Strategy 4: fallback — app-private external dir
            val privateDir = context.getExternalFilesDir("UniversalApks")
                ?: context.filesDir.resolve("UniversalApks")
            privateDir.mkdirs()
            val privateFile = File(privateDir, source.name)
            source.copyTo(privateFile, overwrite = true)
            val fpUri = runCatching {
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileProvider", privateFile
                )
            }.getOrNull()
            Pair(privateFile.absolutePath, fpUri)
        }

    /**
     * Builds an AuthData for [abi] + [density] (+ optional [localeOverride]) device config
     * and purchases splits. Results are merged into [collected] (deduplicated by file name).
     *
     * Play API returns at most ONE locale split per purchase — the best match for the device's
     * Locales property. To collect splits for multiple languages, call this function once per
     * locale with [localeOverride] set to the target language code.
     * When [localeOverride] is null the device's native locale applies (used for ABI/density
     * sweeps where locale splits are not the goal).
     */
    private suspend fun collectForConfig(
        collected: LinkedHashMap<String, PlayFile>,
        baseProps: Properties,
        abi: String,
        density: Int,
        isAnonymous: Boolean,
        localeOverride: String? = null,
        reuseAuth: AuthData? = null
    ) {
        val label = if (localeOverride != null) "$abi @ ${density}dpi / $localeOverride"
                    else "$abi @ ${density}dpi"
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val props = (baseProps.clone() as Properties).apply {
                setProperty("Platforms", abi)
                setProperty("Screen.Density", density.toString())
                if (localeOverride != null) setProperty("Locales", localeOverride)
            }
            // Reuse an existing auth token when provided (avoids a dispenser call per locale).
            // buildAuthDataReusingToken() calls AuthHelper.build() with the same credentials but
            // new device properties — no HTTP call to the dispenser.
            val authData = if (reuseAuth != null) {
                authProvider.buildAuthDataReusingToken(reuseAuth, props)
            } else {
                authProvider.buildAuthDataWithProperties(accountId, props)
            }
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
     * Builds the human-readable metadata text written as [METADATA_FILENAME] into the bundle.
     * Reflects the files that were ACTUALLY downloaded, not the user's selection — so if a
     * split type doesn't exist for this app, it won't appear in the list.
     */
    private fun buildBundleMetadata(downloadedFiles: List<File>): String {
        val allAbiFileIds = ALL_ABIS.map { it.replace("-", "_") }.toSet()
        val densityLabelSet = DENSITY_LABEL.values.toSet()

        val foundAbis = mutableSetOf<String>()
        val foundDensities = mutableSetOf<String>()
        val foundLocales = mutableSetOf<String>()
        var foundDfs = false

        for (file in downloadedFiles) {
            if (file.name.startsWith("df_")) { foundDfs = true; continue }
            val configId = configIdFromName(file.name) ?: continue
            when {
                allAbiFileIds.any { configId.equals(it, ignoreCase = true) } -> {
                    val canonical = ALL_ABIS.find {
                        it.replace("-", "_").equals(configId, ignoreCase = true)
                    } ?: configId
                    foundAbis += canonical
                }
                densityLabelSet.contains(configId) -> foundDensities += configId
                else -> foundLocales += configId.uppercase()
            }
        }

        val sortedAbis = ALL_ABIS.filter { it in foundAbis }
        val sortedDensities = ALL_DENSITIES.mapNotNull { DENSITY_LABEL[it] }
            .filter { it in foundDensities }
        val sortedLocales = foundLocales.sorted()

        return buildString {
            appendLine("$packageName $versionCode")
            appendLine()
            appendLine("Архитектура: ${sortedAbis.ifEmpty { listOf("—") }.joinToString(", ")}.")
            appendLine("Локаль: ${sortedLocales.ifEmpty { listOf("—") }.joinToString(", ")}.")
            appendLine("Размер экрана: ${sortedDensities.ifEmpty { listOf("—") }.joinToString(", ")}.")
            append("Динамические функции: ${if (foundDfs) "да" else "нет"}.")
        }
    }

    /**
     * Packs all [files] into a ZIP at [output] using maximum compression.
     * Flat layout — all APKs at the archive root for SAI / bundletool compatibility.
     * An optional [metadata] string is written as [METADATA_FILENAME] for reference; it is
     * ignored by installers since they only look for .apk entries.
     */
    private suspend fun bundleIntoApks(
        files: List<File>,
        output: File,
        metadata: String? = null
    ) = withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            ZipOutputStream(FileOutputStream(output).buffered(BUFFER_SIZE)).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                if (metadata != null) {
                    zip.putNextEntry(ZipEntry(METADATA_FILENAME))
                    zip.write(metadata.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
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

    private fun notifyResult(
        success: Boolean,
        savedPath: String? = null,
        shareUri: Uri? = null,
        errorMessage: String? = null
    ) {
        notificationManager.cancel(NOTIFICATION_ID_FGS)
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setContentTitle(displayName)
            .setAutoCancel(true)

        if (success) {
            val completedText = context.getString(R.string.universal_apks_notification_complete)
            builder.setContentText(completedText)
            if (savedPath != null) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("$completedText\n$savedPath"))
            }

            if (shareUri != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pendingShare = PendingIntent.getActivity(
                    context,
                    shareUri.hashCode(),
                    Intent.createChooser(shareIntent, displayName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    R.drawable.ic_share,
                    context.getString(R.string.action_share),
                    pendingShare
                )
            }
        } else {
            val failed = context.getString(R.string.universal_apks_notification_failed)
            val text = if (!errorMessage.isNullOrBlank()) "$failed: $errorMessage" else failed
            builder.setContentText(text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        notificationManager.notify(NOTIFICATION_ID_RESULT, builder.build())
    }
}
