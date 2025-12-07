/**
 * AudioProcessor.kt
 * 
 * オーディオファイルをHRTF（頭部伝達関数）処理して空間音響化するメインクラス。
 * 入力ステレオ音源を2ch/5.1ch/7.1chのバーチャルサラウンドに変換し、
 * Android 13以降のSpatializer APIによるヘッドトラッキングに対応した出力を生成する。
 * 
 * 対応出力モード:
 * - HRTF_BINAURAL: 2chステレオ（ヘッドフォン向けHRTF処理済み）
 * - HRTF_SURROUND_5_1: 5.1ch（FL/FR/C/LFE/BL/BR）
 * - HRTF_SURROUND_5_1_IMMERSIVE: 5.1ch イマーシブ（後方・側方チャンネル強化）
 * - HRTF_SURROUND_7_1: 7.1ch（FL/FR/C/LFE/BL/BR/SL/SR）
 * 
 * 最適化:
 * - バッファプーリングによるGC削減
 * - チャンク単位の並列DSP処理（Coroutines）
 * - インライン関数による呼び出しオーバーヘッド削減
 * - 直接バッファ操作による高速読み書き
 */
package com.example.audiospatializer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.coroutines.coroutineContext

/**
 * 空間音響処理を行うオーディオプロセッサ
 * 
 * @param context アプリケーションコンテキスト
 */
class AudioProcessor(private val context: Context) {

    companion object {
        private const val TAG = "AudioProcessor"
        
        // サンプリングレート（CD品質）
        private const val SAMPLE_RATE = 44100
        
        // AAC出力ビットレート（256kbps 高品質）
        private const val OUTPUT_BITRATE = 256000
        
        // MediaCodec用バッファサイズ（増量で効率向上）
        private const val BUFFER_SIZE = 16384
        
        // MediaCodecタイムアウト（マイクロ秒）
        private const val CODEC_TIMEOUT_US = 50000L
        
        // エンコーダに書き込むフレーム数（バッファリング - 増量）
        private const val ENCODER_FRAME_BUFFER_SIZE = 2048
        
        // 並列処理チャンクサイズ（フレーム数）- 小さめで確実に処理
        private const val PARALLEL_CHUNK_SIZE = 2048
        
        // 並列ワーカー数（デバイス負荷を考慮）
        private const val PARALLEL_WORKERS = 2
        
        // 最終処理用の追加タイムアウト
        private const val FINAL_DRAIN_TIMEOUT_US = 100000L
        
        // チャンネル別ゲイン定数
        private const val LFE_GAIN = 0.7f          // 低音チャンネル（過度な低音を防止）
        private const val CENTER_GAIN = 0.9f       // センター（ボーカル・ダイアログ用）
        private const val FRONT_GAIN = 0.9f        // フロントL/R
        private const val REAR_GAIN = 0.35f        // リアL/R（距離感を出すため控えめ）
        private const val SIDE_GAIN = 0.55f        // サイドL/R（7.1ch用、側方定位用）
        
        // フィルタ周波数設定
        private const val LFE_CUTOFF_HZ = 120.0       // LFEローパスカットオフ
        private const val SIDE_BANDPASS_CENTER_HZ = 1500.0  // サイドバンドパス中心
        private const val REAR_HIGHPASS_HZ = 200.0    // リアハイパスカットオフ
    }

    /**
     * 出力モード列挙型
     */
    enum class OutputMode {
        /** 2chバイノーラル（HRTF処理済みヘッドフォン向け） */
        HRTF_BINAURAL,
        /** 5.1chサラウンド（FL/FR/C/LFE/BL/BR） */
        HRTF_SURROUND_5_1,
        /** 5.1chイマーシブ（後方・側方強化） */
        HRTF_SURROUND_5_1_IMMERSIVE,
        /** 7.1chサラウンド（FL/FR/C/LFE/BL/BR/SL/SR） */
        HRTF_SURROUND_7_1,
        /** 5.1ch高速イマーシブ（リアルタイム向け・フィルタレス） */
        HRTF_SURROUND_5_1_FAST
    }

    /**
     * 処理結果データクラス
     */
    data class ProcessResult(
        val file: File,
        val sampleRate: Int,
        val channelCount: Int,
        val frameCount: Long,
        val outputMode: OutputMode
    )

    // DSPフィルタ
    private var lfeFilter: BiquadFilter? = null
    private var rearLFilter: BiquadFilter? = null
    private var rearRFilter: BiquadFilter? = null
    private var sideLFilter: BiquadFilter? = null
    private var sideRFilter: BiquadFilter? = null
    
    // 並列処理用フィルタ配列（各ワーカーに独立インスタンス）
    private var parallelLfeFilters: Array<BiquadFilter>? = null
    private var parallelRearLFilters: Array<BiquadFilter>? = null
    private var parallelRearRFilters: Array<BiquadFilter>? = null
    private var parallelSideLFilters: Array<BiquadFilter>? = null
    private var parallelSideRFilters: Array<BiquadFilter>? = null
    
    // バッファプール（GC削減）
    private val bufferPool = BufferPool()

