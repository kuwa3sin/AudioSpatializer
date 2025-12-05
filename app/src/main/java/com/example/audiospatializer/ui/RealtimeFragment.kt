package com.example.audiospatializer.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiospatializer.R
import com.example.audiospatializer.service.RealtimePlayerService
import com.example.audiospatializer.service.SpotifyListenerService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/**
 * リアルタイム5.1ch空間化プレーヤーUI
 * 
 * 音楽ファイルを選択し、変換せずにリアルタイムで5.1ch空間化再生
 * - アップミックス対応
 * - ヘッドトラッキング対応（Spatializer API）
 */
class RealtimeFragment : Fragment() {

    private lateinit var btnSelectFile: MaterialButton
    private lateinit var btnPlayPause: FloatingActionButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var spatializerStatusText: TextView
    private lateinit var headTrackingStatusText: TextView
    private lateinit var hintText: TextView
    private lateinit var warningCard: MaterialCardView
    private lateinit var warningText: TextView
    private lateinit var pulseIndicator: View
    private lateinit var chipSpatializer: Chip
    private lateinit var chipHeadTracking: Chip
    
    // Spotify連携UI
    private var spotifyCard: MaterialCardView? = null
    private var spotifyTrackText: TextView? = null
    private var spotifyArtistText: TextView? = null
    private var btnSpotifySync: MaterialButton? = null
    
    // アニメーション
    private var pulseAnimator: ObjectAnimator? = null

    private var service: RealtimePlayerService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? RealtimePlayerService.LocalBinder
            service = localBinder?.getService()
            bound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 永続的な権限を取得
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            service?.loadFile(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_realtime, container, false)
        
