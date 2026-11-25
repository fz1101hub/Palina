import Foundation
import Network
import Motore

/** L'aggiornamento dell'orario, come lo mostrano le impostazioni. */
enum StatoAggiornamento: Equatable {
    case fermo
    case inCorso(String)
    case completato
    case giaAggiornato
    case errore(String)
}

/**
 * L'orario (gli stessi file SQLite dell'app Android): quello incluso nell'app o,
 * se e' piu' nuovo, quello scaricato. Ogni settimana GitHub ne prepara uno nuovo
 * gia' pronto (orario.db e reti.db compressi, nella release "ultima"): l'iPhone
 * lo scarica da solo in Wi-Fi, o dalle impostazioni.
 */
@MainActor
final class Archivio: ObservableObject {
    @Published private(set) var orario: Orario?
    /** Il motore della Home (fermate vicine, ricerca, tempi), pronto con l'orario. */
    @Published private(set) var home: MotoreHome?
    @Published private(set) var errore: String?
    /** Cresce quando si apre un orario nuovo: le schermate ricreano i loro motori. */
    @Published private(set) var versione = 0
    @Published private(set) var aggiornamento: StatoAggiornamento = .fermo
    /** Quando e' stato creato l'orario aperto (millisecondi) e se e' quello incluso nell'app. */
    @Published private(set) var creato: Int64 = 0
    @Published private(set) var incluso = true
    private var creatoReti: Int64 = 0
    /** I MB da scaricare per aggiornare (dall'ultimo controllo). */
    @Published private(set) var megabyte = 8

    static let indirizzo = "https://github.com/fz1101hub/Palina/releases/download/ultima/"
    private let memoria = UserDefaults.standard

    init() {
        apri(controlla: true)
    }

    // MARK: - Apertura

    private struct Scelta: Sendable {
        let roma: String
        let reti: String?
        let creatoRoma: Int64
        let inclusoRoma: Bool
        let creatoReti: Int64
    }

    private func apri(controlla: Bool) {
        let scelta = Self.scegliFile(memoria: memoria)
        Task.detached(priority: .userInitiated) {
            guard let scelta else {
                await MainActor.run { self.errore = tx("Manca l'orario incluso nell'app.") }
                return
            }
            Self.pulisci(tenendo: [scelta.roma, scelta.reti].compactMap { $0 })
            let orario = BaseIosKt.orarioDaFile(roma: scelta.roma, reti: scelta.reti)
            await MainActor.run {
                self.orario = orario
                self.home = MotoreHome(orario: orario)
                self.creato = scelta.creatoRoma
                self.incluso = scelta.inclusoRoma
                self.creatoReti = scelta.creatoReti
                self.versione += 1
                if controlla { Task { await self.controlla(automatico: true) } }
            }
        }
    }

    /** L'orario per i widget quando l'app e' stata avviata in sottofondo (aggiornamento dei widget). */
    nonisolated static func orarioPerSfondo() -> Orario? {
        guard let scelta = scegliFile(memoria: UserDefaults.standard) else { return nil }
        return BaseIosKt.orarioDaFile(roma: scelta.roma, reti: scelta.reti)
    }

