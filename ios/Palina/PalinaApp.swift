import SwiftUI
import Motore

@main
struct PalinaApp: App {
    @StateObject private var archivio = Archivio()
    @StateObject private var posizione = Posizione()
    @StateObject private var preferiti = Preferiti()
    @StateObject private var tragitti = Tragitti()
    @StateObject private var seguito = Seguito()
    @AppStorage("aspetto") private var aspetto = "AUTOMATICO"

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
                .environmentObject(tragitti)
                .environmentObject(seguito)
                .tint(Tavolozza.oro)
                .onAppear {
                    // l'Apple Watch chiede il tabellone all'iPhone
                    PonteWatch.condiviso.avvia(archivio: archivio, preferiti: preferiti, posizione: posizione)
                    posizione.avvia()
                }
                .task(id: archivio.versione) {
                    guard let orario = archivio.orario else { return }
                    let primo = tragitti.motore == nil
                    tragitti.prepara(orario: orario)
                    seguito.prepara(orario: orario)
                    if primo, let fermata = Prova.tragittoVerso { tragitti.versoFermata(fermata) }
                }
                .task { await seguito.ciclo() }
                .alert("Segui", isPresented: Binding(get: { seguito.messaggio != nil }, set: { if !$0 { seguito.messaggio = nil } })) {
                    Button("OK", role: .cancel) { seguito.messaggio = nil }
                } message: {
                    Text(seguito.messaggio ?? "")
                }
                .preferredColorScheme(aspetto == "CHIARO" ? .light : aspetto == "SCURO" ? .dark : nil)
        }
    }
}

/** Dove si puo' andare dalla Home: fermate, linee, treni, tragitti e impostazioni. */
enum Destinazione: Hashable {
    case fermata(String)
    case linea(RichiestaLinea)
    /** Un treno con tutte le sue fermate (la richiesta in JSON, dal motore). */
    case corsa(String)
    case tragitti
    /** La scelta della partenza ("da") o dell'arrivo ("a"), con un testo gia' scritto. */
    case luogo(campo: String, testo: String)
    /** Un tragitto dell'elenco (-1 = tutto a piedi). */
    case dettaglioTragitto(Int)
    case impostazioni
}

/** Una linea da aprire: la direzione e, se si arriva da una fermata, la corsa da evidenziare. */
struct RichiestaLinea: Hashable {
    let lineaId: String
    var direzione: Int = -1
    var corsa: String? = nil
    var fermata: String? = nil
}

/** Le schermate aperte sopra la Home (per aprirne una da codice, come nelle prove). */
@MainActor
final class Navigazione: ObservableObject {
    @Published var percorso: [Destinazione] = Prova.daAprire
}

/** La navigazione: la Home e, sopra, fermate, linee e treni aperti. */
struct Principale: View {
    @StateObject private var navigazione = Navigazione()

    var body: some View {
        NavigationStack(path: $navigazione.percorso) {
            HomeVista()
                .navigationDestination(for: Destinazione.self) { destinazione in
                    switch destinazione {
                    case .fermata(let id): FermataVista(fermataId: id)
                    case .linea(let richiesta): LineaVista(richiesta: richiesta)
                    case .corsa(let treno): CorsaVista(treno: treno)
                    case .tragitti: TragittiVista()
                    case .luogo(let campo, let testo): SceltaLuogoVista(campo: campo, testo: testo)
                    case .dettaglioTragitto(let indice): DettaglioTragittoVista(indice: indice)
                    case .impostazioni: ImpostazioniVista()
                    }
                }
        }
        .environmentObject(navigazione)
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
        case "tragitto": return [.tragitti]
        case "luogo": return [.tragitti, .luogo(campo: "a", testo: parti[1])]
        case "impostazioni": return [.impostazioni]
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

    /** `-apriPrimoTreno YES`: nella fermata aperta, apre il primo treno appena arrivano i tempi. */
    static var apriPrimoTreno: Bool { UserDefaults.standard.bool(forKey: "apriPrimoTreno") }

    /** `-apri tragitto:70078`: i tragitti verso quella fermata. */
    static var tragittoVerso: String? {
        guard let apri = UserDefaults.standard.string(forKey: "apri"), apri.hasPrefix("tragitto:") else { return nil }
        return String(apri.dropFirst("tragitto:".count))
    }

    /** `-provaSegui YES`: nella fermata aperta segue la prima linea (senza chiedere le notifiche). */
    static var segui: Bool { UserDefaults.standard.bool(forKey: "provaSegui") }

    /** `-apriPrimoTragitto YES`: apre il primo tragitto appena c'e'. */
    static var apriPrimoTragitto: Bool { UserDefaults.standard.bool(forKey: "apriPrimoTragitto") }

    static var preferiti: [String]? {
        UserDefaults.standard.string(forKey: "provaPreferiti")?.split(separator: ",").map(String.init)
    }
}
