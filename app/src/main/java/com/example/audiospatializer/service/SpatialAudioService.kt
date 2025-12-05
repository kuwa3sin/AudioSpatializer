package com.example.audiospatializer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.audiospatializer.AudioSpatializerApp
import com.example.audiospatializer.MainActivity
import com.example.audiospatializer.R
import com.example.audiospatializer.audio.SpatialAudioController
import com.example.audiospatializer.settings.SpatialAudioSettings
import com.example.audiospatializer.settings.SpatialAudioSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 空間オーディオ制御サービス
 * 
 * Android 13以降のSpatializer APIを制御するフォアグラウンドサービス。
 * 設定のリアルタイム監視と、通知バーからの操作を提供する。
 * 
 * 機能:
 * - イマーシブオーディオのON/OFF制御
 * - ヘッドトラッキングの有効/無効制御
 * - フォアグラウンド通知によるクイック操作
 * - Quick Settings タイルとの連携
 * 
 * 使用方法:
 * コンパニオンオブジェクトのヘルパーメソッドを使用してサービスを操作する。
 * 例: SpatialAudioService.enable(context)
 */
class SpatialAudioService : Service() {

    companion object {
        // =========================================================================
        // 定数
        // =========================================================================
        private const val CHANNEL_ID = "spatial_audio_channel"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PREFIX = "com.example.audiospatializer.service.SpatialAudioService."
        
        /** 設定同期アクション */
        const val ACTION_SYNC = ACTION_PREFIX + "SYNC"
        /** 有効化アクション */
        private const val ACTION_ENABLE = ACTION_PREFIX + "ENABLE"
        /** 無効化アクション */
        private const val ACTION_DISABLE = ACTION_PREFIX + "DISABLE"
        /** トグルアクション */
        private const val ACTION_TOGGLE = ACTION_PREFIX + "TOGGLE"
        /** ヘッドトラッキング設定アクション */
        private const val ACTION_SET_HEAD = ACTION_PREFIX + "SET_HEAD"
        /** ヘッドトラッキング状態のExtra key */
        private const val EXTRA_HEAD_STATE = "extra_head_state"

        // =========================================================================
        // ヘルパーメソッド
        // =========================================================================
        
        /** 設定を同期してサービスを更新 */
        fun requestSync(context: Context) = start(context, ACTION_SYNC)
        
        /** イマーシブオーディオをトグル */
        fun toggle(context: Context) = start(context, ACTION_TOGGLE)
        
        /** イマーシブオーディオを有効化 */
        fun enable(context: Context) = start(context, ACTION_ENABLE)
        
        /** イマーシブオーディオを無効化 */
        fun disable(context: Context) = start(context, ACTION_DISABLE)
        
        /**
         * ヘッドトラッキングを設定
         * @param enabled 有効にする場合true
         */
        fun setHeadTracking(context: Context, enabled: Boolean) {
            val intent = Intent(context, SpatialAudioService::class.java).apply {
                action = ACTION_SET_HEAD
                putExtra(EXTRA_HEAD_STATE, enabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * サービスを指定アクションで開始
         */
        private fun start(context: Context, action: String) {
            val intent = Intent(context, SpatialAudioService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    // =========================================================================
    // インスタンス変数
    // =========================================================================
    
    /** サービススコープ用Job（キャンセル可能） */
    private val serviceJob = SupervisorJob()
    
    /** サービススコープのCoroutineScope */
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    
    /** 空間オーディオ制御クラス */
    private lateinit var controller: SpatialAudioController
    
    /** 設定リポジトリ */
    private lateinit var repository: SpatialAudioSettingsRepository
    
    /** 現在の設定 */
    private var currentSettings = SpatialAudioSettings()

    /**
     * サービス作成時の初期化
     * 
     * 通知チャンネル作成、設定監視開始、フォアグラウンドサービス開始
     */
    override fun onCreate() {
        super.onCreate()
        controller = SpatialAudioController(this)
        repository = (application as AudioSpatializerApp).spatialSettings
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(currentSettings))
        
        // 設定変更を監視してリアルタイムに適用
        serviceScope.launch {
            repository.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                applySettings(settings)
                updateNotification(settings)
                requestTileRefresh()  // Quick Settings タイルを更新
            }
        }
    }

    /**
     * Intentを受信した時の処理
     * 
     * アクションに応じて設定を変更する。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> updateImmersive(enabled = true)
            ACTION_DISABLE -> disableAll()
            ACTION_TOGGLE -> toggleImmersive()
            ACTION_SET_HEAD -> {
                val state = intent.getBooleanExtra(EXTRA_HEAD_STATE, false)
                updateHeadTracking(state)
            }
            ACTION_SYNC -> Unit  // 設定を同期するだけ（Flowで自動適用）
            else -> Unit
        }
        return START_STICKY  // システムに強制終了されても自動再起動
    }

    // =========================================================================
    // 設定変更メソッド
    // =========================================================================

    /**
     * イマーシブオーディオを有効/無効化
     */
    private fun updateImmersive(enabled: Boolean) {
        serviceScope.launch {
            repository.updateSettings(
                immersiveEnabled = enabled,
                headTrackingEnabled = if (enabled) null else false  // 無効時はヘッドトラッキングもOFF
            )
        }
    }

    /**
     * 全ての空間オーディオ機能を無効化
     */
    private fun disableAll() {
        serviceScope.launch {
            repository.updateSettings(immersiveEnabled = false, headTrackingEnabled = false)
        }
    }

    /**
     * イマーシブオーディオをトグル
     */
    private fun toggleImmersive() {
        serviceScope.launch {
            repository.toggleImmersive()
        }
    }

    /**
     * ヘッドトラッキングを設定
     */
    private fun updateHeadTracking(enabled: Boolean) {
        serviceScope.launch {
            if (enabled) {
                // ヘッドトラッキングON時はイマーシブもON
                repository.updateSettings(immersiveEnabled = true, headTrackingEnabled = true)
            } else {
                repository.updateSettings(headTrackingEnabled = false)
            }
        }
    }

    /**
     * 設定をSpatializer APIに適用
     */
    private fun applySettings(settings: SpatialAudioSettings) {
        controller.setSpatializationEnabled(settings.immersiveEnabled)
        controller.setHeadTrackingEnabled(settings.headTrackingEnabled && settings.immersiveEnabled)
        controller.refreshState()
    }

    // =========================================================================
    // 通知関連
    // =========================================================================

    /**
     * 通知を更新
     */
    private fun updateNotification(settings: SpatialAudioSettings) {
        val manager = NotificationManagerCompat.from(this)
        manager.notify(NOTIFICATION_ID, buildNotification(settings))
    }

    /**
     * 通知を構築
     * 
     * 現在の設定に基づいて通知の内容とアクションボタンを設定
     */
    private fun buildNotification(settings: SpatialAudioSettings): Notification {
        val contentText = if (settings.immersiveEnabled) {
            getString(R.string.spatial_service_enabled)
        } else {
            getString(R.string.spatial_service_disabled)
        }
        val actionLabel = if (settings.immersiveEnabled) {
            getString(R.string.spatial_service_action_disable)
        } else {
            getString(R.string.spatial_service_action_enable)
        }
        val actionIntent = if (settings.immersiveEnabled) {
            createServicePendingIntent(ACTION_DISABLE)
        } else {
            createServicePendingIntent(ACTION_ENABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.spatial_service_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(createMainPendingIntent())
            .setOngoing(true)  // スワイプで消せない
            .addAction(0, actionLabel, actionIntent)
            .build()
    }

    /**
     * MainActivityを開くPendingIntent作成
     */
    private fun createMainPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * サービスアクションのPendingIntent作成
     */
    private fun createServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, SpatialAudioService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            action.hashCode(),  // 各アクションで異なるrequestCodeを使用
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知チャンネルを作成
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_spatial_audio),
            NotificationManager.IMPORTANCE_LOW  // 音/振動なし
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    /**
     * Quick Settings タイルの更新をリクエスト
     */
    private fun requestTileRefresh() {
        val component = ComponentName(this, SpatialAudioTileService::class.java)
        TileService.requestListeningState(this, component)
    }

    override fun onBind(intent: Intent?) = null

    /**
     * サービス破棄時のクリーンアップ
     */
    override fun onDestroy() {
        super.onDestroy()
        controller.release()
        serviceScope.cancel()
    }
}
