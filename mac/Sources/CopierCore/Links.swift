import Foundation

/// Links that cross devices. Only http(s) is ever opened on the other side.
public enum Links {
    private static let loopbackHosts: Set<String> = ["localhost", "127.0.0.1", "0.0.0.0", "::1"]

    /// Parses a web link the way people paste them, including bare `localhost:3000/path`.
    public static func webURL(from text: String) -> URL? {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains(" "), !trimmed.contains("\n") else { return nil }
        let lower = trimmed.lowercased()
        if !lower.hasPrefix("http://"), !lower.hasPrefix("https://"),
           loopbackHosts.contains(where: { lower.hasPrefix($0 + ":") || lower == $0 || lower.hasPrefix($0 + "/") }) {
            trimmed = "http://" + trimmed
        }
        guard let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty
        else { return nil }
        return url
    }

    /// A dev server on this Mac is unreachable as `localhost` from the phone; point it at the Mac's
    /// LAN address instead. Other links pass through untouched.
    public static func forPhone(_ url: URL, lanHost: String?) -> URL {
        guard let lanHost,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]")),
              loopbackHosts.contains(host)
        else { return url }
        components.host = lanHost
        return components.url ?? url
    }
}

/// Finds a one-time code in a notification. Deliberately conservative: it needs a code-ish word and
/// skips money amounts, dates, times and phone numbers, because copying a wrong number is worse than
/// copying nothing.
public enum OneTimeCode {
    private static let keywords = [
        "code", "otp", "verification", "verify", "passcode", "pin", "one-time", "one time", "2fa",
        "login", "log in", "sign in", "sign-in", "security", "authenticat", "कोड", "ओटीपी",
    ]
    private static let currencyMarks: Set<Character> = ["₹", "$", "€", "£", "¥"]

    public static func find(in text: String) -> String? {
        let lower = text.lowercased()
        guard keywords.contains(where: lower.contains) else { return nil }

        // "123-456" / "123 456": codes some services print in two halves.
        if let split = firstMatch(#"(?<![\d-])(\d{3})[- ](\d{3})(?![\d-])"#, in: text) {
            return split
        }
        let candidates = matches(#"\b\d{4,8}\b"#, in: text).filter { isStandalone($0, in: text) }
        return candidates.first { text[$0].count == 6 }.map { String(text[$0]) }
            ?? candidates.first.map { String(text[$0]) }
    }

    /// Not glued to a currency mark, a decimal/thousands separator, a date or time separator, a
    /// percent sign, or more digits.
    private static func isStandalone(_ range: Range<String.Index>, in text: String) -> Bool {
        let before = text[..<range.lowerBound].reversed().prefix(2)
        let after = text[range.upperBound...].prefix(2)
        if let previous = before.first {
            if currencyMarks.contains(previous) { return false }
            if "-/:.,".contains(previous), let digit = before.dropFirst().first, digit.isNumber { return false }
        }
        if text[..<range.lowerBound].lowercased().hasSuffix("rs ") || text[..<range.lowerBound].lowercased().hasSuffix("rs.") {
            return false
        }
        if let next = after.first {
            if next == "%" { return false }
            if "-/:.,".contains(next), let digit = after.dropFirst().first, digit.isNumber { return false }
        }
        return true
    }

    private static func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let first = Range(match.range(at: 1), in: text), let second = Range(match.range(at: 2), in: text)
        else { return nil }
        return String(text[first]) + String(text[second])
    }

    private static func matches(_ pattern: String, in text: String) -> [Range<String.Index>] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { Range($0.range, in: text) }
    }
}
