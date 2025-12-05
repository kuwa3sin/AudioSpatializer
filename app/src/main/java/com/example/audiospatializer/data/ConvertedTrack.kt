package com.example.audiospatializer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 変換済み音楽トラックのエンティティ
 * 
 * Roomデータベースに保存される変換済み音楽ファイルのメタデータ。
 * 元のファイルを5.1ch/7.1ch空間オーディオに変換した結果を記録する。
 * 
 * @property id 一意識別子（自動生成）
 * @property displayName 表示名（ファイル名ベース）
 * @property filePath ファイルの絶対パス
 * @property durationMs 再生時間（ミリ秒）
 * @property sampleRate サンプリングレート（Hz）
 * @property channelCount チャンネル数（2=ステレオ、6=5.1ch、8=7.1ch）
 * @property createdAt 作成日時（UNIXタイムスタンプ、ミリ秒）
 * @property fileSizeBytes ファイルサイズ（バイト）
 * @property outputMode 出力モード（"HRTF_BINAURAL", "HRTF_SURROUND_5_1"等）
 */
@Entity(tableName = "converted_tracks")
data class ConvertedTrack(
    /** 一意識別子（自動生成） */
    @PrimaryKey(autoGenerate = true) 
    val id: Long = 0,
    
    /** 表示名（ユーザーにリネーム可能） */
    val displayName: String,
    
    /** ファイルの絶対パス */
    val filePath: String,
    
    /** 再生時間（ミリ秒） */
    val durationMs: Long,
    
    /** サンプリングレート（Hz、通常44100 or 48000） */
    val sampleRate: Int,
    
    /** チャンネル数（2, 6, 8のいずれか） */
    val channelCount: Int,
    
    /** 作成日時（UNIXタイムスタンプ、ミリ秒） */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** ファイルサイズ（バイト） */
    val fileSizeBytes: Long,
    
    /** 
     * 出力モード
     * - "HRTF_BINAURAL": 2chバイノーラル
     * - "HRTF_SURROUND_5_1": 5.1chサラウンド
     * - "HRTF_SURROUND_5_1_IMMERSIVE": 5.1chイマーシブ
     * - "HRTF_SURROUND_7_1": 7.1chサラウンド
     * - "HRTF_SURROUND_5_1_FAST": 5.1ch高速
     */
    val outputMode: String = "SURROUND_5_1"
)
