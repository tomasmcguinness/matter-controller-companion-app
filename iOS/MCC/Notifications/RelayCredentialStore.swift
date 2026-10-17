import Foundation
import Security

/// Keeps each controller's push relay password in the Keychain, keyed by controller id.
///
/// The username lives on `Controller` alongside the rest of its metadata; only the password
/// is secret enough to keep out of `UserDefaults`.
enum RelayCredentialStore {

    private static let service = "com.tomasmcguinness.matter-controller-companion.relay"

    static func password(for controllerID: UUID) -> String? {
        var query = baseQuery(for: controllerID)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?

        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    /// Saves the password, or removes it when `password` is nil or empty.
    static func setPassword(_ password: String?, for controllerID: UUID) {
        removePassword(for: controllerID)

        guard let password, !password.isEmpty else { return }

        var item = baseQuery(for: controllerID)
        item[kSecValueData as String] = Data(password.utf8)
        // Push token rotation can happen on a background launch before the first unlock is
        // long gone, so don't require the device to be unlocked right now.
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        SecItemAdd(item as CFDictionary, nil)
    }

    static func removePassword(for controllerID: UUID) {
        SecItemDelete(baseQuery(for: controllerID) as CFDictionary)
    }

    private static func baseQuery(for controllerID: UUID) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: controllerID.uuidString,
        ]
    }
}
