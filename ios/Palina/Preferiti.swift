import Foundation
import Motore

/** Le fermate preferite e le linee fissate ("fermata|linea"), salvate sull'iPhone come sull'app Android. */
@MainActor
final class Preferiti: ObservableObject {
    @Published private(set) var fermate: Set<String>
    @Published private(set) var fissate: Set<String>
    /** I nomi dati alle fermate ("Casa", "Lavoro"), come su Android. */
    @Published private(set) var nomi: [String: String]

    private let memoria = UserDefaults.standard

    init() {
        if let prova = Prova.preferiti {
            fermate = Set(prova)
        } else {
            fermate = Set(memoria.stringArray(forKey: "preferiti") ?? [])
        }
        fissate = Set(memoria.stringArray(forKey: "linee_fissate") ?? [])
        nomi = CodificaNomi.shared.leggi(json: memoria.string(forKey: "nomi_fermate"))
        if let prova = Prova.nomi { nomi = prova }
    }

    func nome(_ id: String) -> String? { nomi[id] }

    /** Il nome della fermata [id] ("Lavoro"); vuoto lo toglie. */
    func rinomina(_ id: String, _ nome: String) {
        nomi = NomiKt.conNomeFermata(nomi: nomi, id: id, nome: nome)
        memoria.set(CodificaNomi.shared.scrivi(nomi: nomi), forKey: "nomi_fermate")
        DatiWidgetIphone.condiviso.chiedi(forza: true)
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
