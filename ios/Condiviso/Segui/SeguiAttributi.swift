import ActivityKit
import Foundation

/**
 * La Live Activity di "Segui" e "Avvisami" (schermata di blocco, Dynamic
 * Island e Smart Stack dell'Apple Watch): la scrive l'app, il widget la
 * disegna. I testi vengono dal motore, come quelli della notifica Android.
 */
struct SeguiAttributi: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /** Il momento a cui va il conto alla rovescia (il passaggio, o l'arrivo dove si scende). */
        var arrivo: Date
        var tempoReale: Bool
        /** "tempo reale" o "da orario". */
        var fonte: String
        /** "Esci ora", "64 tra 3 minuti", "Scendi alla prossima" (nil: solo il conto alla rovescia). */
        var titolo: String? = nil
        var testo: String? = nil
        /** "a 2 fermate · tempo reale". */
        var dettaglio: String? = nil
        /** La barra di avanzamento. */
        var inizio: Date? = nil
        var fine: Date? = nil
        /** "attesa", "esci", "arrivo", "scegli", "bordo", "scendi". */
        var fase: String? = nil
        var mostraSuccessivo: Bool? = nil
        var mostraUscito: Bool? = nil
        var mostraScegli: Bool? = nil
        var mostraFine: Bool? = nil
    }

    var linea: String
    var capolinea: String
    var fermata: String
    /** Il colore della linea (ARGB). */
    var colore: Int64
    /** I testi del widget nella lingua dell'app: "ora" e quello per i tempi vecchi. */
    var testoOra: String
    var testoScaduto: String
    /** "Palina Roma · avviso per il 64" e i pulsanti, nella lingua dell'app. */
    var intestazione: String = ""
    var testoUscito: String = ""
    var testoSuccessivo: String = ""
    var testoScegli: String = ""
    var testoFine: String = ""
}

/** Gli indirizzi che i pulsanti della Live Activity aprono in Palina. */
enum AzioneSegui: String {
    case uscito, successivo, scendi, fine

    var url: URL { URL(string: "palina://segui/\(rawValue)")! }

    static func da(_ url: URL) -> AzioneSegui? {
        guard url.scheme == "palina", url.host == "segui" else { return nil }
        return AzioneSegui(rawValue: url.lastPathComponent)
    }
}
