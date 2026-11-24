import SwiftUI
import Motore

/** Palina sull'Apple Watch: le preferite e le fermate vicine con i prossimi passaggi, calcolate dall'iPhone. */
@main
struct PalinaWatchApp: App {
    @StateObject private var archivio = ArchivioWatch()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                TabelloneWatch()
            }
            .environmentObject(archivio)
        }
    }
}

/** Le fermate, ognuna con le sue righe: linea, capolinea, "3' · 12' · 25'" (in oro se in tempo reale). */
struct TabelloneWatch: View {
    @EnvironmentObject private var archivio: ArchivioWatch
    @Environment(\.scenePhase) private var fase

    var body: some View {
        List {
            if let stato = archivio.stato, !stato.fermate.isEmpty {
                if let nota = stato.nota {
                    Text(nota).font(.footnote).foregroundStyle(ColoriWatch.oro)
                }
                ForEach(stato.fermate, id: \.id) { fermata in
                    Section {
                        ForEach(Array(fermata.righe.enumerated()), id: \.offset) { _, riga in
                            RigaWatchVista(riga: riga)
                        }
                        if fermata.righe.isEmpty {
                            Text("Nessun passaggio a breve").font(.footnote).foregroundStyle(.secondary)
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text((fermata.preferita ? "♥ " : "") + fermata.nome).font(.headline).textCase(nil)
                            Text(fermata.sotto).font(.caption2).foregroundStyle(.secondary).textCase(nil)
                        }
                    }
                }
                if let aggiornato = stato.aggiornato {
                    Text(stato.vecchio ? "iPhone non raggiungibile · \(aggiornato)" : aggiornato)
                        .font(.caption2)
                        .foregroundStyle(stato.vecchio ? ColoriWatch.oro : .secondary)
                }
            } else if archivio.irraggiungibile {
                Text("Apri Palina sull'iPhone: è lui che calcola i tempi.").foregroundStyle(.secondary)
            } else {
                HStack { ProgressView(); Text("Chiedo i tempi all'iPhone…").font(.footnote) }
            }
            Button("Aggiorna") { archivio.chiedi(forza: true) }
                .tint(ColoriWatch.oro)
        }
        .navigationTitle("Palina")
        .task {
            // i minuti si ricalcolano ogni 15 secondi, i tempi si chiedono ogni 30
            while !Task.isCancelled {
                archivio.ricalcola()
                archivio.chiedi(forza: false)
                try? await Task.sleep(nanoseconds: 15_000_000_000)
            }
        }
        .onChange(of: fase) { _, nuova in if nuova == .active { archivio.chiedi(forza: true) } }
    }
}

struct RigaWatchVista: View {
    let riga: RigaWatch

    var body: some View {
        HStack(alignment: .center, spacing: 6) {
            Text(riga.linea)
                .font(.caption.bold())
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(ColoriWatch.da(argb: riga.colore), in: RoundedRectangle(cornerRadius: 4))
            VStack(alignment: .leading, spacing: 1) {
                Text(riga.capolinea).font(.caption2).lineLimit(1).foregroundStyle(.secondary)
                Text(riga.avviso ?? riga.passaggi)
                    .font(.body.bold())
                    .foregroundStyle(riga.tempoReale || riga.avviso != nil ? ColoriWatch.oro : .primary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(riga.perVoce)
    }
}

enum ColoriWatch {
    static let oro = Color(red: 0xF2 / 255, green: 0xA9 / 255, blue: 0)

    static func da(argb: Int64) -> Color {
        let v = UInt32(truncatingIfNeeded: argb)
        return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}
