import AppKit
import CoreBluetooth

final class ProbeApp: NSObject, NSApplicationDelegate, NSWindowDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let serviceID = CBUUID(string: "77834d26-40f7-11ee-be56-0242ac120002")
    private let txID = CBUUID(string: "77835032-40f7-11ee-be56-0242ac120002")
    private let rxID = CBUUID(string: "77835410-40f7-11ee-be56-0242ac120002")
    private let expectedName: String?
    private let movementEnabled = Bundle.main.object(forInfoDictionaryKey: "HandyProbeMoveEnabled") as? Bool ?? false
    init(expectedName: String? = nil) { self.expectedName = expectedName; super.init() }
    private var sessionActive = false
    private var window: NSWindow!
    private var logView = NSTextView()
    private var status = NSTextField(labelWithString: "待機中。接続ボタンを押すまでスキャンしません。")
    private var connectButton = NSButton(title: "接続・状態を確認", target: nil, action: nil)
    private var moveButton = NSButton(title: TrialConfig.buttonTitle, target: nil, action: nil)
    private var stopButton = NSButton(title: "停止要求", target: nil, action: nil)
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var tx: CBCharacteristic?
    private var rx: CBCharacteristic?
    private var candidates: [UUID: CBPeripheral] = [:]
    private var scanTimer: Timer?
    private var readyPollTimer: Timer?
    private var stageTimer: Timer?
    private var rpcTimer: Timer?
    private var uiTimer: Timer?
    private var pending: (id: UInt32, method: Int, done: (Result<FirmwareReply, Error>) -> Void)?
    private var nextID = UInt32.random(in: 0x40000000...0x70000000)
    private var awaitingPower = false
    private var preflightAccepted = false
    private var minimum: Double?
    private var maximum: Double?
    private var sample: SliderSample?
    private var stopInFlight = false
    private var motionPreparing = false
    private var motionActive = false
    private var motionDeadline: Timer?
    private var legWatchdog: Timer?
    private var pollTimer: Timer?
    private var trialTarget: Double?
    private var trialStart: Double?
    private var returningLeg = false
    private var legDeadlineReached = false
    private var legCheckRequested = false
    private var motionEpoch: UInt64 = 0
    private var terminationReady = false
    private var closing = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 780, height: 580),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Handy BLE Probe — 限定診断"
        window.delegate = self
        let root = NSStackView(); root.orientation = .vertical; root.spacing = 14; root.edgeInsets = NSEdgeInsets(top: 18, left: 18, bottom: 18, right: 18)
        let warning = NSTextField(wrappingLabelWithString: "Firmware応答の確認用です。物理停止を確認した表示ではありません。\n身体に使用せず、可動部に触れず、本体の停止ボタンをすぐ押せる状態でお使いください。")
        warning.textColor = .systemRed
        status.lineBreakMode = .byWordWrapping; status.maximumNumberOfLines = 3
        connectButton.target = self; connectButton.action = #selector(connectPressed)
        stopButton.target = self; stopButton.action = #selector(stopPressed)
        if !movementEnabled { moveButton.title = TrialConfig.buttonTitle + "（準備中）" }
        moveButton.target = self; moveButton.action = #selector(movePressed)
        moveButton.isEnabled = false; stopButton.isEnabled = false
        let buttons = NSStackView(views: [connectButton, moveButton, stopButton]); buttons.spacing = 12
        let scroll = NSScrollView(); scroll.hasVerticalScroller = true; scroll.documentView = logView
        logView.isEditable = false; logView.isSelectable = true; logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.autoresizingMask = [.width]; logView.isVerticallyResizable = true
        logView.textContainer?.widthTracksTextView = true
        root.addArrangedSubview(warning); root.addArrangedSubview(buttons); root.addArrangedSubview(status); root.addArrangedSubview(scroll)
        for view in [warning, buttons, status, scroll] { view.widthAnchor.constraint(equalTo: root.widthAnchor, constant: -36).isActive = true }
        scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 280).isActive = true
        window.contentView = root; window.center(); window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        append("起動。スキャン・送信なし。実機未確認。")
    }
    private func append(_ message: String) {
        let line = "[\(DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium))] \(message)\n"
        logView.textStorage?.append(NSAttributedString(string: line))
        if (logView.string as NSString).length > 20000 { logView.string = String(logView.string.suffix(12000)) }
        logView.scrollToEndOfDocument(nil)
    }
    private func invalidatePreflight() {
        preflightAccepted = false; minimum = nil; maximum = nil; sample = nil; moveButton.isEnabled = false
    }
    private func stageDeadline(_ text: String, seconds: TimeInterval = 3) {
        stageTimer?.invalidate()
        stageTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in self?.fail(text) }
    }
    @objc private func connectPressed() {
        guard !closing else { return }
        invalidatePreflight(); sessionActive = true; connectButton.isEnabled = false; status.textColor = .labelColor
        if let peripheral, peripheral.state == .connected, tx != nil, rx?.isNotifying == true {
            runPreflight(); return
        }
        candidates.removeAll(); awaitingPower = true
        status.stringValue = "Bluetoothの準備を確認しています。"
        stageDeadline("bluetooth_ready_timeout", seconds: 10)
        if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
        else if central?.state == .poweredOn { startScan() }
        else { fail("bluetooth_not_powered_on") }
        if awaitingPower {
            readyPollTimer?.invalidate()
            readyPollTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] timer in
                guard let self, self.awaitingPower, !self.closing else { timer.invalidate(); return }
                if self.central?.state == .poweredOn { self.startScan() }
            }
            if central?.state == .poweredOn { startScan() }
        }
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard awaitingPower else {
            if central.state != .poweredOn && peripheral != nil { fail("bluetooth_unavailable") }; return
        }
        if central.state == .poweredOn { startScan() }
        else if central.state != .unknown && central.state != .resetting { fail("bluetooth_not_powered_on") }
    }
    private func startScan() {
        guard awaitingPower, !closing else { return }
        awaitingPower = false; readyPollTimer?.invalidate(); stageTimer?.invalidate(); candidates.removeAll()
        append("明示スキャン開始：OHD_hw3_対象、20秒で終了。")
        status.stringValue = "Handy 2 Standardを20秒だけ検索しています。"
        central?.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: false) { [weak self] _ in self?.finishScan() }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        guard central.isScanning else { return }
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""
        guard name.hasPrefix("OHD_hw3_"), expectedName == nil || name == expectedName else { return }
        if candidates.count < 2 || candidates[peripheral.identifier] != nil { candidates[peripheral.identifier] = peripheral }
    }
    private func finishScan() {
        central?.stopScan(); scanTimer?.invalidate()
        guard !closing, candidates.count == 1, let chosen = candidates.values.first else {
            fail(candidates.count > 1 ? "multiple_matching_devices_refused" : "no_matching_device"); return
        }
        peripheral = chosen; chosen.delegate = self
        status.stringValue = "対象1台へ接続しています。"; append("広告名prefix一致の1台へ接続（アドレスは記録しません）。")
        stageDeadline("connect_timeout"); central?.connect(chosen)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === self.peripheral, sessionActive, !closing else { central.cancelPeripheralConnection(peripheral); return }
        stageDeadline("service_discovery_timeout"); peripheral.discoverServices([serviceID])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { fail("connect_failed") }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        stageTimer?.invalidate(); rpcTimer?.invalidate(); pending = nil; tx = nil; rx = nil
        cancelMotionTimers(); invalidatePreflight(); stopInFlight = false; self.peripheral = nil
        status.textColor = .systemRed; status.stringValue = "BLE切断。物理停止は未確認です。本体の停止ボタンを確認してください。"
        append("BLE切断：すべての状態・応答待ちを無効化。物理停止未確認。")
        connectButton.isEnabled = !closing; stopButton.isEnabled = false
        if closing { finishClose() }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard sessionActive, !closing else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else { fail("service_not_found"); return }
        stageDeadline("characteristic_discovery_timeout"); peripheral.discoverCharacteristics([txID, rxID], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard sessionActive, !closing else { return }
        guard error == nil, let tx = service.characteristics?.first(where: { $0.uuid == txID }),
              let rx = service.characteristics?.first(where: { $0.uuid == rxID }), tx.properties.contains(.write),
              rx.properties.contains(.notify) || rx.properties.contains(.indicate) else { fail("characteristics_not_supported"); return }
        self.tx = tx; self.rx = rx; stageDeadline("notification_subscribe_timeout"); peripheral.setNotifyValue(true, for: rx)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard sessionActive, !closing else { return }
        guard characteristic.uuid == rxID, error == nil, characteristic.isNotifying else { fail("notification_subscription_failed"); return }
        stageTimer?.invalidate(); stopButton.isEnabled = true; runPreflight()
    }
    private func send(_ method: Int, target: Float? = nil, done: @escaping (Result<FirmwareReply, Error>) -> Void) {
        guard pending == nil, let peripheral, peripheral.state == .connected, let tx, rx?.isNotifying == true else {
            done(.failure(ProbeFailure.invalid("rpc_transport_unavailable_or_busy"))); return
        }
        nextID += 1; let id = nextID
        do {
            let data = try HandyProto.request(id: id, method: method, absoluteTarget: target)
            pending = (id, method, done)
            rpcTimer?.invalidate()
            rpcTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: false) { [weak self] _ in
                self?.complete(.failure(ProbeFailure.invalid("firmware_response_timeout")))
            }
            peripheral.writeValue(data, for: tx, type: .withResponse)
            append("RPC \(method) 送信。Firmware応答待ち（ID \(id)）。")
        } catch { done(.failure(error)) }
    }
    private func complete(_ result: Result<FirmwareReply, Error>) {
        guard let callback = pending?.done else { return }
        pending = nil; rpcTimer?.invalidate(); callback(result)
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if error != nil { complete(.failure(ProbeFailure.invalid("ble_write_failed"))) }
        // CoreBluetooth write success is not Firmware acceptance or physical stopping.
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == rxID else { return }
        guard error == nil, let bytes = characteristic.value else { complete(.failure(ProbeFailure.invalid("notification_failed"))); return }
        do {
            guard let pending else { return }
            guard let reply = try HandyProto.reply(bytes) else { return }
            if try HandyProto.validate(reply, expectedID: pending.id, method: pending.method) { complete(.success(reply)) }
        } catch {
            if let layout = try? HandyProto.diagnosticLayout(bytes, expectedID: pending?.id) { append("返信field-layout: \(layout)") }
            else { append("返信field-layout: 解析不可（生データは記録しません）。") }
            complete(.failure(error))
        }
    }
    private func runPreflight() {
        invalidatePreflight(); connectButton.isEnabled = false
        status.stringValue = "停止要求→範囲→速度設定→状態を確認しています（移動なし）。"
        send(715) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error): self.fail("preflight_stop: \(error)")
            case .success:
                self.append("715のFirmware受理応答。物理停止の確認ではありません。")
                self.send(840) { [weak self] result in
                    guard let self else { return }
                    switch result {
                    case .failure(let error): self.fail("preflight_stroke: \(error)")
                    case .success(let reply):
                        self.minimum = reply.values["minAbsoluteMm"]; self.maximum = reply.values["maxAbsoluteMm"]
                        self.showValues(reply)
                        self.send(2003) { [weak self] settingsResult in
                            guard let self else { return }
                            switch settingsResult {
                            case .failure(let error): self.fail("preflight_slider_settings: \(error)")
                            case .success(let settingsReply):
                                self.showValues(settingsReply)
                        self.send(842) { [weak self] result in
                            guard let self else { return }
                            switch result {
                            case .failure(let error): self.fail("preflight_state: \(error)")
                            case .success(let reply):
                                self.showValues(reply); self.updateSample(reply); self.preflightAccepted = true
                                self.status.stringValue = "4つのFirmware応答を確認しました。実移動・物理停止は未確認です。"
                                self.append("preflight完了。移動は本人のボタン操作でのみ開始します。")
                                self.updateMoveButton()
                                self.connectButton.isEnabled = !self.closing
                            }
                        }
                            }
                        }
                    }
                }
            }
        }
    }
    private func showValues(_ reply: FirmwareReply) {
        append("RPC \(reply.resultTag ?? 0), transport=\(reply.transport): " + reply.values.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ", "))
    }
    private func updateSample(_ reply: FirmwareReply) {
        guard let minimum, let maximum, let position = reply.values["positionAbsoluteMm"], let speed = reply.values["speedAbsoluteMmPerSec"] else { sample = nil; return }
        sample = SliderSample(minimum: minimum, maximum: maximum, position: position, speed: speed, timestamp: ProcessInfo.processInfo.systemUptime)
    }
    private func updateMoveButton() {
        guard movementEnabled, preflightAccepted, !closing, !motionPreparing, !motionActive, !stopInFlight,
              let minimum, let maximum, minimum.isFinite, maximum.isFinite,
              minimum < maximum, maximum - minimum >= TrialConfig.widthMm else { moveButton.isEnabled = false; return }
        // A click always refreshes 842 before planning; elapsed UI reading time does not force a reconnect.
        moveButton.isEnabled = true
    }
    private func cancelMotionTimers() {
        motionDeadline?.invalidate(); motionDeadline = nil
        legWatchdog?.invalidate(); legWatchdog = nil
        pollTimer?.invalidate(); pollTimer = nil
        motionPreparing = false; motionActive = false; trialTarget = nil; trialStart = nil
        returningLeg = false; legDeadlineReached = false; legCheckRequested = false; motionEpoch &+= 1
    }
    @objc private func movePressed() {
        guard movementEnabled, preflightAccepted, !motionPreparing, !motionActive, !stopInFlight, !closing, pending == nil else { return }
        motionPreparing = true; moveButton.isEnabled = false; connectButton.isEnabled = false
        let epoch = motionEpoch
        status.textColor = .labelColor; status.stringValue = "位置と速度を取り直して、100mm幅の1往復を判断します。"
        send(842) { [weak self] result in
            guard let self, self.motionEpoch == epoch, self.motionPreparing, !self.closing else { return }
            do {
                let reply = try result.get(); self.showValues(reply); self.updateSample(reply)
                guard let sample = self.sample else { throw ProbeFailure.invalid("absolute_state_missing") }
                let target = try sample.roundTripTarget(now: ProcessInfo.processInfo.systemUptime)
                let start = Double(Float(sample.position))
                try require(start.isFinite && start >= sample.minimum && start <= sample.maximum, "return_target_invalid")
                self.motionPreparing = false; self.motionActive = true; self.trialTarget = target; self.trialStart = start
                self.preflightAccepted = false; self.returningLeg = false
                self.append("100mm幅1往復の試運転。start=\(start)mm, outbound=\(target)mm。片道\(TrialConfig.durationMs)ms指定。自動反復なし。")
                self.beginLeg(target: target, epoch: epoch)
            } catch { self.fail("trial_refused_or_failed: \(error)") }
        }
    }
    private func beginLeg(target: Double, epoch: UInt64) {
        guard motionEpoch == epoch, motionActive, !stopInFlight, !closing, pending == nil else { return }
        legDeadlineReached = false; legCheckRequested = false
        status.stringValue = returningLeg ? "返りの\(TrialConfig.durationMs / 1000)秒指定。終了後に停止要求を送ります。" : "往きの\(TrialConfig.durationMs / 1000)秒指定。位置確認後にだけ返ります。"
        append("743送信条件: target=\(target)mm, time=\(TrialConfig.durationMs)ms, stop_on_target=true。再送なし。")
        motionDeadline?.invalidate(); legWatchdog?.invalidate()
        let returningAtStart = returningLeg
        // No leg may wait indefinitely for acceptance, position reads, or the next transition.
        legWatchdog = Timer.scheduledTimer(withTimeInterval: TrialConfig.legMonitorSeconds, repeats: false) { [weak self] _ in
            guard let self, self.motionEpoch == epoch, self.motionActive, self.returningLeg == returningAtStart else { return }
            self.fail("leg_monitor_timeout")
        }
        send(743, target: Float(target)) { [weak self] result in
            guard let self, self.motionEpoch == epoch, self.motionActive, !self.closing else { return }
            switch result {
            case .failure(let error): self.fail("move_response: \(error)")
            case .success:
                self.append("743のFirmware受理応答。到達・物理停止の確認ではありません。")
                // The requested motion timer starts at Firmware acceptance, not at the Mac write call.
                self.motionDeadline = Timer.scheduledTimer(withTimeInterval: TrialConfig.durationSeconds, repeats: false) { [weak self] _ in
                    guard let self, self.motionEpoch == epoch, self.motionActive, self.returningLeg == returningAtStart else { return }
                    self.legDeadlineReached = true; self.pollTimer?.invalidate(); self.pollTimer = nil
                    self.checkLegEnd(epoch: epoch)
                }
                self.pollTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.pollState(epoch: epoch) }
            }
        }
    }
    private func checkLegEnd(epoch: UInt64) {
        guard motionEpoch == epoch, motionActive, legDeadlineReached, !legCheckRequested, pending == nil, !closing, !stopInFlight else { return }
        legCheckRequested = true
        send(842) { [weak self] result in
            guard let self, self.motionEpoch == epoch, self.motionActive, !self.closing else { return }
            do {
                let reply = try result.get(); self.showValues(reply); self.updateSample(reply)
                guard let sample = self.sample, let start = self.trialStart, let outbound = self.trialTarget else {
                    throw ProbeFailure.invalid("leg_end_state_missing")
                }
                let target = self.returningLeg ? start : outbound
                try sample.verifyLegEnd(target: target, now: ProcessInfo.processInfo.systemUptime)
                self.append("受信値: 目標との差1mm以内・速度0.1mm/s以下。物理停止の確認とは別です。")
                if self.returningLeg {
                    self.append("1往復の位置応答を確認。追加移動を作らず停止要求へ。")
                    self.requestStop()
                } else {
                    self.returningLeg = true; self.beginLeg(target: start, epoch: epoch)
                }
            } catch { self.fail("leg_end_refused_or_failed: \(error)") }
        }
    }
    private func pollState(epoch: UInt64) {
        guard motionEpoch == epoch, motionActive, !legDeadlineReached, !closing, pending == nil, !stopInFlight else { return }
        send(842) { [weak self] result in
            guard let self, self.motionEpoch == epoch, self.motionActive, !self.closing else { return }
            switch result {
            case .failure(let error): self.fail("motion_state: \(error)")
            case .success(let reply):
                self.showValues(reply); self.updateSample(reply)
                if let sample = self.sample { self.status.stringValue = "受信位置 \(sample.position)mm・速度 \(sample.speed)mm/s。物理停止未確認。" }
                if self.legDeadlineReached { self.checkLegEnd(epoch: epoch) }
            }
        }
    }
    @objc private func stopPressed() { requestStop() }
    private func requestStop() {
        guard !stopInFlight else { return }
        cancelMotionTimers()
        invalidatePreflight(); pending = nil; rpcTimer?.invalidate(); stageTimer?.invalidate()
        connectButton.isEnabled = false
        stopInFlight = true; status.stringValue = "停止要求を送信しています。物理停止は本体で確認してください。"
        send(715) { [weak self] result in
            guard let self else { return }
            self.stopInFlight = false
            switch result {
            case .success: self.append("停止要求のFirmware応答あり。物理停止未確認。")
                self.status.stringValue = "停止要求は受理されました。物理停止は本体で確認してください。"
            case .failure(let error):
                self.status.textColor = .systemRed; self.status.stringValue = "停止応答未確認。本体の停止ボタンを押してください。"
                self.append("停止応答未確認：\(error)。本体の停止ボタンを確認してください。")
            }
            self.connectButton.isEnabled = !self.closing
            if self.closing { self.finishClose() }
        }
    }
    private func fail(_ message: String) {
        sessionActive = false; cancelMotionTimers()
        awaitingPower = false; readyPollTimer?.invalidate(); scanTimer?.invalidate(); central?.stopScan(); stageTimer?.invalidate()
        pending = nil; rpcTimer?.invalidate(); invalidatePreflight()
        status.textColor = .systemRed; status.stringValue = "診断中断：\(message)。物理停止未確認です。"
        append("中断：\(message)。実移動・物理停止未確認。")
        connectButton.isEnabled = !closing
        if peripheral?.state == .connected && tx != nil && rx?.isNotifying == true && !stopInFlight { requestStop() }
        else if let peripheral { central?.cancelPeripheralConnection(peripheral) }
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if terminationReady { return .terminateNow }
        if closing { return .terminateCancel }
        _ = windowShouldClose(window); return .terminateCancel
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if closing { return false }; closing = true
        central?.stopScan(); scanTimer?.invalidate(); readyPollTimer?.invalidate(); awaitingPower = false
        if peripheral?.state == .connected && tx != nil { requestStop() }
        else { finishClose() }
        return false
    }
    private func finishClose() {
        cancelMotionTimers(); rpcTimer?.invalidate(); stageTimer?.invalidate(); uiTimer?.invalidate()
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.terminationReady = true; NSApp.terminate(nil) }
    }
}
