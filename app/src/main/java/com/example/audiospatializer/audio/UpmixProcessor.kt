package com.example.audiospatializer.audio

import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ステレオ → 5.1ch/7.1ch アップミックスプロセッサ
 * 
 * Dolby Pro Logic II風のマトリクスデコード + HRTF的な処理で
 * ステレオ音源から空間的なサラウンドを生成
 */
class UpmixProcessor {
    
    companion object {
        private const val TAG = "UpmixProcessor"
        
        // 5.1ch チャンネル順序: FL, FR, FC, LFE, RL, RR
        const val CHANNEL_FL = 0
        const val CHANNEL_FR = 1
        const val CHANNEL_FC = 2
        const val CHANNEL_LFE = 3
        const val CHANNEL_RL = 4
        const val CHANNEL_RR = 5
        
        // サラウンド強度
        private const val SURROUND_GAIN = 0.7f
        private const val CENTER_GAIN = 0.5f
        private const val LFE_GAIN = 0.3f
        
        // フィルタ係数
        private const val LPF_CUTOFF = 120f  // LFE用ローパス
    }
    
    // 遅延バッファ（リアチャンネルの時間差で空間感を演出）
    private val surroundDelayMs = 15f
    private var surroundDelaySamples = 0
    private var delayBufferL = FloatArray(0)
    private var delayBufferR = FloatArray(0)
    private var delayWriteIndex = 0
    
    // LFE用ローパスフィルタの状態
    private var lpfState = 0f
    private var lpfCoeff = 0f
    
    // Mid/Side処理用
    private var lastMid = 0f
    private var lastSide = 0f
    
    private var sampleRate = 48000
    private var intensity = 0.7f  // 空間化強度
    
    /**
     * サンプルレートを設定
     */
    fun setSampleRate(rate: Int) {
        sampleRate = rate
        
        // 遅延バッファサイズを計算（ステレオ分）
        surroundDelaySamples = (surroundDelayMs * rate / 1000f).toInt()
        if (delayBufferL.size != surroundDelaySamples) {
            delayBufferL = FloatArray(surroundDelaySamples)
            delayBufferR = FloatArray(surroundDelaySamples)
            delayWriteIndex = 0
        }
        
        // LPFの係数を更新
        lpfCoeff = 1f - kotlin.math.exp(-2f * kotlin.math.PI.toFloat() * LPF_CUTOFF / rate)
    }
    
    /**
     * 空間化強度を設定（0.0〜1.0）
     */
    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }
    
    /**
     * ステレオPCM (float, interleaved) → 5.1ch PCM (float, interleaved)
     * 
     * @param stereoInput ステレオ入力 [L0, R0, L1, R1, ...]
     * @return 5.1ch出力 [FL0, FR0, FC0, LFE0, RL0, RR0, FL1, FR1, ...]
     */
    fun processTo51(stereoInput: FloatArray): FloatArray {
        val frameCount = stereoInput.size / 2
        val output = FloatArray(frameCount * 6)
        
        for (i in 0 until frameCount) {
            val left = stereoInput[i * 2]
            val right = stereoInput[i * 2 + 1]
            
            // Mid/Side分解
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            
            // === フロントL/R ===
            // オリジナルのステレオを維持しつつ、少しナローに
            val fl = left * 0.9f + mid * 0.1f
            val fr = right * 0.9f + mid * 0.1f
            
            // === センター ===
            // モノラル成分（ボーカル等）を抽出
            val center = mid * CENTER_GAIN * intensity
            
            // === LFE ===
            // ローパスフィルタで低音のみ抽出
            lpfState += lpfCoeff * (mid - lpfState)
            val lfe = lpfState * LFE_GAIN * intensity
            
            // === リアL/R ===
            // サイド成分 + 遅延で環境音・残響を演出
            val delayedL = if (surroundDelaySamples > 0) {
                val readIdx = (delayWriteIndex + 1) % surroundDelaySamples
                delayBufferL[readIdx]
            } else {
                0f
            }
            val delayedR = if (surroundDelaySamples > 0) {
                val readIdx = (delayWriteIndex + 1) % surroundDelaySamples
                delayBufferR[readIdx]
            } else {
                0f
            }
            
            // 遅延バッファに書き込み（サイド成分ベース）
            if (surroundDelaySamples > 0) {
                delayBufferL[delayWriteIndex] = side + left * 0.2f
                delayBufferR[delayWriteIndex] = -side + right * 0.2f
                delayWriteIndex = (delayWriteIndex + 1) % surroundDelaySamples
            }
            
            // リアチャンネル: 遅延 + 位相反転で包み込み感
            val rl = delayedL * SURROUND_GAIN * intensity
            val rr = delayedR * SURROUND_GAIN * intensity
            
            // 出力に書き込み (FL, FR, FC, LFE, RL, RR)
            val outIdx = i * 6
            output[outIdx + CHANNEL_FL] = fl
            output[outIdx + CHANNEL_FR] = fr
            output[outIdx + CHANNEL_FC] = center
            output[outIdx + CHANNEL_LFE] = lfe
            output[outIdx + CHANNEL_RL] = rl
            output[outIdx + CHANNEL_RR] = rr
        }
        
        return output
    }
    
    /**
     * Short配列版（AudioRecord/AudioTrack用）
     * 
     * @param stereoInput ステレオ入力 [L0, R0, L1, R1, ...]
     * @return 5.1ch出力 [FL0, FR0, FC0, LFE0, RL0, RR0, ...]
     */
    fun processTo51Short(stereoInput: ShortArray): ShortArray {
        // Short → Float 変換
        val floatInput = FloatArray(stereoInput.size)
        for (i in stereoInput.indices) {
            floatInput[i] = stereoInput[i] / 32768f
        }
        
        // 処理
        val floatOutput = processTo51(floatInput)
        
        // Float → Short 変換
        val shortOutput = ShortArray(floatOutput.size)
        for (i in floatOutput.indices) {
            val clamped = floatOutput[i].coerceIn(-1f, 1f)
            shortOutput[i] = (clamped * 32767f).toInt().toShort()
        }
        
        return shortOutput
    }
    
    /**
     * 遅延バッファをリセット
     */
    fun reset() {
        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        delayWriteIndex = 0
        lpfState = 0f
        lastMid = 0f
        lastSide = 0f
    }
}
