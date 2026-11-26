import BackgroundTasks
import SwiftUI
import UIKit
import Motore

@main
struct PalinaApp: App {
    @StateObject private var archivio = Archivio()
    @StateObject private var posizione = Posizione()
    @StateObject private var preferiti = Preferiti()
    @StateObject private var tragitti = Tragitti()
    @StateObject private var seguito = Seguito()
    @StateObject private var salvati = Salvati()
    @UIApplicationDelegateAdaptor(DelegatoApp.self) private var delegato
    @Environment(\.scenePhase) private var fase
    @AppStorage("aspetto") private var aspetto = "AUTOMATICO"
    /** La lingua scelta in Palina ("" come il telefono): cambiandola si ridisegna tutto. */
    @AppStorage("lingua") private var lingua = ""

    init() {
        // la lingua dei testi del motore: quella del telefono o quella scelta in Palina
        LinguaApp.imposta()
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
                .environmentObject(salvati)
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
                    // prove: -provaSalvato YES salva il tragitto da piazza Venezia a Termini
                    if primo, Prova.salvato, let t = tragitti.stato, let da = t.da, let a = t.a { salvati.salva(da: da, a: a, nome: "") }
                    // i widget e le scorciatoie dell'icona
                    DatiWidgetIphone.condiviso.prepara(orario: orario, preferiti: preferiti, posizione: posizione, salvati: salvati)
                    DatiWidgetIphone.condiviso.chiedi(forza: false)
                    Scorciatoie.aggiorna(salvati: salvati.elenco)
                }
                .onChange(of: preferiti.fermate) { DatiWidgetIphone.condiviso.chiedi(forza: true) }
                .onChange(of: preferiti.fissate) { DatiWidgetIphone.condiviso.chiedi(forza: true) }
                .onChange(of: fase) { _, nuova in
                    if nuova == .background {
                        DatiWidgetIphone.condiviso.chiedi(forza: false)
                        PalinaApp.programmaWidget()
                    }
                }
                .task { await seguito.ciclo() }
                .alert(tx("Segui"), isPresented: Binding(get: { seguito.messaggio != nil }, set: { if !$0 { seguito.messaggio = nil } })) {
                    Button("OK", role: .cancel) { seguito.messaggio = nil }
                } message: {
                    Text(seguito.messaggio ?? "")
                }
                .preferredColorScheme(aspetto == "CHIARO" ? .light : aspetto == "SCURO" ? .dark : nil)
                .id(lingua)
                .onChange(of: lingua) {
                    LinguaApp.imposta()
                    tragitti.aggiorna()
                    Scorciatoie.aggiorna(salvati: salvati.elenco)
                    DatiWidgetIphone.condiviso.chiedi(forza: true)
                }
        }
        // i widget si aggiornano anche con Palina chiusa, quando iOS lo permette
        .backgroundTask(.appRefresh(PalinaApp.idWidget)) {
            await DatiWidgetIphone.condiviso.calcolaInSfondo()
            PalinaApp.programmaWidget()
        }
    }

    nonisolated static let idWidget = "it.palinaroma.widget"

    /** Il prossimo aggiornamento dei widget in sottofondo, tra un quarto d'ora o quando iOS decide. */
    nonisolated static func programmaWidget() {
        let richiesta = BGAppRefreshTaskRequest(identifier: idWidget)
        richiesta.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(richiesta)
    }
}

/** Le scorciatoie dell'icona arrivano qui (all'avvio o con l'app gia' aperta). */
final class DelegatoApp: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting sessione: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let azione = options.shortcutItem {
            Task { @MainActor in Richieste.condivise.apri = azione.type }
        }
        let configurazione = UISceneConfiguration(name: nil, sessionRole: sessione.role)
        configurazione.delegateClass = DelegatoScena.self
        return configurazione
    }
}

final class DelegatoScena: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in Richieste.condivise.apri = shortcutItem.type }
        completionHandler(true)
    }
}

/**
 * Cosa aprire: da una scorciatoia ("vicina", "palina", "tragitto:<id>"), da
 * un widget o dalla Live Activity (palina://...).
 */
@MainActor
final class Richieste: ObservableObject {
    static let condivise = Richieste()
    @Published var apri: String?

