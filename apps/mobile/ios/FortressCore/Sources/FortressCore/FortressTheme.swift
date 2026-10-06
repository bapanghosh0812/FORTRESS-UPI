import SwiftUI

/// Fortress design tokens for SwiftUI — mirrors core/designsystem/Tokens.kt.
public enum FortressColor {
    public static let obsidian = Color(hex: 0x0B0B0C)
    public static let graphite = Color(hex: 0x141416)
    public static let graphiteRaised = Color(hex: 0x1C1C1F)
    public static let hairline = Color(hex: 0x2A2A2E)
    public static let platinum = Color(hex: 0xE5E5E7)
    public static let platinumMuted = Color(hex: 0x9A9AA1)
    public static let goldLight = Color(hex: 0xF6D47A)
    public static let gold = Color(hex: 0xD9A441)
    public static let goldDeep = Color(hex: 0xA8711F)
    public static let sapphire = Color(hex: 0x5B8CFF)
    public static let emerald = Color(hex: 0x00E59B)
    public static let danger = Color(hex: 0xFF5A5F)

    public static var goldGradient: LinearGradient {
        LinearGradient(colors: [goldLight, gold, goldDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

public enum FortressMotion {
    /// Interactive springs (interruptible). Matches Compose `FortressMotion.settle`.
    public static var settle: Animation { Animation.spring(response: 0.42, dampingFraction: 0.78) }
    public static var press: Animation { Animation.spring(response: 0.25, dampingFraction: 0.6) }
    public static var magnet: Animation { Animation.spring(response: 0.18, dampingFraction: 0.82) }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
