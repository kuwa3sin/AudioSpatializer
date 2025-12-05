package com.example.audiospatializer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiospatializer.R
import com.example.audiospatializer.audio.HeadTrackingDeviceManager
import com.example.audiospatializer.databinding.FragmentHeadTrackingBinding
import kotlinx.coroutines.launch

/**
 * ヘッドトラッキング設定画面
 * 
 * - 対応デバイスの接続状態表示
 * - システムSpatializer状態表示  
 * - ホワイトリストデバイス一覧表示
 */
class HeadTrackingFragment : Fragment() {

    private var _binding: FragmentHeadTrackingBinding? = null
    private val binding get() = _binding!!

    private lateinit var deviceManager: HeadTrackingDeviceManager
    private lateinit var deviceListAdapter: SupportedDeviceAdapter
    private var listenersRegistered = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            deviceManager.refreshStatus()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.head_tracking_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHeadTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceManager = HeadTrackingDeviceManager(requireContext())

        setupToolbar()
        setupUI()
        checkPermissionsAndRefresh()
        observeStatus()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun setupUI() {
        // デバイスリストのセットアップ
        deviceListAdapter = SupportedDeviceAdapter(deviceManager.getSupportedDevicesList())
        binding.recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceListAdapter
        }

        // 更新ボタン
        binding.buttonRefresh.setOnClickListener {
            checkPermissionsAndRefresh()
        }

        // API情報表示
        binding.textApiLevel.text = "Android API: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})"
        
        // API 33+ 前提なので常に対応
        binding.textSpatializerSupport.text = "Spatializer API: ✓ 対応"
        binding.textHeadTrackerSupport.text = "HeadTracker API: ✓ 対応"
    }

    private fun checkPermissionsAndRefresh() {
        // API 33+ではBLUETOOTH_CONNECT権限が必要
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED -> {
                deviceManager.refreshStatus()
            }
            else -> {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun observeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            deviceManager.statusFlow.collect { status ->
                updateStatusUI(status)
            }
        }

        // Spatializerリスナー登録
        try {
            deviceManager.registerSpatializerListeners(
                ContextCompat.getMainExecutor(requireContext())
            )
            listenersRegistered = true
        } catch (e: Exception) {
            // Spatializerが利用できない場合
            listenersRegistered = false
        }
    }

    private fun updateStatusUI(status: HeadTrackingDeviceManager.HeadTrackingStatus) {
        // 接続デバイス情報
        if (status.isDeviceConnected) {
            binding.cardConnectedDevice.visibility = View.VISIBLE
            binding.textNoDevice.visibility = View.GONE
            binding.textDeviceName.text = status.connectedDeviceName ?: "不明なデバイス"
            binding.textDeviceManufacturer.text = status.connectedDeviceManufacturer ?: ""
            
            val headTrackingText = if (status.isHeadTrackingSupported) {
                "✓ ヘッドトラッキング対応"
            } else {
                "✗ ヘッドトラッキング非対応"
            }
            binding.textHeadTrackingSupport.text = headTrackingText
            
            // 背景色を変更
            val backgroundColor = if (status.isHeadTrackingSupported) {
                ContextCompat.getColor(requireContext(), android.R.color.holo_green_light)
            } else {
                ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light)
            }
            binding.cardConnectedDevice.setCardBackgroundColor(backgroundColor)
        } else {
            binding.cardConnectedDevice.visibility = View.GONE
            binding.textNoDevice.visibility = View.VISIBLE
            binding.textNoDevice.text = getString(R.string.head_tracking_status_no_device)
        }

        // Spatializer状態
        binding.textSpatializerAvailable.text = if (status.isSpatializerAvailable) {
            "✓ Spatializer利用可能"
        } else {
            "✗ Spatializer利用不可"
        }

        binding.textSpatializerEnabled.text = if (status.isSpatializerEnabled) {
            "✓ Spatializer有効"
        } else {
            "✗ Spatializer無効（設定で有効化してください）"
        }

        binding.textHeadTrackerAvailable.text = if (status.isHeadTrackerAvailable) {
            "✓ システムヘッドトラッカー利用可能"
        } else {
            "✗ システムヘッドトラッカー利用不可"
        }

        val levelText = when (status.immersiveAudioLevel) {
            1 -> "マルチチャンネル空間化対応"
            0 -> "空間化非対応"
            -1 -> "その他の空間化モード"
            else -> "不明"
        }
        binding.textImmersiveLevel.text = "イマーシブオーディオレベル: $levelText"

        // 総合ステータス
        val overallStatus = when {
            status.isHeadTrackerAvailable -> {
                "🎧 ヘッドトラッキング空間オーディオが利用可能です"
            }
            status.isSpatializerEnabled && status.isSpatializerAvailable -> {
                "🔊 空間オーディオが利用可能です（ヘッドトラッキングなし）"
            }
            status.isDeviceConnected && status.isHeadTrackingSupported -> {
                "⚠️ 対応デバイス接続中ですが、システム設定でSpatializerを有効にしてください"
            }
            status.isDeviceConnected -> {
                "ℹ️ 接続中のデバイスはヘッドトラッキング非対応です"
            }
            else -> {
                "📱 対応Bluetoothデバイスを接続してください"
            }
        }
        binding.textOverallStatus.text = overallStatus
    }

    override fun onResume() {
        super.onResume()
        deviceManager.refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (listenersRegistered) {
            deviceManager.unregisterSpatializerListeners()
            listenersRegistered = false
        }
        _binding = null
    }
}
