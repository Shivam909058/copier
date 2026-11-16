import Foundation
import Testing
@testable import Copier

@Suite struct PhoneSetupTests {
    @Test func explainsAnOutdatedPhoneApp() {
        let output = "java.lang.SecurityException: Package dev.copier has not requested permission android.permission.WRITE_SECURE_SETTINGS"
        #expect(PhoneSetup.hint(forFailure: output).contains("too old"))
    }

    @Test func pointsAtTheVendorSwitchWhenTheGrantIsRefused() {
        let output = "java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS."
        #expect(PhoneSetup.hint(forFailure: output).contains("Disable permission monitoring"))
    }

    /// Opt-in because it downloads 16 MB from Google: `COPIER_ADB_DOWNLOAD=1 swift test --filter PhoneSetup`.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["COPIER_ADB_DOWNLOAD"] == "1"))
    func downloadsAWorkingADB() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("copier-adb-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let adb = try #require(PhoneSetup.fetchADB(into: directory))
        let version = Process()
        version.executableURL = URL(fileURLWithPath: adb)
        version.arguments = ["version"]
        version.standardOutput = Pipe()
        try version.run()
        version.waitUntilExit()
        #expect(version.terminationStatus == 0)
        #expect(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("platform-tools.zip").path))
    }

    @Test func fallsBackToWhatThePhoneSaid() {
        #expect(PhoneSetup.hint(forFailure: "Error: something else\n") == "The phone refused: Error: something else")
    }
}
