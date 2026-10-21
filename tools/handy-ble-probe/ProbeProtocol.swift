import Foundation

enum ProbeFailure: Error, CustomStringConvertible {
    case invalid(String)
    var description: String { switch self { case .invalid(let code): return code } }
}
func require(_ condition: Bool, _ code: String) throws {
    if !condition { throw ProbeFailure.invalid(code) }
}
struct ProtoField {
    let tag: Int
    let wire: Int
    let number: UInt64?
    let bytes: Data?
}
struct FirmwareReply {
    let id: UInt32
    let errorCode: Int32?
    let resultTag: Int?
    let transport: Int32
    let values: [String: Double]
}
enum TrialConfig {
    static let widthMm = 100.0
    static let durationMs: UInt32 = 10000
    static let durationSeconds = TimeInterval(durationMs) / 1000
    static let legMonitorSeconds = durationSeconds + 1
    static let buttonTitle = "100mm幅を1往復（片道\(durationMs / 1000)秒指定）"
}
enum HandyProto {
    static func varint(_ value: UInt64) -> Data {
        var n = value; var output = Data()
        repeat {
            var byte = UInt8(n & 127); n >>= 7
            if n != 0 { byte |= 128 }
            output.append(byte)
        } while n != 0
        return output
    }
    static func uint(_ tag: Int, _ value: UInt64) -> Data {
        var data = varint(UInt64(tag * 8)); data.append(varint(value)); return data
    }
    static func bytes(_ tag: Int, _ value: Data) -> Data {
        var data = varint(UInt64(tag * 8 + 2)); data.append(varint(UInt64(value.count))); data.append(value); return data
    }
    static func float(_ tag: Int, _ value: Float) -> Data {
        var data = varint(UInt64(tag * 8 + 5)); let n = value.bitPattern
        for i in 0..<4 { data.append(UInt8((n >> (i * 8)) & 255)) }
        return data
    }
    static func request(id: UInt32, method: Int, absoluteTarget: Float? = nil) throws -> Data {
        try require(id >= 0x40000000, "rpc_id_out_of_range")
        try require([715, 840, 842, 743, 2003].contains(method), "rpc_not_allowed")
        var params = Data()
        if method == 743 {
            try require(absoluteTarget != nil && absoluteTarget!.isFinite, "target_not_finite")
            params.append(float(1, absoluteTarget!))
            params.append(uint(2, UInt64(TrialConfig.durationMs)))
            params.append(uint(3, 1))
        } else { try require(absoluteTarget == nil, "unexpected_target") }
        var inner = uint(2, UInt64(id)); inner.append(bytes(method, params))
        var outer = uint(1, 1); outer.append(bytes(2, inner)); return outer
    }
    static func fields(_ data: Data) throws -> [ProtoField] {
        try require(data.count <= 4096, "protobuf_too_large")
        let data = [UInt8](data); var offset = 0
        func readVarint() throws -> UInt64 {
            var result: UInt64 = 0
            for i in 0..<10 {
                try require(offset < data.count, "truncated_varint")
                let byte = data[offset]; offset += 1
                try require(i != 9 || byte <= 1, "varint_overflow")
                result |= UInt64(byte & 127) << (i * 7)
                if byte & 128 == 0 { return result }
            }
            throw ProbeFailure.invalid("varint_overflow")
        }
        var result: [ProtoField] = []
        while offset < data.count {
            try require(result.count < 128, "too_many_fields")
            let key = try readVarint(); let tag64 = key >> 3; let wire = Int(key & 7)
            try require(tag64 > 0 && tag64 <= 0x1fffffff, "invalid_tag")
            let tag = Int(tag64)
            if wire == 0 { result.append(ProtoField(tag: tag, wire: wire, number: try readVarint(), bytes: nil)) }
            else {
                try require([1, 2, 5].contains(wire), "unsupported_wire")
                let size = try wire == 2 ? readVarint() : UInt64(wire == 1 ? 8 : 4)
                try require(size <= UInt64(data.count - offset), "truncated_field")
                let end = offset + Int(size)
                result.append(ProtoField(tag: tag, wire: wire, number: nil, bytes: Data(data[offset..<end])))
                offset = end
            }
        }
        return result
    }
    static func one(_ fields: [ProtoField], _ tag: Int, _ wire: Int, required: Bool = false) throws -> ProtoField? {
        let matches = fields.filter { $0.tag == tag }
        try require(matches.count <= 1, "duplicate_field")
        try require(!required || matches.count == 1, "missing_field")
        if let match = matches.first { try require(match.wire == wire, "wrong_wire") }
        return matches.first
    }
    static func number(_ fields: [ProtoField], _ tag: Int, required: Bool = false) throws -> UInt32 {
        let n = try one(fields, tag, 0, required: required)?.number ?? 0
        try require(n <= UInt64(UInt32.max), "uint32_overflow"); return UInt32(n)
    }
    static func floatValue(_ fields: [ProtoField], _ tag: Int) throws -> Double {
        guard let b = try one(fields, tag, 5)?.bytes else { return 0 }
        let n = b.enumerated().reduce(UInt32(0)) { $0 | UInt32($1.element) << ($1.offset * 8) }
        let value = Float(bitPattern: n); try require(value.isFinite, "nonfinite_measurement")
        return Double(value)
    }
    static func diagnosticLayout(_ bytes: Data, expectedID: UInt32? = nil) throws -> String {
        let envelope = try fields(bytes)
        let type = try number(envelope, 1, required: true)
        guard type == 3 else { return "non_response" }
        let innerBytes = try one(envelope, 4, 2, required: true)!.bytes!
        let inner = try fields(innerBytes)
        func tags(_ fields: [ProtoField]) -> String { fields.map { "\($0.tag):w\($0.wire)" }.joined(separator: ",") }
        // Layout only: never include varint values, bytes, free text, addresses or keys.
        let id = try number(inner, 1)
        let metadata = try one(inner, 3, 0)?.number
        let suffix = " transport=\(Int32(truncatingIfNeeded: metadata ?? 0))"
        return "type=3 envelope=[\(tags(envelope))] response=[\(tags(inner))]\(suffix) pendingIdMatches=\(expectedID == id)"
    }
    static func reply(_ bytes: Data) throws -> FirmwareReply? {
        let envelope = try fields(bytes)
        let type = try number(envelope, 1, required: true)
        if type != 3 { return nil }
        try require(envelope.filter { [2, 3, 4, 5].contains($0.tag) }.count == 1, "invalid_envelope")
        let inner = try fields(one(envelope, 4, 2, required: true)!.bytes!)
        let id = try number(inner, 1)
        var errorCode: Int32?
        if let error = try one(inner, 2, 2)?.bytes {
            let errorFields = try fields(error)
            errorCode = Int32(truncatingIfNeeded: try one(errorFields, 1, 0)?.number ?? 0)
        }
        // Official onboarding schema: Response.transport is int32 tag 3, separate from the result oneof.
        let transportValue = try one(inner, 3, 0)?.number ?? 0
        try require(transportValue <= 5, "invalid_transport_enum")
        let transport = Int32(transportValue)
        let results = inner.filter { ![1, 2, 3].contains($0.tag) }
        try require(results.count <= 1, "multiple_results")
        var values: [String: Double] = [:]
        if let result = results.first {
            try require(result.wire == 2, "wrong_result_wire")
            let rf = try fields(result.bytes!)
            let names = result.tag == 840 ? ["min", "max", "minAbsoluteMm", "maxAbsoluteMm"] :
                result.tag == 842 ? ["position", "positionAbsoluteMm", "motorTempC", "speedAbsoluteMmPerSec"] : []
            for (i, name) in names.enumerated() { values[name] = try floatValue(rf, i + 1) }
            if result.tag == 2003 {
                values["xLimitStart"] = try floatValue(rf, 1); values["xLimitStop"] = try floatValue(rf, 2)
                for (tag, name) in [(3, "xEndBuffer"), (4, "xEndZoneSize"), (5, "xEndZoneSpeed"), (6, "xMinSpeed"), (7, "xMaxSpeed"), (8, "xMinSpeedTheoretical"), (9, "xMaxSpeedTheoretical")] {
                    values[name] = Double(try number(rf, tag))
                }
            }
            if result.tag == 842 {
                values["dir"] = Double(try number(rf, 5)); values["motorPosition"] = Double(try number(rf, 6))
                values["motorTempAdcValue"] = Double(try number(rf, 7))
            }
        }
        return FirmwareReply(id: id, errorCode: errorCode, resultTag: results.first?.tag, transport: transport, values: values)
    }
    static func validate(_ reply: FirmwareReply, expectedID: UInt32, method: Int) throws -> Bool {
        if reply.id != expectedID { return false }
        if let error = reply.errorCode { throw ProbeFailure.invalid("firmware_error_\(error)") }
        let expected = [840, 842, 2003].contains(method) ? method : nil
        try require(reply.resultTag == expected, "unexpected_result")
        return true
    }
}

