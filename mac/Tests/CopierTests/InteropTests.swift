import CopierCore
import Foundation
import Testing
@testable import Copier

/// Runs the Android app's own networking code (android/…/Link.kt, on the JVM) against this server:
/// two independent implementations of PROTOCOL.md talking over a real socket.
/// Opt-in because it needs the Android toolchain: `COPIER_INTEROP=1 swift test --filter Interop`.
@MainActor
@Suite(.serialized, .enabled(if: ProcessInfo.processInfo.environment["COPIER_INTEROP"] == "1"))
struct InteropTests {
    @Test func kotlinPhoneTalksToSwiftMac() async throws {
        try await withHarness { h in
            let token = Crypto.randomBytes(16)
            h.server.beginInvite(token: token)
            let blob = Data((0..<300_000).map { UInt8($0 % 13) })
            let offer = h.transfers.offerData(blob, name: "to-kotlin.bin", mime: "application/octet-stream", purpose: "file", sensitive: false)

            let gradle = try await runKotlinInteropTest([
                "-Pcopier.interop.port=\(h.port)",
                "-Pcopier.interop.mackey=\(h.macIdentity.publicKey.base64URL)",
                "-Pcopier.interop.token=\(token.base64URL)",
                "-Pcopier.interop.offer=\(try #require(offer.id))",
                "-Pcopier.interop.offersha=\(Crypto.sha256(blob).hex)",
            ])
            #expect(gradle.status == 0, "Kotlin side failed:\n\(gradle.output.suffix(4000))")

            #expect(h.store.phones.map(\.name) == ["Kotlin Phone"])
            let clip = h.messages.first { $0.header.t == "clip" }
            #expect(clip?.header.htmlBytes == 9)
            #expect(clip?.body == Data("<b>hi</b>hello from kotlin".utf8))
            guard case let .file(url)? = h.received.first else {
                Issue.record("no file arrived from the Kotlin push")
                return
            }
            #expect(url.lastPathComponent == "from-kotlin.bin")
            #expect(try Data(contentsOf: url) == Data((0..<600_000).map { UInt8($0 % 251) }))
        }
    }
}

/// Runs MacInteropTest through Gradle off the main actor, so the server keeps serving meanwhile.
func runKotlinInteropTest(_ properties: [String]) async throws -> (status: Int32, output: String) {
    let android = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("android")
    return try await Task.detached {
        let process = Process()
        process.executableURL = android.appendingPathComponent("gradlew")
        process.currentDirectoryURL = android
        process.arguments = [":app:testDebugUnitTest", "--tests", "dev.copier.MacInteropTest", "--rerun", "-q"] + properties
        var environment = ProcessInfo.processInfo.environment
        if environment["JAVA_HOME"] == nil {
            environment["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
        }
        process.environment = environment
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let output = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return (process.terminationStatus, String(decoding: output, as: UTF8.self))
    }.value
}
