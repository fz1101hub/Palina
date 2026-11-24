import SwiftUI
import Motore

@main
struct PalinaApp: App {
    @StateObject private var archivio = Archivio()
    @StateObject private var posizione = Posizione()

    var body: some Scene {
        WindowGroup {
            Principale()
                .environmentObject(archivio)
                .environmentObject(posizione)
                .tint(Tavolozza.oro)
        }
    }
}

/** La navigazione: la Home e, sopra, le fermate aperte. */
struct Principale: View {
    @State private var percorso: [String] = Prova.fermataDaAprire.map { [$0] } ?? []

    var body: some View {
        NavigationStack(path: $percorso) {
            HomeVista()
                .navigationDestination(for: String.self) { id in
                    FermataVista(fermataId: id)
                }
        }
    }
}

/**
 * Per le prove sul simulatore (compilazione su GitHub): `-apri fermata:70078`
 * apre una fermata, `-posizione 41.8958,12.4826` finge di essere li'.
 */
enum Prova {
    static var fermataDaAprire: String? {
        guard let apri = UserDefaults.standard.string(forKey: "apri"), apri.hasPrefix("fermata:") else { return nil }
        return String(apri.dropFirst("fermata:".count))
    }

    static var posizione: (Double, Double)? {
        guard let testo = UserDefaults.standard.string(forKey: "posizione") else { return nil }
        let parti = testo.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        return parti.count == 2 ? (parti[0], parti[1]) : nil
    }
}
