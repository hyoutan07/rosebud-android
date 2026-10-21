# Rosebud Android

スマホの再生音と時間から動きを自動生成し、MacのIntifaceへ送る個人用アプリ。**最新版0.12.0／versionCode16（機器タブ・吸引常時表示）**。CPH2199へ0.12.0/code16を上書き導入済み・保存設定を維持。ID・署名・接続設定・保存済み上限/休止割合を保持します。

**吸引の追加・本人による実機動作確認まで完了**：[吸引の仕様・再送・検証](docs/suction.md)。セッション画面を下へスクロールすると、独立した手動0〜5・自動ON/OFF・間隔/継続/目標を操作できます。音声取得・V/F開始は不要。自動OFF・指令0で起動します。2026-10-20、本人から実機確認とタスク完了の報告を受領しました。

既存V/Fの正本は **[お気に入りを基準にした中間の大きな波](docs/favorite-crescendo-spec.md)**。本人は30〜60秒の保持を受容し、お気に入りの強弱を各パターンへ重ね、時間で強くなる流れを希望。0.9.1更新後に「完成だ」「すばらしい」と評価し、第2終了を報告した。今後は具体的な使用感の調整から扱い、新機能・追加試験を自動で始めない。最新版の中間・終盤の追加測定ログは未記録で、本人の完成評価と区別する。

0.9.1修正版は端末更新済み：本人は各パターンの個性を残した上乗せを希望。0.9.0は抽選/保持/音反応を維持した一方、中間の実出力を共通の大波で置換していた。既存の並び/形を土台に戻し、大波を振幅の包絡として重ね、中間Fは次ONで音量も反映する。

音声再確認：0.9で本人が取得のみ開始後、Braveの再生trackと非ゼロ入力を確認（5秒窓RMS -39.21/-43.16 dBFS・read error0）。機器active=false、両台送信0。0.8でゼロになった原因は未確定。

[0.8の休止割合](docs/stage-rest-ratio-spec.md)、[0.7の操作/配色](docs/interaction-refinement-spec.md)、[独立生成の土台](docs/dual-track-spec.md)、[単発確認](docs/flicker-trial.md)。中央選択・往復・承認済みダークUI・休止予算は保持。

第2の実装・初期値・検証・APKは **[docs/android-second-task.md](docs/android-second-task.md)**。ビルド・Lint・33テストは成功。**本人と2台の動作・実音声・静かな区間・一時停止／再開・全停止を短時間確認済み**です。実動作は約3分39秒、USB充電・画面ON・Wake Lockなし。導入の小ささは調整候補、長時間などは未検証です。第1の0.3.2で確認した内容は [docs/android-e2e.md](docs/android-e2e.md)、残件は [docs/TODO.md](docs/TODO.md)、再開は [docs/handoff.md](docs/handoff.md)。

## セッションの整理 0.12.0/code16

本人が機器タブ案を承認し、吸引を小さくせず常時表示する指定を反映。セッション上部でROSEBUD / The Handyを切り替え、下の画面は「セッション」「接続・設定」の2つへ。「動き」の見本画面を削除し、パターンの固定/自動はROSEBUDの操作内に残します。吸引は従来サイズで常時展開、他の設定は折りたたみ。全停止は常に共通です。[仕様・検証と導入状態](docs/session-ui-implementation.md)。Handyの実機出力の無効化は継続します。

## Handy第1実装 0.11.0/code15

[仕様・停止方式の残件・検証](docs/handy-first-implementation.md)。130テスト成功、ビルド成功、Lintエラー0/警告9。セッション下部にHandy専用の無送信プレビューを追加。8/7/5分反復、独立時計・乱数、3候補の重み、Handyだけの弱化/時計保留、手動フィニッシュに対応。**Handyの実機出力は停止経路未成立のため無効**。位置機器を含む実機開始は拒否します。従来の実機V/F/吸引はRosebudだけを明示選択してください。本人の追加依頼でCPH2199へ上書き更新済み。保存設定は前後一致、待機画面を確認。Handyの実機確認は未実施。APKは下記の同じ出力先です。

## 画面と操作

- **セッション**：ROSEBUD / The Handyを機器タブで切り替え。ROSEBUDの吸引は常時展開。各機器の進行と操作を分け、全停止と状態要約は下部に共通表示。
- **ROSEBUD内の現在動作**：振動/Flickerの個別の固定/自動を維持。「動き」見本画面は削除。抽選と保持、現在ONを完了してから切り替える制御は従来どおりです。
- **接続・設定**：「Mac / Intiface」「音声を準備」「診断・その他」。対象選択はMac欄から展開。音追従・取得終了も音声欄へ集約。

