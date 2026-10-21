# Mac限定 Handy BLE診断

2026-10-21。**ビルド・機器なしのテスト済み。実機preflight、100mm幅の1往復、移動途中停止を確認。本人も途中停止・自動で戻らないことを確認。** AndroidとIntifaceの設定・実出力ゲートは変更しない。現行IntifaceのRawWrite/Subscribeは廃止されているため、その方式は不採用。MacからCoreBluetoothを使う一時診断アプリ。

## ビルドと対象限定

追加依存なし、macOSのSwiftコンパイラでビルドする。

```bash
bash tools/handy-ble-probe/build.sh
```

出力は `app/build/handy-ble-probe/Handy BLE Probe.app`。bundleは `jp.hyoutan.handybleprobe`、Bluetooth用途の説明をInfo.plistに設定し、ad-hoc署名する。build.shは生成バイナリの `--self-test` も実行する。初回利用時はmacOSのBluetooth許可が必要。

Intiface側の機器接続を終了してから開く。起動ではスキャン・接続・送信しない。「接続・状態を確認」で20秒だけ検索し、広告名 `OHD_hw3_` prefixの対象1台だけを許可する。複数なら拒否する。機器名には識別情報を含むため実際の値はソース・文書・標準ログへ記録しない。

完全一致フィルターは `--device-name` 引数、または `HANDY_PROBE_DEVICE_NAME` 環境変数を付けたbuild.sh実行でInfo.plistへ埋め込む。引数を優先し、通常ビルドはキーなし。CUAから開く場合は後者を使用する。

```bash
# 表示名は例。本人の対象を読取り確認した値に置き換える。
HANDY_PROBE_DEVICE_NAME=OHD_hw3_YOUR_DEVICE bash tools/handy-ble-probe/build.sh
```

UUIDはservice `77834d26-40f7-11ee-be56-0242ac120002`、tx `77835032-40f7-11ee-be56-0242ac120002`、rx `77835410-40f7-11ee-be56-0242ac120002`。

## Firmware応答と一次根拠

本人の機器はHandy 2 Standard / FW `4.3.0+dcee7e58` / Wi-Fi＋Bluetooth。最初の根拠はserver12.0.2の `thehandy_v3/hdy_rpc.rs` とconfig12.0.1の `thehandy-v3.yml`。実機preflightでは接続・rx通知と715返信受信まで成功したが、旧配布スキーマにないResponse tag3/wire0で厳格パーサーが拒否した。その後、下記の修正と実機試運転へ進んだ。

