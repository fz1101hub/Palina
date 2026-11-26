import SwiftUI
import Motore

/** Palina sull'Apple Watch: le preferite e le fermate vicine con i prossimi passaggi, calcolate dall'iPhone. */
@main
struct PalinaWatchApp: App {
    @StateObject private var archivio = ArchivioWatch()
    @State private var percorso: [String] = []

    init() {
        // i testi del motore nella lingua dell'orologio
        LinguaApp.imposta()
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $percorso) {
                TabelloneWatch()
                    .navigationDestination(for: String.self) { treno in CorsaWatch(treno: treno) }
            }
            .environmentObject(archivio)
            .onAppear {
                // prove sul simulatore: -provaTreno YES apre il primo treno del tabellone di esempio
                if UserDefaults.standard.bool(forKey: "provaTreno"),
                   let treno = archivio.stato?.fermate.flatMap({ $0.righe }).compactMap({ $0.treno }).first {
                    percorso = [treno]
                }
            }
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
                            if let treno = riga.treno {
                                // un treno: si vedono tutte le sue fermate
                                NavigationLink(value: treno) { RigaWatchVista(riga: riga) }
                            } else {
                                RigaWatchVista(riga: riga)
                            }
                        }
                        if fermata.righe.isEmpty {
                            Text(tx("Nessun passaggio a breve")).font(.footnote).foregroundStyle(.secondary)
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 1) {
                            // il nome che le hai dato sull'iPhone ("LAVORO"), sopra il nome della fermata
                            if !fermata.etichetta.isEmpty {
                                Text(fermata.etichetta.uppercased()).font(.system(size: 11, weight: .bold)).foregroundStyle(Color.oroPalina).textCase(nil)
                            }
                            Text((fermata.preferita ? "♥ " : "") + fermata.nome).font(.headline).textCase(nil)
                            Text(fermata.sotto).font(.caption2).foregroundStyle(.secondary).textCase(nil)
                        }
                    }
                }
                if let aggiornato = stato.aggiornato {
                    Text(stato.vecchio ? tx("iPhone non raggiungibile · {0}", aggiornato) : aggiornato)
                        .font(.caption2)
                        .foregroundStyle(stato.vecchio ? ColoriWatch.oro : .secondary)
                }
            } else if archivio.irraggiungibile {
                Text(tx("Apri Palina sull'iPhone: è lui che calcola i tempi.")).foregroundStyle(.secondary)
            } else {
                HStack { ProgressView(); Text(tx("Chiedo i tempi all'iPhone…")).font(.footnote) }
            }
            Button(tx("Aggiorna")) { archivio.chiedi(forza: true) }
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

/** Un treno sull'orologio: le fermate con l'ora (e il ritardo), in evidenza la tua stazione. */
struct CorsaWatch: View {
    let treno: String
    @EnvironmentObject private var archivio: ArchivioWatch
    @State private var stato: StatoCorsa?
    @State private var errore: String?

    var body: some View {
        ScrollViewReader { scorri in
            List {
                if let stato {
                    VStack(alignment: .leading) {
                        Text(stato.titolo).font(.headline)
                        if let verso = stato.verso { Text(verso).font(.caption2).foregroundStyle(.secondary) }
                    }
                    if let e = stato.errore { Text(e).font(.footnote).foregroundStyle(.secondary) }
                    ForEach(Array(stato.righe.enumerated()), id: \.offset) { i, r in
                        HStack {
                            Text(r.nome)
                                .font(r.qui ? .body.bold() : .footnote)
                                .foregroundStyle(r.passata ? .secondary : (r.qui ? ColoriWatch.oro : .primary))
                                .strikethrough(r.soppressa)
                                .lineLimit(2)
                            Spacer()
                            VStack(alignment: .trailing, spacing: 0) {
                                Text(r.ora).font(.footnote.monospacedDigit())
                                    .foregroundStyle(r.oraPrevista != nil ? ColoriWatch.oro : .primary)
                                if let ritardo = r.ritardo { Text(ritardo).font(.caption2).foregroundStyle(ColoriWatch.oro) }
                            }
                        }
                        .id(i)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(r.perVoce)
                    }
                } else if let errore {
                    Text(errore).foregroundStyle(.secondary)
                } else {
                    HStack { ProgressView(); Text(tx("Chiedo il treno all'iPhone…")).font(.footnote) }
                }
            }
            .onChange(of: stato?.qui) { _, qui in
                if let qui, qui >= 0 { scorri.scrollTo(Int(qui), anchor: .center) }
            }
        }
        .navigationTitle(tx("Treno"))
        .task {
            if let s = await archivio.corsa(treno: treno) { stato = s } else { errore = tx("L'iPhone non risponde: riprova.") }
        }
    }
}
