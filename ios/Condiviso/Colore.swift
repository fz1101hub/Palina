import SwiftUI

extension Color {
    /** Un colore ARGB del motore (0xFF862633). */
    init(argb: Int64) {
        let v = UInt32(truncatingIfNeeded: argb)
        self.init(
            .sRGB,
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255,
            opacity: Double((v >> 24) & 0xFF) / 255
        )
    }

    /**
     * L'oro di Palina, per i widget che non hanno il motore (deve restare
     * uguale a Colori.ORO del motore).
     */
    static let oroPalina = Color(argb: 0xFFF2A900)
    static let inchiostroPalina = Color(argb: 0xFF1C1A1A)
}
