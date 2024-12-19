package it.fast4x.rimusic.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloadService.sendAddDownload
import androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload
import androidx.media3.exoplayer.scheduler.Requirements
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.enums.AudioQualityFormat
import it.fast4x.rimusic.utils.audioQualityFormatKey
import it.fast4x.rimusic.utils.autoDownloadSongKey
import it.fast4x.rimusic.utils.autoDownloadSongWhenAlbumBookmarkedKey
import it.fast4x.rimusic.utils.autoDownloadSongWhenLikedKey
import it.fast4x.rimusic.utils.getEnum
import it.fast4x.rimusic.utils.preferences
import it.fast4x.rimusic.utils.removeDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Timer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.concurrent.schedule

@UnstableApi
object MyDownloadHelper {
//    private val executor = Executors.newCachedThreadPool()
//    private val coroutineScope = CoroutineScope(
//        executor.asCoroutineDispatcher() +
//                SupervisorJob() +
//                CoroutineName("MyDownloadService-Executor-Scope")
//    )

    // While the class is not a singleton (lifecycle), there should only be one download state at a time
//    private val mutableDownloadState = MutableStateFlow(false)
//    val downloadState = mutableDownloadState.asStateFlow()
//    private val downloadQueue =
//        Channel<DownloadManager>(onBufferOverflow = BufferOverflow.DROP_OLDEST)

    const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"

    private const val DOWNLOAD_CONTENT_DIRECTORY = "downloads"

    private lateinit var databaseProvider: DatabaseProvider
    lateinit var downloadCache: Cache

    private lateinit var downloadNotificationHelper: DownloadNotificationHelper
    private lateinit var downloadDirectory: File
    private lateinit var downloadManager: DownloadManager
    lateinit var audioQualityFormat: AudioQualityFormat
    //private lateinit var connectivityManager: ConnectivityManager


    var downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    fun getDownload(songId: String): Flow<Download?> {
        return downloads.map { it[songId] }

    }

    @SuppressLint("LongLogTag")
    @Synchronized
    fun getDownloads() {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

    }


