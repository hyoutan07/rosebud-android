# Handy実機診断の証跡

2026-10-21 JST、Handy 2 Standard / FW4.3.0+dcee7e58。Mac限定直接BLE、身体に使用しない条件。[解釈と確認範囲](../../handy-ble-probe.md)。

- first-roundtrip：10mm / 3000ms指定の往復。
- long-roundtrip：10mm / 10000ms指定。移動自体の時間とは別。
- slider-settings：設定の読取りのみ。
- wide-early：100mm往復の往きの早期受信。有限UIバッファで消える前に取得。
- wide-roundtrip：100mm往復完了。
- mid-stop：743で100mm先へ移動中に715。mid-stop-afterの20:41:45で絶対位置45.333mm・速度0を受信。本人も「途中で止まり、戻らなかった」と確認。

AXログはフォーカス要素の同文重複と有限バッファによる冒頭欠落あり。PNGは診断画面で、機械自体の映像ではない。正確な物理所要時間・停止応答時間の計測ではない。
