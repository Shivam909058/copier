import Foundation

/// File names arrive from the other device, so they are untrusted input.
public enum FileNames {
    private static let maxBytes = 200  // APFS allows 255 UTF-8 bytes; leave room for " (12)".

    /// No path components, no control characters, not hidden, never empty, at most 200 UTF-8 bytes.
    public static func sanitize(_ name: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:").union(.controlCharacters)
        var scalars = String.UnicodeScalarView()
        for scalar in name.unicodeScalars {
            scalars.append(forbidden.contains(scalar) ? "_" : scalar)
        }
        var result = String(scalars).trimmingCharacters(in: .whitespaces)
        while result.hasPrefix(".") { result.removeFirst() }

        if result.utf8.count > maxBytes {
            let ext = (result as NSString).pathExtension
            let keepExtension = !ext.isEmpty && ext.utf8.count <= 16
            let suffix = keepExtension ? "." + ext : ""
            var base = keepExtension ? (result as NSString).deletingPathExtension : result
            while !base.isEmpty, base.utf8.count + suffix.utf8.count > maxBytes { base.removeLast() }
            result = base + suffix
        }
        return result.isEmpty ? "file" : result
    }

    /// `name`, or `name (1)`, `name (2)`… — the first one that doesn't exist. Never overwrites.
    public static func uniqueURL(
        in directory: URL,
        name: String,
        exists: (URL) -> Bool = { FileManager.default.fileExists(atPath: $0.path) }
    ) -> URL {
        let first = directory.appendingPathComponent(name)
        guard exists(first) else { return first }
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        var n = 1
        while true {
            let candidate = directory.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            if !exists(candidate) { return candidate }
            n += 1
        }
    }
}
