import SwiftUI
import Motore

/** I colori di Palina, presi dal motore (gli stessi dell'app Android). */
enum Tavolozza {
    static let porpora = Color(argb: Colori.shared.PORPORA)
    static let oro = Color(argb: Colori.shared.ORO)
    static let inchiostro = Color(argb: Colori.shared.INCHIOSTRO)

    /** Le cifre grandi dei minuti: a punti come sulle paline (Doto), o normali se lo si e' scelto. */
    static func cifre(_ grandezza: CGFloat, aPunti: Bool) -> Font {
        aPunti
            ? .custom("Doto-Black", size: grandezza, relativeTo: .title)
            : .system(size: grandezza, weight: .black, design: .rounded)
    }
}

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
}

/** Il riquadro colorato con il nome della linea ("64", "MEB", "Metromare"). */
struct BadgeLinea: View {
    let nome: String
    let colore: Int64
    var grande = false

    var body: some View {
        Text(nome)
            .font(grande ? .headline.bold() : .caption.bold())
            .foregroundStyle(.white)
            .lineLimit(1)
            .padding(.horizontal, grande ? 8 : 6)
            .padding(.vertical, grande ? 4 : 2)
            .background(Color(argb: colore), in: RoundedRectangle(cornerRadius: 5))
    }
}

/** Una fermata in un elenco: nome, distanza, "Palina 70078" e le linee. */
struct RigaFermata: View {
    let voce: VoceFermata

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(voce.nome).font(.body.bold()).lineLimit(2)
                Spacer()
                if let distanza = voce.distanza {
                    Text(distanza).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Text(voce.sotto).font(.footnote).foregroundStyle(.secondary)
            if !voce.linee.isEmpty {
                RigheBadge(nomi: voce.linee, colore: voce.colore)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(voce.perVoce)
        .accessibilityIdentifier("riga-fermata")
    }
}

/** I bollini delle linee che vanno a capo quando non c'e' piu' posto. */
struct RigheBadge: View {
    let nomi: [String]
    let colore: Int64

    var body: some View {
        DisposizioneACapo(spazio: 4) {
            ForEach(nomi, id: \.self) { BadgeLinea(nome: $0, colore: colore) }
        }
    }
}

/** Mette le viste in fila e va a capo (come FlowRow su Android). */
struct DisposizioneACapo: Layout {
    var spazio: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let larghezza = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, riga: CGFloat = 0, massima: CGFloat = 0
        for vista in subviews {
            let d = vista.sizeThatFits(.unspecified)
            if x > 0 && x + d.width > larghezza { x = 0; y += riga + spazio; riga = 0 }
            x += d.width + spazio
            riga = max(riga, d.height)
            massima = max(massima, x - spazio)
        }
        return CGSize(width: min(massima, larghezza), height: y + riga)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, riga: CGFloat = 0
        for vista in subviews {
            let d = vista.sizeThatFits(.unspecified)
            if x > bounds.minX && x + d.width > bounds.maxX { x = bounds.minX; y += riga + spazio; riga = 0 }
            vista.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(d))
            x += d.width + spazio
            riga = max(riga, d.height)
        }
    }
}
