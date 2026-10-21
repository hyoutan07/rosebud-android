import Foundation
func runProtocolTests() throws {
    func expectFailure(_ body: () throws -> Void) throws {
        var failed = false
        do { try body() } catch { failed = true }
        try require(failed, "expected_failure")
    }
    try require(TrialConfig.durationMs == 10000 && TrialConfig.durationSeconds == 10 && TrialConfig.legMonitorSeconds == 11, "shared_trial_time")
    try require(TrialConfig.buttonTitle.contains("10秒指定"), "shared_trial_label")
    let id: UInt32 = 0x40000001
    for method in [715, 840, 842, 2003] {
        let envelope = try HandyProto.fields(HandyProto.request(id: id, method: method))
        try require(envelope.map(\.tag) == [1, 2], "request_envelope")
        let inner = try HandyProto.fields(envelope[1].bytes!)
        try require(inner.map(\.tag) == [2, method] && inner[0].number == UInt64(id), "request_inner")
    }
    let move = try HandyProto.fields(HandyProto.request(id: id, method: 743, absoluteTarget: 42))
    let moveInner = try HandyProto.fields(move[1].bytes!)
    let params = try HandyProto.fields(moveInner[1].bytes!)
    try require(try HandyProto.floatValue(params, 1) == 42, "absolute_target")
    try require(try HandyProto.number(params, 2) == 10000 && HandyProto.number(params, 3) == 1, "move_time_and_stop_on_target")
    for bytes in [[UInt8](arrayLiteral: 0), [0x80], [0x0a, 9, 1], [0x0d, 1, 2], [0x0b], [8] + Array(repeating: 255, count: 10), Array(repeating: 0, count: 4097)] {
        try expectFailure { _ = try HandyProto.fields(Data(bytes)) }
    }
    try expectFailure { _ = try HandyProto.request(id: id, method: 701) }
    try expectFailure { _ = try HandyProto.request(id: id, method: 743, absoluteTarget: .nan) }
    func reply(_ responseID: UInt32, _ tag: Int? = nil, error: Data? = nil, value: Data = Data()) -> Data {
        var inner = HandyProto.uint(1, UInt64(responseID))
        if let error { inner.append(HandyProto.bytes(2, error)) }
        if let tag { inner.append(HandyProto.bytes(tag, value)) }
        var data = HandyProto.uint(1, 3); data.append(HandyProto.bytes(4, inner)); return data
    }
    let unknownResponse = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.uint(1, UInt64(id)) + HandyProto.uint(8, 99))
    try require(try HandyProto.diagnosticLayout(unknownResponse) == "type=3 envelope=[1:w0,4:w2] response=[1:w0,8:w0] transport=0 pendingIdMatches=false", "safe_layout_only")
    try expectFailure { _ = try HandyProto.reply(unknownResponse) }
    let transportReply = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.uint(1, UInt64(id)) + HandyProto.uint(3, 5))
    try require(try HandyProto.diagnosticLayout(transportReply, expectedID: id).contains("transport=5 pendingIdMatches=true"), "official_transport_metadata")
    let withTransport = try HandyProto.reply(transportReply)!
    try require(withTransport.transport == 5 && withTransport.resultTag == nil, "transport_separate_from_result")
    try require(try HandyProto.validate(withTransport, expectedID: id, method: 715), "stop_with_transport")
    try expectFailure { _ = try HandyProto.validate(withTransport, expectedID: id, method: 840) }
    let strokeWithTransport = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.uint(1, UInt64(id)) + HandyProto.uint(3, 5) + HandyProto.bytes(840, Data()))
    let strokeReply = try HandyProto.reply(strokeWithTransport)!
    try require(try HandyProto.validate(strokeReply, expectedID: id, method: 840), "measurement_requires_result")
    let unknownTransport = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.uint(3, 6))
    try expectFailure { _ = try HandyProto.reply(unknownTransport) }
    let duplicateTransport = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.uint(3, 1) + HandyProto.uint(3, 1))
    try expectFailure { _ = try HandyProto.reply(duplicateTransport) }
    let wrongTransportWire = HandyProto.uint(1, 3) + HandyProto.bytes(4, HandyProto.bytes(3, Data()))
    try expectFailure { _ = try HandyProto.reply(wrongTransportWire) }
    var settingsData = HandyProto.float(1, 0)
    settingsData.append(HandyProto.float(2, 114))
    for (tag, value) in [(3, 5), (4, 10), (5, 200), (6, 32), (7, 400), (8, 45), (9, 1600)] {
        settingsData.append(HandyProto.uint(tag, UInt64(value)))
    }
    let settings = try HandyProto.reply(reply(id, 2003, value: settingsData))!
    try require(try HandyProto.validate(settings, expectedID: id, method: 2003), "settings_result_required")
    try require(settings.values["xLimitStop"] == 114 && settings.values["xMinSpeedTheoretical"] == 45 && settings.values["xEndZoneSize"] == 10 && settings.values.count == 9, "settings_whitelist_only")
    try expectFailure { _ = try HandyProto.reply(reply(id, 2003, value: HandyProto.float(8, 45))) }
    try expectFailure { _ = try HandyProto.request(id: id, method: 2004) }
    let stop = try HandyProto.reply(reply(id))!
    try require(try HandyProto.validate(stop, expectedID: id, method: 715), "stop_matching_id")
    try expectFailure { _ = try HandyProto.validate(stop, expectedID: id, method: 2003) }
    try require(try !HandyProto.validate(stop, expectedID: id + 1, method: 715), "wrong_id_ignored")
    try expectFailure { _ = try HandyProto.validate(stop, expectedID: id, method: 842) }
    for errorData in [Data(), HandyProto.uint(1, 42)] {
        let errorReply = try HandyProto.reply(reply(id, error: errorData))!
        try expectFailure { _ = try HandyProto.validate(errorReply, expectedID: id, method: 715) }
    }
    let state = try HandyProto.reply(reply(id, 842))!
    try require(state.values["positionAbsoluteMm"] == 0, "protobuf_default_zero")
    try expectFailure { _ = try HandyProto.reply(reply(id, 842, value: HandyProto.float(2, .infinity))) }
    let valid = SliderSample(minimum: 0, maximum: 200, position: 40, speed: 0, timestamp: 100)
    try require(try valid.roundTripTarget(now: 101) == 140, "move_to_center")
    try require(try SliderSample(minimum: 0, maximum: 200, position: 160, speed: 0, timestamp: 100).roundTripTarget(now: 101) == 60, "reverse_to_center")
    for invalid in [SliderSample(minimum: 0, maximum: 100, position: -1, speed: 0, timestamp: 100),
                    SliderSample(minimum: 0, maximum: 4, position: 2, speed: 0, timestamp: 100),
                    SliderSample(minimum: 0, maximum: 100, position: 50, speed: 1, timestamp: 100),
                    SliderSample(minimum: 0, maximum: 100, position: .nan, speed: 0, timestamp: 100)] {
        try expectFailure { _ = try invalid.roundTripTarget(now: 101) }
    }
    try expectFailure { _ = try valid.roundTripTarget(now: 103) }
    try expectFailure { _ = try valid.roundTripTarget(now: 99) }
    try require(try SliderSample(minimum: 0, maximum: 100, position: 0, speed: 0, timestamp: 100).roundTripTarget(now: 101) == 100, "edge_moves_inward")
    try expectFailure { _ = try SliderSample(minimum: 0, maximum: 15, position: 7.5, speed: 0, timestamp: 100).roundTripTarget(now: 101) }
    let reached = SliderSample(minimum: 0, maximum: 100, position: 50.5, speed: 0.05, timestamp: 100)
    try reached.verifyLegEnd(target: 50, now: 101)
    try expectFailure { try reached.verifyLegEnd(target: 40, now: 101) }
    try expectFailure { try reached.verifyLegEnd(target: 50, now: 103) }
    try expectFailure { try SliderSample(minimum: 0, maximum: 100, position: 50, speed: 0.2, timestamp: 100).verifyLegEnd(target: 50, now: 101) }
    try expectFailure { try reached.verifyLegEnd(target: .nan, now: 101) }
}
