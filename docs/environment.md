# Android開発環境

更新日：2026-10-19。状態：**完了（このMacの環境導入、デバッグAPK生成、Android Lint、CPH2199へのUSB導入・起動確認）**。音声取得機能は未実装。

## 対象と範囲

- 作業リポジトリ：`/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android`
- origin：`https://github.com/hyoutan07/rosebud-android.git`、開始時のブランチはmain。
- Mac：macOS 26.2（25C56）、Apple Silicon／arm64。作業開始時に空き約132 GiB。
- 対象実機：CPH2199、Android 12／API 31、ビルドCPH2199_11_C.42をadbで確認。ColorOS V12、RAM 6 GBはユーザー申告。
- この作業は開発環境と起動確認用アプリまで。親のdocs、既存rosebud-app、Intifaceの設定は変更しない。

## 導入前の調査

PATH、`/Applications`、ユーザーのApplications、`~/Library/Android`、システム／ユーザーのJavaVirtualMachines、HomebrewのCellar／opt／Caskroomなどを確認。Android Studio・SDK・adb・Gradle・有効なJDKは見つからなかった。`/usr/bin/java` と `javac` は存在するが、Javaランタイム未導入と応答した。

Homebrewは `/opt/homebrew/bin/brew` に既存導入され、通常PATHには入っていなかった。今回の構築には使わず、既存パッケージやシェル起動設定は変更しない。Rosetta 2の既存パッケージも確認済みで、新規導入していない。

## 固定した構成

| 項目 | 版・設定 | 保存先／固定箇所 |
| --- | --- | --- |
| Android Studio | Rabbit 1／2026.2.1、配布ビルド2026.2.1.8、Apple Silicon版 | `/Applications/Android Studio.app` |
| ビルド用JDK | Eclipse Temurin 21.0.12.1+1 LTS／arm64 | `/Users/hyuta/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home` |
| IDE用JDK | Studio同梱JBR 25.0.3+-15898627-b508.16 | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| Android SDK | ユーザーの標準配置 | `/Users/hyuta/Library/Android/sdk` |
| Command-line Tools | 22.0／配布番号15859902、Mac ARM版 | SDK内 `cmdline-tools/22.0` |
| SDK Platform | Android 16／API 36、revision 2 | SDK内 `platforms/android-36` |
| Build-Tools | 35.0.0 | SDK内 `build-tools/35.0.0`、`app/build.gradle.kts` |
| Platform-Tools／adb | 37.0.1-15733141／ADB 1.0.41 | SDK内 `platform-tools` |
| Android Gradle Plugin（AGP） | 8.13.2 | ルート `build.gradle.kts` |
| Kotlin Gradle Plugin | 2.3.10 | ルート `build.gradle.kts` |
| Gradle | 8.13 | `gradle/wrapper/gradle-wrapper.properties` |
| compileSdk／targetSdk | 36 | `app/build.gradle.kts` |
| minSdk | 31（Android 12） | `app/build.gradle.kts` |
| Java／Kotlinの出力 | JVM 17 | `app/build.gradle.kts` |

GradleはWrapperで取得するため、システム共通のGradleは導入しない。KotlinもGradleが取得する。SDKの絶対パスはGit管理しない `local.properties` に保存する。ターミナルのJava・SDK設定は `source ./scripts/android-env.sh` で読み込む。`gradle/gradle-daemon-jvm.properties` でもGradle用JDKのメジャー版を21へ固定する。

### 版の選定根拠

