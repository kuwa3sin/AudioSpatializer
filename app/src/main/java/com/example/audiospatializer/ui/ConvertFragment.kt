package com.example.audiospatializer.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiospatializer.AudioProcessor
import com.example.audiospatializer.AudioSpatializerApp
import com.example.audiospatializer.R
import com.example.audiospatializer.data.ConvertedTrack
import com.example.audiospatializer.data.OutputModeItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * オーディオ変換画面
 * 
 * ファイル選択 → 設定 → 変換開始/中断の2ステップUI
 * 
 * 画面遷移時も変換が継続され、戻ってきた際にプログレスが復元される
 */
class ConvertFragment : Fragment() {

    // UI要素
    private lateinit var btnPick: MaterialButton
    private lateinit var selectedFileContainer: MaterialCardView
    private lateinit var selectedFileName: TextView
    private lateinit var selectedFileInfo: TextView
    private lateinit var btnClearFile: MaterialButton
    private lateinit var outputModeDropdown: TextInputLayout
    private lateinit var outputModeAutoComplete: MaterialAutoCompleteTextView
    private lateinit var outputModeDescription: TextView
    private lateinit var outputModeCard: MaterialCardView
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var btnStart: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
    private lateinit var btnCancel: MaterialButton
    
    private lateinit var outputModeItems: List<OutputModeItem>
    private var selectedOutputMode: AudioProcessor.OutputMode = AudioProcessor.OutputMode.HRTF_SURROUND_5_1_IMMERSIVE

    // 状態管理（画面遷移を跨いで保持）
    private var selectedUri: Uri? = null
    private var conversionJob: Job? = null
    
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "audio_conversion_channel"
        private const val NOTIFICATION_ID = 1001
        
        // 静的な変換状態（画面遷移を跨いで保持）
        private val _conversionProgress = MutableStateFlow(ConversionState())
        val conversionProgress = _conversionProgress.asStateFlow()
        
