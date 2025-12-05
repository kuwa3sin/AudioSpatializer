package com.example.audiospatializer.data

import com.example.audiospatializer.AudioProcessor

/**
 * 出力モード選択アイテム
 * 
 * UI表示用のモード情報を保持するデータクラス
 * 
 * @param mode AudioProcessorの出力モード
 * @param displayName UI表示用の名前
 * @param description モードの説明文
 */
data class OutputModeItem(
    val mode: AudioProcessor.OutputMode,
    val displayName: String,
    val description: String
)
