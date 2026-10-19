# Rosebud Android

スマホの内部音声を取得し、Androidで `boiling_frog` を生成してMacのIntifaceへ直接送る個人用アプリです。**0.3.2／versionCode 5**、ID `jp.hyoutan.rosebud`、既存0.2.0と同じデバッグ署名。今回の対象は振動とFlickerの2系統で、第3系統の正の出力は送りません。

第1タスクの実装・初期値・確認結果は [docs/android-e2e.md](docs/android-e2e.md)、次の担当者向けは [docs/handoff.md](docs/handoff.md)。実機で2台の動作、追従ON/OFF、再生の一時停止・再開、画面と通知からの停止、片方の切断時に残る機器の停止を確認しました。残件は **[docs/TODO.md](docs/TODO.md)** にまとめています。19分の既存構成を保ち、15分の新構成・8レシピ・音の特徴による選択は第2タスクへ回します。

## 使う順番

1. Macとスマホを同じWi-Fiへ接続し、MacのIntifaceを起動します。既存Webの動作を止めてタブを閉じます。対象機器の電源を入れます。
2. Rosebudの接続先を入力し「接続先を保存して接続」。**MacのIPだけでも入力できます**（例 `192.168.11.10`）。12345番と `ws://` を補い、全角数字と「。」を整えます。LANのIPは変更されることがあります。
3. 「機器をスキャン」し、一覧を確認します。2台なら **「対応する機器すべて（2台）」→「選んだ対象を決定」**。1台だけ選ぶこともできます。接続後に追加された機器は動作対象へ勝手に追加しません。
4. 初回の比較は「音声追従」OFF、出力上限は両方30%。「動作開始」で初めて動きます。「動作だけ停止」は取得と接続を維持し、再開始は最初からです。
5. 音と連動させる場合は少し下へスクロールして「取得を開始」。録音権限とAndroidの共有許可を本人が操作します。**接続・取得だけでは動きません。** 新しい許可は取得開始ごとに必要です。
6. 有線イヤホンでBraveの再生を始め、「音声追従」ONにして「動作開始」。アプリへ戻ると音量・段階・経過・生成値・機器ごとの送信／応答を確認できます。ON/OFFは動作中も比較可能。音が小さいときも弱めて動作と時間を継続します。
7. 終了は **「全停止（取得・接続も終了）」** または通知の「停止」（ColorOSでは通知右の∨で展開し、下部の「停止」）。全対象へStopDeviceCmdを送り、音声取得・接続を終了します。「取得を停止」も同じ終了処理です。停止応答がない場合は未確認として表示します。

取得前に追従ONへ変えると動作が止まる場合があります。音声取得を始めてから、改めて「動作開始」を押します。取得・通信エラーや対象機器の消失後は、自動再開しません。

今回の実機テストはユーザーの希望により2〜3分程度で区切ります。19分を実時間で待つ必要はなく、全段階と周回は自動テストで時刻を進めて確認します。実機試験では、一時停止／再開、追従ON/OFF、Brave前面、最後に停止と出力が戻らないことを比べます。長時間・非充電の成立とは分けます。

生音声・映像は保存しません。PCへ送るのは機器への指令だけです。マイクへの切り替えはありません。音量補正・上限の具体値、今回の失敗・未検証は [実装・検証の記録](docs/android-e2e.md) に残します。

## 再ビルド・上書き導入

構築済みのJDK 21・SDK・署名を使います。環境の作り直しは不要です。

```bash
cd '/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android'
source ./scripts/android-env.sh
./gradlew assembleDebug lint testDebugUnitTest --console=plain
adb devices -l
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n jp.hyoutan.rosebud/.MainActivity
```

APK：`/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android/app/build/outputs/apk/debug/app-debug.apk`。

Lint：`app/build/reports/lint-results-debug.html`。テスト：`app/build/reports/tests/testDebugUnitTest/index.html`。APKはGit対象外です。署名不一致の場合は自動でアンインストールせず、元の鍵を確認します。実機で動作中にAPKを上書きせず、先に全停止の応答とサービス終了を確認してください。

AGP 8.13.2／Kotlin 2.3.10／Gradle 8.13、compile・target SDK 36、min SDK 31を維持します。[環境記録](docs/environment.md)の0.1.0部分は導入時の履歴、[音声取得記録](docs/verification.md)の0.2.0部分は取得だけの初期検証です。

## 診断を残す

「今回／直近のログを共有」で、音声集計と接続・送信・停止の診断を共有できます。取得の条件メモへ再生元・有線・充電・画面条件を入力し、「現在のメモをログに記録」で試験の目印を残せます。ブラウザの選択は記録用で、取得対象をUIDで制限しません。別の音声アプリは止めて比較します。

USB接続中の開発者向け確認：

```bash
adb -d shell run-as jp.hyoutan.rosebud cat shared_prefs/probe.xml
adb -d shell run-as jp.hyoutan.rosebud tail -n 5 files/control.jsonl
adb -d shell run-as jp.hyoutan.rosebud ls files/probe-logs
```

音声ログは直近4セッション・各約1 MiB×3ファイル、制御ログは別に約1 MiB×3ファイルで巡回します。`control.2.jsonl` → `control.1.jsonl` → `control.jsonl` の順で読みます。必要な結果は試験後に保存してください。PCM・閲覧URL・機器のBluetoothアドレス・端末シリアルは診断へ自動保存しません。