**接続・音声準備だけでは動かず、開始が必要です。** 起動時はプレビュー。音追従OFFなら機器・取得なしで生成でき、機器への指令は送信しません。ONの場合は本当の音声取得を準備します。疑似音声や時間ジャンプはありません。

実機の順番（本人の操作を一つずつ案内）：

1. Macとスマホを同じWi-Fiへ。Intiface起動、既存Webの制御を全停止してタブを閉じ、対象2台の電源をON。
2. 「接続・設定」で接続先を確認して接続。IPだけ・全角数字・「。」にも対応。保存先は `ws://192.168.11.10:12345` ですが、IPは変わり得ます。
3. 一覧から「対応する機器すべて（2台）」を明示選択して決定。追加検出機器は自動で加えません。
4. 「取得を開始」し、録音／共有許可を本人が操作。有線イヤホンでBrave再生を準備。
5. プレビューを外して実機へ。セッションで「音とつくる15分」・音追従・上限を確認し、本人が「開始する」を押します。
6. 一時停止は全対象の停止応答後に時計・レシピ位置を保持し、接続・取得を維持。再開は明示操作。全停止は取得・接続も終了。停止未確認を成功表示しません。

新規導入の出力上限は30/30%、追従OFF。0.9.1導入記録の保存値は上限100/100%・休み28/8/1%・追従ONで、本人が変更した値を初期値で上書きしません。記録時点の値で、現在値や推奨値とは区別します。新15分の上限は出力を拡大縮小する倍率ではなく天井です。比較19分の従来倍率は保持。7%・2秒は本人の最弱候補で、物理最小値の確定ではありません。

新15分の予定されたFlicker休止は、段階別の割合からまとめて生成します。無音・配列固有の谷・同%の切れ目で別途ゼロを追加しません。静かなフィニッシュも15/20%・3秒で動きを保ちます。間Aは振動極弱／Flicker0、間Bは両方0。取得エラー・機器消失・通信断では実行解除し、自動再開しません。通知の停止でも全停止できます（ColorOSは通知の∨で展開）。生音声・画面映像は保存せず、PCへは機器指令だけを送ります。

## ビルド・更新

構築済みのJDK21・SDK・署名を使用し、依存・環境は更新していません。

```bash
cd '/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android'
source ./scripts/android-env.sh
./gradlew assembleDebug lint testDebugUnitTest --console=plain
adb devices -l
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -W -n jp.hyoutan.rosebud/.MainActivity
```

更新直後の起動が`LaunchState: UNKNOWN`でホーム画面に留まる場合、パッケージ更新完了後に最後の起動コマンドだけ再実行します。機器動作中は更新せず、全停止とサービス終了を先に確認。署名不一致で自動アンインストールしません。

APK：`app/build/outputs/apk/debug/app-debug.apk`。Lint：`app/build/reports/lint-results-debug.html`。単体テスト：`app/build/reports/tests/testDebugUnitTest/index.html`。全てGit対象外です。AGP8.13.2／Kotlin2.3.10／Gradle8.13／SDK36／min31を維持。詳細は [環境記録](docs/environment.md)。

## 診断

「診断・その他」の共有で音声集計・接続・生成・送信・停止のログを取り出せます。音声ログは直近4セッション・約1MiB×3、controlログは別の約1MiB×3で巡回。`control.2.jsonl`→`control.1.jsonl`→`control.jsonl`の順です。PCM・URL・Bluetoothアドレス・端末シリアルは自動保存しません。試験後は必要な根拠を抽出し、全文ログを引き継ぎの前提にしないでください。

```bash
adb -d shell run-as jp.hyoutan.rosebud cat shared_prefs/control_settings.xml
adb -d shell run-as jp.hyoutan.rosebud tail -n 5 files/control.jsonl
adb -d shell dumpsys activity services jp.hyoutan.rosebud
adb -d shell dumpsys media_projection
```

実機試験は1ケース2〜3分。15〜80分を待つ代わりに、長い進行は模擬時計・固定seedで検証済みです。通信が届かない条件の物理停止・画面オフ・非充電・長時間は未検証を引き継ぎます。
