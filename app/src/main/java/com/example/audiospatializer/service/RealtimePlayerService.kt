package com.example.audiospatializer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.Spatializer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.audiospatializer.MainActivity
import com.example.audiospatializer.R
import com.example.audiospatializer.audio.UpmixProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * リアルタイム5.1ch空間化プレーヤーサービス
 * 
 * 音楽ファイルをデコード → アップミックス → 5.1ch AudioTrackで再生
 * Spatializer APIが自動適用され、ヘッドトラッキングが有効になる
 * MediaStyle通知を表示してロック画面からも操作可能
 */
class RealtimePlayerService : Service() {
    
    companion object {
        private const val TAG = "RealtimePlayerService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "realtime_player_channel"
        
        private const val ACTION_PLAY = "com.example.audiospatializer.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.example.audiospatializer.ACTION_PAUSE"
        private const val ACTION_STOP = "com.example.audiospatializer.ACTION_STOP"
        
        private val _playerState = MutableStateFlow(PlayerState.IDLE)
        val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
        
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    }
    
    enum class PlayerState {
        IDLE,
        LOADED,
        PLAYING,
        PAUSED
    }
    
    enum class SurroundMode(val displayName: String, val intensity: Float) {
        LIGHT("ライト", 0.4f),
        STANDARD("スタンダード", 0.7f),
        STRONG("ストロング", 1.0f)
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var mediaExtractor: MediaExtractor? = null
    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var isCodecStarted = false
    
    private val upmixProcessor = UpmixProcessor()
    private var audioManager: AudioManager? = null
    private var spatializer: Spatializer? = null
    
    private var currentUri: Uri? = null
    private var sampleRate = 48000
    private var inputChannelCount = 2
    private var durationMs: Long = 0
    private var currentPositionMs: Long = 0
    
    // MediaSession for notification
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null
    
    // 状態
    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()
    
    private val _spatializerActive = MutableStateFlow(false)
    val spatializerActive: StateFlow<Boolean> = _spatializerActive.asStateFlow()
    
    private val _headTrackingActive = MutableStateFlow(false)
    val headTrackingActive: StateFlow<Boolean> = _headTrackingActive.asStateFlow()
    
    private val _surroundMode = MutableStateFlow(SurroundMode.STANDARD)
    val surroundMode: StateFlow<SurroundMode> = _surroundMode.asStateFlow()
    
