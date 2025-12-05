package com.example.audiospatializer.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.audiospatializer.R
import com.example.audiospatializer.data.ConvertedTrack
import com.example.audiospatializer.service.PlaybackService
import com.example.audiospatializer.util.FormatUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 音楽リスト・再生画面
 * 
 * MediaSessionServiceと連携し、バックグラウンド再生とMediaStyle通知をサポート
 */
class MusicsFragment : Fragment() {
    
    private val viewModel: MusicsViewModel by viewModels()
    
    // UI要素
    private lateinit var recycler: RecyclerView
    private lateinit var emptyCard: View
    private lateinit var playerCard: View
    private lateinit var playerTitle: TextView
    private lateinit var playerSubtitle: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var playPauseButton: FloatingActionButton
    
    private var adapter: MusicListAdapter? = null
    
    // Media3
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null
    
    private var progressJob: Job? = null
    private var currentTrack: ConvertedTrack? = null

    override fun onCreateView(
        inflater: LayoutInflater, 
        container: ViewGroup?, 
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_musics, container, false)
        
        // View初期化
        recycler = view.findViewById(R.id.recycler)
        emptyCard = view.findViewById(R.id.emptyCard)
        playerCard = view.findViewById(R.id.playerCard)
        playerTitle = view.findViewById(R.id.playerTitle)
        playerSubtitle = view.findViewById(R.id.playerSubtitle)
        playerSeek = view.findViewById(R.id.playerSeek)
        playPauseButton = view.findViewById(R.id.playerPlayPause)

        // RecyclerView設定
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = MusicListAdapter(
            onPlay = { track -> playTrack(track) },
            onMore = { track, anchor -> showTrackMenu(track, anchor) }
        )
        recycler.adapter = adapter

        // シークバー
        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    controller?.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 再生/一時停止ボタン
        playPauseButton.setOnClickListener { togglePlayback() }
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // トラックリスト監視
        viewModel.tracks.observe(viewLifecycleOwner) { tracks ->
            adapter?.submit(tracks)
            val isEmpty = tracks.isEmpty()
            emptyCard.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        initializeController()
    }

    override fun onStop() {
        super.onStop()
        progressJob?.cancel()
        releaseController()
    }

