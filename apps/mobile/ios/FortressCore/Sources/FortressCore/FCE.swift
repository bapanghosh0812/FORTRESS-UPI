import Foundation

/// Fortress Canonical Encoding v1 — byte-for-byte twin of `shared/crypto/fce` (Go)
/// and `core/crypto/Fce.kt` (Kotlin). See docs/OFFLINE_PROTOCOL.md §2.
public struct FCEWriter: Sendable {
    public static let maxMessageSize = 64 * 1024
    private var buffer = Data()
    private var lastTag = -1

    public init() {}

    public enum Failure: Error { case order, tooLarge, negative }

    public mutating func bytes(_ tag: UInt8, _ value: Data) throws {
        guard Int(tag) > lastTag else { throw Failure.order }
        guard buffer.count + 5 + value.count <= Self.maxMessageSize else { throw Failure.tooLarge }
        lastTag = Int(tag)
        buffer.append(tag)
        var len = UInt32(value.count).bigEndian
        withUnsafeBytes(of: &len) { buffer.append(contentsOf: $0) }
        buffer.append(value)
    }

    public mutating func u64(_ tag: UInt8, _ value: Int64) throws {
        guard value >= 0 else { throw Failure.negative }
        var be = UInt64(value).bigEndian
        try bytes(tag, withUnsafeBytes(of: &be) { Data($0) })
    }

    public mutating func string(_ tag: UInt8, _ value: String) throws {
        try bytes(tag, Data(value.utf8))
    }

    public func build() -> Data { buffer }

    /// `ASCII(domain) || 0x00 || body`
    public static func signingInput(domain: String, body: Data) -> Data {
        var out = Data(domain.utf8)
        out.append(0)
        out.append(body)
        return out
    }
}
