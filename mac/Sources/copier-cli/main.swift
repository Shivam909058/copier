import AppKit
import CopierCore
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

// `copier` — drive Copier from the terminal. Commands go to the running app as a distributed
// notification (fire and forget); `paste`, `status`, `history` and `doctor` read the small files
// the app keeps in ~/Library/Application Support/Copier.

let usage = """
usage: copier <command>

  start [--restart]   bring everything up: app, network, phone connection (shows a QR if needed)
  stop                quit Copier on this Mac
  qr                  show the pairing QR code here and on screen
  doctor              check every link in the chain and say how to fix what's broken
  selftest            try the OTP popup, Mac → phone and phone → Mac copy, guided
  diagnose [seconds]  watch what the phone's copy detector sees, live (default 120 s)
  status [--json]     phone connection and battery

  send <file>…        send files to the phone
  copy [text]         put text on the phone's clipboard (reads stdin when no text is given)
  paste               print the latest text copied on the phone
  open <url>          open a link on the phone (localhost is rewritten to this Mac's address)
  ring                ring the phone
  history [n]         the last n items (default 10)
"""

// Line-buffer stdout so progress shows up live even when piped (scripts, agents, `| tee`).
setvbuf(stdout, nil, _IOLBF, 0)

let bundleID = "dev.copier.mac"
let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("Copier", isDirectory: true)

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

func appIsRunning() -> Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).isEmpty }

func post(_ command: String, _ payload: [String: Any] = [:]) {
    var info = payload
    info["cmd"] = command
    DistributedNotificationCenter.default().postNotificationName(
        Notification.Name("dev.copier.command"), object: nil, userInfo: info, deliverImmediately: true
    )
}

func send(_ command: String, _ payload: [String: Any] = [:]) {
    guard appIsRunning() else { fail("Copier isn't running. Start it with: copier start") }
    post(command, payload)
}

func status() -> [String: Any] {
    guard let data = try? Data(contentsOf: support.appendingPathComponent("status.json")) else { return [:] }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
}

/// Polls until `condition` holds or `seconds` pass.
func wait(_ seconds: Double, until condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while Date() < deadline {
        if condition() { return true }
        Thread.sleep(forTimeInterval: 0.25)
    }
    return condition()
}

func launch() {
    let open = Process()
    open.executableURL = URL(fileURLWithPath: "/usr/bin/open")
    open.arguments = ["-g", "-b", bundleID]  // -g: don't steal focus
    do {
        try open.run()
        open.waitUntilExit()
    } catch {
        fail("couldn't launch Copier: \(error)")
    }
    guard open.terminationStatus == 0 else { fail("Copier isn't installed. Install it: curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash") }
}

func quit() {
    for app in NSRunningApplication.runningApplications(withBundleIdentifier: bundleID) { app.terminate() }
    _ = wait(5) { !appIsRunning() }
}

func batteryText(_ status: [String: Any]) -> String {
    guard let battery = status["battery"] as? Int else { return "" }
    return " · battery \(battery)%\((status["charging"] as? Bool) == true ? " charging" : "")"
}

/// Two QR rows per text line with ▀; colours are forced to black-on-white so it scans on dark
/// terminals too.
func printQR(_ text: String) {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    filter.correctionLevel = "M"
    guard let image = filter.outputImage, let cg = CIContext().createCGImage(image, from: image.extent) else { return }
    let width = cg.width, height = cg.height
    var pixels = [UInt8](repeating: 255, count: width * height)
    guard let context = CGContext(
        data: &pixels, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
    ) else { return }
    context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
    func dark(_ x: Int, _ y: Int) -> Bool { x >= 0 && y >= 0 && x < width && y < height && pixels[y * width + x] < 128 }
    var y = -2
    while y < height + 2 {
        var line = "  "
        for x in -2..<(width + 2) {
            line += "\u{1B}[\(dark(x, y) ? 30 : 97);\(dark(x, y + 1) ? 40 : 107)m▀"
        }
        print(line + "\u{1B}[0m")
        y += 2
    }
}

func showQR() {
    try? FileManager.default.removeItem(at: support.appendingPathComponent("invite.url"))
    post("add-phone")
    let invite = support.appendingPathComponent("invite.url")
    guard wait(5, until: { FileManager.default.fileExists(atPath: invite.path) }),
          let url = try? String(contentsOf: invite, encoding: .utf8)
    else { fail("Copier didn't produce a QR code. Is this Mac on Wi-Fi? Try: copier doctor") }
    print("\nScan with your phone's camera (also shown in the Add Phone window):\n")
    printQR(url)
    print("""

      1 · Install Copier   2 · Open Copier to pair
      The phone must be on the same Wi-Fi as this Mac, or this Mac on the phone's hotspot.

    """)
}

var arguments = Array(CommandLine.arguments.dropFirst())
guard let command = arguments.first else {
    print(usage)
    exit(0)
}
arguments.removeFirst()

