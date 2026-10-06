import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// Hardware-bound P-256 signing on iOS — the Secure Enclave counterpart of
/// Android StrongBox. Keys are generated inside the Enclave, never leave it,
/// and are bound to this device (`ThisDeviceOnly`: excluded from backups and
/// device migration).
///
/// Note: the Secure Enclave has no per-key usage counter equivalent to
/// Android's `setMaxUsageCount(1)`, so offline notes on iOS are capped at the
/// TEE enforcement tier by the issuer (see OFFLINE_PROTOCOL.md §1) and backed
/// by server-side detection.
public struct SecureEnclaveSigner: Sendable {
    public enum Failure: Error { case unavailable, accessControl, keychain(OSStatus) }

    public let tag: String

    public init(tag: String) { self.tag = tag }

    /// Creates (or replaces) the key. `requireUserPresence` gates every signature
    /// behind Face ID / Touch ID / passcode.
    public func generate(requireUserPresence: Bool) throws -> P256.Signing.PublicKey {
        guard SecureEnclave.isAvailable else { throw Failure.unavailable }
        var flags: SecAccessControlCreateFlags = [.privateKeyUsage]
        if requireUserPresence { flags.insert(.userPresence) }
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, flags, &error
        ) else { throw Failure.accessControl }

        let key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
        try store(key.dataRepresentation)
        return key.publicKey
    }

    /// Signs `domain || 0x00 || body` (SHA-256, DER) — identical to the Android/Go format.
    public func sign(domain: String, body: Data, context: LAContext? = nil) throws -> Data {
        let blob = try load()
        let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob, authenticationContext: context)
        return try key.signature(for: FCEWriter.signingInput(domain: domain, body: body)).derRepresentation
    }

    /// X.509 SubjectPublicKeyInfo DER, as the server's `sig.ParseP256PublicKey` expects.
    public static func spki(_ key: P256.Signing.PublicKey) -> Data { key.derRepresentation }

    // The Enclave blob is an opaque, Enclave-encrypted handle — useless off this device —
    // but it still lives only in the Keychain with ThisDeviceOnly accessibility.
    private func store(_ blob: Data) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.fortress.upi.enclave",
            kSecAttrAccount as String: tag,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = blob
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
    }

    private func load() throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.fortress.upi.enclave",
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { throw Failure.keychain(status) }
        return data
    }
}
