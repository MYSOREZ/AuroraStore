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
import com.aurora.extensions.isQAndAbove
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.network.IHttpClient
import com.aurora.store.Constants
import com.aurora.store.R
import com.aurora.store.data.AccountRepository
import com.aurora.store.data.model.AccountType
import com.aurora.store.data.network.HttpClient
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.data.providers.NativeDeviceInfoProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
 */
@HiltWorker
class UniversalApksWorker @AssistedInject constructor(
    private val authProvider: AuthProvider,
    private val accountRepository: AccountRepository,
    private val httpClient: IHttpClient,
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

        private const val NOTIFICATION_ID_FGS = 601
        private const val NOTIFICATION_ID_RESULT = 602

        private const val BUFFER_SIZE = 256 * 1024

        // Delay between dispenser calls to avoid rate-limiting (anonymous accounts only)
        private const val DISPENSER_DELAY_MS = 2000L

        // Target ABIs and densities for universal coverage
        private val TARGET_ABIS = listOf(
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
            "armeabi"
        )
        private val TARGET_DENSITIES = listOf(
            640,  // xxxhdpi
            480,  // xxhdpi
            320,  // xhdpi
            240,  // hdpi
            160,  // mdpi
            120   // ldpi
        )

        fun enqueue(context: Context, app: App, accountId: String) {
            val data = Data.Builder()
                .putString(PACKAGE_NAME, app.packageName)
                .putLong(VERSION_CODE, app.versionCode)
                .putInt(OFFER_TYPE, app.offerType)
                .putString(ACCOUNT_ID, accountId)
                .putString(DISPLAY_NAME, app.displayName)
                .build()

            val request = OneTimeWorkRequestBuilder<UniversalApksWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG/${ app.packageName}",
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

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo(context.getString(R.string.universal_apks_gathering)))

        // Step 1: Collect unique files across all device configs
        val collectedFiles = LinkedHashMap<String, PlayFile>()

        // First purchase: use the EXISTING saved authData (guaranteed to work, no extra call)
        runCatching {
            val savedAuth = authProvider.authData
                ?: throw IllegalStateException("No saved authData")
            val files = PurchaseHelper(savedAuth).using(httpClient)
                .purchase(packageName, versionCode, offerType)
            files.forEach { collectedFiles[it.name] = it }
            Log.i(TAG, "Config 0 (saved): got ${files.size} files")
        }.onFailure { Log.w(TAG, "Config 0 (saved) failed: ${it.message}") }

        // Subsequent purchases: one per ABI config (all at max density to also catch xxxhdpi)
        val baseProps = NativeDeviceInfoProvider.getNativeDeviceProperties(context)
        val isAnonymous = accountRepository.getById(accountId)?.type == AccountType.ANONYMOUS

        for (abi in TARGET_ABIS) {
            if (isStopped) break
            collectForConfig(
                collectedFiles,
                baseProps,
                abi = abi,
                density = 640,  // xxxhdpi — highest density to capture top-tier asset split
                isAnonymous = isAnonymous
            )
            if (isAnonymous) delay(DISPENSER_DELAY_MS)
        }

        // Density sweep: arm64-v8a at each density to catch remaining density-specific splits
        for (density in TARGET_DENSITIES) {
            if (isStopped) break
            // Skip 640 — already covered by the ABI sweep above
            if (density == 640) continue
            collectForConfig(
                collectedFiles,
                baseProps,
                abi = "arm64-v8a",
                density = density,
                isAnonymous = isAnonymous
            )
            if (isAnonymous) delay(DISPENSER_DELAY_MS)
        }

        if (collectedFiles.isEmpty()) {
            Log.e(TAG, "No files collected for $packageName — all purchases failed")
            notifyResult(success = false)
            return Result.failure()
        }

        Log.i(TAG, "Collected ${collectedFiles.size} unique files for $packageName")

        // Step 2: Download all unique files
        val downloadDir = File(context.cacheDir, "Downloads/$packageName/$versionCode/universal")
            .also { it.mkdirs() }

        setForeground(buildForegroundInfo(context.getString(R.string.universal_apks_downloading)))

        val downloadedFiles = mutableListOf<File>()
        for (playFile in collectedFiles.values) {
            if (isStopped) break
            runCatching {
                val localFile = downloadFile(playFile, downloadDir)
                if (localFile != null) downloadedFiles.add(localFile)
            }.onFailure {
                Log.w(TAG, "Failed to download ${playFile.name}: ${it.message}")
            }
        }

        if (downloadedFiles.isEmpty()) {
            Log.e(TAG, "No files downloaded for $packageName")
            notifyResult(success = false)
            return Result.failure()
        }

        // Step 3: Bundle into .apks ZIP
        setForeground(buildForegroundInfo(context.getString(R.string.universal_apks_bundling)))

        val outputDir = context.getExternalFilesDir("UniversalApks")?.also { it.mkdirs() }
            ?: run {
                Log.e(TAG, "Cannot access external files dir")
                notifyResult(success = false)
                return Result.failure()
            }

        val outputFile = File(outputDir, "${packageName}_${versionCode}_universal.apks")

        runCatching {
            bundleIntoApks(downloadedFiles, outputFile)
        }.onFailure {
            Log.e(TAG, "Failed to bundle APKS for $packageName", it)
            outputFile.delete()
            notifyResult(success = false)
            return Result.failure()
        }

        Log.i(TAG, "Universal APKS ready: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
        notifyResult(success = true, outputFile = outputFile)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(context.getString(R.string.universal_apks_gathering))

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
            }
            val authData = authProvider.buildAuthDataWithProperties(accountId, props)
            val files = PurchaseHelper(authData).using(httpClient)
                .purchase(packageName, versionCode, offerType)
            val newFiles = files.filterNot { collected.containsKey(it.name) }
            newFiles.forEach { collected[it.name] = it }
            Log.i(TAG, "Config $label: got ${files.size} files, ${newFiles.size} new")
        }.onFailure {
            Log.w(TAG, "Config $label failed: ${it.message}")
        }
    }

    /**
     * Downloads [playFile] to [dir], resuming if a partial file exists.
     * Returns the completed [File] or null on failure.
     */
    private suspend fun downloadFile(playFile: PlayFile, dir: File): File? =
        withContext(Dispatchers.IO) {
            val targetFile = File(dir, playFile.name)
            if (targetFile.exists() && targetFile.length() == playFile.size) {
                Log.i(TAG, "${playFile.name} already complete, skipping")
                return@withContext targetFile
            }

            val tmpFile = File(dir, "${playFile.name}.tmp")
            val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L

            try {
                val headers = mutableMapOf<String, String>()
                if (existingBytes > 0) headers["Range"] = "bytes=$existingBytes-"

                val response = (httpClient as HttpClient).call(playFile.url, headers)
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for ${playFile.name}")
                    response.close()
                    return@withContext null
                }

                val resuming = existingBytes > 0 && response.code == 206
                FileOutputStream(tmpFile, resuming).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    response.body.byteStream().use { input ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            out.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                    }
                }

                if (!tmpFile.renameTo(targetFile)) {
                    Log.w(TAG, "Could not rename ${tmpFile.name} → ${targetFile.name}")
                    return@withContext null
                }

                Log.i(TAG, "Downloaded ${playFile.name} (${targetFile.length()} bytes)")
                targetFile
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${playFile.name}", e)
                null
            }
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

    private fun notifyResult(success: Boolean, outputFile: File? = null) {
        notificationManager.cancel(NOTIFICATION_ID_FGS)

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
            .setSmallIcon(R.drawable.ic_notification_outlined)
            .setContentTitle(displayName)
            .setAutoCancel(true)

        if (success && outputFile != null) {
            builder.setContentText(context.getString(R.string.universal_apks_notification_complete))

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
            builder.setContentText(context.getString(R.string.universal_apks_notification_failed))
        }

        notificationManager.notify(NOTIFICATION_ID_RESULT, builder.build())
    }
}
