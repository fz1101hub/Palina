import SwiftUI
import Motore

@main
struct PalinaApp: App {
    @StateObject private var archivio = Archivio()
    @StateObject private var posizione = Posizione()
    @StateObject private var preferiti = Preferiti()

    init() {
        // la rete del motore; il contatto e' l'indirizzo del progetto, mai una email
        let versione = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        MotoreSchermateKt.preparaMotore(agenteUtente: "PalinaRoma-iOS/\(versione)")
    }

    var body: some Scene {
        WindowGroup {
            Principale()
                .environmentObject(archivio)
                .environmentObject(posizione)
                .environmentObject(preferiti)
                .tint(Tavolozza.oro)
                .onAppear {
                    // l'Apple Watch chiede il tabellone all'iPhone
                    PonteWatch.condiviso.avvia(archivio: archivio, preferiti: preferiti, posizione: posizione)
                    posizione.avvia()
                }
        }
    }
}

/** Dove si puo' andare dalla Home: una fermata o una linea. */
enum Destinazione: Hashable {
    case fermata(String)
    case linea(RichiestaLinea)
}

/** Una linea da aprire: la direzione e, se si arriva da una fermata, la corsa da evidenziare. */
struct RichiestaLinea: Hashable {
    let lineaId: String
    var direzione: Int = -1
    var corsa: String? = nil
    var fermata: String? = nil
}

/** La navigazione: la Home e, sopra, fermate e linee aperte. */
struct Principale: View {
    @State private var percorso: [Destinazione] = Prova.daAprire

    var body: some View {
        NavigationStack(path: $percorso) {
            HomeVista()
                .navigationDestination(for: Destinazione.self) { destinazione in
                    switch destinazione {
                    case .fermata(let id): FermataVista(fermataId: id)
                    case .linea(let richiesta): LineaVista(richiesta: richiesta)
                    }
                }
        }
    }
}

/**
 * Per le prove sul simulatore (compilazione su GitHub): `-apri fermata:70078`
 * o `-apri linea:64 -direzione 0` apre una schermata, `-posizione 41.8958,12.4826` finge
 * di essere li', `-provaPreferiti 70078,F:12345` mette delle preferite.
 */
enum Prova {
    static var daAprire: [Destinazione] {
        guard let apri = UserDefaults.standard.string(forKey: "apri") else { return [] }
        let parti = apri.split(separator: ":", maxSplits: 1).map(String.init)
        guard parti.count == 2 else { return [] }
        switch parti[0] {
        case "fermata": return [.fermata(parti[1])]
        case "linea":
            // la direzione a parte (-direzione 0): gli id Cotral hanno gia' i due punti ("C:12")
            let dir = UserDefaults.standard.object(forKey: "direzione") != nil ? UserDefaults.standard.integer(forKey: "direzione") : -1
            return [.linea(RichiestaLinea(lineaId: parti[1], direzione: dir))]
        default: return []
        }
    }

    static var posizione: (Double, Double)? {
        guard let testo = UserDefaults.standard.string(forKey: "posizione") else { return nil }
        let parti = testo.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        return parti.count == 2 ? (parti[0], parti[1]) : nil
    }

    static var preferiti: [String]? {
        UserDefaults.standard.string(forKey: "provaPreferiti")?.split(separator: ",").map(String.init)
    }
}