struct SliderSample {
    let minimum: Double
    let maximum: Double
    let position: Double
    let speed: Double
    let timestamp: TimeInterval
    func roundTripTarget(now: TimeInterval) throws -> Double {
        try require([minimum, maximum, position, speed, timestamp, now].allSatisfy(\.isFinite), "nonfinite_state")
        try require(now >= timestamp && now - timestamp <= 2, "state_stale")
        try require(abs(speed) <= 0.1, "slider_not_stationary")
        try require(minimum < maximum && maximum - minimum >= TrialConfig.widthMm, "stroke_too_small")
        try require(position >= minimum && position <= maximum, "position_outside_stroke")
        let middle = minimum + (maximum - minimum) / 2
        let target = position <= middle ? position + TrialConfig.widthMm : position - TrialConfig.widthMm
        try require(target.isFinite && target >= minimum && target <= maximum, "target_outside_stroke")
        // Recheck the actual float serialized to the firmware, not just the Double plan.
        let encoded = Double(Float(target))
        try require(encoded.isFinite && encoded >= minimum && encoded <= maximum && abs(abs(encoded - position) - TrialConfig.widthMm) <= 0.001, "target_rounding_invalid")
        return encoded
    }
    func verifyLegEnd(target: Double, now: TimeInterval) throws {
        try require([minimum, maximum, position, speed, timestamp, now, target].allSatisfy(\.isFinite), "nonfinite_state")
        try require(now >= timestamp && now - timestamp <= 2, "state_stale")
        try require(minimum < maximum && position >= minimum && position <= maximum && target >= minimum && target <= maximum, "position_outside_stroke")
        try require(abs(position - target) <= 1, "target_not_reached")
        try require(abs(speed) <= 0.1, "slider_not_stationary")
    }
}
