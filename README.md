# Rosebud Android

スマホの再生音と時間の流れから動きを自動生成し、MacのIntifaceへ送る個人用アプリ。**0.4.0／versionCode 6**、ID `jp.hyoutan.rosebud`、既存と同じ署名・保存設定を使用します。振動・Flickerの2系統、選択した2台へ同じ生成フレームを送ります。

第2の実装・初期値・検証・APKは **[docs/android-second-task.md](docs/android-second-task.md)**。ビルド・Lint・33テストは成功。**本人と2台の動作・実音声・静かな区間・一時停止／再開・全停止を短時間確認済み**です。実動作は約3分39秒、USB充電・画面ON・Wake Lockなし。導入の小ささは調整候補、長時間などは未検証です。第1の0.3.2で確認した内容は [docs/android-e2e.md](docs/android-e2e.md)、残件は [docs/TODO.md](docs/TODO.md)、再開は [docs/handoff.md](docs/handoff.md)。

## 画面と操作

- **セッション**：新15分＋エンドレス／比較用19分、時間、現在の自動レシピと状態、波形、出力上限、音追従。下部の開始・一時停止・再開と全停止は3画面共通。
- **動き**：8レシピの波形と見本。カードを押しても実行中の動きを上書きしません。通常のレシピは30〜60秒保ち、その中で音に合わせて変化します。
- **接続・設定**：接続先、対象選択、音声準備、プレビュー／実機。条件メモ、ログ共有、Wake Lock等は「診断・その他」へ。

**接続・音声準備だけでは動かず、開始が必要です。** 起動時はプレビュー。音追従OFFなら機器・取得なしで生成でき、機器への指令は送信しません。ONの場合は本当の音声取得を準備します。疑似音声や時間ジャンプはありません。

実機の順番（本人の操作を一つずつ案内）：

1. Macとスマホを同じWi-Fiへ。Intiface起動、既存Webの制御を全停止してタブを閉じ、対象2台の電源をON。
2. 「接続・設定」で接続先を確認して接続。IPだけ・全角数字・「。」にも対応。保存先は `ws://192.168.11.10:12345` ですが、IPは変わり得ます。
3. 一覧から「対応する機器すべて（2台）」を明示選択して決定。追加検出機器は自動で加えません。
4. 「取得を開始」し、録音／共有許可を本人が操作。有線イヤホンでBrave再生を準備。
5. プレビューを外して実機へ。セッションで「音とつくる15分」・音追従・上限を確認し、本人が「開始する」を押します。
6. 一時停止は全対象の停止応答後に時計・レシピ位置を保持し、接続・取得を維持。再開は明示操作。全停止は取得・接続も終了。停止未確認を成功表示しません。

新規導入の出力上限は30/30%、追従OFF。今回の端末の保存値35/34%・追従ONは維持し、初期値で上書きしません。上限は目標値ではなく天井です。正の指令で実際に動く下限は未測定です。

無音では弱めながら時計と動作を続け、演出として予定したレストA/Bだけは実行します。取得エラー・機器消失・通信断では実行解除し、自動再開しません。通知の停止でも全停止できます（ColorOSは通知の∨で展開）。生音声・画面映像は保存せず、PCへは機器指令だけを送ります。

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
