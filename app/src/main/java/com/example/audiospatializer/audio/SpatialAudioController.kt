package com.example.audiospatializer.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.audiospatializer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Android 13+ (API 33) のSpatializer APIを使用した空間オーディオコントローラー
 * 
 * プラットフォームのSpatializer機能とヘッドトラッキングを制御し、
 * カスタムHRTF処理との連携をサポートします。
 */
class SpatialAudioController(private val context: Context) {
    data class HeadTrackingState(
        val spatializerAvailable: Boolean = false,
        val headTrackingEnabled: Boolean = false,
        val headTrackerAvailable: Boolean = false
    )

    /**
     * ヘッドトラッカーのポーズ（姿勢）データ
     * Bluetoothヘッドフォンから取得
     */
    data class HeadPose(
        val yawDegrees: Float = 0f,
        val pitchDegrees: Float = 0f,
        val rollDegrees: Float = 0f
    )

    /**
     * 接続中のヘッドフォン情報
     */
    data class HeadphoneInfo(
        val isConnected: Boolean = false,
        val deviceName: String? = null,
        val supportsSpatialAudio: Boolean = false,
        val supportsHeadTracking: Boolean = false,
        val deviceType: HeadphoneType = HeadphoneType.UNKNOWN
    )

    enum class HeadphoneType {
        UNKNOWN,
        WIRED,
        BLUETOOTH_A2DP,
        BLUETOOTH_LE_AUDIO,
        USB
    }

    companion object {
        private const val TAG = "SpatialAudioController"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    
    // Android 13+ (API 33) Spatializer API を直接使用
    private val spatializer: Spatializer = audioManager.spatializer
    
    // ヘッドトラッカー利用可能状態の監視リスナー
    private val headTrackerAvailableListener = 
        Spatializer.OnHeadTrackerAvailableListener { _, available ->
            Log.d(TAG, "Head tracker availability changed: $available")
            _state.value = _state.value.copy(headTrackerAvailable = available)
            _headphoneInfo.value = _headphoneInfo.value.copy(supportsHeadTracking = available)
        }
    
    // Spatializer状態変更リスナー (匿名クラスで実装)
    private val spatializerStateListener = object : Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) {
            Log.d(TAG, "Spatializer enabled changed: $enabled")
            refreshState()
        }
        
