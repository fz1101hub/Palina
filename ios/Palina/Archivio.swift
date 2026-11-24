import Foundation
import Motore

/** L'orario incluso nell'app (gli stessi file SQLite dell'app Android), aperto una volta. */
@MainActor
final class Archivio: ObservableObject {
    @Published private(set) var orario: Orario?
    /** Il motore della Home (fermate vicine, ricerca, tempi), pronto con l'orario. */
    @Published private(set) var home: MotoreHome?
    @Published private(set) var errore: String?

    init() {
        Task.detached(priority: .userInitiated) {
            let roma = Bundle.main.path(forResource: "orario", ofType: "db", inDirectory: "Risorse")
            let reti = Bundle.main.path(forResource: "reti", ofType: "db", inDirectory: "Risorse")
            guard let roma else {
                await MainActor.run { self.errore = "Manca l'orario incluso nell'app." }
                return
            }
            let orario = BaseIosKt.orarioDaFile(roma: roma, reti: reti)
            await MainActor.run {
                self.orario = orario
                self.home = MotoreHome(orario: orario)
            }
        }
    }
}
