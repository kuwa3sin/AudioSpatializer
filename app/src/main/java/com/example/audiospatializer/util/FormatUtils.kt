package com.example.audiospatializer.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 共通フォーマットユーティリティ
 * 
 * 時間・サイズ・日付のフォーマット関数を提供
 */
object FormatUtils {
    
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    
    /**
     * ミリ秒をMM:SS形式にフォーマット
     * @param durationMs ミリ秒
     * @return フォーマット済み文字列（例: "03:45"）、無効な場合は"--:--"
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "--:--"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
    
    /**
     * バイト数を人間が読みやすい形式にフォーマット
     * @param bytes バイト数
     * @return フォーマット済み文字列（例: "12.5MB"）
     */
    fun formatFileSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            bytes < kb -> "${bytes}B"
            bytes < mb -> "%.1fKB".format(bytes / kb)
            else -> "%.1fMB".format(bytes / mb)
        }
    }
    
    /**
     * タイムスタンプを日時文字列にフォーマット
     * @param timestamp Unix時刻（ミリ秒）
     * @return フォーマット済み文字列（例: "2024/01/15 14:30"）
     */
    fun formatDateTime(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
