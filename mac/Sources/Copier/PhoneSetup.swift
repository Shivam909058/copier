import Foundation

/// The optional one-time USB step. Updates the phone app and grants the two permissions Android
/// only hands out from a computer, so the phone can turn automatic copy back on by itself after a
/// payment app closes (android: SelfManage.kt). Nothing here is needed for normal use.
enum PhoneSetup {
    struct Outcome: Sendable {
        let succeeded: Bool
        let summary: String
    }

    static let package = "dev.copier"

    /// Where Copier keeps its own copy of Android's platform tools when the Mac has none.
    private static let ownTools = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Copier", isDirectory: true)

    private static let adbLocations = [
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
        NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb",
        ownTools.appendingPathComponent("platform-tools/adb").path,
    ]

    /// Downloads Google's official platform tools (about 16 MB) and returns the path to adb, so the
    /// USB setup works on a Mac without Homebrew or Android Studio.
    static func fetchADB(into directory: URL) -> String? {
        let zip = directory.appendingPathComponent("platform-tools.zip")
        defer { try? FileManager.default.removeItem(at: zip) }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            return nil
        }
        let url = "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
        guard shell("/usr/bin/curl", ["-fsSL", "--retry", "2", "-o", zip.path, url]).status == 0,
              shell("/usr/bin/ditto", ["-x", "-k", zip.path, directory.path]).status == 0 else { return nil }
        let adb = directory.appendingPathComponent("platform-tools/adb").path
        return FileManager.default.isExecutableFile(atPath: adb) ? adb : nil
    }

    static func run(apk: URL?) async -> Outcome {
        await Task.detached { runBlocking(apk: apk) }.value
    }

    /// What to do about a failed grant, from what the phone printed.
    static func hint(forFailure output: String) -> String {
        if output.contains("has not requested permission") {
            return "The Copier app on the phone is too old for this. Update it (copier qr → 1 · Install Copier → Update), then run this again."
        }
        if output.contains("SecurityException") {
            return """
            The phone refused. In Developer options, turn on “Disable permission monitoring” (Realme, OPPO, OnePlus) \
            or “USB debugging (Security settings)” (Xiaomi), then run this again.
            """
        }
        return "The phone refused: \(lastLine(output))"
    }

    private static func lastLine(_ output: String) -> String {
        output.split(separator: "\n").last.map(String.init) ?? "no details"
    }

    private static func runBlocking(apk: URL?) -> Outcome {
        guard let adb = adbLocations.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) ?? fetchADB(into: ownTools) else {
            return Outcome(succeeded: false, summary: """
            Couldn't download Android's adb tool from Google. Check the internet connection and run this again, \
            or install it yourself: brew install --cask android-platform-tools
            """)
        }
        let listed = shell(adb, ["devices"]).output.split(separator: "\n").dropFirst()
        let devices = listed.filter { $0.hasSuffix("\tdevice") }
        guard devices.count == 1 else {
            let summary = if devices.count > 1 {
                "More than one Android device is connected. Unplug the others and try again."
            } else if listed.contains(where: { $0.hasSuffix("\tunauthorized") }) {
                "The phone is connected but hasn't allowed this Mac yet. Unlock it, tap Allow on “Allow USB debugging?”, then run this again."
            } else {
                """
                No phone found. On the phone: Settings → About device → tap “Build number” 7 times, then \
                Developer options → USB debugging → on. Connect the cable, unlock the phone and tap Allow. \
                Then run this again.
                """
            }
            return Outcome(succeeded: false, summary: summary)
        }

        var lines: [String] = []
        if let apk, FileManager.default.fileExists(atPath: apk.path) {
            // Over adb the update skips the browser install that Play Protect blocks.
            let install = shell(adb, ["install", "-r", apk.path])
            lines.append(install.status == 0 ? "✓ Phone app updated" : "– Phone app not updated: \(lastLine(install.output))")
        }
        guard shell(adb, ["shell", "pm", "path", package]).status == 0 else {
            return Outcome(succeeded: false, summary: (lines + ["Copier isn't installed on the phone yet. Install it with: copier qr"]).joined(separator: "\n"))
        }

        // (label, adb arguments, required). Optional steps don't exist on every Android version.
        let steps: [(String, [String], Bool)] = [
            ("Turn automatic copy back on by itself", ["shell", "pm", "grant", package, "android.permission.WRITE_SECURE_SETTINGS"], true),
            ("Notice when a payment app closes", ["shell", "appops", "set", package, "GET_USAGE_STATS", "allow"], true),
            ("Read the clipboard from the background", ["shell", "appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"], false),
            ("Notifications", ["shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS"], false),
            ("Stay connected (no battery optimization)", ["shell", "dumpsys", "deviceidle", "whitelist", "+" + package], false),
        ]
        var failure: String?
        for (label, arguments, required) in steps {
            let result = shell(adb, arguments)
            let ok = result.status == 0 && !result.output.contains("Exception")
            lines.append("\(ok ? "✓" : required ? "✗" : "–") \(label)")
            if !ok && required && failure == nil { failure = hint(forFailure: result.output) }
        }
        // No force-stop here: Android switches a force-stopped app's accessibility service off.
        _ = shell(adb, ["shell", "am", "start", "-n", package + "/.MainActivity"])
        lines.append("")
        lines.append(failure ?? "Done. Switch USB debugging off again and unplug; this stays set until Copier is uninstalled from the phone.")
        return Outcome(succeeded: failure == nil, summary: lines.joined(separator: "\n"))
    }

    private static func shell(_ tool: String, _ arguments: [String]) -> (status: Int32, output: String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        do {
            try process.run()
        } catch {
            return (-1, String(describing: error))
        }
        let output = pipe.fileHandleForReading.readDataToEndOfFile()  // drain before wait: no pipe deadlock
        process.waitUntilExit()
        return (process.terminationStatus, String(decoding: output, as: UTF8.self))
    }
}
