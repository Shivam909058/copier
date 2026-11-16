import CopierCore
import Foundation
import Security

struct PairedPhone: Codable, Equatable, Sendable {
    let id: String
    var name: String
    let publicKey: Data
}

struct KeychainError: Error, CustomStringConvertible {
    let status: OSStatus
    var description: String { (SecCopyErrorMessageString(status, nil) as String?) ?? "OSStatus \(status)" }
}

/// Paired phones and settings live in UserDefaults (public keys only). The device's private key
/// lives in the Keychain.
@MainActor
final class Store {
    private let defaults: UserDefaults
    private(set) var phones: [PairedPhone]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        phones = defaults.data(forKey: Keys.phones).flatMap { try? JSONDecoder().decode([PairedPhone].self, from: $0) } ?? []
    }

    func phone(id: String) -> PairedPhone? {
        phones.first { $0.id == id }
    }

    func save(_ phone: PairedPhone) {
        phones.removeAll { $0.id == phone.id }
        phones.append(phone)
        persist()
    }

    func remove(id: String) {
        phones.removeAll { $0.id == id }
        persist()
    }

    var syncClipboard: Bool {
        get { defaults.object(forKey: Keys.syncClipboard) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.syncClipboard) }
    }

    var syncCopiedFiles: Bool {
        get { defaults.object(forKey: Keys.syncCopiedFiles) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.syncCopiedFiles) }
    }

    /// Copy one-time codes from mirrored phone notifications to the Mac clipboard.
    var copyCodes: Bool {
        get { defaults.object(forKey: Keys.copyCodes) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.copyCodes) }
    }

    private func persist() {
        do {
            defaults.set(try JSONEncoder().encode(phones), forKey: Keys.phones)
        } catch {
            Log.app.error("saving paired phones failed: \(String(describing: error), privacy: .public)")
        }
    }

    private enum Keys {
        static let phones = "pairedPhones"
        static let syncClipboard = "syncClipboard"
        static let syncCopiedFiles = "syncCopiedFiles"
        static let copyCodes = "copyCodes"
    }

    /// Loads the device key from the Keychain, creating it on first launch.
    static func loadIdentity() throws -> Identity {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.copier.identity",
            kSecAttrAccount as String: "p256-static",
        ]
        var query = base
        query[kSecReturnData as String] = true
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data {
            return try Identity(rawPrivateKey: data)
        }
        guard status == errSecItemNotFound else { throw KeychainError(status: status) }

        let identity = Identity()
        var add = base
        add[kSecValueData as String] = identity.privateKey.rawRepresentation
        add[kSecAttrLabel as String] = "Copier device key"
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw KeychainError(status: addStatus) }
        return identity
    }
}
