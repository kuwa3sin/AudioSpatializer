# Audio Spatializer

Android向け空間オーディオアプリケーション。ステレオ音源をHRTFベースの5.1chサラウンドに変換、またはリアルタイムで空間化再生します。

## 主要機能

### 🎵 Convert（変換）
- ステレオ音源を5.1chサラウンドに変換
- HRTF処理による没入感のある3Dサウンド
- 変換後はMusicsタブで再生可能

### 📚 Musics（ライブラリ）
- 変換済みファイルの一覧表示・再生
- ExoPlayerによる高品質再生
- ヘッドトラッキング対応

### 🎧 Realtime（リアルタイム再生）
- 音楽ファイルを**変換せずに**リアルタイムで5.1ch空間化再生
- Android Spatializer APIによる自動空間オーディオ適用
- ヘッドトラッキング対応イヤホンで最高の体験

## スクリーンショット

| Convert           | Musics         | Realtime           |
| ----------------- | -------------- | ------------------ |
| ファイル選択→変換 | ライブラリ再生 | リアルタイム空間化 |

## 技術仕様

### 対応フォーマット

| 入力                  | 出力             |
| --------------------- | ---------------- |
| WAV (PCM16, ステレオ) | AAC (M4A)        |
| FLAC                  | 5.1ch サラウンド |
| AAC/M4A               |                  |
| MP3                   |                  |

### システム要件

- Android 13 (API 33) 以上
- Java 21
- Kotlin 1.9+
- ヘッドトラッキング: 対応イヤホン必須（Pixel Buds Pro, Sony WF-1000XM5等）

---

## リアルタイム空間化の仕組み

`RealtimePlayerService.kt`

### 処理フロー

```
音楽ファイル
    │
    ▼
MediaCodec（デコード）
    │
    ▼ ステレオ PCM
    │
UpmixProcessor（5.1ch変換）
    │
    ▼ 5.1ch PCM
    │
AudioTrack（CHANNEL_OUT_5POINT1）
    │
    ▼ SPATIALIZATION_BEHAVIOR_AUTO
    │
Android Spatializer API
    │
    ▼ HRTF + ヘッドトラッキング
    │
🎧 ヘッドフォン出力
```

### UpmixProcessor

ステレオ音源を5.1chにリアルタイム変換:

| チャンネル   | 生成方法                       |
| ------------ | ------------------------------ |
| Front L/R    | 入力そのまま                   |
| Center       | (L + R) / 2                    |
| LFE          | ローパスフィルタ (120Hz)       |
| Surround L/R | 位相反転 + ディレイ + リバーブ |

```kotlin
class UpmixProcessor(sampleRate: Int) {
    private val lfeFilter = BiquadFilter(sampleRate).apply {
        setLowpass(120f, 0.707f)
    }
    
    fun process(left: Float, right: Float): FloatArray {
        val center = (left + right) * 0.5f * centerGain
        val lfe = lfeFilter.process((left + right) * 0.5f) * lfeGain
        val surroundL = processReverb(-left * 0.3f)
        val surroundR = processReverb(-right * 0.3f)
        
        return floatArrayOf(left, right, center, lfe, surroundL, surroundR)
    }
}
```

---

## 5.1chサラウンド変換

`AudioProcessor.kt`

周波数帯域分離によるチャンネル配分:

```
ステレオ入力
    │
    ├── LPF (120Hz) ────────────────→ LFE (0.1ch)
    │
    ├── BPF (300-3000Hz) ───────────→ Center
    │
    ├── HPF (3000Hz) + 残差 ────────→ Front L/R
    │
    └── リバーブ処理 ───────────────→ Surround L/R
```

#### チャンネルゲイン設定

| チャンネル   | ゲイン | 説明                |
| ------------ | ------ | ------------------- |
| Front L/R    | 0.9    | メイン音声          |
| Center       | 0.9    | ボーカル/ダイアログ |
| LFE          | 0.7    | 低域補強            |
| Surround L/R | 0.35   | 環境音/リバーブ     |

#### Biquadフィルター

`BiquadFilter.kt`

2次IIRフィルタの実装:

```
        b0 + b1*z^-1 + b2*z^-2
H(z) = ─────────────────────────
        a0 + a1*z^-1 + a2*z^-2
```

```kotlin
enum class Type { LOWPASS, HIGHPASS, BANDPASS, NOTCH, ALLPASS }

fun process(input: Float): Float {
    val output = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
    x2 = x1; x1 = input
    y2 = y1; y1 = output
    return output
}
```

---

### 5. Android Spatializer API統合

`SpatialAudioController.kt`

Android 13+のSpatializer APIをリフレクションで利用:

```kotlin
class SpatialAudioController(context: Context) {
    private val audioManager: AudioManager
    private var spatializer: Any? = null  // android.media.Spatializer
    
    fun isAvailable(): Boolean {
        // リフレクションでSpatializer.getImmersiveAudioLevel()を呼び出し
    }
    
    fun setHeadTrackingEnabled(enabled: Boolean) {
        // Spatializer.setHeadTrackingEnabled()
    }
}
```

---

## プロジェクト構造

```
app/src/main/java/com/example/audiospatializer/
├── AudioProcessor.kt          # メイン変換エンジン
├── AudioSpatializerApp.kt     # Application
├── BiquadFilter.kt            # Biquadフィルター
├── MainActivity.kt            # メインUI（3タブ構成）
│
├── audio/
│   ├── HeadTrackingDeviceManager.kt  # ヘッドトラッキングデバイス管理
│   ├── SpatialAudioController.kt     # Spatializer API制御
│   └── UpmixProcessor.kt             # ステレオ→5.1chアップミックス
│
├── data/
│   ├── ConvertedTrack.kt      # Roomエンティティ
│   ├── ConvertedTrackDao.kt   # DAO
│   ├── ConvertedDatabase.kt   # Roomデータベース
│   └── ConvertedTrackRepository.kt
│
├── service/
│   ├── RealtimePlayerService.kt   # リアルタイム5.1ch再生サービス
│   ├── PlaybackService.kt         # 変換済みファイル再生サービス
│   ├── SpatialAudioService.kt     # 空間オーディオ変換サービス
│   ├── SpatialAudioTileService.kt # クイック設定タイル
│   └── SpotifyListenerService.kt  # 音楽アプリ連携（通知リスナー）
│
├── settings/
│   └── SpatialAudioSettingsRepository.kt  # DataStore設定
│
└── ui/
    ├── ConvertFragment.kt         # 変換画面
    ├── MusicsFragment.kt          # 再生画面
    ├── RealtimeFragment.kt        # リアルタイム再生画面
    ├── MusicsViewModel.kt
    ├── MusicListAdapter.kt
    ├── SupportedDeviceAdapter.kt
    ├── HeadTrackingActivity.kt    # ヘッドトラッキング設定
    └── HeadTrackingFragment.kt
```

---

## ビルド

```bash
# JDK 21が必要
./gradlew assembleDebug
```

### 依存関係

- AndroidX Core KTX
- Room Database
- ExoPlayer
- DataStore Preferences
- Material Components (Material 3)

---

## UI デザイン

Material 3 Expressiveスタイルを採用:
- 32dp角丸のカード
- フローティングコントロールバー
- パルスアニメーション（再生中）
- ステータスチップ表示

---

## ライセンス

MIT License
