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
        // 更新ボタン
        binding.buttonRefresh.setOnClickListener {
            checkPermissionsAndRefresh()
        }

        // API情報表示
        binding.textApiLevel.text = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        
        // API 33+ 前提なので常に対応
        binding.textSpatializerSupport.text = getString(R.string.status_supported)
        binding.textHeadTrackerSupport.text = getString(R.string.status_supported)
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
            binding.cardNoDevice.visibility = View.GONE
            binding.textDeviceName.text = status.connectedDeviceName ?: getString(R.string.headphone_unknown_device)
            
            // バッジの表示制御
            if (status.isHeadTrackingSupported) {
                binding.badgeSpatialAudio.text = getString(R.string.spatial_audio_supported)
                binding.badgeSpatialAudio.setBackgroundResource(R.drawable.bg_badge_enabled)
                binding.badgeHeadTracking.text = getString(R.string.head_tracking_supported)
                binding.badgeHeadTracking.setBackgroundResource(R.drawable.bg_badge_enabled)
            } else {
                binding.badgeSpatialAudio.text = getString(R.string.spatial_audio_supported)
                binding.badgeSpatialAudio.setBackgroundResource(R.drawable.bg_badge_enabled)
                binding.badgeHeadTracking.text = getString(R.string.head_tracking_not_supported)
                binding.badgeHeadTracking.setBackgroundResource(R.drawable.bg_badge_disabled)
            }
        } else {
            binding.cardConnectedDevice.visibility = View.GONE
            binding.cardNoDevice.visibility = View.VISIBLE
        }

        // スピーカー空間オーディオ対応
        binding.cardSpeakerSpatial.visibility = if (status.speakerSpatialAudioSupported) View.VISIBLE else View.GONE

        // Spatializer状態（アイコン付き）
        binding.textSpatializerAvailable.text = if (status.isSpatializerAvailable) {
            getString(R.string.spatializer_available)
        } else {
            getString(R.string.spatializer_unavailable)
        }
        updateStatusIcon(binding.statusSpatializerAvailable, status.isSpatializerAvailable)

        binding.textSpatializerEnabled.text = if (status.isSpatializerEnabled) {
            getString(R.string.spatializer_enabled)
        } else {
            getString(R.string.spatializer_disabled)
        }
        updateStatusIcon(binding.statusSpatializerEnabled, status.isSpatializerEnabled)

        binding.textHeadTrackerAvailable.text = if (status.isHeadTrackerAvailable) {
            getString(R.string.head_tracker_available)
        } else {
            getString(R.string.head_tracker_unavailable)
        }
        updateStatusIcon(binding.statusHeadTrackerAvailable, status.isHeadTrackerAvailable)

        // イマーシブレベル
        val levelText = when (status.immersiveAudioLevel) {
            1 -> getString(R.string.immersive_multichannel)
            0 -> getString(R.string.immersive_none)
            -1 -> getString(R.string.immersive_other)
            else -> getString(R.string.immersive_unknown)
        }
        binding.textImmersiveLevel.text = levelText

        // 総合ステータス
        updateOverallStatus(status)
    }
    
    private fun updateStatusIcon(iconView: android.widget.ImageView, isPositive: Boolean) {
        if (isPositive) {
            iconView.setImageResource(R.drawable.ic_check_circle_24)
            iconView.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_primary)
        } else {
            iconView.setImageResource(R.drawable.ic_error_24)
            iconView.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_error)
        }
    }
    
    private fun updateOverallStatus(status: HeadTrackingDeviceManager.HeadTrackingStatus) {
        when {
            status.isHeadTrackerAvailable -> {
                binding.textOverallStatus.text = getString(R.string.overall_status_ready)
                binding.textOverallHint.text = getString(R.string.overall_status_ready_hint)
                binding.statusIcon.setImageResource(R.drawable.ic_spatial_tracking_24)
                binding.statusIcon.backgroundTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_primary_container)
                binding.statusIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_on_primary_container)
            }
            status.isSpatializerEnabled && status.isSpatializerAvailable -> {
                binding.textOverallStatus.text = getString(R.string.overall_status_spatial_only)
                binding.textOverallHint.text = getString(R.string.overall_status_spatial_only_hint)
                binding.statusIcon.setImageResource(R.drawable.ic_spatial_audio_24)
                binding.statusIcon.backgroundTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_secondary_container)
                binding.statusIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_on_secondary_container)
            }
            status.isDeviceConnected && !status.isSpatializerEnabled -> {
                binding.textOverallStatus.text = getString(R.string.overall_status_disabled)
                binding.textOverallHint.text = getString(R.string.overall_status_disabled_hint)
                binding.statusIcon.setImageResource(R.drawable.ic_spatial_audio_off_24)
                binding.statusIcon.backgroundTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_error_container)
                binding.statusIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_on_error_container)
            }
            else -> {
                binding.textOverallStatus.text = getString(R.string.overall_status_no_device)
                binding.textOverallHint.text = getString(R.string.overall_status_no_device_hint)
                binding.statusIcon.setImageResource(R.drawable.ic_headphones_off_24)
                binding.statusIcon.backgroundTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_surface_variant)
                binding.statusIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), com.google.android.material.R.color.m3_sys_color_dynamic_light_on_surface_variant)
            }
        }
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