[Handy公式Onboardingの公開bundle](https://onboarding.handyfeeling.com/assets/index-BT7ea_PE.js)を2026-10-21に親担当が取得し、Response.transport=int32/tag3が正式metadataであると確認した。SHA-256は `589d10b66b3b414ee07012a842eb1d2d38271ffe08fb6cc5a5d2e2a208c345b6`。さらに743のxa=float1/t=uint2/stopOnTarget=bool3、840のfloat1〜4、842のfloat1〜4とuint/bool5〜7も配布スキーマとの一致を照合した。

修正版はtransportを結果oneofから分離し、wire0・重複なし・enum0〜5を検査する。値は0=WebSocket/1=BLE/2=Internal/3=External/4=AP/5=WebSocket Public Cert。省略は旧スキーマ互換のdefault0とし、transport値を経路の認証には使わない。実通信はCoreBluetoothに限定する。途中の「状態だけ読む」例外経路は役目を終え削除した。

## preflightと停止

rx通知を購読後、715 `RequestStopCurrentMode` → 840 `RequestSliderStrokeGet` → 2003 `RequestSettingsSliderGet` → 842 `RequestSliderStateGet` を順番に1件ずつ送る。RPCは同時1件・各3秒期限。同じIDでerrorフィールドがないFirmware応答だけを受理し、errorはcode0でも失敗。715は結果なし、840/2003/842は対応するwire2結果が必須。異なるIDは捨て、切断・不正protobuf・非有限数で無効化する。CoreBluetoothの書込み成功をFirmware応答に読み替えない。

画面内の有限ログに値を表示する。アドレス・接続キー・完全な広告名・Firmware自由文エラーは記録しない。相対位置は受信値のまま、絶対位置・範囲・速度はスキーマのmm/mm毎秒表記に従う。proto3の省略数値は0で、省略と実測0を区別できない。715の受理応答は**物理停止を確認した表示ではない**。パース失敗時だけfield-layout・transport・ID一致を診断表示する。

「停止要求」は返りを含む未送信動作・タイマー・古い応答待ちを世代ごと破棄し、新しいIDの715を優先する。エラー時は接続が残っていれば715をbest effortで試す。応答未確認は赤字で本体の停止ボタンを案内する。閉じる／終了時も停止要求を試し、3秒の応答上限後にBLE切断・終了する。Bluetooth断時の停止保証はない。

## 後続の限定試運転

通常ビルドでは「100mm幅を1往復」は準備中で無効。**preflight全4RPCの確認が成立しない限り開始できない**。レビュー後に `HANDY_PROBE_ENABLE_MOVE=1` を明示したビルドだけがボタンを有効にできる。

本人の追加希望により2mm案→10mm→100mm幅の1往復へ変更。これは今回の試運転条件で、機器仕様や本人の好みを表す値ではない。本人は身体に使用せず、可動部に触れず、本体停止ボタンを手元に用意できると回答済み。

押下時に842を再取得し、有限値・2秒以内・絶対位置が取得範囲内・速度0.1mm/s以下を確認。中心方向へ100mmの目標が範囲内に入る場合だけ743（absolute mm / 10000ms / stop_on_target=true）を1件送る。幅の縮小・clamp・中央への事前移動は行わない。743のFirmware受理から10秒後に842で目標との差1mm以内かつ速度0.1mm/s以下を確認できたときだけ、最初に取得した位置へ同じ時間で1回戻す。返り後も842を確認して715へ進む。動作中は同時1RPCの範囲で250msごとに状態照会する。各片道はwrite時点から11秒の別監視上限で、受理・位置照会が遅れた場合も返りを送らず715へ進む。自動反復はない。

## 機器なしの検証範囲

`--self-test` はAppKitアプリやCoreBluetoothマネージャを初期化しない。許可RPC・タグ、切れた入力・大きすぎる入力・不正値、ID照合、error存在、非有限数、公式transport分離と不正wire/重複/未知enum、100mm計画・片道終了判定の範囲・時刻・速度・方向を検証する。

UI描画・Bluetooth権限・実機応答・実動作・物理停止はself-testに含まない。実機の確認事実は下記のとおり。self-testだけで実機成功を先取りしない。

## 実機結果（2026-10-21 JST）

身体に使用せず、可動部に触れず、本人が本体の停止を操作できる条件。Macの直接BLEだけで試験し、Androidの通常出力を解除した結果ではない。

| 試験 | 受信位置・結果 | 本人の確認 |
| --- | --- | --- |
| 20:25、10mm・片道3000ms指定 | 約5.333→15.333→5.333mm。各移動後の最初の250ms照会で目標・速度0 | 動いて往復・停止。秒数は見ていなかったと訂正 |
| 20:31、10mm・片道10000ms指定 | 同じ往復。アプリは各端で指定10秒まで待つが、移動自体は短い | 「めっちゃみじかかった」「往復はしてた」。10cm程度の幅を希望 |
| 20:38、100mm・片道10000ms指定 | 約5.333→105.333→5.333mm。各移動は約3秒、残りは端で待機。終了後715受理 | 10秒未満、かなり遅いので最低速度だと思うと回答。正確な秒数は未計測 |
| 20:41、100mm目標への移動途中に715 | 5.333→40.667mm・速度30.52mm/sの照会後715受理。次のpreflightで45.333mm・速度0。戻り743なし | **途中で止まり、戻らなかった**と確認 |

100mm試験の往きの受信速度は概ね32〜37mm/s、返りも概ね30〜42mm/s。測定値には端部・加減速の変動がある。10秒指定なのに早く着くのは現設定の最低速度付近での制約と整合する。ただしFirmware内部の実装・正確なクランプ式まで確認したとは扱わない。途中停止の応答時間も精密計測していない。

### 読み取った実設定

RPC2003でxLimitStart=0、xLimitStop=123.992996、xEndBuffer=5、xEndZoneSize=10、xEndZoneSpeed=200、xMinSpeed=32、xMaxSpeed=400、xMinSpeedTheoretical=45、xMaxSpeedTheoretical=1600を受信。RPC840の試験時範囲は約5〜118.992996mm。最初のpreflightだけ上限85.935mmだったが、診断側で設定書込みはしておらず、変化の原因は未確定。

公式[HDSP説明](https://ohdoki.notion.site/Handy-Direct-Streaming-Protocol-HDSP-7be2c2a11a544da4bf70ce7a947b00f9)のTはms。公式SDKも743のtをそのまま渡す。µsや絶対時刻へ読み替える根拠はない。100mm/10秒=10mm/sという希望速度は、受信設定と実測の最低速度付近を下回る。表示は「10秒指定」であり実移動に10秒かかる保証ではない。

公式[SliderOverclocking公開UI](https://onboarding.handyfeeling.com/assets/SliderOverclocking-D07m0Zar.js)はxMinSpeedTheoreticalをmm/sと明示し、設定したモーター速度の上下限より遅く/速く動かないと説明する。SHA-256は `76ac4c23cd750d63cf00a84d6c6ce1f8b22766b0a28b2c8ce185164e160c8c11`。実機設定は読取りだけで、2004・校正・速度設定の書込みは許可していない。

### 証跡と終了状態

[証跡一覧](validation/handy-live/README.md)。AXログは有限バッファのため一部冒頭が欠け、フォーカス要素に同じログが重複する。100mmの往きの早期受信はwide-early-ax.txtに分けて保存。画面内の「物理停止未確認」はFirmware応答だけでは断定しない固定表示で、上表の本人観察とは区別する。

20:42に終了時715の応答・BLE切断を確認し、診断を終了。Intiface Central3.2.2+46を再開しEngine running / 0.0.0.0:12345待受を確認。一時変更したListenAll/Advanced表示は元のtrue/false、旧Rawキーは変更なし。再スキャン・自動運動は開始していない。

Androidは0.13.0/code17のまま。通常Handy出力ゲートは無効のままで、入力テスト画面から実機へ送る統合・接続所有の整理が次の仕事。今回の専用停止成立を、標準IntifaceのStopDeviceCmdや通信断時の停止保証へ広げない。長時間・負荷あり・切断試験は未実施。