        override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) {
            Log.d(TAG, "Spatializer available changed: $available")
            refreshState()
        }
    }
    
    private val mainExecutor: Executor = context.mainExecutor
    
    private val _state = MutableStateFlow(HeadTrackingState())
    val state: StateFlow<HeadTrackingState> = _state.asStateFlow()

    private val _headPose = MutableStateFlow(HeadPose())
    val headPose: StateFlow<HeadPose> = _headPose.asStateFlow()

    private val _headphoneInfo = MutableStateFlow(HeadphoneInfo())
    val headphoneInfo: StateFlow<HeadphoneInfo> = _headphoneInfo.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var headPosePollingRunnable: Runnable? = null
    private var isPollingHeadPose = false

    init {
        // リスナーを登録
        try {
            spatializer.addOnHeadTrackerAvailableListener(mainExecutor, headTrackerAvailableListener)
            spatializer.addOnSpatializerStateChangedListener(mainExecutor, spatializerStateListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add spatializer listeners", e)
        }
        
        refreshState()
        refreshHeadphoneInfo()
    }

    fun refreshState() {
        val available = spatializer.isAvailable
        val isEnabled = spatializer.isEnabled
        val headTrackerAvail = spatializer.isHeadTrackerAvailable
        
        _state.value = HeadTrackingState(
            spatializerAvailable = available,
            headTrackingEnabled = isEnabled && headTrackerAvail,
            headTrackerAvailable = headTrackerAvail
        )
        
        Log.d(TAG, "State refreshed: available=$available, enabled=$isEnabled, headTrackerAvail=$headTrackerAvail")
    }

    /**
     * 接続中のヘッドフォン情報を更新
     */
    @SuppressLint("MissingPermission")
    fun refreshHeadphoneInfo() {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            
            // ヘッドフォン/イヤホンデバイスを探す
            val headphoneDevice = devices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            if (headphoneDevice == null) {
                _headphoneInfo.value = HeadphoneInfo(isConnected = false)
                return
            }

            val deviceType = when (headphoneDevice.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> HeadphoneType.BLUETOOTH_A2DP
                AudioDeviceInfo.TYPE_BLE_HEADSET -> HeadphoneType.BLUETOOTH_LE_AUDIO
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> HeadphoneType.WIRED
                AudioDeviceInfo.TYPE_USB_HEADSET -> HeadphoneType.USB
                else -> HeadphoneType.UNKNOWN
            }

            // デバイス名を取得
            var deviceName = headphoneDevice.productName?.toString()?.takeIf { it.isNotBlank() }

            // Bluetoothデバイスの場合、より詳細な名前を取得（権限がなくても続行）
            if (deviceType == HeadphoneType.BLUETOOTH_A2DP || deviceType == HeadphoneType.BLUETOOTH_LE_AUDIO) {
                deviceName = getConnectedBluetoothDeviceName() ?: deviceName
            }

            // 空間オーディオ対応チェック
            val supportsSpatialAudio = checkSpatialAudioSupport(headphoneDevice)
            
            // ヘッドトラッキング対応チェック
            val supportsHeadTracking = isHeadTrackerAvailable()

            _headphoneInfo.value = HeadphoneInfo(
                isConnected = true,
                deviceName = deviceName ?: getString(R.string.headphone_unknown_device),
                supportsSpatialAudio = supportsSpatialAudio,
                supportsHeadTracking = supportsHeadTracking,
                deviceType = deviceType
            )
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT権限がない場合はデフォルト状態を維持
            _headphoneInfo.value = HeadphoneInfo(isConnected = false)
        } catch (_: Exception) {
            _headphoneInfo.value = HeadphoneInfo(isConnected = false)
        }
    }

    /**
     * 接続中のBluetoothデバイス名を取得
     */
    @SuppressLint("MissingPermission")
    private fun getConnectedBluetoothDeviceName(): String? {
        return try {
            val adapter = bluetoothManager?.adapter ?: return null
            
            // A2DPプロファイルから接続デバイスを取得
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        if (profile == BluetoothProfile.A2DP) {
                            val devices = proxy.connectedDevices
                            @Suppress("UNUSED_VARIABLE")
                            val deviceName = devices.firstOrNull()?.name
                            // 非同期で取得されるため、ここでは使用しない
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    } catch (_: SecurityException) {
                        try {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        } catch (_: Exception) {}
                    } catch (_: Exception) {
                        try {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        } catch (_: Exception) {}
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
            
            // 直接ボンディングされたデバイスから取得（フォールバック）
            adapter.bondedDevices?.firstOrNull { device ->
                try {
                    val method = device.javaClass.getMethod("isConnected")
                    method.invoke(device) as? Boolean ?: false
                } catch (_: Exception) {
                    false
                }
            }?.name
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * デバイスが空間オーディオに対応しているかチェック
     */
    private fun checkSpatialAudioSupport(device: AudioDeviceInfo): Boolean {
        // Spatializerが利用可能で、かつBluetoothデバイスの場合は対応とみなす
        if (!spatializer.isAvailable) return false

        // Bluetoothデバイスの場合
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }

    private fun getString(resId: Int): String {
        return context.getString(resId)
    }

    /**
     * ヘッドトラッカー（Bluetoothヘッドフォン）が利用可能かチェック
     * Android 13+ のネイティブAPI使用
     */
    fun isHeadTrackerAvailable(): Boolean {
        return spatializer.isHeadTrackerAvailable
    }

    /**
     * ヘッドトラッカーからポーズ（姿勢）を取得
     * 戻り値: [x, y, z, qx, qy, qz, qw] の配列（位置 + クォータニオン）
     * 
     * 注意: Android標準の公開APIではヘッドポーズの直接取得はサポートされていません。
     * ジャイロセンサーベースのフォールバック実装または将来のAPI拡張を使用します。
     */
    fun getHeadTrackerPose(): FloatArray? {
        // 現状のAndroid 公開 APIではヘッドポーズの直接取得はサポートされていない
        // カスタムHRTF処理ではジャイロセンサーを使用
        return null
    }

    /**
     * ヘッドポーズのポーリングを開始
     * Bluetoothヘッドフォンのヘッドトラッカーから定期的にポーズを取得
     */
    fun startHeadPosePolling() {
        if (isPollingHeadPose) return
        isPollingHeadPose = true

        headPosePollingRunnable = object : Runnable {
            override fun run() {
                if (!isPollingHeadPose) return

                val pose = getHeadTrackerPose()
                if (pose != null && pose.size >= 7) {
                    // クォータニオン [qx, qy, qz, qw] からオイラー角を計算
                    val qx = pose[3]
                    val qy = pose[4]
                    val qz = pose[5]
                    val qw = pose[6]

                    val euler = quaternionToEuler(qx, qy, qz, qw)
                    _headPose.value = HeadPose(
                        yawDegrees = euler[0],
                        pitchDegrees = euler[1],
                        rollDegrees = euler[2]
                    )
                }

                handler.postDelayed(this, 33) // 約30fps
            }
        }
        handler.post(headPosePollingRunnable!!)
    }

    /**
     * ヘッドポーズのポーリングを停止
     */
    fun stopHeadPosePolling() {
        isPollingHeadPose = false
        headPosePollingRunnable?.let { handler.removeCallbacks(it) }
        headPosePollingRunnable = null
    }

    /**
     * クォータニオンからオイラー角（度）に変換
     * 戻り値: [yaw, pitch, roll]
     */
    private fun quaternionToEuler(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
        // Yaw (Z軸回転)
        val sinYaw = 2.0 * (qw * qz + qx * qy)
        val cosYaw = 1.0 - 2.0 * (qy * qy + qz * qz)
        val yaw = Math.toDegrees(kotlin.math.atan2(sinYaw, cosYaw)).toFloat()

        // Pitch (Y軸回転)
        val sinPitch = 2.0 * (qw * qy - qz * qx)
        val pitch = if (kotlin.math.abs(sinPitch) >= 1) {
            Math.toDegrees(kotlin.math.sign(sinPitch) * Math.PI / 2).toFloat()
        } else {
            Math.toDegrees(kotlin.math.asin(sinPitch)).toFloat()
        }

        // Roll (X軸回転)
        val sinRoll = 2.0 * (qw * qx + qy * qz)
        val cosRoll = 1.0 - 2.0 * (qx * qx + qy * qy)
        val roll = Math.toDegrees(kotlin.math.atan2(sinRoll, cosRoll)).toFloat()

        return floatArrayOf(yaw, pitch, roll)
    }

    /**
     * 指定されたAudioAttributesとAudioFormatの組み合わせで空間化が可能かチェック
     */
    fun supportsSpatialization(attributes: AudioAttributes): Boolean {
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        
        return spatializer.canBeSpatialized(attributes, audioFormat)
    }

    /**
     * 空間オーディオの有効/無効を設定
     * 
     * 注意: 公開APIではsetEnabledは直接公開されていないため、
     * システム設定を通じて制御される
     */
    fun setSpatializationEnabled(enabled: Boolean) {
        // 公開APIではisEnabledは読み取り専用
        // ユーザーはシステム設定から空間オーディオを有効/無効にする
        Log.d(TAG, "setSpatializationEnabled($enabled) - Platform spatializer controlled via system settings")
        refreshState()
    }

    /**
     * ヘッドトラッキングの有効/無効を設定
     * 
     * 注意: 公開APIではヘッドトラッキングモードの直接設定はサポートされていない
     */
    fun setHeadTrackingEnabled(enabled: Boolean) {
        // 公開APIではヘッドトラッキングモードの設定は非公開
        // ヘッドトラッカー対応デバイス接続時に自動的に有効になる
        Log.d(TAG, "setHeadTrackingEnabled($enabled) - Head tracking controlled by device")
        refreshState()
    }

    /**
     * 空間化レベルを取得
     */
    fun getSpatializerImmersiveAudioLevel(): Int {
        return spatializer.immersiveAudioLevel
    }

    /**
     * プラットフォームのSpatializerが有効かどうか
     */
    fun isPlatformSpatializerEnabled(): Boolean {
        return spatializer.isEnabled
    }

    /**
     * プラットフォームのSpatializerが利用可能かどうか
     */
    fun isPlatformSpatializerAvailable(): Boolean {
        return spatializer.isAvailable
    }

    fun release() {
        stopHeadPosePolling()
        
        // リスナーを解除
        try {
            spatializer.removeOnHeadTrackerAvailableListener(headTrackerAvailableListener)
            spatializer.removeOnSpatializerStateChangedListener(spatializerStateListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove listeners", e)
        }
    }
}
