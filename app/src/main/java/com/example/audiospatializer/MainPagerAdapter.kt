package com.example.audiospatializer

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.audiospatializer.ui.ConvertFragment
import com.example.audiospatializer.ui.MusicsFragment
import com.example.audiospatializer.ui.RealtimeFragment

/**
 * ViewPager2用ページアダプター
 * 
 * メイン画面の3タブ構成を管理する。
 * FragmentStateAdapterを使用し、Fragment状態の保存・復元に対応。
 * 
 * ページ構成:
 * - 0: ConvertFragment（変換画面）
 * - 1: MusicsFragment（音楽ライブラリ画面）
 * - 2: RealtimeFragment（リアルタイム再生画面）
 * 
 * @param activity 親アクティビティ
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    
    /** タブ（ページ）の総数 */
    override fun getItemCount(): Int = 3
    
    /**
     * 指定位置のFragmentを生成
     * 
     * @param position ページ位置（0-2）
     * @return 対応するFragmentインスタンス
     */
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ConvertFragment()    // 変換画面
        1 -> MusicsFragment()     // 音楽ライブラリ画面
        else -> RealtimeFragment() // リアルタイム再生画面
    }
}
