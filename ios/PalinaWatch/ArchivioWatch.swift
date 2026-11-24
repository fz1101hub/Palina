import Foundation
import WatchConnectivity
import Motore

/**
 * L'ultimo tabellone ricevuto dall'iPhone (salvato, cosi' si vede subito
 * all'avvio) e le richieste di aggiornamento, al massimo ogni 20 secondi.
 */
@MainActor
final class ArchivioWatch: NSObject, ObservableObject, WCSessionDelegate {
    @Published private(set) var stato: StatoWatch?
    @Published private(set) var irraggiungibile = false

    private let motore = MotoreWatch()
    private let memoria = UserDefaults.standard
    private var ultimaRichiesta = Date.distantPast

    override init() {
        super.init()
        if UserDefaults.standard.bool(forKey: "provaEsempio") {
            // le prove sul simulatore, senza iPhone: il tabellone di esempio del motore
            motore.esempio()
        } else if let salvato = memoria.string(forKey: "tabellone") {
            _ = motore.ricevi(json: salvato)
        }
        stato = motore.stato()
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    func ricalcola() { stato = motore.stato() }

    /** Chiede un tabellone nuovo all'iPhone (non piu' di una volta ogni 20 secondi, se non e' [forza]). */
    func chiedi(forza: Bool) {
        guard !UserDefaults.standard.bool(forKey: "provaEsempio") else { return }
        guard forza || Date().timeIntervalSince(ultimaRichiesta) >= 20 else { return }
        let sessione = WCSession.default
        guard sessione.activationState == .activated else { return }
        guard sessione.isReachable else {
            irraggiungibile = stato?.fermate.isEmpty ?? true
            ricalcola()
            return
        }
        ultimaRichiesta = Date()
        sessione.sendMessage(["richiesta": motore.richiesta()], replyHandler: { risposta in
            let json = risposta["json"] as? String
            Task { @MainActor in self.ricevi(json) }
        }, errorHandler: { _ in
            Task { @MainActor in
                self.irraggiungibile = self.stato?.fermate.isEmpty ?? true
            }
        })
    }

    /** Le fermate di un treno, chieste all'iPhone (nil se non risponde). */
    func corsa(treno: String) async -> StatoCorsa? {
        if UserDefaults.standard.bool(forKey: "provaEsempio") { return motore.esempioCorsa(treno: treno) }
        let sessione = WCSession.default
        guard sessione.activationState == .activated, sessione.isReachable else { return nil }
        let json: String? = await withCheckedContinuation { continua in
            sessione.sendMessage(["treno": treno], replyHandler: { risposta in
                continua.resume(returning: risposta["corsa"] as? String)
            }, errorHandler: { _ in
                continua.resume(returning: nil)
            })
        }
        guard let json, !json.isEmpty else { return nil }
        return motore.corsa(json: json)
    }

    private func ricevi(_ json: String?) {
        guard let json, !json.isEmpty, motore.ricevi(json: json) else { return }
        memoria.set(json, forKey: "tabellone")
        irraggiungibile = false
        stato = motore.stato()
    }

    // MARK: WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let contesto = session.receivedApplicationContext["json"] as? String
        Task { @MainActor in
            self.ricevi(contesto)
            self.chiedi(forza: true)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let json = applicationContext["json"] as? String
        Task { @MainActor in self.ricevi(json) }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in if session.isReachable { self.chiedi(forza: true) } }
    }
}
