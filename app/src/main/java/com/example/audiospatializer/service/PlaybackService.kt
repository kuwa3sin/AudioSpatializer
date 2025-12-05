package com.example.audiospatializer.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.audiospatializer.MainActivity

/**
 * バックグラウンド再生対応のMediaSessionService
 * 
 * Media3を使用した音楽再生サービス。以下の機能を提供:
 * - MediaStyle通知の自動表示
 * - システムメディアコントロール（ロック画面、Bluetooth等）との連携
 * - バックグラウンド再生
 * - オーディオフォーカス管理
 * - ヘッドフォン抜き差し時の自動一時停止
 * 
 * 使用方法:
 * MediaControllerを使用してこのサービスに接続し、再生制御を行う。
 * 
 * @see MediaSession Media3のセッション管理
 * @see ExoPlayer 実際の再生を行うプレイヤー
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    /** MediaSessionインスタンス */
    private var mediaSession: MediaSession? = null

    /**
     * サービス作成時の初期化
     * 
     * ExoPlayerとMediaSessionを作成し、再生準備を行う。
     */
    override fun onCreate() {
        super.onCreate()
        
        // ExoPlayer作成（空間オーディオ対応設定）
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)           // メディア再生用途
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)  // 音楽コンテンツ
                    .build(),
                true  // handleAudioFocus: 他アプリとのオーディオフォーカス管理を自動化
            )
            .setHandleAudioBecomingNoisy(true)  // ヘッドフォン抜き差し時に自動一時停止
            .build()

        // 通知タップ時にMainActivityを開くIntent
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP  // 既存のActivityを再利用
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // MediaSession作成（メディアコントロールの中核）
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)  // 通知タップ時のActivity
            .build()
    }

    /**
     * MediaSessionを取得
     * 
     * 他のコンポーネント（MediaController等）がセッションに接続する際に呼ばれる。
     * 
     * @param controllerInfo 接続要求元の情報
     * @return 接続を許可するセッション、拒否する場合はnull
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * タスクが削除された時の処理
     * 
     * ユーザーがアプリをスワイプで終了した場合に呼ばれる。
     * 再生中でない場合はサービスを停止してリソースを解放。
     * 
     * @param rootIntent 削除されたタスクのIntent
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            // 再生中でない、またはメディアがない場合はサービス終了
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    /**
     * サービス破棄時のクリーンアップ
     * 
     * プレイヤーとセッションを解放してリソースリークを防止。
     */
    override fun onDestroy() {
        mediaSession?.run {
            player.release()   // ExoPlayerのリソース解放
            release()          // MediaSessionのリソース解放
            mediaSession = null
        }
        super.onDestroy()
    }
}
