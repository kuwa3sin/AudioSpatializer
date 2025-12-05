package com.example.audiospatializer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Biquadフィルタ（双二次フィルタ）実装
 * 
 * IIR（無限インパルス応答）フィルタの一種で、2次のローパス/ハイパス/バンドパスフィルタを実装。
 * 直接形II転置形式を使用し、数値的安定性を確保。
 * 
 * 用途:
 * - ローパス: LFE（低音エフェクト）チャンネルの低周波抽出
 * - ハイパス: リアチャンネルの低音カット
 * - バンドパス: サイドチャンネルの帯域選択
 * 
 * 参考: Audio EQ Cookbook by Robert Bristow-Johnson
 * 
 * @param type フィルタタイプ（LOWPASS, HIGHPASS, BANDPASS）
 * @param sampleRate サンプリングレート（Hz）
 */
class BiquadFilter(private val type: Type, private var sampleRate: Int) {
    
    /**
     * フィルタタイプ列挙型
     */
    enum class Type { 
        /** ローパスフィルタ - 指定周波数以下を通過 */
        LOWPASS, 
        /** ハイパスフィルタ - 指定周波数以上を通過 */
        HIGHPASS, 
        /** バンドパスフィルタ - 指定周波数帯域を通過 */
        BANDPASS 
    }

    // =========================================================================
    // フィルタ係数（直接形II転置形式）
    // 伝達関数: H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
    // =========================================================================
    private var b0 = 1.0  // 分子係数0（入力の現在値への重み）
    private var b1 = 0.0  // 分子係数1（入力の1サンプル前への重み）
    private var b2 = 0.0  // 分子係数2（入力の2サンプル前への重み）
    private var a1 = 0.0  // 分母係数1（出力の1サンプル前への重み）
    private var a2 = 0.0  // 分母係数2（出力の2サンプル前への重み）

    // =========================================================================
    // 状態変数（左右チャンネル独立）
    // 直接形II転置形式では2つの状態変数を使用
    // =========================================================================
    private var z1L = 0.0  // 左チャンネル状態変数1
    private var z2L = 0.0  // 左チャンネル状態変数2
    private var z1R = 0.0  // 右チャンネル状態変数1
    private var z2R = 0.0  // 右チャンネル状態変数2

    /**
     * フィルタ係数を更新
     * 
     * カットオフ周波数とQ値からフィルタ係数を計算。
     * Audio EQ Cookbookの計算式を使用。
     * 
     * @param cutoffHz カットオフ周波数（Hz）
     * @param q Q値（共振のシャープさ、0.707でButterworth特性）
     * @return 自身への参照（メソッドチェーン用）
     */
    fun update(cutoffHz: Double, q: Double = 0.707): BiquadFilter {
        // 正規化角周波数を計算
        // w0 = 2π × (カットオフ周波数 / サンプリングレート)
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        
        // α = sin(w0) / (2 × Q)
        // Q値が大きいほどαが小さく、シャープな特性になる
        val alpha = sinw0 / (2.0 * q)
        
        // フィルタタイプごとの係数計算
        when (type) {
            Type.LOWPASS -> {
                // ローパス: 低周波を通過、高周波を減衰
                val norm = 1.0 + alpha
                b0 = ((1.0 - cosw0) / 2.0) / norm
                b1 = (1.0 - cosw0) / norm
                b2 = ((1.0 - cosw0) / 2.0) / norm
                a1 = (-2.0 * cosw0) / norm
                a2 = (1.0 - alpha) / norm
            }
            Type.HIGHPASS -> {
                // ハイパス: 高周波を通過、低周波を減衰
                val norm = 1.0 + alpha
                b0 = ((1.0 + cosw0) / 2.0) / norm
                b1 = (-(1.0 + cosw0)) / norm
                b2 = ((1.0 + cosw0) / 2.0) / norm
                a1 = (-2.0 * cosw0) / norm
                a2 = (1.0 - alpha) / norm
            }
            Type.BANDPASS -> {
                // バンドパス: 指定周波数帯域のみ通過
                // ピークゲイン = Q のバンドパス
                val norm = 1.0 + alpha
                b0 = alpha / norm
                b1 = 0.0
                b2 = -alpha / norm
                a1 = (-2.0 * cosw0) / norm
                a2 = (1.0 - alpha) / norm
            }
        }
        
        // 状態変数をリセット（係数変更時は過渡応答を避けるためクリア）
        z1L = 0.0
        z2L = 0.0
        z1R = 0.0
        z2R = 0.0
        
        return this
    }

    /**
     * 単一サンプルを処理（モノラル）
     * 
     * 直接形II転置形式でフィルタ処理を実行。
     * y[n] = b0*x[n] + z1
     * z1 = b1*x[n] - a1*y[n] + z2
     * z2 = b2*x[n] - a2*y[n]
     * 
     * @param input 入力サンプル（-1.0〜1.0）
     * @param isLeft trueなら左チャンネル、falseなら右チャンネルの状態を使用
     * @return フィルタ処理後のサンプル
     */
    fun processMono(input: Float, isLeft: Boolean): Float {
        // 入力をDouble精度に変換（計算精度確保）
        val x = input.toDouble()
        
        return if (isLeft) {
            // 左チャンネルの状態変数を使用
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            y.toFloat()
        } else {
            // 右チャンネルの状態変数を使用
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            y.toFloat()
        }
    }

    /**
     * ステレオバッファを一括処理
     * 
     * 複数サンプルをまとめて処理することで、関数呼び出しオーバーヘッドを削減。
     * 入力と出力で別の配列を使用可能（インプレース処理も可）。
     * 
     * @param inputL 左チャンネル入力配列
     * @param inputR 右チャンネル入力配列
     * @param outL 左チャンネル出力配列
     * @param outR 右チャンネル出力配列
     * @param n 処理するサンプル数
     */
    fun processStereo(inputL: FloatArray, inputR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        for (i in 0 until n) {
            // 左チャンネル処理
            val xL = inputL[i].toDouble()
            val yL = b0 * xL + z1L
            z1L = b1 * xL - a1 * yL + z2L
            z2L = b2 * xL - a2 * yL
            outL[i] = yL.toFloat()

            // 右チャンネル処理
            val xR = inputR[i].toDouble()
            val yR = b0 * xR + z1R
            z1R = b1 * xR - a1 * yR + z2R
            z2R = b2 * xR - a2 * yR
            outR[i] = yR.toFloat()
        }
    }
}
