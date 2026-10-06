// swift-tools-version:6.0
// FortressCore — platform-neutral security and design primitives for the iOS app.
// The Xcode app target (FortressApp) depends on this package; see README.md.
import PackageDescription

let package = Package(
    name: "FortressCore",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "FortressCore", targets: ["FortressCore"]),
    ],
    targets: [
        .target(name: "FortressCore"),
        .testTarget(name: "FortressCoreTests", dependencies: ["FortressCore"]),
    ]
)
