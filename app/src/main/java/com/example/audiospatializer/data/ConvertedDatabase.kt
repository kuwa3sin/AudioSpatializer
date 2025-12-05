package com.example.audiospatializer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 変換済みトラックのRoomデータベース
 * 
 * アプリで変換した音楽ファイルのメタデータを永続化する。
 * シングルトンパターンで一意のインスタンスを保証。
 * 
 * スキーマバージョン履歴:
 * - v1: 初期スキーマ
 * - v2: outputModeカラム追加（バイノーラル/サラウンド区別）
 * 
 * @see ConvertedTrack エンティティクラス
 * @see ConvertedTrackDao データアクセスオブジェクト
 */
@Database(
    entities = [ConvertedTrack::class],
    version = 2,
    exportSchema = false
)
abstract class ConvertedDatabase : RoomDatabase() {
    
    /**
     * DAOインスタンスを取得
     * @return ConvertedTrackDao
     */
    abstract fun convertedTrackDao(): ConvertedTrackDao

    companion object {
        /** スレッドセーフなシングルトンインスタンス */
        @Volatile
        private var INSTANCE: ConvertedDatabase? = null

        /**
         * マイグレーション: v1 → v2
         * 
         * outputModeカラムを追加し、既存レコードには'SURROUND_5_1'をデフォルト設定
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE converted_tracks ADD COLUMN outputMode TEXT NOT NULL DEFAULT 'SURROUND_5_1'")
            }
        }

        /**
         * データベースインスタンスを取得（シングルトン）
         * 
         * ダブルチェックロッキングでスレッドセーフに初期化。
         * 
         * @param context アプリケーションコンテキスト
         * @return ConvertedDatabase インスタンス
         */
        fun getInstance(context: Context): ConvertedDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConvertedDatabase::class.java,
                    "converted_tracks.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
        }
    }
}
