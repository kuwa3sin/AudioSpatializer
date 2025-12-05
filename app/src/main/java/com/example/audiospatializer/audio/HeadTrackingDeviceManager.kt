package com.example.audiospatializer.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Spatializer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * ヘッドトラッキング対応デバイスの管理クラス
 * 
 * ホワイトリスト方式で対応デバイスを検出し、
 * Android Spatializer APIと連携してヘッドトラッキング機能を提供
 */
class HeadTrackingDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "HeadTrackingDeviceManager"
        
        /**
         * ヘッドトラッキング対応デバイスのホワイトリスト
         * 
         * デバイス名の部分一致でマッチング
         * 新しいデバイスは随時追加可能
         */
        val SUPPORTED_DEVICES = listOf(
            // Google Pixel Buds シリーズ
            SupportedDevice("Pixel Buds Pro", "Google", true),
            SupportedDevice("Pixel Buds Pro 2", "Google", true),
            
            // Sony WH シリーズ (オーバーイヤーヘッドホン)
            SupportedDevice("WH-1000XM5", "Sony", true),
            SupportedDevice("WH-1000XM4", "Sony", true),
            SupportedDevice("WH-1000XM6", "Sony", true),  // 将来対応
            
            // Sony WF シリーズ (完全ワイヤレスイヤホン)
            SupportedDevice("WF-1000XM5", "Sony", true),
            SupportedDevice("WF-1000XM4", "Sony", true),
            SupportedDevice("LinkBuds S", "Sony", true),
            
            // Samsung Galaxy Buds シリーズ
            SupportedDevice("Galaxy Buds Pro", "Samsung", true),
            SupportedDevice("Galaxy Buds2 Pro", "Samsung", true),
            SupportedDevice("Galaxy Buds FE", "Samsung", false), // ヘッドトラッキング非対応
            
            // Apple (Android接続時は制限あり)
            SupportedDevice("AirPods Pro", "Apple", false),  // Androidでは制限あり
            SupportedDevice("AirPods Max", "Apple", false),
            
            // Bose
            SupportedDevice("Bose QuietComfort Ultra", "Bose", true),
            SupportedDevice("Bose QC Ultra Earbuds", "Bose", true),
            
            // Sennheiser
            SupportedDevice("MOMENTUM 4", "Sennheiser", true),
            SupportedDevice("MOMENTUM TW 3", "Sennheiser", true),
            
            // JBL
            SupportedDevice("JBL Tour Pro 2", "JBL", true),
            SupportedDevice("JBL Tour One M2", "JBL", true),
        )
    }
    
    data class SupportedDevice(
        val namePattern: String,
        val manufacturer: String,
        val headTrackingSupported: Boolean
    )
    
    data class HeadTrackingStatus(
        val isDeviceConnected: Boolean = false,
        val connectedDeviceName: String? = null,
        val connectedDeviceManufacturer: String? = null,
        val isHeadTrackingSupported: Boolean = false,
        val isSpatializerAvailable: Boolean = false,
        val isSpatializerEnabled: Boolean = false,
        val isHeadTrackerAvailable: Boolean = false,
        val immersiveAudioLevel: Int = 0
    )
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    
    private val _statusFlow = MutableStateFlow(HeadTrackingStatus())
    val statusFlow: StateFlow<HeadTrackingStatus> = _statusFlow.asStateFlow()
    
    private var spatializerStateListener: Spatializer.OnSpatializerStateChangedListener? = null
    private var headTrackerListener: Spatializer.OnHeadTrackerAvailableListener? = null
    
    /**
     * デバイス状態を更新
     */
    fun refreshStatus() {
        val connectedDevice = findConnectedSupportedDevice()
        val spatializerStatus = checkSpatializerStatus()
        
        _statusFlow.value = HeadTrackingStatus(
            isDeviceConnected = connectedDevice != null,
            connectedDeviceName = connectedDevice?.first,
            connectedDeviceManufacturer = connectedDevice?.second?.manufacturer,
            isHeadTrackingSupported = connectedDevice?.second?.headTrackingSupported ?: false,
            isSpatializerAvailable = spatializerStatus.first,
            isSpatializerEnabled = spatializerStatus.second,
            isHeadTrackerAvailable = spatializerStatus.third,
            immersiveAudioLevel = spatializerStatus.fourth
        )
        
        Log.d(TAG, "Status updated: ${_statusFlow.value}")
    }
    
    /**
     * 接続中のサポート対象デバイスを検索
     */
    private fun findConnectedSupportedDevice(): Pair<String, SupportedDevice>? {
        // Bluetooth A2DP デバイスを取得
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                
                val deviceName = device.productName?.toString() ?: continue
                Log.d(TAG, "Found Bluetooth device: $deviceName")
                
                // ホワイトリストとマッチング
                val supportedDevice = SUPPORTED_DEVICES.find { supported ->
                    deviceName.contains(supported.namePattern, ignoreCase = true)
                }
                
                if (supportedDevice != null) {
                    Log.d(TAG, "Matched supported device: ${supportedDevice.namePattern}")
                    return Pair(deviceName, supportedDevice)
                }
            }
        }
        
        return null
    }
    
    /**
     * Spatializer APIの状態を確認 (API 33+)
     */
    private fun checkSpatializerStatus(): Quadruple<Boolean, Boolean, Boolean, Int> {
        return try {
            val spatializer = audioManager.spatializer
            val isAvailable = spatializer.isAvailable
            val isEnabled = spatializer.isEnabled
            val isHeadTrackerAvailable = spatializer.isHeadTrackerAvailable
            val level = spatializer.immersiveAudioLevel
            
            Log.d(TAG, "Spatializer: available=$isAvailable, enabled=$isEnabled, " +
                    "headTracker=$isHeadTrackerAvailable, level=$level")
            
            Quadruple(isAvailable, isEnabled, isHeadTrackerAvailable, level)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get spatializer status", e)
            Quadruple(false, false, false, 0)
        }
    }
    
    /**
     * Spatializer状態変化リスナーを登録 (API 33+)
     */
    fun registerSpatializerListeners(executor: Executor) {
        val spatializer = audioManager.spatializer
        
        // 状態変化リスナー
        spatializerStateListener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) {
                Log.d(TAG, "Spatializer enabled changed: $enabled")
                refreshStatus()
            }
            
            override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) {
                Log.d(TAG, "Spatializer available changed: $available")
                refreshStatus()
            }
        }
        spatializer.addOnSpatializerStateChangedListener(executor, spatializerStateListener!!)
        
        // ヘッドトラッカー利用可能リスナー
        headTrackerListener = object : Spatializer.OnHeadTrackerAvailableListener {
            override fun onHeadTrackerAvailableChanged(spat: Spatializer, available: Boolean) {
                Log.d(TAG, "Head tracker availability changed: $available")
                refreshStatus()
            }
        }
        spatializer.addOnHeadTrackerAvailableListener(executor, headTrackerListener!!)
    }
    
    /**
     * Spatializer状態変化リスナーを解除 (API 33+)
     */
    fun unregisterSpatializerListeners() {
        try {
            val spatializer = audioManager.spatializer
            
            spatializerStateListener?.let {
                try {
                    spatializer.removeOnSpatializerStateChangedListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove state listener", e)
                }
                spatializerStateListener = null
            }
            
            headTrackerListener?.let {
                try {
                    spatializer.removeOnHeadTrackerAvailableListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove head tracker listener", e)
                }
                headTrackerListener = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister spatializer listeners", e)
        }
    }
    
    /**
     * 現在の状態でヘッドトラッキングが利用可能かどうか
     */
    fun isHeadTrackingAvailable(): Boolean {
        val status = _statusFlow.value
        return status.isDeviceConnected && 
               status.isHeadTrackingSupported &&
               status.isSpatializerAvailable &&
               status.isSpatializerEnabled
    }
    
    /**
     * システムのヘッドトラッカーが利用可能かどうか (API 33+)
     */
    fun isSystemHeadTrackerAvailable(): Boolean {
        return _statusFlow.value.isHeadTrackerAvailable
    }
    
    /**
     * サポート対象デバイスの一覧を取得
     */
    fun getSupportedDevicesList(): List<SupportedDevice> = SUPPORTED_DEVICES
    
    /**
     * カスタムデバイスを追加（将来の拡張用）
     */
    fun addCustomDevice(namePattern: String, manufacturer: String, headTrackingSupported: Boolean) {
        // 実装: SharedPreferencesなどに保存して動的に追加可能に
        Log.d(TAG, "Custom device added: $namePattern ($manufacturer)")
    }
    
    /**
     * 4つの値を保持するデータクラス
     */
    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