    @Synchronized
    fun getDownloadNotificationHelper(context: Context?): DownloadNotificationHelper {
        if (!MyDownloadHelper::downloadNotificationHelper.isInitialized) {
            downloadNotificationHelper =
                DownloadNotificationHelper(context!!, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        }
        return downloadNotificationHelper
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        ensureDownloadManagerInitialized(context)
        return downloadManager
    }

    /*
        @Synchronized
        fun getDownloadTracker(context: Context): DownloadTracker {
            ensureDownloadManagerInitialized(context)
            return downloadTracker
        }

     */

    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        if (!MyDownloadHelper::downloadCache.isInitialized) {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache
    }

    @Synchronized
    fun getDownloadSimpleCache(context: Context): Cache {
        if (!MyDownloadHelper::downloadCache.isInitialized) {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache
    }

    @Synchronized
    private fun ensureDownloadManagerInitialized(context: Context) {
        audioQualityFormat =
            context.preferences.getEnum(audioQualityFormatKey, AudioQualityFormat.Auto)

        if (!MyDownloadHelper::downloadManager.isInitialized) {
            downloadManager = DownloadManager(
                context,
                getDatabaseProvider(context),
                getDownloadCache(context),
                createDataSourceFactory(),
                Executor(Runnable::run)
            ).apply {
                maxParallelDownloads = 3
                //minRetryCount = 2
                //requirements = Requirements(Requirements.NETWORK)

                addListener(
                    object : DownloadManager.Listener {
//                        override fun onIdle(downloadManager: DownloadManager) =
//                            mutableDownloadState.update { false }

                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?
                        ) = run {
                            //downloadQueue.trySend(downloadManager).let { }
                            syncDownloads(download)
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download
                        ) = run {
                            //downloadQueue.trySend(downloadManager).let { }
                            syncDownloads(download)
                        }
                    }
                )
            }

            //downloadTracker =
            //    DownloadTracker(context, getHttpDataSourceFactory(context), downloadManager)
        }
    }

    @Synchronized
    private fun syncDownloads(download: Download) {
        downloads.update { map ->
            map.toMutableMap().apply {
                set(download.request.id, download)
            }
        }
        getDownloads()
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (!MyDownloadHelper::databaseProvider.isInitialized) databaseProvider =
            StandaloneDatabaseProvider(context)
        return databaseProvider
    }

    @Synchronized
    fun getDownloadDirectory(context: Context): File {
        if (!MyDownloadHelper::downloadDirectory.isInitialized) {
            downloadDirectory = context.getExternalFilesDir(null) ?: context.filesDir
            downloadDirectory.resolve(DOWNLOAD_CONTENT_DIRECTORY).also { directory ->
                if (directory.exists()) return@also
                directory.mkdir()
            }
            //Log.d("downloadMedia", downloadDirectory.path)
        }
        return downloadDirectory
    }


        fun addDownload(context: Context, mediaItem: MediaItem) {
            if (mediaItem.isLocal) return

            val downloadRequest = DownloadRequest
                .Builder(
                    /* id      = */ mediaItem.mediaId,
                    /* uri     = */ mediaItem.requestMetadata.mediaUri
                        ?: Uri.parse("https://music.youtube.com/watch?v=${mediaItem.mediaId}")
                )
                .setCustomCacheKey(mediaItem.mediaId)
                .setData("${mediaItem.mediaMetadata.artist.toString()} - ${mediaItem.mediaMetadata.title.toString()}".encodeToByteArray()) // Title in notification
                .build()

            Database.asyncTransaction {
                runCatching {
                    insert(mediaItem)
                }.also { if (it.isFailure) return@asyncTransaction }
            }

            sendAddDownload(
                context,MyDownloadService::class.java,downloadRequest,false
            )

                //coroutineScope.launch {
//                    context.download<MyDownloadService>(downloadRequest).exceptionOrNull()?.let {
//                        if (it is CancellationException) throw it
//
//                        Timber.e(it.stackTraceToString())
//                        println("MyDownloadHelper scheduleDownload exception ${it.stackTraceToString()}")
//                    }
                //}

        }

    fun removeDownload(context: Context, mediaItem: MediaItem) {
        if (mediaItem.isLocal) return

        sendRemoveDownload(context,MyDownloadService::class.java,mediaItem.mediaId,false)
        //coroutineScope.launch {
//            context.removeDownload<MyDownloadService>(mediaItem.mediaId).exceptionOrNull()?.let {
//                if (it is CancellationException) throw it
//
//                Timber.e(it.stackTraceToString())
//                println("MyDownloadHelper removeDownload exception ${it.stackTraceToString()}")
//            }
        //}
    }

    fun resumeDownloads(context: Context){
        DownloadService.sendResumeDownloads(
            context,
            MyDownloadService::class.java,
            false)
    }

    fun autoDownload(context: Context, mediaItem: MediaItem){
        if (context.preferences.getBoolean(autoDownloadSongKey, false)) {
            if (downloads.value[mediaItem.mediaId]?.state != Download.STATE_COMPLETED)
                addDownload(context, mediaItem)
        }
    }

    fun autoDownloadWhenLiked(context: Context, mediaItem: MediaItem){
        if (context.preferences.getBoolean(autoDownloadSongWhenLikedKey, false)) {
                autoDownload(context, mediaItem)
        }
    }

    fun autoDownloadWhenAlbumBookmarked(context: Context, mediaItems: List<MediaItem>){
        println("autoDownloadWhenAlbumBookmarked inside ${mediaItems.size}")
        if (context.preferences.getBoolean(autoDownloadSongWhenAlbumBookmarkedKey, false)) {
            mediaItems.forEach { mediaItem ->
                println("autoDownloadWhenAlbumBookmarked forEach ${mediaItem.mediaId} ${downloads.value[mediaItem.mediaId]?.state}")
                autoDownload(context, mediaItem)

            }
        }
    }


}
