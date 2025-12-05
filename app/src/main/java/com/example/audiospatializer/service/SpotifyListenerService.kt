package com.example.audiospatializer.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotifyや他の音楽アプリの再生情報を監視するサービス
 * 
 * NotificationListenerServiceとMediaSessionを使って、
 * 現在再生中の曲情報を取得する
 */
class SpotifyListenerService : NotificationListenerService() {
    
    companion object {
        private const val TAG = "SpotifyListenerService"
        
        // 監視対象のパッケージ
        private val MUSIC_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
            "com.apple.android.music"
        )
        
        private val _currentTrack = MutableStateFlow<TrackInfo?>(null)
        val currentTrack: StateFlow<TrackInfo?> = _currentTrack.asStateFlow()
        
        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
        
        private val _activeApp = MutableStateFlow<String?>(null)
        val activeApp: StateFlow<String?> = _activeApp.asStateFlow()
        
        fun isListenerEnabled(context: Context): Boolean {
            val componentName = ComponentName(context, SpotifyListenerService::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(componentName.flattenToString())
        }
    }
    
    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        val positionMs: Long,
        val packageName: String
    )
    
    private var mediaSessionManager: MediaSessionManager? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SpotifyListenerService created")
        
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        registerMediaSessionListener()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SpotifyListenerService destroyed")
        
        // コールバックを解除
        controllerCallbacks.forEach { (controller, callback) ->
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback", e)
            }
        }
        controllerCallbacks.clear()
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        registerMediaSessionListener()
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        
        // 音楽アプリの通知を検出
        if (sbn.packageName in MUSIC_PACKAGES) {
            Log.d(TAG, "Music notification from ${sbn.packageName}")
            updateFromNotification(sbn)
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        
        if (sbn.packageName in MUSIC_PACKAGES && sbn.packageName == _activeApp.value) {
            Log.d(TAG, "Music notification removed: ${sbn.packageName}")
            _isPlaying.value = false
        }
    }
    
    private fun registerMediaSessionListener() {
        try {
            val controllers = mediaSessionManager?.getActiveSessions(
                ComponentName(this, SpotifyListenerService::class.java)
            ) ?: return
            
            Log.d(TAG, "Found ${controllers.size} active media sessions")
            
            for (controller in controllers) {
                if (controller.packageName in MUSIC_PACKAGES) {
                    registerControllerCallback(controller)
                }
            }
            
            // セッション変更を監視
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                { newControllers ->
                    newControllers?.forEach { controller ->
                        if (controller.packageName in MUSIC_PACKAGES) {
                            registerControllerCallback(controller)
                        }
                    }
                },
                ComponentName(this, SpotifyListenerService::class.java)
            )
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException accessing media sessions", e)
        }
    }
    
    private fun registerControllerCallback(controller: MediaController) {
        if (controllerCallbacks.containsKey(controller)) return
        
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                Log.d(TAG, "Playback state changed: ${state?.state}")
                updatePlaybackState(controller, state)
            }
            
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                Log.d(TAG, "Metadata changed")
                updateMetadata(controller, metadata)
            }
        }
        
        try {
            controller.registerCallback(callback)
            controllerCallbacks[controller] = callback
            Log.d(TAG, "Registered callback for ${controller.packageName}")
            
            // 現在の状態を取得
            updatePlaybackState(controller, controller.playbackState)
            updateMetadata(controller, controller.metadata)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error registering callback", e)
        }
    }
    
    private fun updatePlaybackState(controller: MediaController, state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        _isPlaying.value = playing
        
        if (playing) {
            _activeApp.value = controller.packageName
        }
        
        // 再生位置を更新
        val currentTrackValue = _currentTrack.value
        if (currentTrackValue != null && state != null) {
            _currentTrack.value = currentTrackValue.copy(positionMs = state.position)
        }
    }
    
    private fun updateMetadata(controller: MediaController, metadata: MediaMetadata?) {
        metadata ?: return
        
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        
        _currentTrack.value = TrackInfo(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            positionMs = controller.playbackState?.position ?: 0,
            packageName = controller.packageName
        )
        
        Log.d(TAG, "Track: $title by $artist (${duration}ms)")
    }
    
    private fun updateFromNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getString(Notification.EXTRA_TEXT)
        
        if (title != null) {
            // 通知からの情報で更新（MediaSessionからの情報がない場合のフォールバック）
            val currentTrackValue = _currentTrack.value
            if (currentTrackValue == null || currentTrackValue.title != title) {
                Log.d(TAG, "Updated from notification: $title - $text")
            }
        }
        
        _activeApp.value = sbn.packageName
    }
}