    func da(_ url: URL) {
        guard url.scheme == "palina", let host = url.host else { return }
        let resto = url.pathComponents.filter { $0 != "/" }.joined(separator: "/")
        apri = resto.isEmpty ? host : host + ":" + resto
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
    /** Dove scendi (da "Avvisami"). */
    case scendi
    /** Il numero della palina (dalla scorciatoia). */
    case cercaPalina
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
    @ObservedObject private var richieste = Richieste.condivise
    @EnvironmentObject private var seguito: Seguito
    @EnvironmentObject private var salvati: Salvati
    @EnvironmentObject private var tragitti: Tragitti
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione

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
                    case .scendi: ScendiVista()
                    case .cercaPalina: CercaPalinaVista()
                    }
                }
        }
        .environmentObject(navigazione)
        .onOpenURL { url in
            if AzioneSegui.da(url) != nil { seguito.apri(url) } else { richieste.da(url) }
        }
        .onChange(of: seguito.apriScendi) { _, apri in
            if apri {
                seguito.apriScendi = false
                navigazione.percorso = [.scendi]
            }
        }
        .task(id: richieste.apri) { await esegui() }
    }

    /** Apre quello che chiede una scorciatoia o un widget. */
    private func esegui() async {
        guard let cosa = richieste.apri else { return }
        richieste.apri = nil
        if cosa == "home" {
            navigazione.percorso = []
        } else if cosa == "palina" {
            navigazione.percorso = [.cercaPalina]
        } else if cosa == "tragitto" {
            navigazione.percorso = [.tragitti]
        } else if cosa.hasPrefix("tragitto:") {
            let id = String(cosa.dropFirst("tragitto:".count))
            if let t = salvati.elenco.first(where: { $0.id == id }) { tragitti.scegliSalvato(t) }
            navigazione.percorso = [.tragitti]
        } else if cosa.hasPrefix("fermata:") {
            navigazione.percorso = [.fermata(String(cosa.dropFirst("fermata:".count)))]
        } else if cosa == "vicina" {
            posizione.avvia()
            // la posizione e l'orario arrivano tra poco
            for _ in 0..<20 {
                if let home = archivio.home, let lat = posizione.lat, let lon = posizione.lon {
                    if let prima = home.vicine(lat: lat, lon: lon).first { navigazione.percorso = [.fermata(prima.id)] }
                    return
                }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }
}

/** Il numero della palina (dalla scorciatoia): la ricerca con la tastiera dei numeri. */
struct CercaPalinaVista: View {
    @EnvironmentObject private var archivio: Archivio
    @State private var testo = ""
    @FocusState private var fuoco: Bool

    var body: some View {
        List {
            Section {
                TextField(tx("Numero della palina"), text: $testo)
                    .keyboardType(.numberPad)
                    .focused($fuoco)
                    .font(.title2.monospacedDigit())
            } footer: {
                Text(tx("Il numero è sul cartello della fermata, per esempio 70078."))
            }
            let trovate = testo.count >= 2 ? (archivio.home?.cerca(testo: testo) ?? []) : []
            ForEach(trovate, id: \.id) { voce in
                NavigationLink(value: Destinazione.fermata(voce.id)) { RigaFermata(voce: voce) }
            }
        }
        .navigationTitle(tx("Numero della palina"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { fuoco = true }
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
        case "palina": return [.cercaPalina]
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

    /** `-provaAggiorna YES`: nelle impostazioni cerca subito un orario nuovo nella release. */
    static var aggiorna: Bool { UserDefaults.standard.bool(forKey: "provaAggiorna") }

    /** `-provaSegui YES`: nella fermata aperta segue la prima linea (senza chiedere le notifiche). */
    static var segui: Bool { UserDefaults.standard.bool(forKey: "provaSegui") }

    /** `-provaSalvato YES`: salva il tragitto aperto con `-apri tragitto:...` (Home e widget). */
    static var salvato: Bool { UserDefaults.standard.bool(forKey: "provaSalvato") }

    /** `-provaAvvisami YES`: nella fermata aperta apre "Avvisami" per la prima linea. */
    static var avvisami: Bool { UserDefaults.standard.bool(forKey: "provaAvvisami") }

    /** `-apriPrimoTragitto YES`: apre il primo tragitto appena c'e'. */
    static var apriPrimoTragitto: Bool { UserDefaults.standard.bool(forKey: "apriPrimoTragitto") }

    static var preferiti: [String]? {
        UserDefaults.standard.string(forKey: "provaPreferiti")?.split(separator: ",").map(String.init)
    }

    /** `-provaNomi 70078=Lavoro`: i nomi dati alle fermate (Home e widget come nel disegno). */
    static var nomi: [String: String]? {
        guard let testo = UserDefaults.standard.string(forKey: "provaNomi") else { return nil }
        var nomi: [String: String] = [:]
        for coppia in testo.split(separator: ",") {
            let parti = coppia.split(separator: "=", maxSplits: 1).map(String.init)
            if parti.count == 2 { nomi[parti[0]] = parti[1] }
        }
        return nomi
    }
}
