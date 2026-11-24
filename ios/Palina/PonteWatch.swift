import Foundation
import WatchConnectivity
import Motore

/**
 * Il collegamento con l'Apple Watch: quando l'orologio chiede il tabellone,
 * l'iPhone lo calcola con il motore (lo stesso calcolo che il telefono Android
 * fa per Wear OS) e glielo manda in JSON.
 */
@MainActor
final class PonteWatch: NSObject, WCSessionDelegate {
    static let condiviso = PonteWatch()

    private weak var archivio: Archivio?
    private weak var preferiti: Preferiti?
    private weak var posizione: Posizione?

    func avvia(archivio: Archivio, preferiti: Preferiti, posizione: Posizione) {
        self.archivio = archivio
        self.preferiti = preferiti
        self.posizione = posizione
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /** Calcola il tabellone per l'orologio (JSON), o nil se l'orario non e' ancora aperto. */
    func tabellone(richiesta: String) async -> String? {
        guard let orario = archivio?.orario else { return nil }
        let lat = posizione?.lat
        let lon = posizione?.lon
        return try? await MotorePerWatch(orario: orario).tabellone(
            preferiti: preferiti?.fermate ?? [],
            fissate: preferiti?.fissate ?? [],
            conPosizione: lat != nil && lon != nil,
            lat: lat ?? 0,
            lon: lon ?? 0,
            richiesta: richiesta
        )
    }

    func corsa(treno: String) async -> String? {
        guard let orario = archivio?.orario else { return nil }
        return try? await MotorePerWatch(orario: orario).corsa(treno: treno)
    }

    // MARK: WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}
    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        let richiesta = message["richiesta"] as? String
        let treno = message["treno"] as? String
        Task { @MainActor in
            if let treno {
                replyHandler(["corsa": await self.corsa(treno: treno) ?? ""])
            } else {
                let json = await self.tabellone(richiesta: richiesta ?? "")
                replyHandler(["json": json ?? ""])
                // anche come contesto: l'orologio lo trova all'avvio, se era spento
                if let json { try? WCSession.default.updateApplicationContext(["json": json]) }
            }
        }
    }
}