        private var activeJob: Job? = null
    }
    
    /**
     * 変換状態データクラス
     */
    data class ConversionState(
        val isConverting: Boolean = false,
        val progress: Int = 0,
        val stage: String = "",
        val fileName: String? = null,
        val result: ConversionResult? = null
    )
    
    enum class ConversionResult { SUCCESS, FAILED, CANCELLED }
    
    // 現在の変換状態
    private val isConverting: Boolean
        get() = _conversionProgress.value.isConverting

    // ランチャー
    private lateinit var pickLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    // 依存関係
    private lateinit var audioProcessor: AudioProcessor

    private val repository by lazy {
        (requireActivity().application as AudioSpatializerApp).repository
    }

    private val notificationManager by lazy {
        requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioProcessor = AudioProcessor(requireContext())

        // 通知権限リクエスト
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 結果は無視 */ }

        // ファイルピッカー
        pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // 永続的な読み取り権限を取得
                    try {
                        val flags = (result.data?.flags ?: 0) and 
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri, 
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    } catch (_: Exception) {}
                    
                    setSelectedFile(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_convert, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupListeners()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        observeConversionState()
        updateUIState()
    }
    
    /**
     * 変換状態を監視してUIを更新
     */
    private fun observeConversionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                conversionProgress.collect { state ->
                    updateUIFromState(state)
                }
            }
        }
    }
    
    /**
     * 状態からUIを更新
     */
    private fun updateUIFromState(state: ConversionState) {
        if (!isAdded) return
        
        progressBar.progress = state.progress.coerceIn(0, 100)
        
        when {
            state.result == ConversionResult.SUCCESS -> {
                progressText.text = getString(R.string.status_done)
                statusIcon.setImageResource(R.drawable.ic_check_circle_24)
                statusIcon.visibility = View.VISIBLE
                progressBar.progress = 100
            }
            state.result == ConversionResult.FAILED -> {
                progressText.text = getString(R.string.status_failed)
                statusIcon.setImageResource(R.drawable.ic_error_24)
                statusIcon.visibility = View.VISIBLE
            }
            state.result == ConversionResult.CANCELLED -> {
                progressText.text = getString(R.string.status_cancelled)
                statusIcon.visibility = View.GONE
                progressBar.progress = 0
            }
            state.isConverting -> {
                statusIcon.visibility = View.GONE
                progressText.text = if (state.progress in 1 until 100) {
                    "${getString(R.string.status_spatializing)}: ${state.progress}%"
                } else {
                    state.stage.ifEmpty { getString(R.string.status_spatializing) }
                }
            }
        }
        
        updateUIState()
    }

    private fun initViews(view: View) {
        btnPick = view.findViewById(R.id.btnPick)
        selectedFileContainer = view.findViewById(R.id.selectedFileContainer)
        selectedFileName = view.findViewById(R.id.selectedFileName)
        selectedFileInfo = view.findViewById(R.id.selectedFileInfo)
        btnClearFile = view.findViewById(R.id.btnClearFile)
        outputModeDropdown = view.findViewById(R.id.outputModeDropdown)
        outputModeAutoComplete = view.findViewById(R.id.outputModeAutoComplete)
        outputModeDescription = view.findViewById(R.id.outputModeDescription)
        outputModeCard = view.findViewById(R.id.outputModeCard)
        progressCard = view.findViewById(R.id.progressCard)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)
        statusIcon = view.findViewById(R.id.statusIcon)
        btnStart = view.findViewById(R.id.btnStart)
        btnCancel = view.findViewById(R.id.btnCancel)
        
        // 出力モードリストを初期化
        outputModeItems = listOf(
            OutputModeItem(
                AudioProcessor.OutputMode.HRTF_SURROUND_5_1_IMMERSIVE,
                getString(R.string.output_mode_immersive),
                getString(R.string.output_mode_immersive_desc)
            ),
            OutputModeItem(
                AudioProcessor.OutputMode.HRTF_SURROUND_5_1_FAST,
                getString(R.string.output_mode_fast),
                getString(R.string.output_mode_fast_desc)
            )
            // 以下はマスク（将来的に復活可能）
            // OutputModeItem(
            //     AudioProcessor.OutputMode.HRTF_SURROUND_7_1,
            //     getString(R.string.output_mode_71),
            //     getString(R.string.output_mode_71_desc)
            // ),
            // OutputModeItem(
            //     AudioProcessor.OutputMode.HRTF_BINAURAL,
            //     getString(R.string.output_mode_binaural),
            //     getString(R.string.output_mode_binaural_desc)
            // ),
            // OutputModeItem(
            //     AudioProcessor.OutputMode.HRTF_SURROUND_5_1,
            //     getString(R.string.output_mode_surround),
            //     getString(R.string.output_mode_surround_desc)
            // )
        )
        
        setupOutputModeDropdown()
    }
    
    /**
     * 出力モードプルダウンをセットアップ
     */
    private fun setupOutputModeDropdown() {
        val displayNames = outputModeItems.map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        outputModeAutoComplete.setAdapter(adapter)
        
        // デフォルト選択
        if (outputModeItems.isNotEmpty()) {
            outputModeAutoComplete.setText(outputModeItems[0].displayName, false)
            outputModeDescription.text = outputModeItems[0].description
            selectedOutputMode = outputModeItems[0].mode
        }
        
        // 選択変更時
        outputModeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            val item = outputModeItems[position]
            outputModeDescription.text = item.description
            selectedOutputMode = item.mode
        }
    }

    private fun setupListeners() {
        btnPick.setOnClickListener { openPicker() }
        btnClearFile.setOnClickListener { clearSelectedFile() }
        btnStart.setOnClickListener { startConversion() }
        btnCancel.setOnClickListener { cancelConversion() }
    }

    /**
     * UI状態の更新
     */
    private fun updateUIState() {
        val hasFile = selectedUri != null
        
        // ファイル選択エリア
        selectedFileContainer.visibility = if (hasFile) View.VISIBLE else View.GONE
        
        // ボタン状態
        btnStart.isEnabled = hasFile && !isConverting
        btnCancel.isEnabled = isConverting
        btnPick.isEnabled = !isConverting
        btnClearFile.isEnabled = !isConverting
        
        // 設定カード
        setCardEnabled(outputModeCard, !isConverting)
        
        // ステータステキスト
        if (!isConverting && selectedUri == null) {
            progressText.text = getString(R.string.status_ready)
            statusIcon.visibility = View.GONE
            progressBar.progress = 0
        } else if (!isConverting && selectedUri != null) {
            progressText.text = getString(R.string.status_file_selected)
            statusIcon.visibility = View.GONE
            progressBar.progress = 0
        }
    }

    /**
     * カード内の要素を有効/無効化
     */
    private fun setCardEnabled(card: MaterialCardView, enabled: Boolean) {
        card.alpha = if (enabled) 1f else 0.6f
        outputModeDropdown.isEnabled = enabled
        outputModeAutoComplete.isEnabled = enabled
    }

    /**
     * ファイルピッカーを開く
     */
    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/wav", "audio/x-wav",
                "audio/flac", "audio/x-flac",
                "audio/aac", "audio/mp4", "audio/m4a",
                "audio/mpeg", "audio/mp3"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickLauncher.launch(intent)
    }

    /**
     * 選択されたファイルを設定
     */
    private fun setSelectedFile(uri: Uri) {
        selectedUri = uri
        
        // ファイル情報を取得
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                
                val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                
                selectedFileName.text = name
                selectedFileInfo.text = formatFileSize(size)
            }
        }
        
        updateUIState()
    }

    /**
     * ファイル選択をクリア
     */
    private fun clearSelectedFile() {
        selectedUri = null
        updateUIState()
    }

    /**
     * ファイルサイズをフォーマット
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> getString(R.string.file_size_format, bytes / (1024f * 1024f))
            bytes >= 1024 -> getString(R.string.file_size_kb_format, bytes / 1024f)
            else -> "$bytes B"
        }
    }

    /**
     * 選択された出力モードを取得
     */
    private fun getSelectedOutputMode(): AudioProcessor.OutputMode {
        return selectedOutputMode
    }

    /**
     * 変換を開始
     */
    private fun startConversion() {
        val uri = selectedUri ?: return
        val selectedMode = getSelectedOutputMode()
        
        // 状態を更新
        _conversionProgress.value = ConversionState(
            isConverting = true,
            progress = 0,
            stage = getString(R.string.status_preparing),
            fileName = selectedFileName.text.toString()
        )
        
        updateUIState()
        showProgressNotification(0, getString(R.string.status_preparing))
        
        // アプリケーションスコープでジョブを実行（画面遷移に影響されない）
        val app = requireActivity().application as AudioSpatializerApp
        activeJob = app.applicationScope.launch {
            val result = withContext(Dispatchers.IO) {
                audioProcessor.processAudio(uri, selectedMode) { percent, stage ->
                    // 状態を更新（StateFlowで自動的にUIに反映）
                    _conversionProgress.value = _conversionProgress.value.copy(
                        progress = percent.coerceIn(0, 100),
                        stage = stage
                    )
                    showProgressNotification(percent, stage)
                }
            }
            
            // 通知をキャンセル
            notificationManager.cancel(NOTIFICATION_ID)
            
            val success = result != null
            if (success) {
                result?.let { saveConvertedMetadata(it) }
            }
            
            // 完了状態を更新
            _conversionProgress.value = ConversionState(
                isConverting = false,
                progress = if (success) 100 else 0,
                stage = "",
                fileName = result?.file?.name,
                result = if (success) ConversionResult.SUCCESS else ConversionResult.FAILED
            )
            
            // 完了通知を表示
            showCompletionNotification(result?.file?.name, success)
            
            // Snackbarを表示（Fragmentがアタッチされている場合のみ）
            if (isAdded) {
                val message = if (success) {
                    getString(R.string.conversion_success, result!!.file.name)
                } else {
                    getString(R.string.conversion_failed)
                }
                view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
            }
            
            activeJob = null
        }
        
        conversionJob = activeJob
    }

    /**
     * 変換をキャンセル
     */
    private fun cancelConversion() {
        activeJob?.cancel()
        activeJob = null
        conversionJob = null
        
        notificationManager.cancel(NOTIFICATION_ID)
        
        // キャンセル状態を更新
        _conversionProgress.value = ConversionState(
            isConverting = false,
            progress = 0,
            stage = "",
            result = ConversionResult.CANCELLED
        )
        
        updateUIState()
        
        view?.let { 
            Snackbar.make(it, getString(R.string.status_cancelled), Snackbar.LENGTH_SHORT).show() 
        }
    }

    // =========================================================================
    // 通知関連
    // =========================================================================

    private fun requestNotificationPermissionIfNeeded() {
        // API 33+では通知権限が必要
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.conversion_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.conversion_notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun showProgressNotification(progress: Int, stage: String) {
        val title = getString(R.string.conversion_notification_title)
        val text = if (progress in 1 until 100) {
            getString(R.string.status_converting_with_percent, progress)
        } else {
            stage
        }

        val notification = NotificationCompat.Builder(requireContext(), NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_transform_24)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSilent(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(fileName: String?, success: Boolean) {
        val title = getString(R.string.conversion_notification_title)
        val text = if (success && fileName != null) {
            getString(R.string.conversion_success, fileName)
        } else {
            getString(R.string.conversion_failed)
        }

        val notification = NotificationCompat.Builder(requireContext(), NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(
                if (success) R.drawable.ic_check_circle_24
                else R.drawable.ic_error_24
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    // =========================================================================
    // データ保存
    // =========================================================================

    private suspend fun saveConvertedMetadata(result: AudioProcessor.ProcessResult) {
        val durationMs = if (result.sampleRate > 0) {
            (result.frameCount * 1000L) / result.sampleRate
        } else {
            0L
        }
        val track = ConvertedTrack(
            displayName = result.file.name,
            filePath = result.file.absolutePath,
            durationMs = durationMs,
            sampleRate = result.sampleRate,
            channelCount = result.channelCount,
            fileSizeBytes = result.file.length(),
            outputMode = result.outputMode.name
        )
        repository.save(track)
    }
}
