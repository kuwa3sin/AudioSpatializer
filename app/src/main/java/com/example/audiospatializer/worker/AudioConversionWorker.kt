package com.example.audiospatializer.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.audiospatializer.AudioProcessor
import com.example.audiospatializer.AudioSpatializerApp
import com.example.audiospatializer.R
import com.example.audiospatializer.data.ConvertedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * バックグラウンドでオーディオ変換を行うWorker
 * Foreground Serviceとして動作し、進捗を通知で表示
 */
class AudioConversionWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_MODE = "output_mode"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_OUTPUT_FILE = "output_file"
        const val KEY_ERROR_MESSAGE = "error_message"
        
        const val NOTIFICATION_CHANNEL_ID = "audio_conversion_channel"
        const val NOTIFICATION_ID = 1001
        
        const val STATUS_PREPARING = "preparing"
        const val STATUS_CONVERTING = "converting"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }

    private val notificationManager = 
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString(KEY_INPUT_URI) 
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No input URI"))
        
        val outputModeString = inputData.getString(KEY_OUTPUT_MODE) 
            ?: AudioProcessor.OutputMode.HRTF_SURROUND_5_1.name
        
        val inputUri = Uri.parse(inputUriString)
        val outputMode = try {
            AudioProcessor.OutputMode.valueOf(outputModeString)
        } catch (e: Exception) {
            AudioProcessor.OutputMode.HRTF_SURROUND_5_1
        }

        // Foreground Serviceとして開始
        setForeground(createForegroundInfo(0, STATUS_PREPARING))

        return withContext(Dispatchers.IO) {
            try {
                val processor = AudioProcessor(context)
                
                val result = processor.processAudio(inputUri, outputMode) { percent, stage ->
                    // 進捗更新
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS to percent,
                        KEY_STATUS to STATUS_CONVERTING
                    ))
                    updateNotification(percent, stage)
                }

                if (result != null) {
                    // DBに保存
                    saveConvertedMetadata(result)
                    
                    // 完了通知
                    showCompletionNotification(result.file.name, true)
                    
                    Result.success(workDataOf(
                        KEY_OUTPUT_FILE to result.file.absolutePath,
                        KEY_STATUS to STATUS_COMPLETED
                    ))
                } else {
                    showCompletionNotification(null, false)
                    Result.failure(workDataOf(
                        KEY_ERROR_MESSAGE to "Conversion failed",
                        KEY_STATUS to STATUS_FAILED
                    ))
                }
            } catch (e: Exception) {
                showCompletionNotification(null, false)
                Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to (e.message ?: "Unknown error"),
                    KEY_STATUS to STATUS_FAILED
                ))
            }
        }
    }

    private fun createForegroundInfo(progress: Int, stage: String): ForegroundInfo {
        createNotificationChannel()
        
        val title = context.getString(R.string.conversion_notification_title)
        val text = when (stage) {
            STATUS_PREPARING -> context.getString(R.string.status_preparing)
            else -> context.getString(R.string.status_converting_with_percent, progress)
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_music_note_24)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(progress: Int, stage: String) {
        createNotificationChannel()
        
        val title = context.getString(R.string.conversion_notification_title)
        val text = context.getString(R.string.status_converting_with_percent, progress)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_music_note_24)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(fileName: String?, success: Boolean) {
        createNotificationChannel()
        
        val title = context.getString(R.string.conversion_notification_title)
        val text = if (success && fileName != null) {
            context.getString(R.string.conversion_success, fileName)
        } else {
            context.getString(R.string.conversion_failed)
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(
                if (success) R.drawable.ic_music_note_24 
                else android.R.drawable.ic_dialog_alert
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.conversion_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.conversion_notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun saveConvertedMetadata(result: AudioProcessor.ProcessResult) {
        val repository = (context.applicationContext as AudioSpatializerApp).repository
        val durationMs = if (result.sampleRate > 0) {
            (result.frameCount * 1000L) / result.sampleRate
        } else {
            0L
        }
        val track = ConvertedTrack(
            displayName = result.file.name,
            filePath = result.file.absolutePath,
            durationMs = durationMs,
            sampleRate = result.sampleRate,
            channelCount = result.channelCount,
            fileSizeBytes = result.file.length(),
            outputMode = result.outputMode.name
        )
        repository.save(track)
    }
}