switch command {
case "start", "up":
    if arguments.contains("--restart"), appIsRunning() {
        print("Restarting Copier…")
        quit()
    }
    let started = Date().timeIntervalSince1970
    if !appIsRunning() {
        print("Starting Copier…")
        launch()
        guard wait(10, until: { appIsRunning() && ((status()["updated"] as? Double) ?? 0) >= started }) else {
            fail("Copier didn't start. Look at: /usr/bin/log show --last 2m --predicate 'subsystem == \"dev.copier\"'")
        }
    } else {
        post("restart-network")  // fresh listener + Bonjour announcement after a network change
    }
    let current = status()
    guard (current["paired"] as? Int ?? 0) > 0 else {
        print("No phone paired yet.")
        showQR()
        exit(0)
    }
    print("Waiting for \(current["phone"] as? String ?? "your phone")…")
    if wait(15, until: { (status()["connected"] as? Bool) == true }) {
        let now = status()
        print("✓ Connected to \(now["phone"] as? String ?? "your phone")\(batteryText(now)). Copy on one device, paste on the other.")
    } else {
        print("""
        ✗ The phone didn't connect. Open Copier on the phone, and check it's on the same Wi-Fi as this Mac.
          Still stuck? Re-pair with the QR below, or run: copier doctor
        """)
        showQR()
    }

case "stop":
    guard appIsRunning() else { fail("Copier isn't running") }
    quit()
    print("Copier stopped")

case "qr":
    if !appIsRunning() {
        launch()
        _ = wait(10) { appIsRunning() }
    }
    showQR()

case "doctor":
    var problems = 0
    func check(_ ok: Bool, _ good: String, _ bad: String, fix: String) {
        print(ok ? "✓ \(good)" : "✗ \(bad)\n    → \(fix)")
        if !ok { problems += 1 }
    }
    let installed = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) != nil
    check(installed, "Copier is installed", "Copier isn't installed",
          fix: "curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash")
    check(appIsRunning(), "Copier is running", "Copier isn't running", fix: "copier start")
    let current = status()
    let address = current["address"] as? String
    check(address != nil, "This Mac's network address: \(address ?? "")", "This Mac has no network address",
          fix: "join Wi-Fi, or connect this Mac to your phone's hotspot")
    let port = current["port"] as? Int
    check(port != nil, "Listening for the phone on port \(port ?? 0)", "Not listening for the phone",
          fix: "copier start --restart")
    let paired = current["paired"] as? Int ?? 0
    check(paired > 0, "\(paired) phone\(paired == 1 ? "" : "s") paired", "No phone paired", fix: "copier qr")
    let connected = (current["connected"] as? Bool) == true
    check(connected, "Connected to \(current["phone"] as? String ?? "the phone")\(batteryText(current))",
          "The phone isn't connected",
          fix: "open Copier on the phone; same Wi-Fi as this Mac (office Wi-Fi often blocks devices: use the phone's hotspot); then copier start")
    if connected {
        switch current["autoCopy"] as? Bool {
        case true?:
            print("✓ Automatic copy is running on the phone")
        case false?:
            let note = (current["autoCopyNote"] as? String).map { " (\($0))" } ?? ""
            check(false, "", "Automatic copy is off on the phone\(note), so copying there won't reach this Mac",
                  fix: "on the phone: open Copier (or tap its notification) → Automatic copy → Turn on. Payment apps pause it on purpose.")
        case nil:
            print("– Automatic copy: unknown (update the phone app: copier qr → Install → Update)")
        }
    }
    let access = current["clipboardAccess"] as? String ?? "allowed"
    check(access == "allowed", "Clipboard access: allowed",
          "macOS asks before Copier reads the clipboard (\(access)), so Mac → phone sync is paused",
          fix: "System Settings → Privacy & Security → Paste from Other Apps → Copier → Always Allow")
    let notifications = (current["notifications"] as? Bool) ?? true
    print(notifications ? "✓ Notifications on" : "– Notifications off (optional: System Settings → Notifications → Copier)")
    print(problems == 0 ? "\nAll good." : "\n\(problems) thing\(problems == 1 ? "" : "s") to fix.")
    exit(problems == 0 ? 0 : 1)

case "selftest":
    guard appIsRunning() else { fail("Copier isn't running. Start it with: copier start") }
    guard (status()["connected"] as? Bool) == true else { fail("The phone isn't connected. Run: copier start") }
    struct Item: Decodable {
        let direction: String
        let kind: String
        let text: String?
        let sensitive: Bool?
        let date: Date
    }
    func latestFromPhone() -> Item? {
        let data = (try? Data(contentsOf: support.appendingPathComponent("activity.json"))) ?? Data("[]".utf8)
        return ((try? JSONDecoder().decode([Item].self, from: data)) ?? []).first { $0.direction == "fromPhone" && $0.kind == "text" }
    }
    let code = String(Int.random(in: 100_000...999_999))
    let started = Date()

    print("1/3  A phone notification with a one-time code → Mac")
    post("selftest-otp", ["text": code])
    print("     Top-right of the Mac: “Code \(code) copied”. Press ⌘V anywhere on the Mac to paste it.")
    print("     It's also under Notifications in the Copier window.\n")

    print("2/3  Mac → phone clipboard")
    post("copy", ["text": "Copier test: your code is \(code)"])
    print("     Paste on the phone now; you should see “Copier test: your code is \(code)”.\n")

    print("3/3  Phone → Mac clipboard")
    print("     On the phone, select some text and tap Copy (for example in the place you just pasted).")
    print("     Waiting up to 90 seconds…")
    if wait(90, until: { (latestFromPhone()?.date ?? .distantPast) > started }), let item = latestFromPhone() {
        let what = item.sensitive == true ? "a password-type item" : "“\(String((item.text ?? "").prefix(80)))”"
        print("     ✓ Arrived on the Mac: \(what). Press ⌘V on the Mac to paste it.")
    } else {
        print("""
             ✗ Nothing arrived from the phone. On the phone, check Accessibility → “Copier — send copies to Mac”
               is on (Copier app → Setup), then run: copier doctor
        """)
        exit(1)
    }

