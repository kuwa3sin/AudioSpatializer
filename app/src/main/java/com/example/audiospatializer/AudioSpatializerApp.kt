package com.example.audiospatializer

import android.app.Application
import com.example.audiospatializer.data.ConvertedDatabase
import com.example.audiospatializer.data.ConvertedRepository
import com.example.audiospatializer.settings.SpatialAudioSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * アプリケーションクラス
 * 
 * アプリ全体で共有するシングルトンインスタンス（データベース、リポジトリ等）を管理。
 * アプリの起動時に初期化され、アプリ終了まで存続する。
 * 
 * 管理するインスタンス:
 * - database: Room データベース（変換済みトラック情報の永続化）
 * - repository: トラック情報のCRUD操作を提供
 * - spatialSettings: 空間オーディオ設定の永続化
 * - applicationScope: アプリスコープのCoroutineScope（長時間処理用）
 */
class AudioSpatializerApp : Application() {
    
    /**
     * Room データベースインスタンス
     * 
     * 遅延初期化（lazy）で、最初のアクセス時にのみ生成される。
     * シングルトンパターンで一意のインスタンスを保証。
     */
    val database: ConvertedDatabase by lazy { ConvertedDatabase.getInstance(this) }
    
    /**
     * トラック情報リポジトリ
     * 
     * データベースへのアクセスを抽象化し、CRUD操作を提供する。
     * ファイル操作とデータベース操作を連携して実行。
     */
    val repository: ConvertedRepository by lazy { ConvertedRepository(database.convertedTrackDao()) }
    
    /**
     * 空間オーディオ設定リポジトリ
     * 
     * ヘッドトラッキングのON/OFF等の設定をDataStoreで永続化。
     */
    val spatialSettings: SpatialAudioSettingsRepository by lazy { SpatialAudioSettingsRepository(this) }
    
    /**
     * アプリケーションスコープ（画面遷移に影響されない）
     * 
     * 変換処理などの長時間タスクに使用する。
     * SupervisorJobにより、子ジョブの失敗が他に影響しない。
     * Dispatchers.Mainをデフォルトとし、UIスレッドからの安全な呼び出しを可能に。
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
