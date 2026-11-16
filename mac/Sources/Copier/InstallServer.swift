import CopierCore
import Darwin
import Foundation
import Network

/// Plain-HTTP server behind the QR code: a landing page and the APK. It only runs while Add Phone
/// is open, and serves nothing secret — the pairing token lives in the URL fragment, which
/// browsers never send.
@MainActor
final class InstallServer {
    private let apk: URL?
    private var listener: NWListener?
    private(set) var boundPort: UInt16?

    init(apk: URL? = Bundle.main.url(forResource: "Copier", withExtension: "apk")) {
        self.apk = apk
    }

    var hasAPK: Bool {
        apk.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
    }

    func start(port: UInt16 = Proto.installPort, localOnly: Bool = false) {
        guard listener == nil else { return }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let endpointPort = NWEndpoint.Port(rawValue: port) ?? .any
        do {
            let listener: NWListener
            if localOnly {
                parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: endpointPort)
                listener = try NWListener(using: parameters)
            } else {
                listener = try NWListener(using: parameters, on: endpointPort)
            }
            listener.newConnectionHandler = { [weak self] connection in
                MainActor.assumeIsolated { self?.serve(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                MainActor.assumeIsolated {
                    switch state {
                    case .ready:
                        self?.boundPort = self?.listener?.port?.rawValue
                    case let .failed(error):
                        Log.net.error("install server failed: \(String(describing: error), privacy: .public)")
                        self?.stop()
                    default:
                        break
                    }
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            Log.net.error("install server could not start: \(String(describing: error), privacy: .public)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        boundPort = nil
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: .main)
        Task {
            guard let head = try? await Self.readRequestHead(connection) else {
                connection.cancel()
                return
            }
            connection.send(content: response(to: head), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
                connection.cancel()
            })
        }
    }

    private func response(to head: String) -> Data {
        let requestLine = head.split(separator: "\r\n", maxSplits: 1).first?.split(separator: " ") ?? []
        guard requestLine.count >= 2, requestLine[0] == "GET" else {
            return Self.http(405, "Method Not Allowed", type: "text/plain", body: Data("Only GET is supported.".utf8))
        }
        let path = requestLine[1].split(separator: "?", maxSplits: 1).first.map(String.init) ?? "/"
        switch path {
        case "/", "/index.html":
            return Self.http(200, "OK", type: "text/html; charset=utf-8", body: Data(Self.page.utf8))
        case "/copier.apk":
            guard let apk, let data = try? Data(contentsOf: apk) else {
                return Self.http(404, "Not Found", type: "text/plain", body: Data("This build of Copier doesn't include the Android app.".utf8))
            }
            Log.net.info("serving the APK (\(data.count) bytes)")
            return Self.http(200, "OK", type: "application/vnd.android.package-archive", body: data,
                             extra: ["Content-Disposition": "attachment; filename=\"Copier.apk\""])
        default:
            return Self.http(404, "Not Found", type: "text/plain", body: Data("Not found.".utf8))
        }
    }

    private static func readRequestHead(_ connection: NWConnection) async throws -> String {
        var buffer = Data()
        while buffer.count < 8192 {
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, _, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else {
                        continuation.resume(throwing: LinkError.closed)
                    }
                }
            }
            buffer.append(chunk)
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                return String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
            }
        }
        throw LinkError.frameTooLarge(buffer.count)
    }

    private static func http(_ status: Int, _ reason: String, type: String, body: Data, extra: [String: String] = [:]) -> Data {
        var head = """
        HTTP/1.1 \(status) \(reason)\r
        Content-Type: \(type)\r
        Content-Length: \(body.count)\r
        Cache-Control: no-store\r
        X-Content-Type-Options: nosniff\r
        Referrer-Policy: no-referrer\r
        Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'\r
        Connection: close\r

        """
        for (name, value) in extra { head += "\(name): \(value)\r\n" }
        return Data((head + "\r\n").utf8) + body
    }

    /// The "Open Copier" link is assembled in the browser from `location.hash`, so the pairing
    /// token never leaves the phone.
    private static let page = """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Copier</title>
    <style>
    :root { color-scheme: light dark; }
    body { font: 17px/1.45 system-ui, -apple-system, Roboto, sans-serif; margin: 0 auto; padding: 32px 20px; max-width: 440px; }
    h1 { font-size: 28px; margin: 0 0 4px; }
    p { margin: 0 0 24px; opacity: .75; }
    a.btn { display: block; text-align: center; padding: 16px; border-radius: 14px; text-decoration: none; font-weight: 600; margin: 18px 0 6px; background: #1a73e8; color: #fff; }
    a.btn.next { background: transparent; color: inherit; border: 2px solid currentColor; }
    small { display: block; opacity: .65; }
    </style></head>
    <body>
    <h1>Copier</h1>
    <p>Clipboard and files between this phone and your Mac.</p>
    <a class="btn" href="/copier.apk" download="Copier.apk">1 · Install Copier</a>
    <small>If Android asks, allow your browser to install apps, then tap Install.</small>
    <a class="btn next" id="open" href="#">2 · Open Copier to pair</a>
    <small id="hint">After installing, come back here and tap this.</small>
    <script>
    var fragment = location.hash.slice(1), open = document.getElementById('open');
    if (fragment) {
      open.href = 'intent://pair?' + fragment + '#Intent;scheme=copier;package=dev.copier;end';
    } else {
      open.style.display = 'none';
      document.getElementById('hint').textContent = 'To pair, scan the QR code on your Mac again.';
    }
    </script>
    </body></html>
    """
}

/// IPv4 addresses a phone on the same network could reach, best first.
enum LANAddress {
    static func candidates() -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var found: [(interface: String, ip: String)] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(entry.pointee.ifa_flags)
            guard let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
                continue
            }
            let ip = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            guard !ip.hasPrefix("169.254.") else { continue }  // link-local: no DHCP, unreachable
            found.append((String(cString: entry.pointee.ifa_name), ip))
        }
        return found.sorted { rank($0.interface) < rank($1.interface) }.map(\.ip)
    }

    /// Wi-Fi/Ethernet first (en0 is the built-in one); VPN tunnels, bridges and VMs last.
    private static func rank(_ interface: String) -> Int {
        if interface == "en0" { return 0 }
        if interface.hasPrefix("en") { return 1 }
        if ["utun", "bridge", "vmenet", "awdl", "llw"].contains(where: interface.hasPrefix) { return 3 }
        return 2
    }
}