    /**
     * MediaControllerを初期化してサービスに接続
     */
    private fun initializeController() {
        val sessionToken = SessionToken(
            requireContext(),
            ComponentName(requireContext(), PlaybackService::class.java)
        )
        
        controllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()
        controllerFuture?.addListener(
            {
                val mediaController = controller ?: return@addListener
                
                // プレイヤーリスナー設定
                mediaController.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlayerUi()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayerUi()
                        if (isPlaying) {
                            startProgressUpdates()
                        } else {
                            progressJob?.cancel()
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        updatePlayerUi()
                    }
                })
                
                // 既に再生中の場合はUIを更新
                if (mediaController.mediaItemCount > 0) {
                    playerCard.visibility = View.VISIBLE
                    updatePlayerUi()
                    if (mediaController.isPlaying) {
                        startProgressUpdates()
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * MediaControllerを解放
     */
    private fun releaseController() {
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null
    }

    /**
     * トラック再生
     */
    private fun playTrack(track: ConvertedTrack) {
        val file = File(track.filePath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.player_missing_file, Toast.LENGTH_SHORT).show()
            return
        }
        
        currentTrack = track
        
        val mediaController = controller ?: run {
            Toast.makeText(requireContext(), "プレイヤー準備中...", Toast.LENGTH_SHORT).show()
            return
        }
        
        // MediaItemを作成（メタデータ付き）
        val mediaItem = MediaItem.Builder()
            .setUri(file.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.displayName)
                    .setArtist("Spatial Audio")
                    .build()
            )
            .build()
        
        mediaController.setMediaItem(mediaItem)
        mediaController.prepare()
        mediaController.play()
        
        playerCard.visibility = View.VISIBLE
        startProgressUpdates()
        updatePlayerUi()
    }

    /**
     * 進捗更新開始
     */
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val mediaController = controller
                if (mediaController != null) {
                    val duration = mediaController.duration.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
                    val position = mediaController.currentPosition.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
                    playerSeek.max = duration.toInt()
                    playerSeek.progress = position.toInt()
                }
                delay(500)
            }
        }
    }

    /**
     * 再生/一時停止切り替え
     */
    private fun togglePlayback() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) {
            mediaController.pause()
        } else {
            mediaController.play()
        }
    }

    /**
     * プレイヤーUI更新
     */
    private fun updatePlayerUi() {
        val track = currentTrack
        val mediaController = controller
        
        if (track == null && mediaController?.mediaItemCount == 0) {
            playerTitle.text = getString(R.string.player_idle_title)
            playerSubtitle.text = getString(R.string.player_idle_subtitle)
            playerCard.visibility = View.GONE
            return
        }
        
        // トラック情報表示
        if (track != null) {
            playerTitle.text = track.displayName
            val meta = getString(
                R.string.player_meta_template,
                FormatUtils.formatDuration(track.durationMs),
                track.sampleRate,
                track.channelCount
            )
            playerSubtitle.text = meta
        } else if (mediaController != null && mediaController.mediaItemCount > 0) {
            val metadata = mediaController.mediaMetadata
            playerTitle.text = metadata.title ?: "Unknown"
            playerSubtitle.text = metadata.artist ?: ""
        }
        
        val isPlaying = mediaController?.isPlaying == true
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
        )
        playPauseButton.contentDescription = if (isPlaying) getString(R.string.player_pause) else getString(R.string.player_play)
    }

    /**
     * トラック共有
     */
    private fun shareTrack(track: ConvertedTrack) {
        val context = requireContext()
        val file = File(track.filePath)
        val authority = context.packageName + ".fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }
            .getOrElse {
                Toast.makeText(context, R.string.share_audio_error, Toast.LENGTH_SHORT).show()
                return
            }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.share_audio_title, track.displayName)))
        }.onFailure {
            Toast.makeText(context, R.string.share_audio_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * トラックメニュー表示
     */
    private fun showTrackMenu(track: ConvertedTrack, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_track_options, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> {
                        showRenameDialog(track)
                        true
                    }
                    R.id.action_delete -> {
                        showDeleteDialog(track)
                        true
                    }
                    R.id.action_share -> {
                        shareTrack(track)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * リネームダイアログ表示
     */
    private fun showRenameDialog(track: ConvertedTrack) {
        val context = requireContext()
        
        // TextInputLayout + TextInputEditText を使用
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.rename_dialog_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(48, 32, 48, 0)
        }
        
        val editText = TextInputEditText(context).apply {
            setText(track.displayName)
            selectAll()
        }
        inputLayout.addView(editText)
        
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rename_dialog_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text?.toString()?.trim()
                if (!newName.isNullOrEmpty() && newName != track.displayName) {
                    performRename(track, newName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * リネーム実行
     */
    private fun performRename(track: ConvertedTrack, newName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.renameFile(track, newName)
            if (result != null) {
                Toast.makeText(requireContext(), R.string.rename_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.rename_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 削除確認ダイアログ表示
     */
    private fun showDeleteDialog(track: ConvertedTrack) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_dialog_title)
            .setMessage(getString(R.string.delete_dialog_message, track.displayName))
            .setIcon(R.drawable.ic_delete_24)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                performDelete(track)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 削除実行
     */
    private fun performDelete(track: ConvertedTrack) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 再生中のトラックを削除する場合は停止
            if (currentTrack?.id == track.id) {
                controller?.stop()
                controller?.clearMediaItems()
                currentTrack = null
                playerCard.visibility = View.GONE
            }
            
            val success = viewModel.deleteWithFile(track)
            if (success) {
                Toast.makeText(requireContext(), R.string.delete_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.delete_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
