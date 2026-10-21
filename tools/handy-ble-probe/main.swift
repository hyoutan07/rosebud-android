import AppKit
import Foundation

if CommandLine.arguments.contains("--self-test") {
    do { try runProtocolTests(); print("Handy BLE Probe: protocol tests passed (no Bluetooth initialization)") }
    catch { fputs("Self-test failed: \(error)\n", stderr); exit(1) }
} else {
    let app = NSApplication.shared
    var expectedName = Bundle.main.object(forInfoDictionaryKey: "HandyProbeExpectedAdvertisementName") as? String
    if let index = CommandLine.arguments.firstIndex(of: "--device-name") {
        guard index + 1 < CommandLine.arguments.count else { fputs("Missing device-name argument\n", stderr); exit(1) }
        let name = CommandLine.arguments[index + 1]
        guard name.hasPrefix("OHD_hw3_"), name.utf8.count <= 80 else { fputs("Invalid device-name argument\n", stderr); exit(1) }
        expectedName = name
    }
    if let expectedName, !expectedName.hasPrefix("OHD_hw3_") || expectedName.utf8.count > 80 {
        fputs("Invalid bundled device-name filter\n", stderr); exit(1)
    }
    let delegate = ProbeApp(expectedName: expectedName)
    app.setActivationPolicy(.regular)
    app.delegate = delegate
    app.run()
}
