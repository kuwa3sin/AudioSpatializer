package com.example.audiospatializer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.audiospatializer.audio.SpatialAudioController
import com.example.audiospatializer.ui.HeadTrackingActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * メインアクティビティ
 * 
 * アプリのエントリーポイント。3つのタブを持つViewPager2を管理する。
 * 
 * タブ構成:
 * - Convert: 音楽ファイルを5.1ch空間オーディオに変換
 * - Musics: 変換済み音楽ファイルの再生
 * - Realtime: リアルタイム5.1ch空間化再生
 * 
 * デバイスカード表示優先順位:
 * 1. ヘッドホン接続時: ヘッドホン名 + 機能バッジ
 * 2. トランスオーラル対応 & ヘッドホン未接続: スマホ名 + トランスオーラル対応
 * 3. トランスオーラル非対応 & ヘッドホン未接続: 未接続メッセージ + 接続ボタン
 */
class MainActivity : AppCompatActivity() {
    
    // デバイスカード
    private lateinit var deviceCard: MaterialCardView
    private lateinit var deviceIcon: ImageView
    private lateinit var deviceName: TextView
    private lateinit var badgeContainer: LinearLayout
    private lateinit var spatialAudioBadge: TextView
    private lateinit var headTrackingBadge: TextView
    private lateinit var transauralBadge: TextView
    private lateinit var btnDeviceAction: MaterialButton
    
    // Spatial Audio Controller
    private lateinit var spatialController: SpatialAudioController
    
    // 現在の状態
    private var currentHeadphoneInfo: SpatialAudioController.HeadphoneInfo? = null
    private var currentSpeakerInfo: SpatialAudioController.SpeakerSpatialInfo? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // デバイスカード初期化
        deviceCard = findViewById(R.id.deviceCard)
        deviceIcon = findViewById(R.id.deviceIcon)
        deviceName = findViewById(R.id.deviceName)
        badgeContainer = findViewById(R.id.badgeContainer)
        spatialAudioBadge = findViewById(R.id.spatialAudioBadge)
        headTrackingBadge = findViewById(R.id.headTrackingBadge)
        transauralBadge = findViewById(R.id.transauralBadge)
        btnDeviceAction = findViewById(R.id.btnDeviceAction)
        
        // カードタップでデバイス情報画面へ遷移
        deviceCard.setOnClickListener {
            startActivity(Intent(this, HeadTrackingActivity::class.java))
        }
        
        // バッジタップでSpatializer設定へ遷移
        spatialAudioBadge.setOnClickListener {
            openSpatializerSettings()
        }
        headTrackingBadge.setOnClickListener {
            openSpatializerSettings()
        }
        
        // SpatialAudioController初期化
        spatialController = SpatialAudioController(this)

