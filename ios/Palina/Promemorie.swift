import Foundation
import UserNotifications
import Motore

/**
 * I promemoria ricorrenti ("nei giorni feriali alle 8:10, il 64 a Lavoro"),
 * salvati come su Android (stesso JSON, dal motore). L'iPhone non puo' chiedere
 * i tempi all'ora del promemoria: le notifiche si preparano prima (con l'orario
 * per la prima, quando si sa) e si riprogrammano a ogni apertura.
 */
@MainActor
final class Promemorie: ObservableObject {
    static let condivisi = Promemorie()

    @Published private(set) var elenco: [Promemoria] = []
    private let memoria = UserDefaults.standard
    private var orario: Orario?
    private var preferiti: Preferiti?
    private var salvati: Salvati?
    static let categoria = "promemoria"
    private static let prefisso = "promemoria:"

    init() {
        elenco = CodificaPromemoria.shared.leggi(json: memoria.string(forKey: "promemoria"))
    }

    func prepara(orario: Orario, preferiti: Preferiti, salvati: Salvati) {
        self.orario = orario
        self.preferiti = preferiti
        self.salvati = salvati
    }

    func salva(_ p: Promemoria) {
        scrivi(PromemoriaKt.conPromemoria(elenco: elenco, p: p))
    }

    func togli(_ id: String) {
        scrivi(PromemoriaKt.senzaPromemoria(elenco: elenco, id: id))
    }

    private func scrivi(_ nuovi: [Promemoria]) {
        elenco = nuovi
        memoria.set(CodificaPromemoria.shared.scrivi(elenco: nuovi), forKey: "promemoria")
        Task { await programma() }
    }

    /** Toglie le notifiche dei promemoria gia' programmate e mette quelle nuove. */
    func programma() async {
        guard let orario, let salvati else { return }
        let centro = UNUserNotificationCenter.current()
        let vecchie = await centro.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(Self.prefisso) }
        centro.removePendingNotificationRequests(withIdentifiers: vecchie)
        guard !elenco.isEmpty else { return }
        let notifiche = (try? await PromemoriaKt.notifichePromemoria(
            elenco: elenco, orario: orario, nomi: preferiti?.nomi ?? [:], salvati: salvati.elenco,
            adessoMs: Int64(Date().timeIntervalSince1970 * 1000), massimo: 48
        )) ?? []
        for n in notifiche {
            let contenuto = UNMutableNotificationContent()
            contenuto.title = n.titolo
            contenuto.body = n.testo
            contenuto.sound = .default
            contenuto.interruptionLevel = .timeSensitive
            contenuto.categoryIdentifier = n.avvisami.isEmpty ? "" : Self.categoria
            contenuto.userInfo = ["apri": n.apri, "avvisami": n.avvisami]
            let tra = Double(n.alleMs) / 1000 - Date().timeIntervalSince1970
            guard tra > 1 else { continue }
            let richiesta = UNNotificationRequest(
                identifier: Self.prefisso + n.id,
                content: contenuto,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: tra, repeats: false)
            )
            try? await centro.add(richiesta)
        }
    }

    /** Il pulsante "Avvisami" sulla notifica del promemoria (apre Palina). */
    static func registraCategoria() {
        let avvisami = UNNotificationAction(identifier: "avvisami", title: tx("Avvisami"), options: [.foreground])
        let categoria = UNNotificationCategory(identifier: categoria, actions: [avvisami], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([categoria])
    }

    /** Le voci per le Impostazioni. */
    func voci() -> [VocePromemoria] {
        PromemoriaKt.vociPromemoria(elenco: elenco, orario: orario, nomi: preferiti?.nomi ?? [:], salvati: salvati?.elenco ?? [])
    }

    func titolo(_ p: Promemoria) -> String {
        let nome = preferiti?.nome(p.fermataId) ?? (p.perLinea ? orario?.fermata(id: p.fermataId)?.nome : nil)
        return PromemoriaKt.titoloPromemoria(p: p, nomeFermata: nome, salvati: salvati?.elenco ?? [])
    }
}

/**
 * "Avvisi sulle tue linee": in sottofondo (quando iOS lo permette) e a ogni
 * apertura si leggono gli avvisi di Roma Mobilita'; i nuovi sulle tue linee e
 * fermate, e lo sciopero di domani, diventano una notifica. Come su Android.
 */
@MainActor
enum AvvisiLinee {
    static var attivi: Bool { UserDefaults.standard.object(forKey: "avvisi_linee") as? Bool ?? true }

    static func controlla(orario: Orario, preferiti: Preferiti, salvati: Salvati, promemorie: Promemorie) async {
        guard attivi else { return }
        let memoria = UserDefaults.standard
        let tue = AvvisiPerTeKt.leTueLinee(
            orario: orario, preferiti: preferiti.fermate, fissate: preferiti.fissate,
            partenze: Array(salvati.partenze.values), promemoria: promemorie.elenco
        )
        let visti = CodificaVisti.shared.leggi(json: memoria.string(forKey: "avvisi_visti"))
        guard let esito = try? await AvvisiPerTeKt.controllaAvvisiPerTe(
            orario: orario, tue: tue, visti: visti, adessoMs: Int64(Date().timeIntervalSince1970 * 1000)
        ) else { return }
        memoria.set(CodificaVisti.shared.scrivi(visti: esito.visti), forKey: "avvisi_visti")
        for n in esito.notifiche {
            let contenuto = UNMutableNotificationContent()
            contenuto.title = n.titolo
            contenuto.body = n.testo
            contenuto.sound = .default
            contenuto.userInfo = ["apri": n.apri]
            let richiesta = UNNotificationRequest(identifier: n.id, content: contenuto, trigger: nil)
            try? await UNUserNotificationCenter.current().add(richiesta)
        }
    }
}