- [Android公式：AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：8.13.2はKotlin 2.3対応。必要なGradleの最低版は8.13、JDKは17以上、標準Build-Toolsは35.0.0、対応APIは36.1まで。
- [Kotlin公式：互換表](https://kotlinlang.org/docs/gradle-configure-project.html)：Kotlin 2.3.10の対応範囲はGradle 7.6.3〜9.0.0、AGP 8.2.2〜9.0.0。今回の組合せはこの範囲内。
- [Gradle公式：Java互換表](https://docs.gradle.org/current/userguide/compatibility.html)：JDK 21でGradleを動かすには8.5以上。Studio同梱JBR 25はGradle 8.13の実行対象外なので、ビルド用にJDK 21を追加した。
- [Android公式：StudioとAGPの互換表](https://developer.android.com/studio/releases#android_gradle_plugin_and_android_studio_compatibility)：Rabbit 1はAGP 7.1〜9.4をサポート。
- [Android公式：JDKの役割](https://developer.android.com/build/jdks)：KotlinのビルドにもJDKが必要。IDE自体は同梱JBRで動かし、IDEとターミナルの**Gradle用**JDKは21へ揃える。Panda 1以降のStudioはGradle Daemon JVM criteriaに対応する。

最新のメジャー版への追随は今回の目的にせず、互換性を確認できる版を固定した。**API 36はMacでコンパイルする際のSDK**であり、**API 31は端末へインストールできる最低条件**。CPH2199にAndroid 16を入れる必要はない。Androidの実行環境はアプリを動かすため、スマホへMac用JDKを入れる必要もない。

## 公式配布元と確認

2026-10-19、このチャットでユーザーがAndroid Studio・SDKの規約に同意し、今回使うツールの同意操作を任せると回答した。公式のライセンス確認画面で、必要なSDKパッケージの分だけ同意する。未使用パッケージのライセンスをまとめて自動承認しない。

- [Android Studio／SDK公式ダウンロード](https://developer.android.com/studio)、[利用規約](https://developer.android.com/studio/terms)
- Studio：`https://edgedl.me.gvt1.com/android/studio/install/2026.2.1.8/android-studio-rabbit1-mac_arm.dmg`
- Studio SHA-256：`16d4a0f8a52413b51869fc18d5bdcdf9cecd38615cd7bb7cf14d51b99d2fef54`
- Command-line Tools：`https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip`
- Command-line Tools SHA-256：`835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e`
- JDK：[Eclipse Adoptium公式API](https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse) で取得したTemurin 21.0.12.1+1。[公式リリース](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1) の `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz` を使用。
- JDK SHA-256：`3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998`
- SDKの配布メタデータ：`https://dl.google.com/android/repository/repository2-3.xml`
- Gradle Wrapperのスクリプト・jar：Gradle公式GitHubのタグ `v8.13.0`。
- Wrapper jar SHA-256：`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`
- Gradle 8.13 bin SHA-256：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`

上記チェックサムは公式公開値と照合済み。StudioはmacOSの署名検証で `valid on disk`、Gatekeeperで `accepted / Notarized Developer ID` を確認した。Gradle配布ZIPはWrapperの `distributionSha256Sum` でも検証する。SDKの追加パッケージは公式sdkmanagerで取得した。NDK、エミュレーター、仮想端末のシステムイメージは不要。

使用中のCommand-line Tools 22.0ではsdkmanagerに非推奨の案内が出るが、必要なパッケージの導入は正常終了した。今後のSDK管理をGUIで行う場合はStudioのSDK Managerを使える。CLIの後継は公式表示の `android sdk`。今回の固定構成の再現には下記の確認済みsdkmanagerコマンドを記載する。

## ビルドとLint

```bash
cd '/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android'
source ./scripts/android-env.sh
./gradlew assembleDebug lint
```

成果物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/debug/output-metadata.json`
- `app/build/reports/lint-results-debug.html`（debugのレポート）

アプリIDは `jp.hyoutan.rosebud`、版は0.1.0（versionCode 1）。デバッグ用の署名付きAPKで、ストア公開用ではない。署名鍵はAndroid標準の `~/.android/debug.keystore` を使い、リポジトリに含めない。今後更新を端末へ上書きする際は、このMacの署名鍵とアプリIDを維持する。

## Android Studioの初回操作

1. Finderの「アプリケーション」からAndroid Studioを開く。macOSのダウンロードアプリの確認が出たら、公式Google製のアプリであることを確認して開く。
2. 既存設定はまだないため、設定のインポートは不要。データ送信・Googleログイン・Geminiはビルドの必須条件ではない。データ送信は本人の希望で選ぶ。
3. セットアップでSDKを選ぶ際は `/Users/hyuta/Library/Android/sdk`。追加ダウンロードの選択画面があれば実機向けの構成とし、仮想端末やシステムイメージは不要。
4. **Open** でリポジトリを開く。GradleはプロジェクトのWrapperを使う。自動でAGP／Gradleを更新する提案が出ても、この段階では固定した版で同期する。
5. プロジェクトのDaemon JVM criteriaはJDK 21。**Android Studio > Settings > Build, Execution, Deployment > Build Tools > Gradle** でJDKを手動指定する必要がある場合は `/Users/hyuta/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home` を選ぶ。Studio同梱JBR 25をGradleに指定しない。ターミナルを開く場合は `source ./scripts/android-env.sh` を使う。

GUIの初回ウィザードやIDEの同期・Runは、コマンドラインのビルドとは別の確認項目。

## 実機接続・APK導入

[README](../README.md#スマホへ入れて起動する) に初心者向けの手順を記載。[Android公式：USBデバッグと実機実行](https://developer.android.com/studio/run/device) を根拠とし、ColorOS固有のメニュー位置・追加確認は実機で確認する。

`adb devices -l` に1台の実機が `device` と表示されたら：

```bash
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -W -n jp.hyoutan.rosebud/.MainActivity
```

複数台の場合は `-d` の代わりに `-s <adb devicesで表示された対象の番号>` を付け、対象を選ぶ。端末番号は公開文書やGitへ記録しない。

2026-10-19、ユーザーがビルド番号を7回タップして開発者向けオプションを有効にし、USBデバッグを許可した後、上記コマンドで導入・起動に成功した。端末上に追加のインストール確認は出ず、ユーザーがアプリの画面表示を確認した。実際の設定画面全体をこちらで見たわけではないため、ColorOSの全メニュー名・配置を確認済みとはしない。

## 困ったとき

| 症状 | 確認・対処 |
| --- | --- |
| Java Runtimeが見つからない | リポジトリで `source ./scripts/android-env.sh`。続いて `java -version` と `./gradlew --version`。JAVA_HOMEを既に別のJavaへ設定している場合は、ビルド用JDK 21のパスへ合わせる。 |
| SDK location not found | `echo "$ANDROID_HOME"` を確認。通常は `/Users/hyuta/Library/Android/sdk`。ローカル専用の `local.properties` に `sdk.dir=/Users/hyuta/Library/Android/sdk` を設定できる。 |
| Gradleやライブラリの取得に失敗 | インターネット／プロキシを確認して同じビルドコマンドを再実行。不要な版変更やキャッシュ全削除から始めない。 |
| SDKライセンスの確認が出る | `sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'` を実行し、表示された規約を確認して必要なものへ同意する。 |
| `adb` が見つからない | `source ./scripts/android-env.sh`。`adb version`、`ls "$ANDROID_HOME/platform-tools/adb"` を確認。 |
| 端末一覧が空 | 充電専用でないUSBケーブル、別ポート、端末のUSBモード、USBデバッグ設定を確認。macOSではWindows用のUSBドライバーは通常不要。 |
| `unauthorized` | スマホを解除し、USBデバッグの許可ダイアログを確認。必要ならケーブルをつなぎ直す。 |
| `INSTALL_FAILED_USER_RESTRICTED` | ColorOSのインストール確認・開発者設定を実機で確認。表示文言を記録し、関係のない保護設定を一括で解除しない。 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 同じアプリIDが別の署名で入っている可能性。元の署名鍵を使う。アンインストールはアプリデータが消えるため、自動で実行しない。 |

## 確認結果と未確認

2026-10-19、このMacで次を確認した。

| 確認 | 結果 |
| --- | --- |
| `java -version` | Temurin 21.0.12.1+1 LTS、arm64 |
| `./gradlew --version` | Gradle 8.13、Launcher JVM 21、Daemon JVM criteriaも21 |
| `./gradlew assembleDebug lint --console=plain` | 最終実行で **BUILD SUCCESSFUL**、48タスク、約3秒（依存取得済み） |
| Android Lint | **0 errors, 1 warning**。バックアップ設定の指摘は修正済み |
| APKメタデータ | `jp.hyoutan.rosebud`／0.1.0／versionCode 1、minSdk 31、targetSdk 36、起動Activityあり |
| APK署名 | Build-Toolsの `apksigner verify --verbose` が成功、v2署名あり |
| 要求権限 | APKに追加のuses-permissionなし |
| `adb devices -l` | CPH2199がUSB接続で `device` と表示 |
| `adb -d install -r .../app-debug.apk` | `Success`、インストール成功 |
| `adb -d shell am start -W -n jp.hyoutan.rosebud/.MainActivity` | `Status: ok`、対象Activityのコールド起動成功 |
| 実機での稼働・表示 | 対象アプリのプロセス稼働をadbで確認し、ユーザーがスマホの画面表示を確認 |
| Git除外 | `local.properties`、`.gradle/`、`.kotlin/`、`app/build/` が除外されることを確認 |

APKの絶対パス：

```text
/Users/hyuta/Desktop/hyuta/遊び/bluetooth/rosebud-android/app/build/outputs/apk/debug/app-debug.apk
```

最終APKのサイズは約2.4 MB、SHA-256は `c8f5c9450933011f2fd5074fa481bce3899b4b0f1f86dc729e3110116fa1ed30`。APKとLintレポートはローカルのビルド成果物で、Gitには含めない。ソースとWrapperから再生成できる。

残るLintの1件は `AndroidGradlePluginVersion`。Gradle 8.13に対し8.14.6への更新案内が出る。公式互換表の標準構成で固定して確認するため、今回は8.13を維持した。警告の抑制やbaselineによる非表示化はしていない。

初回ビルドではSDK XMLの版4に対してAGP内の解析が版3までという警告も出たが、処理は継続して成功した。最終の再実行では出ていない。上記Lint 1件とは別のビルド環境の案内として記録する。

**未確認**：Studio GUIの初回ウィザード・同期・Run、実機での長時間稼働や各種画面条件。内部音声の取得、Chrome／Brave、有線聴取、画面オフ中の継続は、機能未実装のため未検証。起動成功を音声取得の成立とは扱わない。

次はメインセッションで固める音声取得仕様を引き継ぎ、今回確認できたUSB接続・APK導入の手順で、機能を追加したアプリを実機検証する。
