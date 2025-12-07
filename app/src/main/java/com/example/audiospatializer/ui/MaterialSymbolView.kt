package com.example.audiospatializer.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import com.example.audiospatializer.R

/**
 * Material Symbols アイコンを表示するカスタムView
 * 
 * フォントベースでアイコンを表示し、サイズや色を柔軟に変更可能
 */
class MaterialSymbolView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        // Material Symbols アイコンのコードポイント
        // https://fonts.google.com/icons で検索可能
        
        // ファイル・フォルダ関連
        const val ICON_FOLDER_OPEN = "\uE2C8"      // folder_open
        const val ICON_AUDIO_FILE = "\uEB82"       // audio_file
        const val ICON_MUSIC_NOTE = "\uE405"       // music_note
        
        // 再生コントロール
        const val ICON_PLAY_ARROW = "\uE037"       // play_arrow
        const val ICON_PAUSE = "\uE034"            // pause
        const val ICON_STOP = "\uE047"             // stop
        
        // アクション
        const val ICON_CLOSE = "\uE5CD"            // close
        const val ICON_DELETE = "\uE872"           // delete
        const val ICON_EDIT = "\uE3C9"             // edit
        const val ICON_SHARE = "\uE80D"            // share
        const val ICON_TRANSFORM = "\uE428"        // transform
        const val ICON_MORE_VERT = "\uE5D4"        // more_vert
        
        // 状態表示
        const val ICON_CHECK_CIRCLE = "\uE86C"     // check_circle
        const val ICON_ERROR = "\uE000"            // error
        const val ICON_WARNING = "\uE002"          // warning
        const val ICON_INFO = "\uE88E"             // info
        
        // オーディオ関連
        const val ICON_HEADPHONES = "\uF01F"       // headphones
        const val ICON_HEADPHONES_OFF = "\uE605"   // headset_off
        const val ICON_SPATIAL_AUDIO = "\uEBE8"    // spatial_audio
        const val ICON_SURROUND_SOUND = "\uE049"   // surround_sound
        
        private var cachedTypeface: Typeface? = null
    }

    init {
        // フォントを設定
        typeface = getMaterialSymbolsTypeface(context)
        
        // デフォルトスタイル
        includeFontPadding = false
    }

    private fun getMaterialSymbolsTypeface(context: Context): Typeface {
        return cachedTypeface ?: run {
            val tf = ResourcesCompat.getFont(context, R.font.material_symbols_outlined)
                ?: Typeface.DEFAULT
            cachedTypeface = tf
            tf
        }
    }

    /**
     * アイコンを設定
     */
    fun setIcon(iconCode: String) {
        text = iconCode
    }
}