        // ViewPager2の設定
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        
        // TabLayoutとViewPager2を連携
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_convert)
                1 -> getString(R.string.tab_musics)
                else -> getString(R.string.tab_realtime)
            }
        }.attach()
        
        // ヘッドホンとスピーカー情報を組み合わせて監視
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                spatialController.headphoneInfo.combine(spatialController.speakerSpatialInfo) { headphone, speaker ->
                    Pair(headphone, speaker)
                }.collect { (headphone, speaker) ->
                    currentHeadphoneInfo = headphone
                    currentSpeakerInfo = speaker
                    updateDeviceCard(headphone, speaker)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        spatialController.refreshState()
        spatialController.refreshHeadphoneInfo()
        spatialController.refreshSpeakerInfo()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        spatialController.release()
    }
    
    /**
     * デバイスカードを更新
     * 
     * 優先順位:
     * 1. ヘッドホン接続時
     * 2. トランスオーラル対応 & ヘッドホン未接続
     * 3. トランスオーラル非対応 & ヘッドホン未接続
     */
    private fun updateDeviceCard(
        headphone: SpatialAudioController.HeadphoneInfo,
        speaker: SpatialAudioController.SpeakerSpatialInfo
    ) {
        when {
            // ケース1: ヘッドホン接続時
            headphone.isConnected -> {
                showHeadphoneConnectedState(headphone)
            }
            // ケース2: トランスオーラル対応 & ヘッドホン未接続
            speaker.supportsSpatialAudio -> {
                showTransauralSupportedState(speaker)
            }
            // ケース3: トランスオーラル非対応 & ヘッドホン未接続
            else -> {
                showNoDeviceState()
            }
        }
    }
    
    /**
     * ケース1: ヘッドホン接続状態の表示
     */
    private fun showHeadphoneConnectedState(headphone: SpatialAudioController.HeadphoneInfo) {
        // アイコン: ヘッドホン
        deviceIcon.setImageResource(R.drawable.ic_headphones_24)
        deviceIcon.backgroundTintList = ContextCompat.getColorStateList(this, 
            com.google.android.material.R.color.m3_sys_color_dynamic_light_primary_container)
        deviceIcon.imageTintList = ContextCompat.getColorStateList(this,
            com.google.android.material.R.color.m3_sys_color_dynamic_light_on_primary_container)
        
        // デバイス名
        deviceName.text = headphone.deviceName ?: getString(R.string.headphone_unknown_device)
        
        // トランスオーラルバッジは非表示
        transauralBadge.visibility = View.GONE
        
        // 空間オーディオバッジ
        spatialAudioBadge.visibility = View.VISIBLE
        if (headphone.supportsSpatialAudio) {
            spatialAudioBadge.text = getString(R.string.badge_spatial_audio)
            spatialAudioBadge.setBackgroundResource(R.drawable.bg_badge_enabled)
            spatialAudioBadge.isClickable = true
        } else {
            spatialAudioBadge.text = getString(R.string.spatial_audio_not_supported)
            spatialAudioBadge.setBackgroundResource(R.drawable.bg_badge_disabled)
            spatialAudioBadge.isClickable = false
        }
        
        // ヘッドトラッキングバッジ
        headTrackingBadge.visibility = View.VISIBLE
        if (headphone.supportsHeadTracking) {
            headTrackingBadge.text = getString(R.string.badge_head_tracking)
            headTrackingBadge.setBackgroundResource(R.drawable.bg_badge_enabled)
            headTrackingBadge.isClickable = true
        } else {
            headTrackingBadge.text = getString(R.string.head_tracking_not_supported)
            headTrackingBadge.setBackgroundResource(R.drawable.bg_badge_disabled)
            headTrackingBadge.isClickable = false
        }
        
        // ボタン: 設定
        btnDeviceAction.text = getString(R.string.btn_settings)
        btnDeviceAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    
    /**
     * ケース2: トランスオーラル対応 & ヘッドホン未接続
     */
    private fun showTransauralSupportedState(speaker: SpatialAudioController.SpeakerSpatialInfo) {
        // アイコン: スピーカー
        deviceIcon.setImageResource(R.drawable.ic_speaker_24)
        deviceIcon.backgroundTintList = ContextCompat.getColorStateList(this,
            com.google.android.material.R.color.m3_sys_color_dynamic_light_tertiary_container)
        deviceIcon.imageTintList = ContextCompat.getColorStateList(this,
            com.google.android.material.R.color.m3_sys_color_dynamic_light_on_tertiary_container)
        
        // デバイス名: スマホ名
        deviceName.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        
        // 空間オーディオ・ヘッドトラッキングバッジは非表示
        spatialAudioBadge.visibility = View.GONE
        headTrackingBadge.visibility = View.GONE
        
        // トランスオーラルバッジを表示
        transauralBadge.visibility = View.VISIBLE
        transauralBadge.text = getString(R.string.speaker_spatial_audio_supported)
        transauralBadge.setBackgroundResource(R.drawable.bg_badge_enabled)
        
        // ボタン: 設定
        btnDeviceAction.text = getString(R.string.btn_settings)
        btnDeviceAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    
    /**
     * ケース3: トランスオーラル非対応 & ヘッドホン未接続
     */
    private fun showNoDeviceState() {
        // アイコン: ヘッドホンオフ
        deviceIcon.setImageResource(R.drawable.ic_headphones_off_24)
        deviceIcon.backgroundTintList = ContextCompat.getColorStateList(this,
            com.google.android.material.R.color.m3_sys_color_dynamic_light_error_container)
        deviceIcon.imageTintList = ContextCompat.getColorStateList(this,
            com.google.android.material.R.color.m3_sys_color_dynamic_light_on_error_container)
        
        // デバイス名
        deviceName.text = getString(R.string.no_headphone_connected)
        
        // 空間オーディオ・ヘッドトラッキングバッジは非表示
        spatialAudioBadge.visibility = View.GONE
        headTrackingBadge.visibility = View.GONE
        
        // トランスオーラル非対応バッジを表示
        transauralBadge.visibility = View.VISIBLE
        transauralBadge.text = getString(R.string.badge_transaural_not_supported)
        transauralBadge.setBackgroundResource(R.drawable.bg_badge_disabled)
        
        // ボタン: 接続
        btnDeviceAction.text = getString(R.string.btn_connect)
        btnDeviceAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    
    /**
     * Spatializer設定を開く
     */
    private fun openSpatializerSettings() {
        try {
            // Android 13+ では音声設定を開く
            val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // フォールバック
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
