import XCTest
@testable import FortressCore

final class FCETests: XCTestCase {
    /// Same golden vector as fce_test.go and CryptoCoreTest.kt.
    func testGoldenVector() throws {
        var w = FCEWriter()
        try w.u64(1, 5)
        try w.string(2, "ab")
        XCTAssertEqual(w.build().map { String(format: "%02x", $0) }.joined(), "0100000008000000000000000502000000026162")
    }

    func testRejectsDescendingTags() throws {
        var w = FCEWriter()
        try w.u64(2, 1)
        XCTAssertThrowsError(try w.u64(1, 1))
    }
}
