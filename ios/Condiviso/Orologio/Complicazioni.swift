import Foundation

/**
 * Le voci delle complicazioni dell'Apple Watch, minuto per minuto: le calcola
 * il motore nell'app per l'orologio (MotoreWatch.complicazioni) e le lascia nel
 * gruppo condiviso con il widget, che le disegna.
 */
struct VociComplicazioni: Codable {
    var voci: [VoceComplicazione]
}

struct VoceComplicazione: Codable {
    /** Da quando vale, in millisecondi. */
    var alle: Int64
    var titolo: String
    var righe: [RigaComplicazione]
    var nota: String?
    /** Per le complicazioni piccole: la linea del primo passaggio e "3'". */
    var linea: String?
    var tra: String?
    var colore: Int64?
    /** "64 tra 3' · Piazza Venezia", per la complicazione su una riga. */
    var inline: String?
}

struct RigaComplicazione: Codable, Hashable {
    var linea: String
    var colore: Int64
    var passaggi: String
    var tempoReale: Bool
}

enum Gruppo {
    /**
     * Il gruppo condiviso tra l'app per l'orologio e le complicazioni. AltStore
     * cambia il nome del gruppo e lo scrive in ALTAppGroups.
     */
    static var nome: String {
        (Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String])?.first ?? "group.it.palinaroma"
    }

    static var memoria: UserDefaults { UserDefaults(suiteName: nome) ?? .standard }

    static let chiave = "complicazioni"

    static func leggi() -> VociComplicazioni? {
        guard let testo = memoria.string(forKey: chiave), let dati = testo.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(VociComplicazioni.self, from: dati)
    }
}