        btnSelectFile = view.findViewById(R.id.btnSelectFile)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnStop = view.findViewById(R.id.btnStop)
        statusCard = view.findViewById(R.id.statusCard)
        statusText = view.findViewById(R.id.statusText)
        fileNameText = view.findViewById(R.id.fileNameText)
        spatializerStatusText = view.findViewById(R.id.spatializerStatusText)
        headTrackingStatusText = view.findViewById(R.id.headTrackingStatusText)
        hintText = view.findViewById(R.id.hintText)
        warningCard = view.findViewById(R.id.warningCard)
        warningText = view.findViewById(R.id.warningText)
        pulseIndicator = view.findViewById(R.id.pulseIndicator)
        chipSpatializer = view.findViewById(R.id.chipSpatializer)
        chipHeadTracking = view.findViewById(R.id.chipHeadTracking)
        
        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }
        
        btnPlayPause.setOnClickListener {
            service?.togglePlayPause()
        }
        
        btnStop.setOnClickListener {
            service?.stop()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Spotify連携UIの初期化（オプション）
        spotifyCard = view.findViewById(R.id.spotifyCard)
        spotifyTrackText = view.findViewById(R.id.spotifyTrackText)
        spotifyArtistText = view.findViewById(R.id.spotifyArtistText)
        btnSpotifySync = view.findViewById(R.id.btnSpotifySync)
        
        btnSpotifySync?.setOnClickListener {
            checkNotificationListenerPermission()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RealtimePlayerService.playerState.collect { state ->
                        updateUI(state)
                    }
                }
                launch {
                    RealtimePlayerService.errorMessage.collect { error ->
                        if (error != null) {
                            showWarning(error)
                        } else {
                            hideWarning()
                        }
                    }
                }
                // Spotify再生情報を監視
                launch {
                    SpotifyListenerService.currentTrack.collect { track ->
                        updateSpotifyUI(track)
                    }
                }
                launch {
                    SpotifyListenerService.isPlaying.collect { playing ->
                        updateSpotifyPlayingState(playing)
                    }
                }
            }
        }
    }
    
    private fun checkNotificationListenerPermission() {
        if (!SpotifyListenerService.isListenerEnabled(requireContext())) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("通知へのアクセス")
                .setMessage("Spotifyの再生情報を取得するには、通知へのアクセスを許可してください。\n\n設定画面で「Audio Spatializer」を有効にしてください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            Toast.makeText(requireContext(), "通知アクセスは有効です。Spotifyで再生を開始してください。", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateSpotifyUI(track: SpotifyListenerService.TrackInfo?) {
        spotifyCard?.visibility = if (track != null) View.VISIBLE else View.GONE
        
        if (track != null) {
            spotifyTrackText?.text = track.title
            spotifyArtistText?.text = track.artist
            
            // アプリ名を表示
            val appName = when (track.packageName) {
                "com.spotify.music" -> "Spotify"
                "com.google.android.apps.youtube.music" -> "YouTube Music"
                "com.amazon.mp3" -> "Amazon Music"
                "com.apple.android.music" -> "Apple Music"
                else -> track.packageName
            }
            btnSpotifySync?.text = "🎵 $appName で再生中"
        }
    }
    
    private fun updateSpotifyPlayingState(playing: Boolean) {
        // 再生中のみカードを強調
        spotifyCard?.let { card ->
            if (playing) {
                card.strokeWidth = 4
                card.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(com.google.android.material.R.color.design_default_color_primary)
                ))
            } else {
                card.strokeWidth = 1
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(requireContext(), RealtimePlayerService::class.java)
        requireContext().startService(intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            requireContext().unbindService(serviceConnection)
            bound = false
        }
    }

    private fun updateUI(state: RealtimePlayerService.PlayerState) {
        when (state) {
            RealtimePlayerService.PlayerState.IDLE -> {
                statusCard.visibility = View.GONE
                hintText.visibility = View.VISIBLE
                btnPlayPause.isEnabled = false
                btnStop.isEnabled = false
                stopPulseAnimation()
            }
            RealtimePlayerService.PlayerState.LOADED -> {
                statusCard.visibility = View.VISIBLE
                hintText.visibility = View.GONE
                statusText.text = "準備完了"
                btnPlayPause.isEnabled = true
                btnPlayPause.setImageResource(R.drawable.ic_play_24)
                btnStop.isEnabled = false
                stopPulseAnimation()
            }
            RealtimePlayerService.PlayerState.PLAYING -> {
                statusCard.visibility = View.VISIBLE
                hintText.visibility = View.GONE
                statusText.text = "再生中 - 5.1ch空間化"
                btnPlayPause.isEnabled = true
                btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                btnStop.isEnabled = true
                startPulseAnimation()
            }
            RealtimePlayerService.PlayerState.PAUSED -> {
                statusText.text = "一時停止"
                btnPlayPause.setImageResource(R.drawable.ic_play_24)
                stopPulseAnimation()
            }
        }
    }
    
    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.6f, 1f)
        
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(pulseIndicator, scaleX, scaleY, alpha).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
    
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseIndicator.scaleX = 1f
        pulseIndicator.scaleY = 1f
        pulseIndicator.alpha = 1f
    }
    
    private fun showWarning(message: String) {
        warningCard.visibility = View.VISIBLE
        warningText.text = message
    }
    
    private fun hideWarning() {
        warningCard.visibility = View.GONE
    }

    private fun observeService() {
        val svc = service ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.currentFileName.collect { name ->
                        fileNameText.text = name ?: "ファイル未選択"
                    }
                }
                launch {
                    svc.spatializerActive.collect { active ->
                        spatializerStatusText.text = if (active) "Spatializer: 有効" else "Spatializer: 無効"
                        chipSpatializer.isChecked = active
                        chipSpatializer.text = if (active) "Spatializer ✓" else "Spatializer"
                    }
                }
                launch {
                    svc.headTrackingActive.collect { active ->
                        headTrackingStatusText.text = if (active) "ヘッドトラッキング: 有効" else "ヘッドトラッキング: 無効"
                        chipHeadTracking.isChecked = active
                        chipHeadTracking.text = if (active) "Head Tracking ✓" else "Head Tracking"
                    }
                }
            }
        }
    }
}
