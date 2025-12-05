package com.example.audiospatializer.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.audiospatializer.R

/**
 * ヘッドトラッキング設定画面用のアクティビティ
 * 
 * HeadTrackingFragmentをホストし、対応デバイスの管理とシステム空間オーディオ設定を提供
 */
class HeadTrackingActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_head_tracking)
        
        // Edge-to-edge対応
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragmentContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // HeadTrackingFragmentを追加
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, HeadTrackingFragment())
                .commit()
        }
    }
}
