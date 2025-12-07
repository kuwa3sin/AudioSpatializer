# Audio Spatializer

<p align="center">
  <img src="https://img.shields.io/badge/Android-13%2B-green?logo=android" alt="Android 13+">
  <img src="https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Material-You-blue?logo=material-design" alt="Material You">
</p>

ステレオ音源を5.1chサラウンドに変換し、Android Spatializer APIによる空間オーディオ再生を実現するアプリケーションです。
ヘッドトラッキング対応デバイスを利用することで、頭の動きに追従する没入感のあるオーディオ体験を提供します。

## 主な機能

### Convert（変換）
ステレオ音源を5.1chサラウンドに変換します。

- **品質優先モード**: 疑似HRTF(頭部伝達関数)を利用した変換
- **高速モード**: リアルタイム処理向けの軽量な変換
- 対応入力形式: WAV, FLAC, AAC, MP3
- 出力形式: AAC (M4A) 5.1ch

### Musics（再生）
変換済みファイルのライブラリ管理と再生を行います。

- 再生/一時停止、シークバー操作
- ファイルのリネーム・削除・エクスポート
- トランスオーラルモード対応デバイスでは、内蔵スピーカーでサラウンド再生が可能です。
- ヘッドトラッキング対応デバイスを接続すると、頭の動きに連動して音像が移動します。

### Realtime（リアルタイム再生）
音楽ファイルをリアルタイムで5.1ch空間化しながら再生します。

- 変換不要で即座に空間オーディオを体験できます。
- ヘッドトラッキングにも対応

### デバイス情報
デバイスの空間オーディオ対応状況を確認できます。

- 空間オーディオへの対応状況
- ヘッドトラッキング対応状況
- トランスオーラルモード対応状況

## スクリーンショット

| Convert                                                                                                                              | Musics                                                                                                                               | Realtime                                                                                                                             | デバイス情報                                                                                                                         |
| ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ |
| <img width="1280" height="2582" alt="Image" src="https://github.com/user-attachments/assets/7ddb22a9-c65f-4231-9f61-0b4969f8a507" /> | <img width="1265" height="2566" alt="Image" src="https://github.com/user-attachments/assets/4a49c239-9ace-454e-9eb8-01cbf11aa8d5" /> | <img width="1280" height="2577" alt="Image" src="https://github.com/user-attachments/assets/35a2f475-cf2b-4a89-a791-6a4c803b5914" /> | <img width="1280" height="2608" alt="Image" src="https://github.com/user-attachments/assets/beed0492-6bef-4a37-b6ec-737b85c139f9" /> |
| ファイル選択→変換                                                                                                                    | ライブラリ再生                                                                                                                       | リアルタイム空間化                                                                                                                   | デバイス対応確認                                                                                                                     |

## 技術仕様

### システム要件

| 項目               | 要件                 |
| ------------------ | -------------------- |
| Android            | 13 (API 33) 以上     |
| Java               | 21                   |
| ヘッドトラッキング | 対応ヘッドセット必須 |

### 空間オーディオの対応デバイス例

- Google
  - Pixel 6 以降 (ただし、aを除く)
  - Pixel Fold
  - Pixel Tablet
    - このうち、Pixel 10シリーズ、Pixel Fold、Pixel 9 Pro Fold、Pixel Tabletでは内蔵スピーカーでのサラウンド再生(トランスオーラルモード)が利用可能です。

- その他対応デバイス

### ヘッドトラッキングの対応デバイス例

- Google
  - Pixel Buds Pro
  - Pixel Buds Pro 2 (検証済み)
  
- Sony
  - WH-1000XM5
  - WH-1000XM6 (検証済み)
  - WH-ULT900N / ULT WEAR
  - WF-1000XM5
  - WF-LS900N / LinkBuds S
  
- その他対応デバイス

## アーキテクチャ

### 処理フロー

```
音声ファイル
    │
    ▼
MediaCodec（デコード）
    │
    | ステレオ PCM
    ▼
UpmixProcessor / AudioProcessor（5.1ch変換）
    │
    | 5.1ch PCM
    ▼
AudioTrack（CHANNEL_OUT_5POINT1）
    │
    | SPATIALIZATION_BEHAVIOR_AUTO
    ▼
Android Spatializer API
    │
    | 5.1ch音源 + ヘッドトラッキング
    ▼ 
音声を出力
```

### 5.1ch チャンネル生成

| チャンネル   | 生成方法                 |
| ------------ | ------------------------ |
| Front L/R    | 入力そのまま             |
| Center       | (L + R) / 2              |
| LFE          | ローパスフィルタ (120Hz) |
| Surround L/R | 位相反転 + リバーブ      |

## プロジェクト構造

```
app/src/main/java/com/example/audiospatializer/
├── AudioProcessor.kt              # メイン変換エンジン
├── BiquadFilter.kt                # DSPフィルター
├── MainActivity.kt                # メイン画面
│
├── audio/
│   ├── HeadTrackingDeviceManager.kt   # デバイス管理
│   ├── SpatialAudioController.kt      # Spatializer API
│   └── UpmixProcessor.kt              # リアルタイムアップミックス
│
├── data/
│   ├── ConvertedTrack.kt          # Roomエンティティ
│   └── ConvertedRepository.kt     # リポジトリ
│
├── service/
│   ├── RealtimePlayerService.kt   # リアルタイム再生
│   └── PlaybackService.kt         # ライブラリ再生
│
└── ui/
    ├── ConvertFragment.kt         # 変換タブ
    ├── MusicsFragment.kt          # 再生タブ
    ├── RealtimeFragment.kt        # リアルタイムタブ
    └── HeadTrackingFragment.kt    # デバイス情報
```

## UI/UX

- **Material You (Dynamic Color)**: 壁紙に合わせたテーマカラー
- **Material 3**: 角丸カードのデザイン、フローティングコントロール
- **ダークモード対応**: システム設定に連動

## ビルド

```bash
# JDK 21が必要
./gradlew assembleDebug
```

### 主要な依存関係

- AndroidX Core KTX
- Room Database
- Media3 ExoPlayer
- DataStore Preferences
- Material Components 3
