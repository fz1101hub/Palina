import ActivityKit
import Foundation

/**
 * La Live Activity di "Segui" (schermata di blocco, Dynamic Island e Smart
 * Stack dell'Apple Watch): la scrive l'app, il widget la disegna. I testi
 * vengono dal motore, come quelli di "Segui" su Wear OS.
 */
struct SeguiAttributi: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /** Il passaggio seguito. */
        var arrivo: Date
        var tempoReale: Bool
        /** "tempo reale" o "da orario". */
        var fonte: String
    }

    var linea: String
    var capolinea: String
    var fermata: String
    /** Il colore della linea (ARGB). */
    var colore: Int64
}
