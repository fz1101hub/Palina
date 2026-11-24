import ActivityKit
import Foundation
import UserNotifications
import Motore

/**
 * "Segui" una linea a una fermata, come sull'orologio Wear OS (stesso calcolo
 * nel motore): una Live Activity col conto alla rovescia sulla schermata di
 * blocco, nella Dynamic Island e nello Smart Stack dell'Apple Watch, e un
 * avviso 2 minuti prima del passaggio. Mentre Palina e' aperta i tempi si
 * aggiornano ogni 30 secondi; chiusa, il conto alla rovescia continua
 * sull'ultimo tempo letto.
 */
@MainActor
final class Seguito: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published private(set) var stato: StatoSegui?
    /** Da mostrare una volta (nessun passaggio, Live Activity spente...). */
    @Published var messaggio: String?

    private var motore: MotoreSegui?
    private var orario: Orario?
    private var avvisoProgrammato: Int64 = 0
    private var ultimoMostrato: SeguiAttributi.ContentState?
    private let memoria = UserDefaults.standard
    private static let idAvviso = "segui-arrivo"

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

    /** Comincia a seguire una linea dal tabellone di una fermata. */
    func segui(fermataId: String, lineaId: String, capolinea: String) async {
        guard let motore else { return }
        // nelle prove sul simulatore nessuno risponderebbe alla domanda
        let consentite = Prova.segui ? true
            : (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        guard let nuovo = try? await motore.inizia(fermataId: fermataId, lineaId: lineaId, capolinea: capolinea) else {
            messaggio = "Non trovo un prossimo passaggio da seguire per questa linea."
            return
        }
        await chiudi()
        stato = nuovo
        avvisoProgrammato = 0
        await mostra(nuovo)
        if !ActivityAuthorizationInfo().areActivitiesEnabled {
            messaggio = "Le Live Activity di Palina sono spente: il conto alla rovescia si vede solo nell'app. Puoi attivarle nelle Impostazioni dell'iPhone."
        } else if !consentite {
            messaggio = "Le notifiche di Palina sono spente: niente avviso 2 minuti prima. Puoi attivarle nelle Impostazioni dell'iPhone."
        }
    }

    func smetti() {
        motore?.smetti()
        stato = nil
        Task { await chiudi() }
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
                    // il mezzo e' passato da un minuto (o si segue da due ore): finito
                    stato = nil
                    await chiudi()
                }
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }

    // MARK: - Live Activity e avviso

    private func mostra(_ s: StatoSegui) async {
        memoria.set(s.json, forKey: "seguito")
        let contenuto = SeguiAttributi.ContentState(
            arrivo: Date(timeIntervalSince1970: Double(s.arrivoMs) / 1000),
            tempoReale: s.tempoReale,
            fonte: s.fonte
        )
        // si ridisegna solo quando cambia qualcosa (il conto alla rovescia va da solo)
        if contenuto != ultimoMostrato || Activity<SeguiAttributi>.activities.isEmpty {
            ultimoMostrato = contenuto
            let attivita = ActivityContent(state: contenuto, staleDate: contenuto.arrivo.addingTimeInterval(90))
            if let aperta = Activity<SeguiAttributi>.activities.first(where: { $0.attributes.fermata == s.fermata && $0.attributes.linea == s.linea }) {
                await aperta.update(attivita)
            } else if ActivityAuthorizationInfo().areActivitiesEnabled {
                for vecchia in Activity<SeguiAttributi>.activities { await vecchia.end(nil, dismissalPolicy: .immediate) }
                let attributi = SeguiAttributi(linea: s.linea, capolinea: s.capolinea, fermata: s.fermata, colore: s.colore)
                _ = try? Activity.request(attributes: attributi, content: attivita, pushType: nil)
            }
        }
        programmaAvviso(s)
    }

    /** L'avviso 2 minuti prima; si sposta se il mezzo ritarda o anticipa di almeno 20 secondi. */
    private func programmaAvviso(_ s: StatoSegui) {
        let tra = Double(s.avvisoAlleMs) / 1000 - Date().timeIntervalSince1970
        guard tra > 1, abs(s.avvisoAlleMs - avvisoProgrammato) >= 20_000 else { return }
        avvisoProgrammato = s.avvisoAlleMs
        let contenuto = UNMutableNotificationContent()
        contenuto.title = s.avvisoTitolo
        contenuto.body = s.avvisoTesto
        contenuto.sound = .default
        contenuto.interruptionLevel = .timeSensitive
        let richiesta = UNNotificationRequest(
            identifier: Self.idAvviso,
            content: contenuto,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: tra, repeats: false)
        )
        UNUserNotificationCenter.current().add(richiesta)
    }

    private func chiudi() async {
        memoria.removeObject(forKey: "seguito")
        ultimoMostrato = nil
        avvisoProgrammato = 0
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.idAvviso])
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
