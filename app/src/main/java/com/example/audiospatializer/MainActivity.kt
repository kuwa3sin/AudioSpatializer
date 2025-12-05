package com.example.audiospatializer

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
 * 機能:
 * - Edge-to-Edge UI対応
 * - Material 3 Toolbarによるナビゲーション
 * - オプションメニューからヘッドトラッキング画面へのアクセス
 * - ヘッドフォン接続状態の表示
 */
class MainActivity : AppCompatActivity() {
    
    // Headphone info views
    private lateinit var headphoneCard: MaterialCardView
    private lateinit var noHeadphoneCard: MaterialCardView
    private lateinit var headphoneIcon: ImageView
    private lateinit var headphoneName: TextView
    private lateinit var spatialAudioBadge: TextView
    private lateinit var headTrackingBadge: TextView
    private lateinit var btnOpenBluetooth: MaterialButton
    
    // Spatial Audio Controller
    private lateinit var spatialController: SpatialAudioController
    
    /**
     * アクティビティ作成時の処理
     * 
     * レイアウト設定、ツールバー設定、ViewPager2とTabLayoutの連携を行う
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-Edge表示を有効化（ステータスバー・ナビゲーションバー透過）
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Material Toolbarをアクションバーとして設定
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Headphone info views初期化
        headphoneCard = findViewById(R.id.headphoneCard)
        noHeadphoneCard = findViewById(R.id.noHeadphoneCard)
        headphoneIcon = findViewById(R.id.headphoneIcon)
        headphoneName = findViewById(R.id.headphoneName)
        spatialAudioBadge = findViewById(R.id.spatialAudioBadge)
        headTrackingBadge = findViewById(R.id.headTrackingBadge)
        btnOpenBluetooth = findViewById(R.id.btnOpenBluetooth)
        
        // Bluetooth設定を開くボタン
        btnOpenBluetooth.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        
        // SpatialAudioController初期化
        spatialController = SpatialAudioController(this)

        // ViewPager2の設定
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        
        // TabLayoutとViewPager2を連携
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            // 各タブのテキストを設定
            tab.text = when (position) {
                0 -> getString(R.string.tab_convert)   // 変換タブ
                1 -> getString(R.string.tab_musics)    // 音楽タブ
                else -> getString(R.string.tab_realtime) // リアルタイムタブ
            }
        }.attach()
        
        // ヘッドホン接続状態の監視
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                spatialController.headphoneInfo.collect { headphone ->
                    updateHeadphoneUI(headphone)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        spatialController.refreshState()
        spatialController.refreshHeadphoneInfo()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        spatialController.release()
    }
    
    /**
     * ヘッドフォンUI更新
     */
    private fun updateHeadphoneUI(headphone: SpatialAudioController.HeadphoneInfo) {
        if (headphone.isConnected) {
            headphoneCard.visibility = View.VISIBLE
            noHeadphoneCard.visibility = View.GONE
            headphoneName.text = headphone.deviceName ?: getString(R.string.headphone_unknown_device)
            headphoneIcon.setImageResource(R.drawable.ic_headphones_24)
            
            // Spatial Audio対応状況
            if (headphone.supportsSpatialAudio) {
                spatialAudioBadge.text = getString(R.string.spatial_audio_supported)
                spatialAudioBadge.setBackgroundResource(R.drawable.bg_badge_enabled)
            } else {
                spatialAudioBadge.text = getString(R.string.spatial_audio_not_supported)
                spatialAudioBadge.setBackgroundResource(R.drawable.bg_badge_disabled)
            }
            
            // Head Tracking対応状況
            if (headphone.supportsHeadTracking) {
                headTrackingBadge.text = getString(R.string.head_tracking_supported)
                headTrackingBadge.setBackgroundResource(R.drawable.bg_badge_enabled)
            } else {
                headTrackingBadge.text = getString(R.string.head_tracking_not_supported)
                headTrackingBadge.setBackgroundResource(R.drawable.bg_badge_disabled)
            }
        } else {
            headphoneCard.visibility = View.GONE
            noHeadphoneCard.visibility = View.VISIBLE
        }
    }
    
    /**
     * オプションメニュー作成
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    /**
     * オプションメニュー項目選択時の処理
     * 
     * @param item 選択されたメニュー項目
     * @return 処理が完了した場合true
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_head_tracking -> {
                // ヘッドトラッキング設定画面へ遷移
                startActivity(Intent(this, HeadTrackingActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}