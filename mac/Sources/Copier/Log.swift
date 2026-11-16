import os

/// `log stream --predicate 'subsystem == "dev.copier"'`. Never log clipboard or file contents.
enum Log {
    static let app = Logger(subsystem: "dev.copier", category: "app")
    static let net = Logger(subsystem: "dev.copier", category: "net")
    static let clip = Logger(subsystem: "dev.copier", category: "clipboard")
    static let transfer = Logger(subsystem: "dev.copier", category: "transfer")
}