    /** I file da aprire: per Roma e per le reti, il piu' nuovo tra incluso e scaricato. */
    nonisolated private static func scegliFile(memoria: UserDefaults) -> Scelta? {
        func incluso(_ nome: String) -> (String, Int64)? {
            guard let db = Bundle.main.path(forResource: nome, ofType: "db", inDirectory: "Risorse") else { return nil }
            let info = Bundle.main.path(forResource: nome == "orario" ? "orario-incluso" : "reti-incluse", ofType: "txt", inDirectory: "Risorse")
            let creato = info.flatMap { try? String(contentsOfFile: $0, encoding: .utf8) }
                .flatMap { Int64($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
            return (db, creato)
        }
        func scaricato(_ nome: String) -> (String, Int64)? {
            let creato = Int64(memoria.integer(forKey: "\(nome)_scaricato"))
            guard creato > 0 else { return nil }
            let percorso = cartella.appendingPathComponent("\(nome)-\(creato).db").path
            return FileManager.default.fileExists(atPath: percorso) ? (percorso, creato) : nil
        }
        func migliore(_ nome: String) -> (String, Int64, Bool)? {
            let a = incluso(nome)
            let b = scaricato(nome)
            if let b, b.1 > (a?.1 ?? 0) { return (b.0, b.1, false) }
            return a.map { ($0.0, $0.1, true) }
        }
        guard let roma = migliore("orario") else { return nil }
        let reti = migliore("reti")
        return Scelta(roma: roma.0, reti: reti?.0, creatoRoma: roma.1, inclusoRoma: roma.2, creatoReti: reti?.1 ?? 0)
    }

    nonisolated private static var cartella: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Orari")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /** Toglie gli orari scaricati che non servono piu'. */
    nonisolated private static func pulisci(tenendo: [String]) {
        let tenuti = Set(tenendo.map { URL(fileURLWithPath: $0).lastPathComponent })
        let nomi = (try? FileManager.default.contentsOfDirectory(atPath: cartella.path)) ?? []
        for nome in nomi where !tenuti.contains(nome) {
            try? FileManager.default.removeItem(at: cartella.appendingPathComponent(nome))
        }
    }

    // MARK: - Aggiornamento

    /** Cosa c'e' nella release: quando sono stati creati orario e reti e quanto pesano compressi. */
    private struct Disponibili: Decodable {
        let orario: Int64
        let reti: Int64?
        let orarioByte: Int64?
        let retiByte: Int64?
    }

    /**
     * Cerca un orario nuovo. [automatico]: al massimo una volta al giorno e solo
     * su una rete senza limiti (Wi-Fi); dalle impostazioni sempre.
     */
    func controlla(automatico: Bool) async {
        if case .inCorso = aggiornamento { return }
        if automatico {
            let ultimo = memoria.double(forKey: "orario_controllato")
            if Date().timeIntervalSince1970 - ultimo < 24 * 3600 { return }
            guard await Self.reteSenzaLimiti() else { return }
        }
        aggiornamento = .inCorso(tx("Controllo se c'è un orario nuovo…"))
        do {
            let (dati, _) = try await URLSession.shared.data(from: URL(string: Self.indirizzo + "orari-iphone.json")!)
            let disponibili = try JSONDecoder().decode(Disponibili.self, from: dati)
            memoria.set(Date().timeIntervalSince1970, forKey: "orario_controllato")
            megabyte = Int(((disponibili.orarioByte ?? 0) + (disponibili.retiByte ?? 0) + 999_999) / 1_000_000)
            if megabyte == 0 { megabyte = 8 }
            var nuovi = false
            // nelle prove si scarica anche se quello incluso e' gia' il piu' recente
            if disponibili.orario > (Prova.aggiorna ? 0 : creato) {
                aggiornamento = .inCorso(tx("Scarico l'orario di Roma…"))
                try await scarica("orario", creato: disponibili.orario)
                nuovi = true
            }
            if let reti = disponibili.reti, reti > (Prova.aggiorna ? 0 : creatoReti) {
                aggiornamento = .inCorso(tx("Scarico le reti Cotral e Trenitalia…"))
                try await scarica("reti", creato: reti)
                nuovi = true
            }
            if nuovi {
                aggiornamento = .completato
                apri(controlla: false)
            } else {
                aggiornamento = automatico ? .fermo : .giaAggiornato
            }
        } catch {
            aggiornamento = automatico ? .fermo : .errore(Self.descrivi(error))
        }
    }

    /** Scarica e decomprime un database, lo prova e lo mette al suo posto. */
    private func scarica(_ nome: String, creato: Int64) async throws {
        let (compresso, risposta) = try await URLSession.shared.data(from: URL(string: Self.indirizzo + "\(nome).db.deflate")!)
        if let http = risposta as? HTTPURLResponse, http.statusCode != 200 {
            throw ErroreOrario.risposta(http.statusCode)
        }
        let destinazione = Self.cartella.appendingPathComponent("\(nome)-\(creato).db")
        try await Task.detached(priority: .utility) {
            // compresso con DEFLATE senza intestazione: e' il formato "zlib" di Apple
            let database = try (compresso as NSData).decompressed(using: .zlib) as Data
            let provvisorio = destinazione.appendingPathExtension("nuovo")
            try database.write(to: provvisorio, options: .atomic)
            // un database che non si apre o quasi vuoto non sostituisce quello buono
            let prova = BaseIosKt.orarioDaFile(roma: provvisorio.path, reti: nil)
            let fermate = prova.numeroFermate
            prova.chiudi()
            guard fermate > 1000 else {
                try? FileManager.default.removeItem(at: provvisorio)
                throw ErroreOrario.incompleto
            }
            try? FileManager.default.removeItem(at: destinazione)
            try FileManager.default.moveItem(at: provvisorio, to: destinazione)
        }.value
        memoria.set(Int(creato), forKey: "\(nome)_scaricato")
    }

    private enum ErroreOrario: Error {
        case risposta(Int)
        case incompleto
    }

    nonisolated private static func descrivi(_ errore: Error) -> String {
        switch errore {
        case ErroreOrario.risposta(let codice): return tx("il server ha risposto {0}.", codice)
        case ErroreOrario.incompleto: return tx("il file scaricato non è completo.")
        case is DecodingError: return tx("le informazioni sull'orario non sono leggibili.")
        default: return tx("controlla la connessione e riprova.")
        }
    }

    /** Wi-Fi o cavo, senza risparmio dati. */
    nonisolated private static func reteSenzaLimiti() async -> Bool {
        await withCheckedContinuation { continuazione in
            let monitor = NWPathMonitor()
            let risposto = Segno()
            // il gestore gira sempre sulla stessa coda: basta un segno per rispondere una volta sola
            monitor.pathUpdateHandler = { percorso in
                guard !risposto.fatto else { return }
                risposto.fatto = true
                monitor.cancel()
                continuazione.resume(returning: percorso.status == .satisfied && !percorso.isExpensive && !percorso.isConstrained)
            }
            monitor.start(queue: DispatchQueue(label: "rete"))
        }
    }
}

/** Un segno da alzare una volta sola, sulla coda del monitor di rete. */
private final class Segno: @unchecked Sendable {
    var fatto = false
}
