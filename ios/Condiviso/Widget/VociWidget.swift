import Foundation

/**
 * Le voci dei widget dell'iPhone, minuto per minuto per la prossima ora: le
 * calcola il motore nell'app (vociWidget, lo stesso contenuto dei widget
 * Android) e le lascia nel gruppo condiviso con l'estensione dei widget.
 */
struct VociWidgetTelefono: Codable {
    var voci: [VoceWidgetTelefono]
    var vuoto: TestiVuoti?
    /** Le fermate preferite e i tragitti salvati che si possono scegliere nei widget. */
    var elenco: ElencoWidget?
}

struct ElencoWidget: Codable {
    var fermate: [VoceElenco]
    var tragitti: [VoceElenco]
}

struct VoceElenco: Codable {
    var id: String
    var nome: String
    var sotto: String?
}

struct TestiVuoti: Codable {
    var tragitto: String
    var vecchio: String
}

struct VoceWidgetTelefono: Codable {
    /** Da quando vale, in millisecondi. */
    var alle: Int64
    var tabellone: WidgetTabelloneVoce
    var vicina: WidgetVicinaVoce
    var tragitto: WidgetTragittoVoce?
    /** Il widget per ogni fermata e ogni tragitto che si puo' scegliere (id -> contenuto). */
    var fermate: [String: WidgetTabelloneVoce]?
    var tragitti: [String: WidgetTragittoVoce]?
}

struct RigaWidgetVoce: Codable, Hashable {
    var linea: String
    var colore: Int64
    var capolinea: String
    var poi: String
    var tra: String
    var tempoReale: Bool
}

struct WidgetTabelloneVoce: Codable {
    var etichetta: String
    var nome: String
    var fermataId: String
    var ora: String
    var righe: [RigaWidgetVoce]
    var nota: String
}

struct WidgetVicinaVoce: Codable {
    var nome: String
    var fermataId: String
    var distanza: String
    var righe: [RigaWidgetVoce]
    var nota: String?
    var etichetta: String
}

struct WidgetTragittoVoce: Codable {
    var id: String
    var nome: String
    var esci: String
    var numero: String
    var unita: String
    var sotto: String
    var linea: String
    var colore: Int64
    var poi: String?
    /** "64 → Metro B · 1 cambio · 32 min". */
    var viaggio: String?
}

enum GruppoWidget {
    /**
     * Il gruppo condiviso tra l'app e i widget. AltStore cambia il nome del
     * gruppo e lo scrive in ALTAppGroups.
     */
    static var nome: String {
        (Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String])?.first ?? "group.it.palinaroma"
    }

    static var memoria: UserDefaults { UserDefaults(suiteName: nome) ?? .standard }

    static let chiave = "widget_telefono"

    static func leggi() -> VociWidgetTelefono? {
        guard let testo = memoria.string(forKey: chiave), let dati = testo.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(VociWidgetTelefono.self, from: dati)
    }
}
