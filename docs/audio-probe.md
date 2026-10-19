# 内部音声プローブの実装（0.2.0時点）

現在の0.3.2では、この取得処理を保って接続・生成・2台制御を追加しました。現在の構成は [android-e2e.md](android-e2e.md) を参照。以下は0.2.0当時の記録です。

更新日：2026-10-19。対象：Rosebud Probe 0.2.0／ビルド2。実装・ビルド済み。実機の成立範囲は [verification.md](verification.md)、操作は [README](../README.md) を正本とします。

親プロジェクトの `docs/android-audio-probe-spec.md` を実装対象とし、既存の環境設定用アプリ（052c8ff）を拡張しました。親docs、rosebud-app、Intifaceは変更していません。

## 構成

- `MainActivity`：録音・通知・MediaProjectionの許可、開始／停止、メーター、条件メモ、共有。プラットフォームのActivity APIを利用し、UIライブラリを増やしません。
- `CaptureService`：`mediaProjection` 型のフォアグラウンドサービス。通知を先に作成してから `getMediaProjection` を呼び、コールバックを登録します。再開始ごとに新しい許可を使い、`START_NOT_STICKY` でトークンを使い回しません。
- `AudioStats`：48 kHz・ステレオ・PCM16のRMS／ピーク、累積サンプル、無音時間を集計。符号付き16 bitをDoubleへ変換し、左右の逆位相を足し合わせず、サンプルの二乗からRMSを計算します。
- `ProbeApplication`／`ProbeRepository`：アプリケーションに属する状態と容量上限付きの端末内ログ。Activity参照は保持しません。
- `LogShareProvider`：共有された特定のURIだけを一時的に読める、非公開・読み取り専用ContentProvider。共有用コピーはキャッシュ下に限定し、任意のパスや書き込みを許可しません。

キャプチャの入力には `setAudioPlaybackCaptureConfig` だけを使用し、`setAudioSource` は呼びません。対象usageは `MEDIA`／`GAME`／`UNKNOWN`。ブラウザ選択は比較条件のラベルであり、UIDフィルターは追加していません。他の許可された同一プロファイル内の再生音も混ざり得るため、比較時は他の音声を止めます。映像のVirtualDisplay・Surfaceは作りません。

取得ワーカーは約100 msを上限とする固定長バッファを繰り返し使い、非ブロッキングreadで空のときだけ20 ms待機します。PCMをディスクやログへ保存しません。取得したデータは音量集計に使って破棄します。

## Android 12とtargetSdk 36

2026-10-19に以下の公式仕様を確認しました。ビルド時のSDKと、端末が実行するOSの挙動は区別します。

| 根拠 | 実装への反映 |
| --- | --- |
| [Android：再生音のキャプチャ](https://developer.android.com/media/platform/av-capture) | RECORD_AUDIO、利用者のMediaProjection許可、同じユーザープロファイルが必要。再生元のusage・キャプチャ許可が条件。通常の自作アプリが保護音声を取得できるとは扱わない |
| [AudioRecord.Builder](https://developer.android.com/reference/android/media/AudioRecord.Builder#setAudioPlaybackCaptureConfig(android.media.AudioPlaybackCaptureConfiguration)) | 再生音用の設定をAudioRecordへ渡す。マイクへフォールバックしない |
| [FGSのmediaProjection種別](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection) | 利用者が画面で許可した後にサービスを開始し、startForegroundの後にgetMediaProjection。基本権限・種別固有権限・サービス型を宣言 |
| [Android 12以降の背景からのFGS開始制限](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | 利用者の画面操作を開始入口とし、起動時や再起動後に自動取得しない |
| [MediaProjectionと資源回収](https://developer.android.com/media/grow/media-projection) | 停止コールバックでワーカー停止を要求。Android 14以降の画面共有UIにもAPI分岐。新OSの仕様だけでAndroid 12のロック時動作を断定しない |
| [Doze／App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) | FGS・Wake Lockによる無期限継続を保証しない。電源条件とidle状態を記録して実測する |

manifestは `RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`WAKE_LOCK` を宣言します。通知の実行時許可はAndroid 13以上だけで求めます。Android 12では通知権限の確認画面は出ません。MediaProjection FGSの種別固有権限は新しいOSの要件に対応する宣言で、Android 12の実行時に新OSの権限画面を作るものではありません。マイク用FGS型やインターネット・ストレージの権限は追加していません。

CPH2199（API 31）上での音声専用MediaProjection成立は実機で確認する項目です。他のOSでの一般保証は今回の対象外です。ブラウザの版はPackageManagerから通常のアプリ情報を読み、Chromeの出荷時の旧システム版と現行版を混同しません。

## 継続・終了とログの意味

標準設定ではWake Lockを使いません。比較用に利用者が選んだ場合だけ、取得中のPARTIAL_WAKE_LOCKを15分のタイムアウト付きで保持し、ワーカー動作中に5分ごとに更新します。停止・サービス破棄では解放します。電池最適化の自動除外要求や端末設定の変更はしません。

画面／通知からの停止とMediaProjectionの取り消しは、共通の停止フラグへ集めます。AudioRecordのstop／releaseは所有するワーカーのfinallyで実行し、その後MediaProjection・動的レシーバー・Wake Lock・FGS通知を終了してサービスを止めます。サービス破棄でも終了を要求し、プロセス終了時はOSが保持資源を回収します。次回起動は最終保存状態から `previous_process_exit_unknown` として扱い、正常終了と偽りません。

- 約5秒ごとの `audio_window` は、その区間のサンプル数・RMS／ピークと累積集計を含みます。サンプルは左右を含む値の数で、96,000サンプルが48 kHzステレオの1秒です。
- 無音は1ブロックのRMS < 0.001（−60 dBFS）の区間です。1秒以上続くと `silence_begin`、音が戻ると `silence_end` を記録します。短い無音も累積値に含みます。これは音の小ささの目安で、会話内容やプレイヤー状態を識別しません。
- 音声ゼロのサンプルと、サンプルが届かない状態を区別します。2秒以上届かなければ `no_samples_for_2s`、復帰時に `read_resumed`。`no_data_age_ms` と `max_read_gap_ms` でも途切れを見ます。サスペンド中にコードが走らない場合は復帰時の単調時計との差を残します。
- `screen_event`、`screen_on`、`probe_ui_visible/hidden` は表示状態の根拠です。ブラウザの前面／背景を自動監視する利用状況権限は追加していないため、手動の試験時刻・メモと合わせます。
- `output_device_types` は利用可能な出力機器一覧です。type 3は有線ヘッドセット、type 4は有線ヘッドホンですが、一覧だけでその経路から実際に聴いたと断定しません。利用者の聴取確認と合わせます。
- `power_save`、`device_idle`、`ignoring_battery_optimizations`、充電状態・バッテリー残量・メディア音量を記録します。adbでの診断はUSB充電・起床の影響を受け得るため、非接続での試験を別扱いにします。

保存上限は4セッション、1セッションあたり約1 MiBのイベント3世代（約12 MiB＋小さなメタデータ）。共有キャッシュは最大約3 MiBのコピーを3個。PCMは固定バッファだけで、ログをメモリに蓄積しません。巡回で消えたイベントは復元せず、ヘッダーと累積集計を残します。ディスク保存の失敗はUIと共有ログに表示します。

追加した自動テストは、無音とステレオ時間換算、負の最大振幅、左右の逆位相、重み付き全体RMS／区間集計のリセット、無音区間の分断、バッファ有効長の6件です。取得権限や実機の継続性をこれらの単体テストで保証しません。
