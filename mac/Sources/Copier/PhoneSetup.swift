import Foundation

/// Grants the Android app its special permissions over adb in one go. Android only lets these be
/// granted from a computer (or root), which is exactly what the Mac is.
enum PhoneSetup {
    struct Outcome: Sendable {
        let succeeded: Bool
        let summary: String
    }

    static let package = "dev.copier"

    private static let adbLocations = [
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb",
    ]

    static func run() async -> Outcome {
        await Task.detached { runBlocking() }.value
    }

    private static func runBlocking() -> Outcome {
        guard let adb = adbLocations.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else {
            return Outcome(succeeded: false, summary: "adb isn't installed. Install it with:\n\nbrew install --cask android-platform-tools")
        }
        let devices = shell(adb, ["devices"]).output
            .split(separator: "\n")
            .dropFirst()
            .filter { $0.hasSuffix("\tdevice") }
        guard devices.count == 1 else {
            return Outcome(
                succeeded: false,
                summary: devices.isEmpty
                    ? "No phone found. Connect it with USB, unlock it and accept “Allow USB debugging”."
                    : "More than one Android device is connected. Unplug the others and try again."
            )
        }
        guard shell(adb, ["shell", "pm", "path", package]).status == 0 else {
            return Outcome(succeeded: false, summary: "Copier isn't installed on the phone yet.")
        }

        // (label, adb arguments, required). Optional steps don't exist on every Android version.
        let steps: [(String, [String], Bool)] = [
            ("Automatic phone → Mac clipboard", ["shell", "pm", "grant", package, "android.permission.READ_LOGS"], true),
            ("Read clipboard from the background", ["shell", "appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"], true),
            ("Notifications", ["shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS"], false),
            ("Stay connected (no battery optimization)", ["shell", "dumpsys", "deviceidle", "whitelist", "+" + package], false),
        ]
        var lines: [String] = []
        var succeeded = true
        for (label, arguments, required) in steps {
            let ok = shell(adb, arguments).status == 0
            lines.append("\(ok ? "✓" : required ? "✗" : "–") \(label)")
            if !ok && required { succeeded = false }
        }
        // READ_LOGS only applies to a fresh process.
        _ = shell(adb, ["shell", "am", "force-stop", package])
        _ = shell(adb, ["shell", "am", "start", "-n", package + "/.MainActivity"])
        return Outcome(succeeded: succeeded, summary: lines.joined(separator: "\n"))
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