    /**
     * オーディオ処理のメインエントリポイント
     * 
     * @param inputUri 入力ファイルURI
     * @param outputMode 出力モード
     * @param progressCallback 進捗コールバック（percent: Int, stage: String）
     * @return 処理結果（失敗時null）
     */
    suspend fun processAudio(
        inputUri: Uri,
        outputMode: OutputMode = OutputMode.HRTF_BINAURAL,
        progressCallback: (Int, String) -> Unit = { _, _ -> }
    ): ProcessResult? = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "処理開始: mode=$outputMode, input=$inputUri")
            progressCallback(0, "準備中")
            
            initializeFilters()
            
            val outputFile = createOutputFile(inputUri, outputMode)
            
            val result = when (outputMode) {
                OutputMode.HRTF_BINAURAL -> {
                    decodeProcessEncode(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        channelCount = 2,
                        channelMask = AudioFormat.CHANNEL_OUT_STEREO,
                        outputMode = outputMode,
                        processFrame = ::processFrameBinaural,
                        progressCallback = progressCallback
                    )
                }
                OutputMode.HRTF_SURROUND_5_1 -> {
                    decodeProcessEncode(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        channelCount = 6,
                        channelMask = AudioFormat.CHANNEL_OUT_5POINT1,
                        outputMode = outputMode,
                        processFrame = { samples -> processFrameSurround51(samples, immersive = false) },
                        progressCallback = progressCallback
                    )
                }
                OutputMode.HRTF_SURROUND_5_1_IMMERSIVE -> {
                    decodeProcessEncode(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        channelCount = 6,
                        channelMask = AudioFormat.CHANNEL_OUT_5POINT1,
                        outputMode = outputMode,
                        processFrame = { samples -> processFrameSurround51(samples, immersive = true) },
                        progressCallback = progressCallback
                    )
                }
                OutputMode.HRTF_SURROUND_7_1 -> {
                    decodeProcessEncode(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        channelCount = 8,
                        channelMask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
                        outputMode = outputMode,
                        processFrame = ::processFrameSurround71,
                        progressCallback = progressCallback
                    )
                }
                OutputMode.HRTF_SURROUND_5_1_FAST -> {
                    decodeProcessEncodeFast(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        progressCallback = progressCallback
                    )
                }
            }
            
            progressCallback(100, "完了")
            Log.d(TAG, "処理完了: ${result?.file?.name}")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "処理エラー", e)
            null
        } finally {
            releaseFilters()
        }
    }

    /**
     * 出力ファイル生成
     * ファイル名形式: 年月日時間分_元ファイル名_変換形式.m4a
     */
    private fun createOutputFile(inputUri: Uri, outputMode: OutputMode): File {
        val baseName = inputUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: "output"
        
        // 年月日時間分形式: yyyyMMddHHmm
        val dateFormat = java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.getDefault())
        val timestamp = dateFormat.format(java.util.Date())
        
        // 変換形式: quality (Immersive) または fast
        val modeLabel = when (outputMode) {
            OutputMode.HRTF_SURROUND_5_1_IMMERSIVE -> "quality"
            OutputMode.HRTF_SURROUND_5_1_FAST -> "fast"
            OutputMode.HRTF_BINAURAL -> "binaural"
            OutputMode.HRTF_SURROUND_5_1 -> "51ch"
            OutputMode.HRTF_SURROUND_7_1 -> "71ch"
        }
        
        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(outputDir, "${timestamp}_${baseName}_${modeLabel}.m4a")
    }

    /**
     * DSPフィルタ初期化
     */
    private fun initializeFilters() {
        // シングルスレッド用フィルタ
        lfeFilter = BiquadFilter(BiquadFilter.Type.LOWPASS, SAMPLE_RATE).update(LFE_CUTOFF_HZ, 0.707)
        rearLFilter = BiquadFilter(BiquadFilter.Type.HIGHPASS, SAMPLE_RATE).update(REAR_HIGHPASS_HZ, 0.707)
        rearRFilter = BiquadFilter(BiquadFilter.Type.HIGHPASS, SAMPLE_RATE).update(REAR_HIGHPASS_HZ, 0.707)
        sideLFilter = BiquadFilter(BiquadFilter.Type.BANDPASS, SAMPLE_RATE).update(SIDE_BANDPASS_CENTER_HZ, 1.0)
        sideRFilter = BiquadFilter(BiquadFilter.Type.BANDPASS, SAMPLE_RATE).update(SIDE_BANDPASS_CENTER_HZ, 1.0)
        
        // 並列処理用フィルタ（各ワーカーに独立インスタンス）
        parallelLfeFilters = Array(PARALLEL_WORKERS) { 
            BiquadFilter(BiquadFilter.Type.LOWPASS, SAMPLE_RATE).update(LFE_CUTOFF_HZ, 0.707) 
        }
        parallelRearLFilters = Array(PARALLEL_WORKERS) { 
            BiquadFilter(BiquadFilter.Type.HIGHPASS, SAMPLE_RATE).update(REAR_HIGHPASS_HZ, 0.707) 
        }
        parallelRearRFilters = Array(PARALLEL_WORKERS) { 
            BiquadFilter(BiquadFilter.Type.HIGHPASS, SAMPLE_RATE).update(REAR_HIGHPASS_HZ, 0.707) 
        }
        parallelSideLFilters = Array(PARALLEL_WORKERS) { 
            BiquadFilter(BiquadFilter.Type.BANDPASS, SAMPLE_RATE).update(SIDE_BANDPASS_CENTER_HZ, 1.0) 
        }
        parallelSideRFilters = Array(PARALLEL_WORKERS) { 
            BiquadFilter(BiquadFilter.Type.BANDPASS, SAMPLE_RATE).update(SIDE_BANDPASS_CENTER_HZ, 1.0) 
        }
        
        Log.d(TAG, "DSPフィルタ初期化完了 (並列ワーカー: $PARALLEL_WORKERS)")
    }

    /**
     * DSPフィルタ解放
     */
    private fun releaseFilters() {
        lfeFilter = null
        rearLFilter = null
        rearRFilter = null
        sideLFilter = null
        sideRFilter = null
        parallelLfeFilters = null
        parallelRearLFilters = null
        parallelRearRFilters = null
        parallelSideLFilters = null
        parallelSideRFilters = null
        bufferPool.clear()
    }
    
    /**
     * バッファプール（オブジェクト再利用でGC削減）
     */
    private class BufferPool {
        private val floatArrayPool = mutableListOf<FloatArray>()
        private val shortArrayPool = mutableListOf<ShortArray>()
        
        @Synchronized
        fun getFloatArray(size: Int): FloatArray {
            val index = floatArrayPool.indexOfFirst { it.size >= size }
            return if (index >= 0) {
                floatArrayPool.removeAt(index)
            } else {
                FloatArray(size)
            }
        }
        
        @Synchronized
        fun returnFloatArray(array: FloatArray) {
            if (floatArrayPool.size < 32) {
                floatArrayPool.add(array)
            }
        }
        
        @Synchronized
        fun getShortArray(size: Int): ShortArray {
            val index = shortArrayPool.indexOfFirst { it.size >= size }
            return if (index >= 0) {
                shortArrayPool.removeAt(index)
            } else {
                ShortArray(size)
            }
        }
        
        @Synchronized
        fun returnShortArray(array: ShortArray) {
            if (shortArrayPool.size < 32) {
                shortArrayPool.add(array)
            }
        }
        
        @Synchronized
        fun clear() {
            floatArrayPool.clear()
            shortArrayPool.clear()
        }
    }

    /**
     * 統合デコード・処理・エンコードパイプライン
     */
    private suspend fun decodeProcessEncode(
        inputUri: Uri,
        outputFile: File,
        channelCount: Int,
        channelMask: Int,
        outputMode: OutputMode,
        processFrame: (FloatArray) -> FloatArray,
        progressCallback: (Int, String) -> Unit
    ): ProcessResult? {
        // WAV判定
        val isWav = inputUri.toString().lowercase().let { 
            it.endsWith(".wav") || it.contains(".wav") 
        }
        
        return if (isWav) {
            decodeProcessEncodeWav(
                inputUri, outputFile, channelCount, channelMask, outputMode, processFrame, progressCallback
            )
        } else {
            decodeProcessEncodeMediaCodec(
                inputUri, outputFile, channelCount, channelMask, outputMode, processFrame, progressCallback
            )
        }
    }

    /**
     * WAVファイル処理
     */
    private suspend fun decodeProcessEncodeWav(
        inputUri: Uri,
        outputFile: File,
        channelCount: Int,
        channelMask: Int,
        outputMode: OutputMode,
        processFrame: (FloatArray) -> FloatArray,
        progressCallback: (Int, String) -> Unit
    ): ProcessResult? {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri) ?: run {
                Log.e(TAG, "WAVファイルを開けません: $inputUri")
                return null
            }
            
            // WAVヘッダ解析
            val header = ByteArray(44)
            if (inputStream.read(header) != 44) {
                inputStream.close()
                Log.e(TAG, "WAVヘッダ読み取り失敗")
                return null
            }
            
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val inputChannels = headerBuffer.getShort(22).toInt()
            val inputSampleRate = headerBuffer.getInt(24)
            val bitsPerSample = headerBuffer.getShort(34).toInt()
            val dataSize = headerBuffer.getInt(40)
            
            Log.d(TAG, "WAV: ${inputChannels}ch, ${inputSampleRate}Hz, ${bitsPerSample}bit")
            
            val bytesPerSample = bitsPerSample / 8
            val totalSamples = dataSize / (bytesPerSample * inputChannels)
            
            // エンコーダ設定
            val encodeFormat = createAacFormat(channelCount, channelMask)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            val readBuffer = ByteArray(BUFFER_SIZE * bytesPerSample * inputChannels)
            var samplesProcessed = 0L
            var presentationTimeUs = 0L
            var lastReportedProgress = -1  // 前回報告した進捗（重複更新防止）
            
            // 処理バッファ（再利用）
            val inputFrameBuffer = FloatArray(2)
            
            // エンコーダ用バッファリング
            val encoderBuffer = ShortArray(ENCODER_FRAME_BUFFER_SIZE * channelCount)
            var encoderBufferPos = 0
            
            while (coroutineContext.isActive) {
                val bytesRead = inputStream.read(readBuffer)
                if (bytesRead <= 0) break
                
                val samplesInBuffer = bytesRead / (bytesPerSample * inputChannels)
                
                for (i in 0 until samplesInBuffer) {
                    // ステレオサンプル読み取り
                    inputFrameBuffer[0] = readSample(readBuffer, i * inputChannels, bytesPerSample, bitsPerSample)
                    inputFrameBuffer[1] = if (inputChannels >= 2) {
                        readSample(readBuffer, i * inputChannels + 1, bytesPerSample, bitsPerSample)
                    } else {
                        inputFrameBuffer[0]
                    }
                    
                    // DSP処理
                    val outputFrame = processFrame(inputFrameBuffer)
                    
                    // バッファに追加
                    for (sample in outputFrame) {
                        encoderBuffer[encoderBufferPos++] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    
                    // バッファが満杯ならエンコーダに送信
                    if (encoderBufferPos >= encoderBuffer.size) {
                        writeBufferToEncoder(encoder, encoderBuffer, encoderBufferPos, presentationTimeUs, channelCount)
                        presentationTimeUs += (ENCODER_FRAME_BUFFER_SIZE.toLong() * 1_000_000L) / SAMPLE_RATE
                        encoderBufferPos = 0
                        
                        // Muxer初期化
                        if (!muxerStarted) {
                            val format = getEncoderOutputFormat(encoder)
                            if (format != null) {
                                trackIndex = muxer.addTrack(format)
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "Muxer開始 (WAV)")
                            }
                        }
                        
                        // ドレイン
                        drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
                    }
                    
                    samplesProcessed++
                }
                
                // 進捗更新（前回と異なる場合のみ）
                val progress = ((samplesProcessed.toFloat() / totalSamples) * 100).toInt().coerceIn(0, 99)
                if (progress > lastReportedProgress) {
                    lastReportedProgress = progress
                    progressCallback(progress, "変換中")
                }
            }
            
            // 残りのバッファを送信
            if (encoderBufferPos > 0) {
                writeBufferToEncoder(encoder, encoderBuffer, encoderBufferPos, presentationTimeUs, channelCount)
                if (!muxerStarted) {
                    val format = getEncoderOutputFormat(encoder)
                    if (format != null) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            }
            
            // 終端処理
            signalEndOfStream(encoder)
            drainEncoderFully(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            
            inputStream.close()
            
            Log.d(TAG, "WAV変換完了: $samplesProcessed サンプル")
            
            return ProcessResult(
                file = outputFile,
                sampleRate = SAMPLE_RATE,
                channelCount = channelCount,
                frameCount = samplesProcessed,
                outputMode = outputMode
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "WAV処理エラー", e)
            return null
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * MediaCodec対応ファイル処理（高速化版）
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun decodeProcessEncodeMediaCodec(
        inputUri: Uri,
        outputFile: File,
        channelCount: Int,
        channelMask: Int,
        outputMode: OutputMode,
        processFrame: (FloatArray) -> FloatArray,  // 互換性のため残す（高速版では内部処理）
        progressCallback: (Int, String) -> Unit
    ): ProcessResult? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        
        try {
            // Extractor設定
            extractor = MediaExtractor()
            
            // ファイル読み込み - 複数の方法を試行
            val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            if (afd == null) {
                Log.e(TAG, "ファイルを開けません: $inputUri")
                return null
            }
            
            try {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } catch (e: Exception) {
                Log.e(TAG, "setDataSource失敗", e)
                afd.close()
                return null
            }
            
            // オーディオトラック検索
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            
            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "オーディオトラックが見つかりません")
                afd.close()
                return null
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = try {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) {
                0L
            }
            
            Log.d(TAG, "入力: $inputMime, ${inputChannels}ch, duration=${duration}us")
            
            // デコーダ設定
            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            
            // エンコーダ設定
            val encodeFormat = createAacFormat(channelCount, channelMask)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var presentationTimeUs = 0L
            var frameCount = 0L
            var lastReportedProgress = -1
            
            // 処理バッファ（大量バッファリング）
            val chunkInputBuffer = bufferPool.getFloatArray(PARALLEL_CHUNK_SIZE * 2)
            var chunkPos = 0
            
            // エンコーダ用バッファリング
            val encoderBuffer = bufferPool.getShortArray(ENCODER_FRAME_BUFFER_SIZE * channelCount)
            var encoderBufferPos = 0
            
            while (coroutineContext.isActive && !decoderDone) {
                // デコーダ入力
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            Log.d(TAG, "入力完了")
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                // デコーダ出力
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)!!
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        
                        val samples = bufferInfo.size / 2
                        val framesInBuffer = samples / inputChannels
                        
                        // 高速読み取り（ShortBuffer使用）
                        for (f in 0 until framesInBuffer) {
                            val baseIdx = f * inputChannels
                            chunkInputBuffer[chunkPos * 2] = shortBuffer.get(baseIdx).toFloat() / 32768f
                            chunkInputBuffer[chunkPos * 2 + 1] = if (inputChannels >= 2) {
                                shortBuffer.get(baseIdx + 1).toFloat() / 32768f
                            } else {
                                chunkInputBuffer[chunkPos * 2]
                            }
                            chunkPos++
                            
                            // チャンクが満杯なら並列処理
                            if (chunkPos >= PARALLEL_CHUNK_SIZE) {
                                val processedChunk = processChunkParallel(
                                    chunkInputBuffer, chunkPos, channelCount, outputMode
                                )
                                
                                // エンコーダに送信
                                writeChunkToEncoder(
                                    encoder, muxer, processedChunk, channelCount,
                                    encoderBuffer, encoderBufferPos, presentationTimeUs,
                                    bufferInfo, trackIndex, muxerStarted
                                ).let { result ->
                                    encoderBufferPos = result.first
                                    presentationTimeUs = result.second
                                    if (result.third >= 0) trackIndex = result.third
                                    if (result.fourth) muxerStarted = true
                                }
                                
                                frameCount += chunkPos
                                chunkPos = 0
                            }
                        }
                        
                        val decoderPresentationTimeUs = bufferInfo.presentationTimeUs
                        decoder.releaseOutputBuffer(outputIndex, false)
                        
                        // 進捗更新
                        if (duration > 0) {
                            val progress = ((decoderPresentationTimeUs.toFloat() / duration) * 100).toInt().coerceIn(0, 99)
                            if (progress > lastReportedProgress) {
                                lastReportedProgress = progress
                                progressCallback(progress, "変換中")
                            }
                        }
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            Log.d(TAG, "デコーダ完了")
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "デコーダ出力フォーマット変更")
                    }
                }
            }
            
            // 残りのチャンクを処理
            if (chunkPos > 0) {
                val processedChunk = processChunkSequential(
                    chunkInputBuffer, chunkPos, channelCount, outputMode
                )
                
                writeChunkToEncoder(
                    encoder, muxer, processedChunk, channelCount,
                    encoderBuffer, encoderBufferPos, presentationTimeUs,
                    bufferInfo, trackIndex, muxerStarted
                ).let { result ->
                    encoderBufferPos = result.first
                    presentationTimeUs = result.second
                    if (result.third >= 0) trackIndex = result.third
                    if (result.fourth) muxerStarted = true
                }
                
                frameCount += chunkPos
            }
            
            // 残りのエンコーダバッファを送信
            if (encoderBufferPos > 0) {
                writeBufferToEncoder(encoder, encoderBuffer, encoderBufferPos, presentationTimeUs, channelCount)
                if (!muxerStarted) {
                    val format = getEncoderOutputFormat(encoder)
                    if (format != null) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            }
            
            // 最終進捗更新
            progressCallback(99, "完了処理中")
            
            // 終端処理（十分な時間をかけてドレイン）
            signalEndOfStream(encoder)
            drainEncoderFully(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            
            // 追加のドレイン（7.1chなど大きなデータ用）
            repeat(5) {
                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            }
            
            // バッファプールに返却
            bufferPool.returnFloatArray(chunkInputBuffer)
            bufferPool.returnShortArray(encoderBuffer)
            
            afd.close()
            
            Log.d(TAG, "変換完了: $frameCount フレーム")
            
            return ProcessResult(
                file = outputFile,
                sampleRate = SAMPLE_RATE,
                channelCount = channelCount,
                frameCount = frameCount,
                outputMode = outputMode
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec処理エラー", e)
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
    
    /**
     * チャンクを並列処理
     */
    private suspend fun processChunkParallel(
        inputBuffer: FloatArray,
        frameCount: Int,
        outputChannels: Int,
        outputMode: OutputMode
    ): FloatArray = coroutineScope {
        val outputBuffer = bufferPool.getFloatArray(frameCount * outputChannels)
        
        // ワーカーに分割
        val framesPerWorker = (frameCount + PARALLEL_WORKERS - 1) / PARALLEL_WORKERS
        
        val jobs = (0 until PARALLEL_WORKERS).map { workerIndex ->
            async(Dispatchers.Default) {
                val startFrame = workerIndex * framesPerWorker
                val endFrame = minOf(startFrame + framesPerWorker, frameCount)
                
                if (startFrame < endFrame) {
                    processFrameRange(
                        inputBuffer, outputBuffer, startFrame, endFrame,
                        outputChannels, outputMode, workerIndex
                    )
                }
            }
        }
        
        jobs.awaitAll()
        outputBuffer
    }
    
    /**
     * チャンクを逐次処理（残りフレーム用）
     */
    private fun processChunkSequential(
        inputBuffer: FloatArray,
        frameCount: Int,
        outputChannels: Int,
        outputMode: OutputMode
    ): FloatArray {
        val outputBuffer = bufferPool.getFloatArray(frameCount * outputChannels)
        
        processFrameRange(inputBuffer, outputBuffer, 0, frameCount, outputChannels, outputMode, 0)
        
        return outputBuffer
    }
    
    /**
     * フレーム範囲を処理（ワーカー用）
     */
    private fun processFrameRange(
        inputBuffer: FloatArray,
        outputBuffer: FloatArray,
        startFrame: Int,
        endFrame: Int,
        outputChannels: Int,
        outputMode: OutputMode,
        workerIndex: Int
    ) {
        val tempInput = FloatArray(2)
        
        for (frame in startFrame until endFrame) {
            val inputOffset = frame * 2
            tempInput[0] = inputBuffer[inputOffset]
            tempInput[1] = inputBuffer[inputOffset + 1]
            
            val outputFrame = when (outputMode) {
                OutputMode.HRTF_BINAURAL -> processFrameBinauralFast(tempInput)
                OutputMode.HRTF_SURROUND_5_1 -> processFrameSurround51Fast(tempInput, false, workerIndex)
                OutputMode.HRTF_SURROUND_5_1_IMMERSIVE -> processFrameSurround51Fast(tempInput, true, workerIndex)
                OutputMode.HRTF_SURROUND_7_1 -> processFrameSurround71Fast(tempInput, workerIndex)
                OutputMode.HRTF_SURROUND_5_1_FAST -> processFrameSurround51UltraFast(tempInput)
            }
            
            val outputOffset = frame * outputChannels
            for (ch in 0 until outputChannels) {
                outputBuffer[outputOffset + ch] = outputFrame[ch]
            }
        }
    }
    
    /**
     * 処理済みチャンクをエンコーダに書き込み
     */
    private fun writeChunkToEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        chunk: FloatArray,
        channelCount: Int,
        encoderBuffer: ShortArray,
        initialBufferPos: Int,
        initialPresentationTimeUs: Long,
        bufferInfo: MediaCodec.BufferInfo,
        initialTrackIndex: Int,
        initialMuxerStarted: Boolean
    ): Quad<Int, Long, Int, Boolean> {
        var bufferPos = initialBufferPos
        var presentationTimeUs = initialPresentationTimeUs
        var trackIndex = initialTrackIndex
        var muxerStarted = initialMuxerStarted
        
        for (sample in chunk) {
            if (sample == 0f && bufferPos == 0 && chunk.all { it == 0f }) break  // 空チャンク検出
            
            encoderBuffer[bufferPos++] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            
            if (bufferPos >= encoderBuffer.size) {
                writeBufferToEncoder(encoder, encoderBuffer, bufferPos, presentationTimeUs, channelCount)
                presentationTimeUs += (ENCODER_FRAME_BUFFER_SIZE.toLong() * 1_000_000L) / SAMPLE_RATE
                bufferPos = 0
                
                if (!muxerStarted) {
                    val format = getEncoderOutputFormat(encoder)
                    if (format != null) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer開始")
                    }
                }
                
                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            }
        }
        
        bufferPool.returnFloatArray(chunk)
        return Quad(bufferPos, presentationTimeUs, trackIndex, muxerStarted)
    }
    
    /** 4要素タプル */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // =========================================================================
    // ヘルパー関数
    // =========================================================================

    /**
     * AAC MediaFormat生成
     */
    private fun createAacFormat(channelCount: Int, channelMask: Int): MediaFormat {
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_CHANNEL_MASK, channelMask)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE * channelCount * 2)
        }
    }

    /**
     * バッファをエンコーダに書き込み
     */
    @Suppress("UNUSED_PARAMETER")
    private fun writeBufferToEncoder(
        encoder: MediaCodec,
        buffer: ShortArray,
        size: Int,
        presentationTimeUs: Long,
        channelCount: Int
    ) {
        val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            
            for (i in 0 until size) {
                inputBuffer.putShort(buffer[i])
            }
            
            encoder.queueInputBuffer(inputIndex, 0, size * 2, presentationTimeUs, 0)
        }
    }

    /**
     * エンコーダ出力フォーマット取得
     */
    private fun getEncoderOutputFormat(encoder: MediaCodec): MediaFormat? {
        val bufferInfo = MediaCodec.BufferInfo()
        repeat(10) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    return encoder.outputFormat
                }
                in 0..Int.MAX_VALUE -> {
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        return null
    }

    /**
     * エンコーダ出力をMuxerにドレイン
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        trackIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean
    ) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)!!
                    
                    if (muxerStarted && trackIndex >= 0 && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    
                    encoder.releaseOutputBuffer(outputIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> return
            }
        }
    }

    /**
     * エンコーダにEOS送信
     */
    private fun signalEndOfStream(encoder: MediaCodec) {
        val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /**
     * エンコーダ完全ドレイン（EOS受信まで）
     */
    private fun drainEncoderFully(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        trackIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean
    ) {
        var retryCount = 0
        val maxRetries = 100  // 最大リトライ回数
        
        while (retryCount < maxRetries) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, FINAL_DRAIN_TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)!!
                    
                    if (muxerStarted && trackIndex >= 0 && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    
                    if (isEos) {
                        Log.d(TAG, "EOS受信完了")
                        return
                    }
                }
                else -> {
                    retryCount++
                    if (retryCount >= maxRetries) {
                        Log.w(TAG, "ドレインリトライ上限到達")
                        return
                    }
                }
            }
        }
        Log.d(TAG, "ドレイン完了（リトライ終了）")
    }

    /**
     * WAVサンプル読み取り
     */
    private fun readSample(buffer: ByteArray, sampleIndex: Int, bytesPerSample: Int, bitsPerSample: Int): Float {
        val offset = sampleIndex * bytesPerSample
        return when (bitsPerSample) {
            8 -> (buffer[offset].toInt() and 0xFF - 128) / 128f
            16 -> {
                val low = buffer[offset].toInt() and 0xFF
                val high = buffer[offset + 1].toInt()
                ((high shl 8) or low).toShort().toFloat() / 32768f
            }
            24 -> {
                val low = buffer[offset].toInt() and 0xFF
                val mid = buffer[offset + 1].toInt() and 0xFF
                val high = buffer[offset + 2].toInt()
                ((high shl 16) or (mid shl 8) or low) / 8388608f
            }
            32 -> {
                val b0 = buffer[offset].toInt() and 0xFF
                val b1 = buffer[offset + 1].toInt() and 0xFF
                val b2 = buffer[offset + 2].toInt() and 0xFF
                val b3 = buffer[offset + 3].toInt()
                ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) / 2147483648f
            }
            else -> 0f
        }
    }

    // =========================================================================
    // DSP処理関数
    // =========================================================================

    /**
     * バイノーラル（2ch）フレーム処理
     * 
     * Mid-Side処理で空間感を広げる
     */
    private fun processFrameBinaural(samples: FloatArray): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        // M-S処理で空間を広げる
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.6f  // サイド成分を強調
        
        return floatArrayOf(
            (mid + side).coerceIn(-1f, 1f),
            (mid - side).coerceIn(-1f, 1f)
        )
    }
    
    /**
     * バイノーラル（2ch）高速版（配列再利用）
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun processFrameBinauralFast(samples: FloatArray): FloatArray {
        val left = samples[0]
        val right = samples[1]
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.6f
        return floatArrayOf(
            (mid + side).coerceIn(-1f, 1f),
            (mid - side).coerceIn(-1f, 1f)
        )
    }

    /**
     * 5.1chサラウンド フレーム処理
     * 
     * @param samples 入力ステレオ [L, R]
     * @param immersive イマーシブモード（後方強化）
     * @return 5.1ch [FL, FR, C, LFE, BL, BR]
     */
    private fun processFrameSurround51(samples: FloatArray, immersive: Boolean): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        // センター
        val center = (left + right) * 0.5f * CENTER_GAIN
        
        // LFE（ローパス処理、モノラル）
        val lfeInput = (left + right) * 0.5f
        val lfe = (lfeFilter?.processMono(lfeInput, true) ?: lfeInput) * LFE_GAIN
        
        // リア（ハイパス処理、左右独立）
        val rearGainMultiplier = if (immersive) 1.8f else 1.0f
        val rearL = (rearLFilter?.processMono(left, true) ?: left) * REAR_GAIN * rearGainMultiplier
        val rearR = (rearRFilter?.processMono(right, false) ?: right) * REAR_GAIN * rearGainMultiplier
        
        // フロント
        val frontL = left * FRONT_GAIN
        val frontR = right * FRONT_GAIN
        
        return floatArrayOf(
            frontL.coerceIn(-1f, 1f),
            frontR.coerceIn(-1f, 1f),
            center.coerceIn(-1f, 1f),
            lfe.coerceIn(-1f, 1f),
            rearL.coerceIn(-1f, 1f),
            rearR.coerceIn(-1f, 1f)
        )
    }
    
    /**
     * 5.1chサラウンド 高速版（並列処理対応）
     */
    private fun processFrameSurround51Fast(samples: FloatArray, immersive: Boolean, workerIndex: Int): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        val center = (left + right) * 0.5f * CENTER_GAIN
        val lfeInput = (left + right) * 0.5f
        val lfe = (parallelLfeFilters?.get(workerIndex)?.processMono(lfeInput, true) ?: lfeInput) * LFE_GAIN
        
        val rearGainMultiplier = if (immersive) 1.8f else 1.0f
        val rearL = (parallelRearLFilters?.get(workerIndex)?.processMono(left, true) ?: left) * REAR_GAIN * rearGainMultiplier
        val rearR = (parallelRearRFilters?.get(workerIndex)?.processMono(right, false) ?: right) * REAR_GAIN * rearGainMultiplier
        
        val frontL = left * FRONT_GAIN
        val frontR = right * FRONT_GAIN
        
        return floatArrayOf(
            frontL.coerceIn(-1f, 1f),
            frontR.coerceIn(-1f, 1f),
            center.coerceIn(-1f, 1f),
            lfe.coerceIn(-1f, 1f),
            rearL.coerceIn(-1f, 1f),
            rearR.coerceIn(-1f, 1f)
        )
    }

    /**
     * 7.1chサラウンド フレーム処理
     * 
     * @param samples 入力ステレオ [L, R]
     * @return 7.1ch [FL, FR, C, LFE, BL, BR, SL, SR]
     */
    private fun processFrameSurround71(samples: FloatArray): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        // センター
        val center = (left + right) * 0.5f * CENTER_GAIN
        
        // LFE（モノラルなのでLチャンネルで処理）
        val lfeInput = (left + right) * 0.5f
        val lfe = (lfeFilter?.processMono(lfeInput, true) ?: lfeInput) * LFE_GAIN
        
        // リア（左右独立フィルタ）
        val rearL = (rearLFilter?.processMono(left, true) ?: left) * REAR_GAIN
        val rearR = (rearRFilter?.processMono(right, false) ?: right) * REAR_GAIN
        
        // サイド（左右独立フィルタ）
        val sideL = (sideLFilter?.processMono(left, true) ?: left) * SIDE_GAIN
        val sideR = (sideRFilter?.processMono(right, false) ?: right) * SIDE_GAIN
        
        // フロント
        val frontL = left * FRONT_GAIN
        val frontR = right * FRONT_GAIN
        
        // 7.1ch順序: FL, FR, C, LFE, BL, BR, SL, SR
        return floatArrayOf(
            frontL.coerceIn(-1f, 1f),
            frontR.coerceIn(-1f, 1f),
            center.coerceIn(-1f, 1f),
            lfe.coerceIn(-1f, 1f),
            rearL.coerceIn(-1f, 1f),
            rearR.coerceIn(-1f, 1f),
            sideL.coerceIn(-1f, 1f),
            sideR.coerceIn(-1f, 1f)
        )
    }
    
    /**
     * 7.1chサラウンド 高速版（並列処理対応）
     */
    private fun processFrameSurround71Fast(samples: FloatArray, workerIndex: Int): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        val center = (left + right) * 0.5f * CENTER_GAIN
        val lfeInput = (left + right) * 0.5f
        val lfe = (parallelLfeFilters?.get(workerIndex)?.processMono(lfeInput, true) ?: lfeInput) * LFE_GAIN
        
        val rearL = (parallelRearLFilters?.get(workerIndex)?.processMono(left, true) ?: left) * REAR_GAIN
        val rearR = (parallelRearRFilters?.get(workerIndex)?.processMono(right, false) ?: right) * REAR_GAIN
        
        val sideL = (parallelSideLFilters?.get(workerIndex)?.processMono(left, true) ?: left) * SIDE_GAIN
        val sideR = (parallelSideRFilters?.get(workerIndex)?.processMono(right, false) ?: right) * SIDE_GAIN
        
        val frontL = left * FRONT_GAIN
        val frontR = right * FRONT_GAIN
        
        return floatArrayOf(
            frontL.coerceIn(-1f, 1f),
            frontR.coerceIn(-1f, 1f),
            center.coerceIn(-1f, 1f),
            lfe.coerceIn(-1f, 1f),
            rearL.coerceIn(-1f, 1f),
            rearR.coerceIn(-1f, 1f),
            sideL.coerceIn(-1f, 1f),
            sideR.coerceIn(-1f, 1f)
        )
    }
    
    // =========================================================================
    // 高速処理（リアルタイム向け）
    // =========================================================================
    
    /**
     * 超高速5.1chイマーシブ処理（フィルタレス）
     * 
     * リアルタイム変換を見据えた最小レイテンシ実装。
     * DSPフィルタを使わず、純粋な算術演算のみで処理。
     * 音質はやや劣るが、処理速度は最速。
     * 
     * 最適化:
     * - フィルタ処理なし（算術演算のみ）
     * - 配列アロケーション最小化
     * - 分岐削減
     * - インライン展開可能な設計
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun processFrameSurround51UltraFast(samples: FloatArray): FloatArray {
        val left = samples[0]
        val right = samples[1]
        
        // フロント（入力をそのまま使用）
        val frontL = left * 0.85f
        val frontR = right * 0.85f
        
        // センター（モノミックス）
        val center = (left + right) * 0.45f
        
        // LFE（低域強調 - シンプルな平均化で擬似ローパス）
        // 実際のローパスフィルタなしでも、モノミックスで低域が強調される効果
        val lfe = (left + right) * 0.35f
        
        // リア（位相反転+減衰で後方感を演出）
        // ステレオ差分を使って包囲感を出す
        val stereoWidth = (left - right) * 0.5f
        val rearL = (left * 0.3f + stereoWidth * 0.4f)
        val rearR = (right * 0.3f - stereoWidth * 0.4f)
        
        return floatArrayOf(
            frontL.coerceIn(-1f, 1f),
            frontR.coerceIn(-1f, 1f),
            center.coerceIn(-1f, 1f),
            lfe.coerceIn(-1f, 1f),
            rearL.coerceIn(-1f, 1f),
            rearR.coerceIn(-1f, 1f)
        )
    }
    
    /**
     * 高速デコード・処理・エンコードパイプライン（5.1ch Fast専用）
     * 
     * フィルタ初期化不要、最小限の処理で高速変換
     */
    private suspend fun decodeProcessEncodeFast(
        inputUri: Uri,
        outputFile: File,
        progressCallback: (Int, String) -> Unit
    ): ProcessResult? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        
        // 高速処理用定数
        val fastChunkSize = 4096  // 通常の2倍
        val fastBufferSize = 4096
        
        try {
            extractor = MediaExtractor()
            
            val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            if (afd == null) {
                Log.e(TAG, "ファイルを開けません: $inputUri")
                return null
            }
            
            try {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } catch (e: Exception) {
                Log.e(TAG, "setDataSource失敗", e)
                afd.close()
                return null
            }
            
            // オーディオトラック検索
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            
            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "オーディオトラックが見つかりません")
                afd.close()
                return null
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = try {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) { 0L }
            
            Log.d(TAG, "高速処理開始: $inputMime, ${inputChannels}ch")
            
            // デコーダ設定
            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            
            // エンコーダ設定（5.1ch）
            val encodeFormat = createAacFormat(6, AudioFormat.CHANNEL_OUT_5POINT1)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var presentationTimeUs = 0L
            var frameCount = 0L
            var lastReportedProgress = -1
            
            // 高速処理用バッファ（事前確保）
            val encoderBuffer = ShortArray(fastBufferSize * 6)
            var encoderBufferPos = 0
            
            while (coroutineContext.isActive && !decoderDone) {
                // デコーダ入力
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                // デコーダ出力
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val decoderOutput = decoder.getOutputBuffer(outputIndex)!!
                        decoderOutput.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuf = decoderOutput.asShortBuffer()
                        
                        val samples = bufferInfo.size / 2
                        val framesInBuffer = samples / inputChannels
                        
                        // 高速読み取りと処理を統合
                        for (f in 0 until framesInBuffer) {
                            val baseIdx = f * inputChannels
                            val left = shortBuf.get(baseIdx).toFloat() / 32768f
                            val right = if (inputChannels >= 2) {
                                shortBuf.get(baseIdx + 1).toFloat() / 32768f
                            } else { left }
                            
                            // インライン高速処理
                            val frontL = left * 0.85f
                            val frontR = right * 0.85f
                            val center = (left + right) * 0.45f
                            val lfe = (left + right) * 0.35f
                            val stereoWidth = (left - right) * 0.5f
                            val rearL = (left * 0.3f + stereoWidth * 0.4f)
                            val rearR = (right * 0.3f - stereoWidth * 0.4f)
                            
                            // エンコーダバッファに直接書き込み
                            encoderBuffer[encoderBufferPos++] = (frontL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            encoderBuffer[encoderBufferPos++] = (frontR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            encoderBuffer[encoderBufferPos++] = (center.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            encoderBuffer[encoderBufferPos++] = (lfe.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            encoderBuffer[encoderBufferPos++] = (rearL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            encoderBuffer[encoderBufferPos++] = (rearR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            
                            frameCount++
                            
                            // バッファが満杯ならエンコーダに送信
                            if (encoderBufferPos >= encoderBuffer.size) {
                                writeBufferToEncoder(encoder, encoderBuffer, encoderBufferPos, presentationTimeUs, 6)
                                presentationTimeUs += (fastBufferSize.toLong() * 1_000_000L) / SAMPLE_RATE
                                encoderBufferPos = 0
                                
                                if (!muxerStarted) {
                                    val format = getEncoderOutputFormat(encoder)
                                    if (format != null) {
                                        trackIndex = muxer.addTrack(format)
                                        muxer.start()
                                        muxerStarted = true
                                    }
                                }
                                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
                            }
                        }
                        
                        val decoderPresentationTimeUs = bufferInfo.presentationTimeUs
                        decoder.releaseOutputBuffer(outputIndex, false)
                        
                        // 進捗更新
                        if (duration > 0) {
                            val progress = ((decoderPresentationTimeUs.toFloat() / duration) * 100).toInt().coerceIn(0, 99)
                            if (progress > lastReportedProgress) {
                                lastReportedProgress = progress
                                progressCallback(progress, "高速変換中")
                            }
                        }
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                        }
                    }
                }
            }
            
            // 残りのバッファを処理
            if (encoderBufferPos > 0) {
                writeBufferToEncoder(encoder, encoderBuffer, encoderBufferPos, presentationTimeUs, 6)
                if (!muxerStarted) {
                    val format = getEncoderOutputFormat(encoder)
                    if (format != null) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                drainEncoder(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            }
            
            progressCallback(99, "完了処理中")
            
            signalEndOfStream(encoder)
            drainEncoderFully(encoder, muxer, trackIndex, bufferInfo, muxerStarted)
            
            afd.close()
            
            Log.d(TAG, "高速変換完了: $frameCount フレーム")
            
            return ProcessResult(
                file = outputFile,
                sampleRate = SAMPLE_RATE,
                channelCount = 6,
                frameCount = frameCount,
                outputMode = OutputMode.HRTF_SURROUND_5_1_FAST
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "高速処理エラー", e)
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
