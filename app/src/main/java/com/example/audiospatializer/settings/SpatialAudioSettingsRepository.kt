package com.example.audiospatializer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore拡張プロパティ
 * 
 * Context に spatialSettingsDataStore プロパティを追加し、
 * 空間オーディオ設定専用のDataStoreインスタンスを提供する。
 */
private val Context.spatialSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "spatial_audio_settings"
)

/**
 * 空間オーディオ設定データクラス
 * 
 * @property immersiveEnabled イマーシブオーディオが有効か
 * @property headTrackingEnabled ヘッドトラッキングが有効か
 */
data class SpatialAudioSettings(
    /** イマーシブオーディオ有効フラグ */
    val immersiveEnabled: Boolean = false,
    /** ヘッドトラッキング有効フラグ */
    val headTrackingEnabled: Boolean = false
)

/**
 * 空間オーディオ設定リポジトリ
 * 
 * Jetpack DataStoreを使用して空間オーディオ関連の設定を永続化する。
 * Flowベースのリアクティブな設定監視と、suspendベースの設定更新をサポート。
 * 
 * 機能:
 * - イマーシブオーディオのON/OFF設定
 * - ヘッドトラッキングのON/OFF設定
 * - 設定変更のリアルタイム監視
 * 
 * @param context アプリケーションコンテキスト
 */
class SpatialAudioSettingsRepository(private val context: Context) {

    companion object {
        /** イマーシブ有効設定のキー */
        private val KEY_IMMERSIVE_ENABLED = booleanPreferencesKey("immersive_enabled")
        /** ヘッドトラッキング有効設定のキー */
        private val KEY_HEAD_TRACKING_ENABLED = booleanPreferencesKey("head_tracking_enabled")
    }

    /**
     * 設定のFlow
     * 
     * DataStoreの変更を自動的に検知し、最新の設定を発行する。
     */
    val settingsFlow: Flow<SpatialAudioSettings> = context.spatialSettingsDataStore.data.map { prefs ->
        SpatialAudioSettings(
            immersiveEnabled = prefs[KEY_IMMERSIVE_ENABLED] ?: false,
            headTrackingEnabled = prefs[KEY_HEAD_TRACKING_ENABLED] ?: false
        )
    }

    /**
     * 設定を更新
     * 
     * 指定された設定のみ更新する。nullの設定は変更しない。
     * 
     * @param immersiveEnabled イマーシブオーディオ設定（nullで変更なし）
     * @param headTrackingEnabled ヘッドトラッキング設定（nullで変更なし）
     */
    suspend fun updateSettings(
        immersiveEnabled: Boolean? = null,
        headTrackingEnabled: Boolean? = null
    ) {
        context.spatialSettingsDataStore.edit { prefs ->
            immersiveEnabled?.let { prefs[KEY_IMMERSIVE_ENABLED] = it }
            headTrackingEnabled?.let { prefs[KEY_HEAD_TRACKING_ENABLED] = it }
        }
    }

    /**
     * イマーシブオーディオをトグル
     * 
     * イマーシブをOFFにした場合、ヘッドトラッキングも自動的にOFFになる。
     * 
     * @return 更新後の設定
     */
    suspend fun toggleImmersive(): SpatialAudioSettings {
        val current = currentSettings()
        val nextImmersive = !current.immersiveEnabled
        context.spatialSettingsDataStore.edit { prefs ->
            prefs[KEY_IMMERSIVE_ENABLED] = nextImmersive
            // イマーシブOFF時はヘッドトラッキングも無効化
            if (!nextImmersive) {
                prefs[KEY_HEAD_TRACKING_ENABLED] = false
            }
        }
        return SpatialAudioSettings(
            immersiveEnabled = nextImmersive,
            headTrackingEnabled = if (nextImmersive) current.headTrackingEnabled else false
        )
    }

    /**
     * 現在の設定を取得
     * 
     * @return 現在の設定値
     */
    suspend fun currentSettings(): SpatialAudioSettings = settingsFlow.first()
}
