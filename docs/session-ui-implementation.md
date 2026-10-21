# セッションの機器タブ：0.12.0 / code16

2026-10-21。本人が[機器タブ案](session-ui-proposal.md)を承認し、「吸引は常に見せてほしい、小さくしないでほしい」と追加指定。The Handy実装を主目的として維持しながら、その操作を整理する変更。

## 反映したこと

- メイン画面は「セッション」「接続・設定」の2つ。「動き」タブ、波形見本、不要になった描画クラスを削除。生成エンジン・通信・音声取得・吸引の実装は変更していない。
- セッション上部にROSEBUD / The Handyを配置。表示とスクロール位置だけを切り替え、開始・停止・明示対象選択・時計リセット・設定の送信を呼ばない。各タブと共通の下部欄から両機器の状態が見える。
- ROSEBUDの進行・段階・振動/Flickerの固定/自動選択を1つのカードへ。開始/再開をROSEBUDの操作欄へ移動。従来の一時停止は全対象に作用するため、実行中は「全対象を一時停止」と表示。個別停止を新たに実装したとは扱わない。
- 吸引はROSEBUD内に**常時展開**。自動ON/OFF、間隔±1秒、継続±0.05秒、目標0〜5、手動0〜5の全操作を維持。スクロールは必要だが、折りたたみの操作はない。旧XMLの操作要素の全属性と、コンテナーの寸法・18dp内側余白を比較し一致。文字・ボタン・SeekBarを小さくしていない。
- ROSEBUDの「出力上限・休み・シナリオ」は折りたたみ。休止割合はその中で直接編集。従来の保存キー、初期値、既存設定の読み方を保持。
- Handyは独立時計、生成中のまとまり、弱化/解除、フィニッシュ、開始/終了。長さ・範囲・移動時間と、出やすさを別の折りたたみに整理。待機中の段階配分は保存したスライダー値を表示する。
- Handy実機出力は引き続き無効。実機出力準備中・停止方式確認待ちを表示し、プレビューの開始/終了だけを操作できる。音追従ON時は音声準備が必要。未取得の現在位置や到達済みを表示しない。
- 画面下の「すべて停止」を常に共通で残す。接続・設定でも同じ全停止を使う。
- 接続・設定を「Mac / Intiface」「音声を準備」「診断・その他」へ整理。機器のスキャン/明示選択は「対象機器を選ぶ」から展開。音追従・取得終了も音声欄へ。プレビューはMac欄、単発確認・ログ・版・詳しい制御状態は診断欄へ移動。

## 変更しない制御

`MotionController`、`CaptureService`、`HandyTrack`、`SuctionTrack`、`ScenarioEngine`、`FlickerTrack`、`BoilingFrog`のコード差分なし。アプリID・署名・SDK・依存を維持。Next.js変更なし。

タブ選択はActivity再生成時の表示だけを保存し、cold起動はROSEBUD。表示復元で機器を選択・開始しない。全停止/通知停止/取得終了/エラー等は既存の経路を使う。Handyの停止方式・既知位置合わせ・実機送信への接続は[Handy第1実装の残件](handy-first-implementation.md)を維持する。

## 検証

最終結果・ハッシュ・端末導入状態は[検証記録](validation/2026-10-21-device-tabs-0.12.0.json)に記録する。

`./gradlew testDebugUnitTest assembleDebug lint --console=plain` を実行。UIのために生成・通信の試験を削除・無効化せず、既存130件を実行する。レイアウトIDの欠落/重複がないこと、吸引の表示属性・サイズの保持、制御実装の差分なしを確認。端末が接続できる場合は、Service/MediaProjection停止を確認して同じ署名で上書きし、設定の維持と画面切り替え・吸引の常時表示・共通全停止の配置を確認する。機器のスキャン・動作は開始しない。

APK：`app/build/outputs/apk/debug/app-debug.apk`。端末確認の有無は、APK作成と区別して上の検証記録へ残す。

最終結果：**130件成功、失敗/エラー/スキップ0、ビルド成功、Lintエラー0/警告9（従来同数）**。APKの署名は従来と一致。ビルド完了時点ではUSB未接続で端末更新・実画面確認待ち。その後、下記の更新と実画面確認を実施。

版固定のAPK：`app/build/releases/rosebud-0.12.0-debug.apk`。SHA-256：`48c30086fb86364be6a9d1166f38d9484e718903dc4558bc5ddc8e94cc3ea79b`。

## 端末への更新・実画面確認

本人がUSB接続設定を変更した後、CPH2199を認識。更新前の再確認でServiceなし、MediaProjection null、control_closed（未接続・active=false・吸引0・Handy停止）を確認して上書き更新した。0.12.0/code16、cold起動成功。control_settings.xmlは更新前から画面確認後までバイト一致。

[ROSEBUD画面](validation/ui-device-tabs/rosebud.png)、[Handyタブ](validation/ui-device-tabs/handy.png)、[常時展開の吸引](validation/ui-device-tabs/suction.png)、[接続・設定](validation/ui-device-tabs/settings.png)を目視確認。タブの切り替えとスクロールのみで、機器の接続・スキャン・開始、音声取得、プレビューの開始は行っていない。確認後もServiceなし・MediaProjection null。吸引0・自動OFF、ROSEBUDとHandyは待機。コード/APKは`00c9f8a`から変更なし。
