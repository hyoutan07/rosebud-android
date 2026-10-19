# rosebud-android

Android内部音声の取得検証に向けた、Kotlinアプリの開発用リポジトリです。

現在は **起動確認用の最小アプリ**（Rosebud Probe、0.1.0／ビルド1）です。起動すると説明とバージョンを表示します。音声取得・PC通信・Bluetooth制御は、次の実装で扱います。追加権限や常駐サービスはありません。

対象実機はCPH2199／Android 12／ColorOS V12です。APKの生成と、実機での起動確認は分けて扱います。環境の版・導入先・検証結果は [docs/environment.md](docs/environment.md) にまとめています。

2026-10-19に、このMacで `assembleDebug` とAndroid Lintが成功しました。Lintは **エラー0件・警告1件**（固定したGradleへの更新案内）です。端末は未接続のため、実機起動は未確認です。

## このMacでビルドする

ターミナルで、次を順に実行します。`source` はそのターミナル内のJava・SDK設定を読み込みます。新しいターミナルでは再び実行してください。

```bash
cd '/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android'
source ./scripts/android-env.sh
./gradlew assembleDebug lint
```

初回はGradleと依存ライブラリの取得にインターネット接続が必要です。Gradle自体を別途インストールする必要はありません。

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- Lintレポート：`app/build/reports/lint-results-debug.html`
- APKの絶対パス：`/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android/app/build/outputs/apk/debug/app-debug.apk`

ビルド用はcompileSdk／targetSdk **36（Android 16）**、インストールできる最低版はminSdk **31（Android 12）**です。スマホをAndroid 16へ更新する必要はありません。MacのJDKはビルドに使う道具で、アプリ本体はKotlinで書きます。

## スマホへ入れて起動する

まだ実機検証はしていません。次は、データ転送できるUSBケーブルでスマホをMacにつなぐところからです。

1. スマホの「設定」から端末情報・バージョン情報を開き、「ビルド番号」（ColorOSではバージョン番号などの表記）を7回タップして開発者向けオプションを有効にします。求められたら端末のロック解除番号を入力します。
2. 開発者向けオプションで「USBデバッグ」を有効にします。実際のメニューの位置・表記はColorOSで確認してください。
3. USBでMacに接続し、スマホに出る「USBデバッグを許可」の確認を、ご自身のMacであることを確かめて許可します。
4. 上のビルド手順と同じターミナルで実行します。

```bash
adb devices -l
```

端末の行に `device` と出れば準備できています。`unauthorized` ならスマホ側の許可画面を確認します。

```bash
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -W -n jp.hyoutan.rosebud/.MainActivity
```

スマホ側でインストール確認が出たら内容を確認して許可します。ホーム画面の **Rosebud Probe** からも起動できます。「起動確認用アプリ」とバージョン0.1.0が表示されれば、この段階の起動確認は完了です。`-d` はUSB接続した実機1台を指定します。複数台ある場合の指定や困ったときの対処は [環境手順](docs/environment.md) を参照してください。

## Android Studioで開く

Finderの「アプリケーション」から **Android Studio** を開き、**Open** でこのリポジトリのフォルダを選びます。初回ウィザードでは既存SDKの場所を `/Users/hyuta/Library/Android/sdk` に合わせてください。実機で進めるため、エミュレーターや仮想端末は不要です。GoogleログインやGeminiの設定も、ビルドには不要です。

Gradleの同期後、USB接続した実機を選んで ▶ Run でも起動できます。Gradle JDKの設定と初回起動の未確認範囲は [環境手順](docs/environment.md) にあります。