case "diagnose":
    guard appIsRunning() else { fail("Copier isn't running. Start it with: copier start") }
    guard (status()["connected"] as? Bool) == true else { fail("The phone isn't connected. Run: copier start") }
    let seconds = arguments.first.flatMap(Int.init).map { max(10, min(900, $0)) } ?? 120
    let log = support.appendingPathComponent("diagnostics.log")
    post("diagnose", ["text": String(seconds)])
    print("""
    For \(seconds) s the phone reports what its copy detector sees: the names of buttons you tap and
    "copied" messages. Never what you copied. Now copy something on the phone.

    """)
    var printed = 0
    let deadline = Date().addingTimeInterval(Double(seconds))
    while Date() < deadline {
        let lines = ((try? String(contentsOf: log, encoding: .utf8)) ?? "").split(separator: "\n")
        for line in lines.dropFirst(printed) { print(line) }
        printed = max(printed, lines.count)
        Thread.sleep(forTimeInterval: 0.4)
    }
    print("\nDone. If a copy didn't show up above, send these lines along when you report it.")

case "send":
    guard !arguments.isEmpty else { fail("usage: copier send <file>…") }
    let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    let paths = arguments.map { URL(fileURLWithPath: $0, relativeTo: cwd).standardizedFileURL.path }
    for path in paths where !FileManager.default.fileExists(atPath: path) { fail("no such file: \(path)") }
    send("send", ["paths": paths])
    print("Sending \(paths.count) file\(paths.count == 1 ? "" : "s") to the phone")

case "copy":
    let text = arguments.isEmpty
        ? String(decoding: FileHandle.standardInput.readDataToEndOfFile(), as: UTF8.self)
        : arguments.joined(separator: " ")
    guard !text.isEmpty else { fail("nothing to copy") }
    send("copy", ["text": text])

case "paste":
    struct Item: Decodable {
        let direction: String
        let kind: String
        let text: String?
        let sensitive: Bool?
    }
    let data = (try? Data(contentsOf: support.appendingPathComponent("activity.json"))) ?? Data("[]".utf8)
    let items = (try? JSONDecoder().decode([Item].self, from: data)) ?? []
    guard let text = items.first(where: { $0.direction == "fromPhone" && $0.kind == "text" && $0.sensitive != true })?.text else {
        fail("nothing copied on the phone yet")
    }
    print(text, terminator: text.hasSuffix("\n") ? "" : "\n")

case "open":
    guard let raw = arguments.first, let url = Links.webURL(from: raw) else { fail("usage: copier open <http(s) url or localhost:port>") }
    send("open", ["url": url.absoluteString])

case "ring":
    send("ring")

case "status":
    let current = status()
    if arguments.first == "--json" {
        let data = (try? JSONSerialization.data(withJSONObject: current, options: [.sortedKeys, .prettyPrinted])) ?? Data("{}".utf8)
        print(String(decoding: data, as: UTF8.self))
    } else {
        let phone = current["phone"] as? String ?? "no phone"
        let connected = (current["connected"] as? Bool) == true
        print("\(phone): \(connected ? "connected" : "not connected")\(batteryText(current))\(appIsRunning() ? "" : " (Copier isn't running)")")
    }

case "history":
    struct Item: Decodable {
        let direction: String
        let kind: String
        let title: String
        let text: String?
        let sensitive: Bool?
        let date: Date
    }
    let data = (try? Data(contentsOf: support.appendingPathComponent("activity.json"))) ?? Data("[]".utf8)
    let items = (try? JSONDecoder().decode([Item].self, from: data)) ?? []
    let count = arguments.first.flatMap(Int.init) ?? 10
    let formatter = DateFormatter()
    formatter.dateFormat = "MMM d HH:mm"
    for item in items.prefix(count) {
        let arrow = item.direction == "toPhone" ? "→ phone" : "← phone"
        let what = item.kind == "text"
            ? (item.sensitive == true ? "(password)" : String((item.text ?? "").prefix(60)).replacingOccurrences(of: "\n", with: " "))
            : item.title
        print("\(formatter.string(from: item.date))  \(arrow)  \(what)")
    }

case "-h", "--help", "help":
    print(usage)

default:
    fail("unknown command: \(command)\n\n\(usage)")
}
