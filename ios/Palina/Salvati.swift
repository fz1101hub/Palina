import Foundation
import UIKit
import WidgetKit
import Motore

/**
 * I tragitti salvati ("Lavoro → Casa"), salvati sull'iPhone come sull'app
 * Android (stesso JSON, dal motore), con le prossime partenze per "esci tra".
 */
@MainActor
final class Salvati: ObservableObject {
    @Published private(set) var elenco: [TragittoSalvato] = []
    @Published private(set) var partenze: [String: PartenzeTragitto] = [:]
    private let memoria = UserDefaults.standard

    init() {
        elenco = CodificaTragittiSalvati.shared.leggi(json: json)
    }

    var json: String { memoria.string(forKey: "tragitti_salvati") ?? "" }

    func salvato(da: Luogo?, a: Luogo?) -> TragittoSalvato? {
        TragittiSalvatiKt.tragittoGiaSalvato(elenco: elenco, partenza: da, arrivo: a)
    }

    func salva(da: Luogo, a: Luogo, nome: String) {
        let id = "t" + String(Int64(Date().timeIntervalSince1970 * 1000), radix: 36)
        scrivi(TragittiSalvatiKt.aggiungiTragittoSalvato(elenco: elenco, partenza: da, arrivo: a, nome: nome, id: id))
    }

    func rinomina(_ id: String, _ nome: String) {
        scrivi(TragittiSalvatiKt.rinominaTragittoSalvato(elenco: elenco, id: id, nome: nome))
    }

    func rimuovi(_ id: String) {
        scrivi(TragittiSalvatiKt.rimuoviTragittoSalvato(elenco: elenco, id: id))
        partenze[id] = nil
    }

    private func scrivi(_ nuovi: [TragittoSalvato]) {
        elenco = nuovi
        memoria.set(CodificaTragittiSalvati.shared.scrivi(elenco: nuovi), forKey: "tragitti_salvati")
        Scorciatoie.aggiorna(salvati: nuovi)
        DatiWidgetIphone.condiviso.chiedi(forza: true)
    }

    /** Le partenze del tragitto [t]: si richiedono solo se vecchie di 2 minuti o finite. */
    func aggiornaPartenze(_ t: TragittoSalvato, posizione: (Double, Double)?, forza: Bool = false) async {
        let adesso = Int64(Date().timeIntervalSince1970 * 1000)
        if !forza, let prima = partenze[t.id], adesso - prima.calcolato < 120_000,
           prima.partenze.contains(where: { $0.partenza > adesso }) { return }
        let punto = posizione.map { Punto(lat: $0.0, lon: $0.1) }
        guard let nuove = try? await TragittiSalvatiKt.partenzeTragittoSalvato(t: t, posizione: punto) else { return }
        // senza rete restano quelle di prima
        if nuove.partenze.isEmpty, let prima = partenze[t.id], prima.partenze.contains(where: { $0.partenza > adesso }) { return }
        partenze[t.id] = nuove
    }

    func carta(_ t: TragittoSalvato, adesso: Date) -> CartaSalvato {
        TragittiSalvatiKt.cartaTragittoSalvato(t: t, partenze: partenze[t.id], adessoMs: Int64(adesso.timeIntervalSince1970 * 1000))
    }
}

/** Le scorciatoie tenendo premuta l'icona (testi dal motore, come su Android). */
@MainActor
enum Scorciatoie {
    static func aggiorna(salvati: [TragittoSalvato]) {
        UIApplication.shared.shortcutItems = WidgetTelefonoKt.scorciatoie(salvati: salvati).map { s in
            let icona: UIApplicationShortcutIcon
            if s.id == "vicina" {
                icona = UIApplicationShortcutIcon(systemImageName: "mappin.and.ellipse")
            } else if s.id == "palina" {
                icona = UIApplicationShortcutIcon(systemImageName: "circle.grid.3x3")
            } else {
                icona = UIApplicationShortcutIcon(systemImageName: "point.topleft.down.to.point.bottomright.curvepath")
            }
            return UIApplicationShortcutItem(type: s.id, localizedTitle: s.titolo, localizedSubtitle: nil, icon: icona)
        }
    }
}

/**
 * I dati dei widget dell'iPhone: li calcola il motore (stesso calcolo di
 * Android), poi le voci minuto per minuto vanno nel gruppo condiviso con
 * l'estensione dei widget.
 */
@MainActor
final class DatiWidgetIphone {
    static let condiviso = DatiWidgetIphone()

    private var motore: MotoreWidgetTelefono?
    private var orario: Orario?
    private var preferiti: Preferiti?
    private var posizione: Posizione?
    private var salvati: Salvati?
    private var inCorso = false
    private var ultimo = Date.distantPast
    private let memoria = UserDefaults.standard

    func prepara(orario: Orario, preferiti: Preferiti, posizione: Posizione, salvati: Salvati) {
        if self.orario !== orario {
            self.orario = orario
            motore = MotoreWidgetTelefono(orario: orario)
        }
        self.preferiti = preferiti
        self.posizione = posizione
        self.salvati = salvati
    }

    /** Ricalcola (non piu' di una volta al minuto, se non [forza]). */
    func chiedi(forza: Bool) {
        Task { await calcola(forza: forza) }
    }

    func calcola(forza: Bool) async {
        guard let motore, let preferiti, !inCorso else { return }
        if !forza, Date().timeIntervalSince(ultimo) < 60 { return }
        inCorso = true
        defer { inCorso = false }
        ultimo = Date()
        let lat = posizione?.lat
        let lon = posizione?.lon
        if let lat, let lon { memoria.set("\(lat),\(lon)", forKey: "posizione_widget") }
        let ricordata = memoria.string(forKey: "posizione_widget")?.split(separator: ",").compactMap { Double($0) }
        let punto: (Double, Double)? = (lat != nil && lon != nil) ? (lat!, lon!) : (ricordata?.count == 2 ? (ricordata![0], ricordata![1]) : nil)
        guard let dati = try? await motore.calcola(
            preferiti: preferiti.fermate, fissate: preferiti.fissate,
            conPosizione: punto != nil, lat: punto?.0 ?? 0, lon: punto?.1 ?? 0,
            salvati: salvati?.json ?? "", prima: memoria.string(forKey: "dati_widget") ?? "", forza: forza,
            nomi: preferiti.nomi
        ) else { return }
        memoria.set(dati, forKey: "dati_widget")
        scriviVoci()
    }

    /** In sottofondo (aggiornamento dei widget): se l'app non e' aperta, si apre l'orario solo per questo. */
    func calcolaInSfondo() async {
        if motore == nil {
            guard let orario = await Task.detached(priority: .utility, operation: { Archivio.orarioPerSfondo() }).value else {
                scriviVoci()
                return
            }
            self.orario = orario
            motore = MotoreWidgetTelefono(orario: orario)
            preferiti = preferiti ?? Preferiti()
            salvati = salvati ?? Salvati()
        }
        await calcola(forza: false)
        scriviVoci()
    }

    /** Le voci per la prossima ora dai dati salvati (anche senza rete), poi WidgetKit le ridisegna. */
    func scriviVoci() {
        guard let dati = memoria.string(forKey: "dati_widget") else { return }
        GruppoWidget.memoria.set(MotoreWidgetKt.vociWidgetTelefono(dati: dati), forKey: GruppoWidget.chiave)
        WidgetCenter.shared.reloadTimelines(ofKind: "palina-tabellone")
        WidgetCenter.shared.reloadTimelines(ofKind: "palina-vicina")
        WidgetCenter.shared.reloadTimelines(ofKind: "palina-tragitto")
    }
}