    private val _intensity = MutableStateFlow(0.7f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()
    
    inner class LocalBinder : Binder() {
        fun getService(): RealtimePlayerService = this@RealtimePlayerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        spatializer = audioManager?.spatializer
        notificationManager = getSystemService(NotificationManager::class.java)
        
        createNotificationChannel()
        setupMediaSession()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> {
                stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        stop()
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.realtime_spatial_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.realtime_spatial_channel_desc)
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }
    
    private fun setupMediaSession() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        mediaSession = MediaSessionCompat(this, "RealtimePlayer").apply {
            setSessionActivity(pendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }
                override fun onPause() {
                    pause()
                }
                override fun onStop() {
                    stop()
                }
            })
            isActive = true
        }
    }
    
    private fun updateNotification() {
        val isPlaying = _playerState.value == PlayerState.PLAYING
        
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause_24,
                "Pause",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, RealtimePlayerService::class.java).apply { action = ACTION_PAUSE },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play_24,
                "Play",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, RealtimePlayerService::class.java).apply { action = ACTION_PLAY },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_stop_24,
            "Stop",
            PendingIntent.getService(
                this, 0,
                Intent(this, RealtimePlayerService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // アルバムアートを取得
        val albumArt = currentUri?.let { uri ->
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(this, uri)
                    retriever.embeddedPicture?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spatial_audio_24)
            .setContentTitle(_currentFileName.value ?: "Unknown")
            .setContentText("5.1ch 空間オーディオ再生中")
            .setSubText("Spatial Audio")
            .setLargeIcon(albumArt)
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun updateMediaSessionState() {
        val state = when (_playerState.value) {
            PlayerState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlayerState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlayerState.LOADED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }
        
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, currentPositionMs, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .build()
        
        mediaSession?.setPlaybackState(playbackState)
    }
    
    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, _currentFileName.value ?: "Unknown")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "5.1ch Spatial Audio")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build()
        
        mediaSession?.setMetadata(metadata)
    }
    
    fun loadFile(uri: Uri) {
        stop()
        
        try {
            currentUri = uri
            _currentFileName.value = getFileName(uri)
            
            // Duration取得
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(this, uri)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
            } catch (e: Exception) {
                durationMs = 0
            }
            
            // MediaExtractorでファイルを開く
            mediaExtractor = MediaExtractor().apply {
                setDataSource(this@RealtimePlayerService, uri, null)
            }
            
            // オーディオトラックを探す
            val trackIndex = findAudioTrack(mediaExtractor!!)
            if (trackIndex < 0) {
                throw IllegalStateException("オーディオトラックが見つかりません")
            }
            
            mediaExtractor?.selectTrack(trackIndex)
            val format = mediaExtractor?.getTrackFormat(trackIndex)
                ?: throw IllegalStateException("フォーマット取得失敗")
            
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            inputChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            
            Log.d(TAG, "Loaded: $sampleRate Hz, $inputChannelCount ch, mime=$mime, duration=${durationMs}ms")
            
            upmixProcessor.setSampleRate(sampleRate)
            upmixProcessor.setIntensity(_intensity.value)
            
            // デコーダー初期化
            mediaCodec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
            }
            
            // 5.1ch AudioTrack初期化
            setupAudioTrack()
            
            _playerState.value = PlayerState.LOADED
            _errorMessage.value = null
            
            // MediaSession更新
            updateMediaSessionMetadata()
            updateMediaSessionState()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file", e)
            _errorMessage.value = "読み込み失敗: ${e.message}"
            _playerState.value = PlayerState.IDLE
        }
    }
    
    private fun setupAudioTrack() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()
        
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        checkSpatializerState()
    }
    
    fun togglePlayPause() {
        when (_playerState.value) {
            PlayerState.LOADED, PlayerState.PAUSED -> play()
            PlayerState.PLAYING -> pause()
            else -> {}
        }
    }
    
    fun play() {
        if (_playerState.value != PlayerState.LOADED && _playerState.value != PlayerState.PAUSED) return
        
        try {
            // MediaCodecは一度だけstart()を呼ぶ
            if (!isCodecStarted) {
                mediaCodec?.start()
                isCodecStarted = true
            }
            
            audioTrack?.play()
            
            _playerState.value = PlayerState.PLAYING
            startPlaybackLoop()
            
            // 通知更新
            updateMediaSessionState()
            updateNotification()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            _errorMessage.value = "再生失敗: ${e.message}"
        }
    }
    
    fun pause() {
        if (_playerState.value != PlayerState.PLAYING) return
        
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.pause()
        _playerState.value = PlayerState.PAUSED
        
        // 通知更新
        updateMediaSessionState()
        updateNotification()
    }
    
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        isCodecStarted = false
        
        try {
            mediaExtractor?.release()
        } catch (_: Exception) {}
        mediaExtractor = null
        
        upmixProcessor.reset()
        _playerState.value = PlayerState.IDLE
        currentPositionMs = 0
        
        // 通知を削除
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateMediaSessionState()
    }
    
    fun setSurroundMode(mode: SurroundMode) {
        _surroundMode.value = mode
        _intensity.value = mode.intensity
        upmixProcessor.setIntensity(mode.intensity)
    }
    
    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        upmixProcessor.setIntensity(_intensity.value)
    }
    
    private fun startPlaybackLoop() {
        playbackJob = serviceScope.launch(Dispatchers.IO) {
            val extractor = mediaExtractor ?: return@launch
            val codec = mediaCodec ?: return@launch
            val track = audioTrack ?: return@launch
            
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            
            try {
                while (isActive && !outputDone && _playerState.value == PlayerState.PLAYING) {
                    // 入力バッファにデータを供給
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    
                    // 出力バッファからデコード済みデータを取得
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // デコードされたPCMデータをアップミックス
                            val pcmData = extractPcmData(outputBuffer, bufferInfo.size)
                            val stereoData = convertToStereoShort(pcmData, inputChannelCount)
                            val surroundData = upmixProcessor.processTo51Short(stereoData)
                            
                            // 5.1chで再生
                            track.write(surroundData, 0, surroundData.size)
                        }
                        
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
                
                // 再生完了
                if (outputDone) {
                    stop()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                _errorMessage.value = "再生エラー: ${e.message}"
                stop()
            }
        }
    }
    
    private fun extractPcmData(buffer: ByteBuffer, size: Int): ByteArray {
        val data = ByteArray(size)
        buffer.position(0)
        buffer.get(data, 0, size)
        return data
    }
    
    private fun convertToStereoShort(pcmData: ByteArray, channelCount: Int): ShortArray {
        val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)
        
        return when (channelCount) {
            1 -> {
                // モノラル → ステレオに複製
                val stereo = ShortArray(samples.size * 2)
                for (i in samples.indices) {
                    stereo[i * 2] = samples[i]
                    stereo[i * 2 + 1] = samples[i]
                }
                stereo
            }
            2 -> samples
            else -> {
                // マルチチャンネル → ステレオにダウンミックス
                val frameCount = samples.size / channelCount
                val stereo = ShortArray(frameCount * 2)
                for (i in 0 until frameCount) {
                    val srcOffset = i * channelCount
                    val left = samples[srcOffset].toInt()
                    val right = if (channelCount > 1) samples[srcOffset + 1].toInt() else left
                    stereo[i * 2] = left.coerceIn(-32768, 32767).toShort()
                    stereo[i * 2 + 1] = right.coerceIn(-32768, 32767).toShort()
                }
                stereo
            }
        }
    }
    
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
    
    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }
    
    private fun checkSpatializerState() {
        spatializer?.let { spat ->
            _spatializerActive.value = spat.isEnabled && spat.isAvailable
            _headTrackingActive.value = spat.isHeadTrackerAvailable
            Log.d(TAG, "Spatializer: available=${spat.isAvailable}, enabled=${spat.isEnabled}, headTracker=${spat.isHeadTrackerAvailable}")
        }
    }
}
