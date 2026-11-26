import AppIntents
import Foundation
import Motore

/*
 * Siri: "Quanto manca a Lavoro con Palina?". La risposta e' quella del motore,
 * la stessa che Android legge ad alta voce; le fermate sono le preferite (con il
 * nome che hai dato), dall'elenco che l'app prepara per i widget.
 */

/** Una fermata preferita per Siri: il nome dato ("Lavoro") o quello della fermata. */
struct FermataPerSiri: AppEntity {
    let id: String
    let nome: String
    let sotto: String

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Fermata")
    static var defaultQuery = DomandaFermatePerSiri()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(nome)", subtitle: "\(sotto)") }
}

struct DomandaFermatePerSiri: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [FermataPerSiri] {
        Self.tutte().filter { identifiers.contains($0.id) }
    }

    func entities(matching testo: String) async throws -> [FermataPerSiri] {
        let cercato = testo.lowercased()
        return Self.tutte().filter { $0.nome.lowercased().contains(cercato) || $0.sotto.lowercased().contains(cercato) }
    }

    func suggestedEntities() async throws -> [FermataPerSiri] { Self.tutte() }

    static func tutte() -> [FermataPerSiri] {
        (GruppoWidget.leggi()?.elenco?.fermate ?? []).map { FermataPerSiri(id: $0.id, nome: $0.nome, sotto: $0.sotto ?? "") }
    }
}

/** "Quanto manca": le prossime linee alla fermata, dette da Siri. */
struct QuantoMancaIntent: AppIntent {
    static var title: LocalizedStringResource = "Quanto manca"
    static var description = IntentDescription("I prossimi passaggi a una fermata preferita, letti da Siri.")
    static var openAppWhenRun = false

    @Parameter(title: "Fermata")
    var fermata: FermataPerSiri?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let fermata else { throw $fermata.needsValueError() }
        LinguaApp.imposta()
        guard let orario = await Task.detached(priority: .userInitiated, operation: { Archivio.orarioPerSfondo() }).value else {
            return .result(dialog: IntentDialog(stringLiteral: tx("Apri Palina per preparare l'orario.")))
        }
        let fissate = Set(UserDefaults.standard.stringArray(forKey: "linee_fissate") ?? [])
        let nomeDato = CodificaNomi.shared.leggi(json: UserDefaults.standard.string(forKey: "nomi_fermate"))[fermata.id]
        let risposta = (try? await VoceKt.rispostaQuantoManca(
            orario: orario, fermataId: fermata.id, nome: nomeDato, fissate: fissate, linea: nil, quante: 3
        )) ?? tx("Non riesco a leggere i tempi d'attesa. Controlla la connessione.")
        return .result(dialog: IntentDialog(stringLiteral: risposta))
    }
}

/** Le frasi per Siri (tradotte in AppShortcuts.strings). */
struct ScorciatoiePalina: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: QuantoMancaIntent(),
            phrases: [
                "Quanto manca a \(\.$fermata) con \(.applicationName)",
                "Quando passa il bus a \(\.$fermata) con \(.applicationName)",
                "Tempi di \(\.$fermata) su \(.applicationName)",
                "Quanto manca con \(.applicationName)",
            ],
            shortTitle: "Quanto manca",
            systemImageName: "bus"
        )
    }
}
