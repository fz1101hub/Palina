import Foundation

/** Le fermate preferite e le linee fissate ("fermata|linea"), salvate sull'iPhone come sull'app Android. */
@MainActor
final class Preferiti: ObservableObject {
    @Published private(set) var fermate: Set<String>
    @Published private(set) var fissate: Set<String>

    private let memoria = UserDefaults.standard

    init() {
        if let prova = Prova.preferiti {
            fermate = Set(prova)
        } else {
            fermate = Set(memoria.stringArray(forKey: "preferiti") ?? [])
        }
        fissate = Set(memoria.stringArray(forKey: "linee_fissate") ?? [])
    }

    func eUnaPreferita(_ id: String) -> Bool { fermate.contains(id) }

    func alternaPreferita(_ id: String) {
        if fermate.contains(id) { fermate.remove(id) } else { fermate.insert(id) }
        memoria.set(Array(fermate), forKey: "preferiti")
    }

    /** [chiave] viene dal motore (MotoreFermata.chiaveDaFissare). */
    func alternaFissata(_ chiave: String) {
        if fissate.contains(chiave) { fissate.remove(chiave) } else { fissate.insert(chiave) }
        memoria.set(Array(fissate), forKey: "linee_fissate")
    }
}
