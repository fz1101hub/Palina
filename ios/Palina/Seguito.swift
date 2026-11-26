import ActivityKit
import Foundation
import UserNotifications
import Motore

/**
 * "Segui" e "Avvisami" una linea a una fermata, come su Android (stesso
 * calcolo nel motore): una Live Activity sulla schermata di blocco, nella
 * Dynamic Island e nello Smart Stack dell'Apple Watch ("Esci ora", il
 * conto alla rovescia, "Scendi alla prossima"), e gli avvisi all'ora giusta.
 * Mentre Palina e' aperta i tempi si aggiornano ogni 30 secondi; chiusa, il
 * conto alla rovescia continua sull'ultimo tempo letto e gli avvisi restano
 * programmati.
 */
@MainActor
final class Seguito: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published private(set) var stato: StatoSegui?
    /** Da mostrare una volta (nessun passaggio, Live Activity spente...). */
    @Published var messaggio: String?
    /** "Scegli dove scendi" dalla Live Activity: la Home apre la scelta. */
    @Published var apriScendi = false

    private var motore: MotoreSegui?
    private var orario: Orario?
    private var avvisoProgrammato: Int64 = 0
    private var discesaProgrammata: Int64 = 0
    private var ultimoMostrato: SeguiAttributi.ContentState?
    private let memoria = UserDefaults.standard
    private static let idAvviso = "segui-arrivo"
    private static let idDiscesa = "segui-discesa"

    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    func prepara(orario: Orario) {
        guard self.orario !== orario else { return }
        self.orario = orario
        let motore = MotoreSegui(orario: orario)
        self.motore = motore
        // un seguimento di prima (l'app era stata chiusa): si riprende, o si chiude se e' finito
        if let salvato = memoria.string(forKey: "seguito"), let ripreso = motore.riprendi(json: salvato) {
            stato = ripreso
            Task { await mostra(ripreso) }
        } else if stato == nil {
            Task { await chiudi() }
        }
    }

    /** Cosa propone "Avvisami" (con i minuti a piedi, se la posizione si sa). */
    func proposta(fermataId: String, lineaId: String, capolinea: String, quando: String, posizione: (Double, Double)?) async -> PropostaAvviso? {
        guard let motore else { return nil }
        return try? await motore.proposta(
            fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, quando: quando,
            conPosizione: posizione != nil, lat: posizione?.0 ?? 0, lon: posizione?.1 ?? 0
        )
    }

    /** "Segui" dal tabellone: avviso 2 minuti prima, come sull'orologio. */
    func segui(fermataId: String, lineaId: String, capolinea: String) async {
        await avvisami(fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, quando: "", scendi: false, blocco: true, posizione: nil)
    }

    /**
     * "Avvisami": [quando] "uscire", "cinque" o "due" ("" = "Segui"); [scendi]: poi avvisa quando scendere;
     * [blocco]: "Seguilo sulla schermata di blocco" (la Live Activity), altrimenti solo gli avvisi.
     */
    func avvisami(fermataId: String, lineaId: String, capolinea: String, quando: String, scendi: Bool, blocco: Bool, posizione: (Double, Double)?) async {
        guard let motore else { return }
        // nelle prove sul simulatore nessuno risponderebbe alla domanda
        let consentite = Prova.segui ? true
            : (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        guard let nuovo = try? await motore.avvisami(
            fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, quando: quando,
            conPosizione: posizione != nil, lat: posizione?.0 ?? 0, lon: posizione?.1 ?? 0, scendi: scendi, blocco: blocco
        ) else {
            messaggio = tx("Non trovo un prossimo passaggio da seguire per questa linea.")
            return
        }
        await chiudi()
        stato = nuovo
        await mostra(nuovo)
        if blocco, !ActivityAuthorizationInfo().areActivitiesEnabled {
            messaggio = tx("Le Live Activity di Palina sono spente: il conto alla rovescia si vede solo nell'app. Puoi attivarle nelle Impostazioni dell'iPhone.")
        } else if !consentite {
            messaggio = tx("Le notifiche di Palina sono spente: niente avvisi. Puoi attivarle nelle Impostazioni dell'iPhone.")
        }
    }

    func smetti() {
        motore?.smetti()
        stato = nil
        Task { await chiudi() }
    }

    func stoUscendo() {
        guard let nuovo = motore?.stoUscendo() else { return }
        stato = nuovo
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.idAvviso])
        Task { await mostra(nuovo) }
    }

    func prendiSuccessivo() async {
        guard let motore, let nuovo = try? await motore.prendiSuccessivo() else { return }
        stato = nuovo
        avvisoProgrammato = 0
        await mostra(nuovo)
    }

    func fermateDiscesa() async -> [FermataDiscesa] {
        (try? await motore?.fermateDiscesa()) ?? []
    }

    func scendiA(_ fermataId: String) async {
        guard let motore, let nuovo = try? await motore.scendiA(fermataId: fermataId) else { return }
        stato = nuovo
        discesaProgrammata = 0
        await mostra(nuovo)
    }

    /** I pulsanti della Live Activity aprono Palina con palina://segui/... */
    func apri(_ url: URL) {
        guard let azione = AzioneSegui.da(url) else { return }
        switch azione {
        case .uscito: stoUscendo()
        case .successivo: Task { await prendiSuccessivo() }
        case .scendi: apriScendi = true
        case .fine: smetti()
        case .apri: break
        }
    }

    /** Mentre l'app e' aperta: i tempi ogni 30 secondi (il conto alla rovescia va da solo). */
    func ciclo() async {
        var ultimaRichiesta = Date.distantPast
        while !Task.isCancelled {
            if stato != nil, let motore {
                let nuovo: StatoSegui?
                if Date().timeIntervalSince(ultimaRichiesta) >= 30 {
                    ultimaRichiesta = Date()
                    nuovo = (try? await motore.aggiorna()) ?? motore.stato()
                } else {
                    nuovo = motore.stato()
                }
                if let nuovo {
                    stato = nuovo
                    await mostra(nuovo)
                } else {
                    // passato da un minuto, arrivati, o si segue da due ore: finito
                    stato = nil
                    await chiudi()
                }
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }

    // MARK: - Live Activity e avvisi

    private func data(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    private func mostra(_ s: StatoSegui) async {
        memoria.set(s.json, forKey: "seguito")
        let contenuto = SeguiAttributi.ContentState(
            arrivo: s.contoMs > 0 ? data(s.contoMs) : data(s.arrivoMs),
            tempoReale: s.tempoReale,
            fonte: s.fonte,
            titolo: s.titoloScheda,
            testo: s.testoScheda,
            dettaglio: s.dettaglio,
            inizio: s.fineMs > s.inizioMs ? data(s.inizioMs) : nil,
            fine: s.fineMs > s.inizioMs ? data(s.fineMs) : nil,
            fase: s.fase,
            mostraSuccessivo: s.mostraSuccessivo,
            mostraUscito: s.mostraUscito,
            mostraScegli: s.mostraScegli,
            mostraFine: s.mostraFine,
            fermateMancanti: Int(s.fermateMancanti),
            daTesto: s.daTesto,
            aTesto: s.aTesto,
            verso: s.verso,
            arrivi: s.arrivi
        )
        // "Seguilo sulla schermata di blocco" spento: solo gli avvisi, niente Live Activity
        if !s.blocco {
            for attivita in Activity<SeguiAttributi>.activities { await attivita.end(nil, dismissalPolicy: .immediate) }
            ultimoMostrato = contenuto
        }
        // si ridisegna solo quando cambia qualcosa (il conto alla rovescia va da solo)
        if s.blocco, contenuto != ultimoMostrato || Activity<SeguiAttributi>.activities.isEmpty {
            ultimoMostrato = contenuto
            let scade = (s.fase == "scegli" ? Date().addingTimeInterval(15 * 60) : contenuto.arrivo.addingTimeInterval(90))
            let attivita = ActivityContent(state: contenuto, staleDate: scade)
            if let aperta = Activity<SeguiAttributi>.activities.first(where: { $0.attributes.fermata == s.fermata && $0.attributes.linea == s.linea }) {
                await aperta.update(attivita)
            } else if ActivityAuthorizationInfo().areActivitiesEnabled {
                for vecchia in Activity<SeguiAttributi>.activities { await vecchia.end(nil, dismissalPolicy: .immediate) }
                let attributi = SeguiAttributi(
                    linea: s.linea, capolinea: s.capolinea, fermata: s.fermata, colore: s.colore,
                    testoOra: tx("ora§adesso"), testoScaduto: tx("tempi da aggiornare: apri Palina"),
                    intestazione: s.intestazione,
                    testoUscito: tx("Sto uscendo"), testoSuccessivo: tx("Prendo il successivo"),
                    testoScegli: tx("Scegli dove scendi"), testoFine: tx("Fine viaggio"),
                    testoApri: tx("Apri"), testoMin: "min"
                )
                _ = try? Activity.request(attributes: attributi, content: attivita, pushType: nil)
            }
        }
        programma(id: Self.idAvviso, alle: s.avvisoAlleMs, titolo: s.avvisoTitolo, testo: s.avvisoTesto, ultimo: &avvisoProgrammato)
        if s.discesaAlleMs > 0 {
            programma(id: Self.idDiscesa, alle: s.discesaAlleMs, titolo: s.discesaTitolo, testo: s.discesaTesto, ultimo: &discesaProgrammata)
        }
    }

    /** Un avviso all'ora [alle]; si sposta se il mezzo ritarda o anticipa di almeno 20 secondi. */
    private func programma(id: String, alle: Int64, titolo: String, testo: String, ultimo: inout Int64) {
        let tra = Double(alle) / 1000 - Date().timeIntervalSince1970
        guard tra > 1, abs(alle - ultimo) >= 20_000 else { return }
        ultimo = alle
        let contenuto = UNMutableNotificationContent()
        contenuto.title = titolo
        contenuto.body = testo
        contenuto.sound = .default
        contenuto.interruptionLevel = .timeSensitive
        let richiesta = UNNotificationRequest(
            identifier: id,
            content: contenuto,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: tra, repeats: false)
        )
        UNUserNotificationCenter.current().add(richiesta)
    }

    private func chiudi() async {
        memoria.removeObject(forKey: "seguito")
        ultimoMostrato = nil
        avvisoProgrammato = 0
        discesaProgrammata = 0
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.idAvviso, Self.idDiscesa])
        for attivita in Activity<SeguiAttributi>.activities {
            await attivita.end(nil, dismissalPolicy: .immediate)
        }
    }

    // l'avviso si vede anche con Palina aperta
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
