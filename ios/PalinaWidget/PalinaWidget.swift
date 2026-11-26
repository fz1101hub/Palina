import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/**
 * I widget dell'iPhone: la Live Activity di "Segui" e "Avvisami" e i widget
 * della schermata Home e di blocco (la tua fermata, la piu' vicina, "esci
 * tra"). I contenuti vengono dal motore, tramite l'app.
 */
@main
struct PalinaWidgets: WidgetBundle {
    var body: some Widget {
        SeguiLiveActivity()
        WidgetLaTuaFermata()
        WidgetPiuVicina()
        WidgetEsciTra()
    }
}

// MARK: - Live Activity

/**
 * "Segui" e "Avvisami": il conto alla rovescia fino al passaggio ("Esci ora",
 * poi a bordo "Scendi alla prossima"), sulla schermata di blocco, nella
 * Dynamic Island e (con watchOS 11) nello Smart Stack dell'Apple Watch. Si
 * aggiorna da solo: l'app sposta l'arrivo col tempo reale.
 */
struct SeguiLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SeguiAttributi.self) { contesto in
            SchermataBlocco(attributi: contesto.attributes, stato: contesto.state, scaduto: contesto.isStale)
                .activityBackgroundTint(Color(hex: 0x0E0B0B))
                .activitySystemActionForegroundColor(.oroPalina)
        } dynamicIsland: { contesto in
            let a = contesto.attributes
            let s = contesto.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Linea(nome: a.linea, colore: a.colore, grande: true)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Conto(arrivo: s.arrivo, tempoReale: s.tempoReale, testoOra: a.testoOra)
                        .font(.title2.bold())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(s.titolo ?? ("→ " + a.capolinea)).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(s.testo ?? (a.fermata + " · " + s.fonte))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            } compactLeading: {
                Linea(nome: a.linea, colore: a.colore, grande: false)
            } compactTrailing: {
                Conto(arrivo: s.arrivo, tempoReale: s.tempoReale, testoOra: a.testoOra)
                    .font(.caption.bold())
                    .frame(maxWidth: 46)
            } minimal: {
                Conto(arrivo: s.arrivo, tempoReale: s.tempoReale, testoOra: a.testoOra)
                    .font(.caption2.bold())
            }
            .keylineTint(Color(argb: a.colore))
        }
    }
}

/** Il conto alla rovescia "4:32" (in oro se in tempo reale), o "ora" quando e' arrivato. */
private struct Conto: View {
    let arrivo: Date
    let tempoReale: Bool
    /** "ora" nella lingua dell'app (lo scrive l'app, il widget non ha il motore). */
    let testoOra: String

    var body: some View {
        Group {
            if arrivo > Date() {
                Text(timerInterval: Date()...arrivo, countsDown: true)
            } else {
                Text(testoOra)
            }
        }
        .monospacedDigit()
        .multilineTextAlignment(.trailing)
        .foregroundStyle(tempoReale ? Color.oroPalina : Color.white)
    }
}

private struct Linea: View {
    let nome: String
    let colore: Int64
    let grande: Bool

    var body: some View {
        Text(nome)
            .font(cifreDoto(grande ? 17 : 12))
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, grande ? 8 : 4)
            .padding(.vertical, grande ? 4 : 1)
            .background(Color(argb: colore), in: RoundedRectangle(cornerRadius: 5))
    }
}

/** I colori della tavola "Blocco" del disegno. */
private enum Blocco {
    static let fondo = Color(hex: 0x0E0B0B)
    static let bordo = Color(hex: 0x3A2E1A)
    static let nero = Color(hex: 0x050404)
    static let riga = Color(hex: 0x2B2323)
    static let tenue = Color(hex: 0xB9B1AB)
    static let chiaro = Color(hex: 0xE3D9CF)
    static let porpora = Color(hex: 0x862633)
    static let pulsante = Color(hex: 0x2A2424)
    static let inchiostro = Color(hex: 0x1C1A1A)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/** Le cifre a punti delle paline (Doto), anche nei widget. */
func cifreDoto(_ grandezza: CGFloat) -> Font { .custom("Doto-Black", size: grandezza) }

/** Un pulsante della Live Activity (come nel disegno): apre Palina con l'azione (palina://segui/...). */
private struct Pulsante: View {
    let testo: String
    let azione: AzioneSegui
    let pieno: Bool

    var body: some View {
        Link(destination: azione.url) {
            Text(testo)
                .font(.system(size: 14, weight: pieno ? .bold : .medium))
                .foregroundStyle(pieno ? Blocco.inchiostro : Color(hex: 0xF4EFEA))
                .lineLimit(1)
                .padding(.horizontal, pieno ? 16 : 14)
                .frame(height: 40)
                .background(pieno ? Color.oroPalina : Blocco.pulsante, in: Capsule())
        }
    }
}

/** La barra del disegno: avanza col tempo, i pallini delle fermate che mancano, il cerchio d'arrivo. */
private struct Barra: View {
    let inizio: Date
    let fine: Date
    let fermate: Int

    var body: some View {
        ProgressView(timerInterval: inizio...fine, countsDown: false) {
            EmptyView()
        } currentValueLabel: {
            EmptyView()
        }
        .progressViewStyle(.linear)
        .tint(.oroPalina)
        .padding(.trailing, 14)
        .overlay(alignment: .leading) {
            // le fermate che mancano, tra qui e l'arrivo
            if fermate > 0 {
                GeometryReader { g in
                    let n = min(fermate, 6)
                    ForEach(1...n, id: \.self) { i in
                        Circle()
                            .fill(Blocco.fondo)
                            .overlay(Circle().stroke(Color(hex: 0x8E8580), lineWidth: 2))
                            .frame(width: 9, height: 9)
                            .position(x: (g.size.width - 14) * CGFloat(i) / CGFloat(n + 1), y: g.size.height / 2)
                    }
                }
            }
        }
        .overlay(alignment: .trailing) {
            Circle()
                .strokeBorder(Color.oroPalina, lineWidth: 5)
                .background(Circle().fill(Blocco.fondo))
                .frame(width: 20, height: 20)
        }
        .frame(height: 28)
    }
}

/** Il numero della linea (Doto su porpora o sul colore della linea). */
private struct Numero: View {
    let nome: String
    let colore: Int64
    let grandezza: CGFloat
    let altezza: CGFloat

    var body: some View {
        Text(nome)
            .font(cifreDoto(grandezza))
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 5)
            .frame(minWidth: altezza * 1.4, minHeight: altezza, maxHeight: altezza)
            .background(Color(argb: colore), in: RoundedRectangle(cornerRadius: 7))
    }
}

/**
 * Sulla schermata di blocco, come la tavola "Blocco" del disegno: "Esci ora"
 * con il conto alla rovescia nel riquadro, la barra con le fermate e i
 * pulsanti; a bordo la fascia d'oro "Scendi alla prossima".
 */
private struct SchermataBlocco: View {
    let attributi: SeguiAttributi
    let stato: SeguiAttributi.ContentState
    let scaduto: Bool

    private var aBordo: Bool { stato.fase == "bordo" || stato.fase == "scendi" }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if stato.fase == "scendi" {
                HStack(spacing: 12) {
                    Image(systemName: "iphone.radiowaves.left.and.right")
                        .font(.system(size: 24, weight: .semibold))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(stato.titolo ?? "").font(.system(size: 22, weight: .black)).lineLimit(1).minimumScaleFactor(0.7)
                        Text(stato.testo ?? "").font(.system(size: 15, weight: .medium)).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .foregroundStyle(Blocco.inchiostro)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .background(Color.oroPalina)
            }
            VStack(alignment: .leading, spacing: 10) {
                if aBordo {
                    bordo
                } else {
                    attesa
                }
                if !pulsanti.isEmpty {
                    HStack(spacing: 8) {
                        Spacer(minLength: 0)
                        ForEach(pulsanti) { p in Pulsante(testo: p.testo, azione: p.azione, pieno: p.pieno) }
                    }
                    .padding(.top, 2)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, stato.fase == "scendi" ? 14 : 16)
            .padding(.bottom, 12)
        }
        .background(Blocco.fondo)
        .overlay(ContainerRelativeShape().strokeBorder(Blocco.bordo, lineWidth: 1))
    }

    /** Prima di salire: intestazione, "Esci ora" col testo, il riquadro dei minuti, la barra. */
    @ViewBuilder
    private var attesa: some View {
        HStack(spacing: 8) {
            Image(systemName: "bus.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Color.oroPalina)
                .frame(width: 22, height: 22)
                .background(Blocco.porpora, in: RoundedRectangle(cornerRadius: 6))
            Text(attributi.intestazione.isEmpty ? attributi.fermata : attributi.intestazione)
                .font(.system(size: 13))
                .foregroundStyle(Blocco.tenue)
                .lineLimit(1)
        }
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(stato.titolo ?? ("→ " + attributi.capolinea))
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(stato.fase == "attesa" || stato.fase == "arrivo" || stato.fase == "scegli" ? Color.white : Color.oroPalina)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(stato.testo ?? (attributi.fermata + " · " + stato.fonte))
                    .font(.system(size: 15))
                    .foregroundStyle(Blocco.chiaro)
                    .lineLimit(3)
            }
            Spacer(minLength: 0)
            if stato.fase != "scegli" {
                VStack(spacing: 0) {
                    Conto(arrivo: stato.arrivo, tempoReale: stato.tempoReale, testoOra: attributi.testoOra)
                        .font(cifreDoto(30))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(stato.arrivo, style: .time)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.oroPalina)
                }
                .frame(minWidth: 64)
                .padding(.vertical, 8)
                .padding(.horizontal, 6)
                .background(Blocco.nero, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Blocco.riga, lineWidth: 1))
            }
        }
        if let inizio = stato.inizio, let fine = stato.fine, fine > inizio {
            Barra(inizio: inizio, fine: fine, fermate: stato.fermateMancanti ?? 0)
        }
        HStack {
            Text(scaduto ? attributi.testoScaduto : (stato.dettaglio ?? stato.fonte))
                .foregroundStyle(stato.tempoReale && !scaduto ? Color.oroPalina : Blocco.tenue)
            Spacer(minLength: 8)
            Text(attributi.fermata).foregroundStyle(Blocco.tenue)
        }
        .font(.system(size: 12.5))
        .lineLimit(1)
    }

    /** A bordo: la linea e "Verso ...", la barra fino a dove scendi, le due fermate con le ore. */
    @ViewBuilder
    private var bordo: some View {
        HStack(spacing: 10) {
            Numero(nome: attributi.linea, colore: attributi.colore, grandezza: 17, altezza: 28)
            Text(stato.verso ?? attributi.capolinea).font(.system(size: 16, weight: .bold)).foregroundStyle(.white).lineLimit(1)
            Spacer(minLength: 4)
            Text(stato.arrivi ?? "").font(.system(size: 14)).foregroundStyle(Blocco.tenue)
        }
        if stato.fase == "bordo", let testo = stato.testo {
            Text(testo).font(.system(size: 13.5)).foregroundStyle(Blocco.chiaro).lineLimit(2)
        }
        if let inizio = stato.inizio, let fine = stato.fine, fine > inizio {
            Barra(inizio: inizio, fine: fine, fermate: 0)
        }
        HStack {
            Text(stato.daTesto ?? attributi.fermata)
            Spacer(minLength: 8)
            Text(stato.aTesto ?? "")
        }
        .font(.system(size: 12.5))
        .foregroundStyle(Blocco.tenue)
        .lineLimit(1)
        if scaduto {
            Text(attributi.testoScaduto).font(.system(size: 12.5)).foregroundStyle(Blocco.tenue)
        }
    }

    private struct DatiPulsante: Identifiable {
        let testo: String
        let azione: AzioneSegui
        let pieno: Bool
        var id: String { azione.rawValue }
    }

    /** I pulsanti del disegno: "Prendo il successivo" e "Sto uscendo"; a bordo "Fine viaggio" e "Apri". */
    private var pulsanti: [DatiPulsante] {
        var elenco: [DatiPulsante] = []
        if stato.mostraSuccessivo == true { elenco.append(DatiPulsante(testo: attributi.testoSuccessivo, azione: .successivo, pieno: false)) }
        if stato.mostraUscito == true { elenco.append(DatiPulsante(testo: attributi.testoUscito, azione: .uscito, pieno: true)) }
        if stato.mostraScegli == true { elenco.append(DatiPulsante(testo: attributi.testoScegli, azione: .scendi, pieno: true)) }
        if stato.mostraFine == true { elenco.append(DatiPulsante(testo: attributi.testoFine, azione: .fine, pieno: false)) }
        if aBordo, !attributi.testoApri.isEmpty { elenco.append(DatiPulsante(testo: attributi.testoApri, azione: .apri, pieno: true)) }
        return elenco
    }
}

// MARK: - Widget della schermata Home e di blocco

struct VoceWidget: TimelineEntry {
    let date: Date
    let voce: VoceWidgetTelefono?
    let vuoto: TestiVuoti?
    /** La fermata o il tragitto scelto nel widget (nil: automatica). */
    var scelta: String? = nil
}

// MARK: - La scelta della fermata e del tragitto (tra quelli che l'app ha preparato)

struct FermataWidget: AppEntity {
    let id: String
    let nome: String
    let sotto: String

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Fermata")
    static var defaultQuery = DomandaFermate()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(nome)", subtitle: "\(sotto)") }
}

struct DomandaFermate: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [FermataWidget] {
        Self.tutte().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [FermataWidget] { Self.tutte() }

    static func tutte() -> [FermataWidget] {
        (GruppoWidget.leggi()?.elenco?.fermate ?? []).map { FermataWidget(id: $0.id, nome: $0.nome, sotto: $0.sotto ?? "") }
    }
}

struct TragittoWidget: AppEntity {
    let id: String
    let nome: String

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Tragitto")
    static var defaultQuery = DomandaTragitti()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(nome)") }
}

struct DomandaTragitti: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [TragittoWidget] {
        Self.tutti().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [TragittoWidget] { Self.tutti() }

    static func tutti() -> [TragittoWidget] {
        (GruppoWidget.leggi()?.elenco?.tragitti ?? []).map { TragittoWidget(id: $0.id, nome: $0.nome) }
    }
}

struct SceltaFermata: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Scegli la fermata"
    static var description = IntentDescription("Automatica: la preferita a cui sei vicino")

    @Parameter(title: "Fermata")
    var fermata: FermataWidget?
}

struct SceltaTragitto: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Scegli il tragitto"
    static var description = IntentDescription("Automatico: il tragitto che parte più vicino a dove sei")

    @Parameter(title: "Tragitto")
    var tragitto: TragittoWidget?
}

/** Il widget della fermata: quella scelta o, se nessuna, la preferita a cui sei vicino. */
struct FornitoreFermata: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> VoceWidget { FornitoreTelefono().placeholder(in: context) }

    func snapshot(for configuration: SceltaFermata, in context: Context) async -> VoceWidget {
        var voce = FornitoreTelefono.istantanea(in: context)
        voce.scelta = configuration.fermata?.id
        return voce
    }

    func timeline(for configuration: SceltaFermata, in context: Context) async -> Timeline<VoceWidget> {
        FornitoreTelefono.linea(scelta: configuration.fermata?.id)
    }
}

/** Il widget "esci tra": il tragitto scelto o quello che parte piu' vicino. */
struct FornitoreTragitto: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> VoceWidget { FornitoreTelefono().placeholder(in: context) }

    func snapshot(for configuration: SceltaTragitto, in context: Context) async -> VoceWidget {
        var voce = FornitoreTelefono.istantanea(in: context)
        voce.scelta = configuration.tragitto?.id
        return voce
    }

    func timeline(for configuration: SceltaTragitto, in context: Context) async -> Timeline<VoceWidget> {
        FornitoreTelefono.linea(scelta: configuration.tragitto?.id)
    }
}

/** Le voci preparate dall'app, dal minuto attuale in poi; finite (un'ora) resta "Apri Palina". */
struct FornitoreTelefono: TimelineProvider {
    func placeholder(in context: Context) -> VoceWidget {
        VoceWidget(date: Date(), voce: Self.esempio, vuoto: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (VoceWidget) -> Void) {
        completion(Self.istantanea(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VoceWidget>) -> Void) {
        completion(Self.linea(scelta: nil))
    }

    static func istantanea(in context: Context) -> VoceWidget {
        if context.isPreview && GruppoWidget.leggi() == nil {
            return VoceWidget(date: Date(), voce: esempio, vuoto: nil)
        }
        return voci(scelta: nil).first ?? VoceWidget(date: Date(), voce: nil, vuoto: GruppoWidget.leggi()?.vuoto)
    }

    /** Le voci di un'ora; finite resta "Apri Palina per aggiornare". */
    static func linea(scelta: String?) -> Timeline<VoceWidget> {
        let elenco = voci(scelta: scelta)
        let vuoto = GruppoWidget.leggi()?.vuoto
        let fine = VoceWidget(date: (elenco.last?.date ?? Date()).addingTimeInterval(60), voce: nil, vuoto: vuoto, scelta: scelta)
        return Timeline(entries: elenco.isEmpty ? [VoceWidget(date: Date(), voce: nil, vuoto: vuoto, scelta: scelta)] : elenco + [fine], policy: .never)
    }

    private static func voci(scelta: String?) -> [VoceWidget] {
        let adesso = Date()
        let letto = GruppoWidget.leggi()
        let tutte = (letto?.voci ?? []).map {
            VoceWidget(date: Date(timeIntervalSince1970: Double($0.alle) / 1000), voce: $0, vuoto: letto?.vuoto, scelta: scelta)
        }
        let passate = tutte.filter { $0.date <= adesso }
        return (passate.last.map { [$0] } ?? []) + tutte.filter { $0.date > adesso }
    }

    static let esempio = VoceWidgetTelefono(
        alle: 0,
        tabellone: WidgetTabelloneVoce(
            etichetta: "LAVORO", nome: "P.za Venezia", fermataId: "", ora: "18:34",
            righe: [
                RigaWidgetVoce(linea: "64", colore: 0xFF862633, capolinea: "Stazione S. Pietro", poi: "poi 9", tra: "1′", tempoReale: true),
                RigaWidgetVoce(linea: "40", colore: 0xFF862633, capolinea: "P.za Pia", poi: "poi 13", tra: "4′", tempoReale: true),
            ],
            nota: ""
        ),
        vicina: WidgetVicinaVoce(
            nome: "Plebiscito", fermataId: "", distanza: "121 m",
            righe: [
                RigaWidgetVoce(linea: "87", colore: 0xFF862633, capolinea: "", poi: "", tra: "5′", tempoReale: true),
                RigaWidgetVoce(linea: "63", colore: 0xFF862633, capolinea: "", poi: "", tra: "6′", tempoReale: true),
            ],
            nota: nil, etichetta: "PIÙ VICINA"
        ),
        tragitto: WidgetTragittoVoce(
            id: "", nome: "Lavoro → Casa", esci: "ESCI TRA", numero: "4", unita: "min",
            sotto: "64 alle 18:41 · a Casa 19:05", linea: "64", colore: 0xFF862633, poi: nil,
            viaggio: "64 → Metro B · 1 cambio · 24 min"
        ),
        fermate: nil, tragitti: nil
    )
}

/** I colori dei widget (come nel disegno): tabellone nero e oro col tema scuro, carta e ambra col chiaro. */
private struct ColoriWidget {
    let scuro: Bool
    var fondo: Color { scuro ? Color(hex: 0x050404) : .white }
    var bordo: Color { scuro ? Color(hex: 0x2B2323) : Color(hex: 0xDDD4C9) }
    var cornice: Color { scuro ? Color(hex: 0x120E0E) : Color(hex: 0xEFE9E1) }
    var trattoTestata: Color { scuro ? Color(hex: 0x3A2E1A) : Color(hex: 0xE3D2AE) }
    var trattoRiga: Color { scuro ? Color(hex: 0x2B2323) : Color(hex: 0xDDD4C9) }
    var caldo: Color { scuro ? Color(hex: 0x2A1F12) : Color(hex: 0xFBEFD3) }
    var caldoSotto: Color { scuro ? Color(hex: 0xE3D9CF) : Color(hex: 0x3A332E) }
    var accento: Color { scuro ? .oroPalina : Color(hex: 0x8A5A00) }
    var testo: Color { scuro ? Color(hex: 0xF4EFEA) : Color(hex: 0x1C1715) }
    var tenue: Color { scuro ? Color(hex: 0xB9B1AB) : Color(hex: 0x5F5751) }
}

/** Il "1px dashed" del disegno. */
private struct Tratteggio: View {
    let colore: Color

    var body: some View {
        GeometryReader { g in
            Path { p in
                p.move(to: CGPoint(x: 0, y: 0.5))
                p.addLine(to: CGPoint(x: g.size.width, y: 0.5))
            }
            .stroke(colore, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
        }
        .frame(height: 1)
    }
}

/** Il fondo del tabellone: bordo di 2 punti, cornice interna di 4, poi il nero (o la carta). */
private struct FondoTabellone: View {
    let colori: ColoriWidget
    let cornice: Bool

    var body: some View {
        ZStack {
            ContainerRelativeShape().fill(cornice ? colori.cornice : colori.fondo)
            if cornice { ContainerRelativeShape().inset(by: 6).fill(colori.fondo) }
            ContainerRelativeShape().strokeBorder(colori.bordo, lineWidth: 2)
        }
    }
}

private struct RigaWidget: View {
    let riga: RigaWidgetVoce
    let colori: ColoriWidget
    /** Il tabellone (badge 42x30, minuti 28) o il widget piccolo (36x26, minuti 24). */
    let grande: Bool

    var body: some View {
        HStack(spacing: 10) {
            Text(riga.linea)
                .font(cifreDoto(grande ? 18 : 15))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, grande ? 5 : 4)
                .frame(minWidth: grande ? 42 : 36, minHeight: grande ? 30 : 26, maxHeight: grande ? 30 : 26)
                .background(Color(argb: riga.colore), in: RoundedRectangle(cornerRadius: grande ? 7 : 6))
            if grande {
                (Text(riga.capolinea).foregroundStyle(colori.testo)
                    + Text(riga.poi.isEmpty ? "" : " · " + riga.poi).foregroundStyle(colori.tenue))
                    .font(.system(size: 14))
                    .lineLimit(1)
            }
            Spacer(minLength: 2)
            Text(riga.tra)
                .font(cifreDoto(grande ? 28 : 24))
                .foregroundStyle(riga.tempoReale ? colori.accento : colori.tenue)
                .lineLimit(1)
                .padding(.trailing, grande ? 8 : 0)
        }
        .frame(height: grande ? 46 : nil)
    }
}

/** "Apri Palina per aggiornare" quando le voci sono finite. */
private func testoVecchio(_ voce: VoceWidget) -> String {
    voce.vuoto?.vecchio ?? NSLocalizedString("Apri Palina per aggiornare", comment: "")
}

struct WidgetLaTuaFermata: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "palina-tabellone", intent: SceltaFermata.self, provider: FornitoreFermata()) { voce in
            VistaTabellone(voce: voce)
        }
        .configurationDisplayName(Text("La tua fermata"))
        .description(Text("Una fermata a scelta, o la preferita a cui sei vicino, con le linee che hai fissato."))
        .supportedFamilies([.systemMedium, .systemLarge, .accessoryRectangular])
    }
}

struct VistaTabellone: View {
    let voce: VoceWidget
    @Environment(\.colorScheme) private var schema
    @Environment(\.widgetFamily) private var famiglia

    var body: some View {
        let colori = ColoriWidget(scuro: schema == .dark)
        // la fermata scelta (se non c'e' piu' tra i dati: "apri Palina") o quella automatica
        let t = voce.scelta.map { voce.voce?.fermate?[$0] } ?? voce.voce?.tabellone
        Group {
            if famiglia == .accessoryRectangular {
                VStack(alignment: .leading, spacing: 1) {
                    Text(t?.nome ?? "Palina").font(.headline).lineLimit(1).widgetAccentable()
                    if let t, !t.righe.isEmpty {
                        ForEach(t.righe.prefix(2), id: \.self) { r in
                            HStack(spacing: 4) {
                                Text(r.linea).font(.caption.bold()).lineLimit(1)
                                Text(r.tra + (r.poi.isEmpty ? "" : " · " + r.poi)).font(.caption).lineLimit(1)
                            }
                        }
                    } else {
                        Text(t?.nota ?? testoVecchio(voce)).font(.caption2).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // come nel disegno: etichetta ("LAVORO"), nome, ora; tratteggi; le righe da 46; "tocca per aggiornare"
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        Text(t?.etichetta ?? "PALINA").font(.system(size: 12, weight: .bold)).tracking(1.4).foregroundStyle(colori.accento).lineLimit(1)
                        Text(t?.nome ?? "Palina Roma").font(.system(size: 13)).foregroundStyle(colori.tenue).lineLimit(1)
                        Spacer(minLength: 2)
                        Text(t?.ora ?? "").font(cifreDoto(17)).foregroundStyle(colori.accento).padding(.trailing, 8)
                    }
                    .padding(.bottom, 6)
                    Tratteggio(colore: colori.trattoTestata)
                    if let t, !t.righe.isEmpty {
                        let righe = Array(t.righe.prefix(famiglia == .systemLarge ? 5 : 2))
                        ForEach(Array(righe.enumerated()), id: \.offset) { i, r in
                            if i > 0 { Tratteggio(colore: colori.trattoRiga) }
                            RigaWidget(riga: r, colori: colori, grande: true)
                        }
                    }
                    Spacer(minLength: 0)
                    Text(voce.voce == nil ? testoVecchio(voce) : (t?.nota ?? testoVecchio(voce)))
                        .font(.system(size: 12))
                        .foregroundStyle(colori.tenue)
                        .lineLimit(2)
                        .padding(.top, 4)
                }
                .widgetURL(URL(string: (t?.fermataId ?? voce.scelta).map { $0.isEmpty ? "palina://home" : "palina://fermata/\($0)" } ?? "palina://home"))
            }
        }
        .containerBackground(for: .widget) {
            if famiglia == .accessoryRectangular { Color.clear } else { FondoTabellone(colori: colori, cornice: true) }
        }
    }
}

struct WidgetPiuVicina: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "palina-vicina", provider: FornitoreTelefono()) { voce in
            VistaVicina(voce: voce)
        }
        .configurationDisplayName(Text("Fermata più vicina"))
        .description(Text("La fermata più vicina e le prossime due linee."))
        .supportedFamilies([.systemSmall, .accessoryRectangular])
    }
}

struct VistaVicina: View {
    let voce: VoceWidget
    @Environment(\.colorScheme) private var schema
    @Environment(\.widgetFamily) private var famiglia

    var body: some View {
        let colori = ColoriWidget(scuro: schema == .dark)
        let v = voce.voce?.vicina
        Group {
            if famiglia == .accessoryRectangular {
                VStack(alignment: .leading, spacing: 1) {
                    Text(v?.nome ?? "Palina").font(.headline).lineLimit(1).widgetAccentable()
                    if let v, !v.righe.isEmpty {
                        Text(v.righe.map { "\($0.linea) \($0.tra)" }.joined(separator: " · ")).font(.caption).lineLimit(1)
                    } else {
                        Text(v?.nota ?? testoVecchio(voce)).font(.caption2).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // come nel disegno: "PIÙ VICINA", il nome con la distanza, due linee con i minuti
                VStack(alignment: .leading, spacing: 0) {
                    Text(v?.etichetta ?? "PALINA").font(.system(size: 11.5, weight: .bold)).tracking(1.2).foregroundStyle(colori.accento)
                    (Text(v?.nome ?? "Palina").font(.system(size: 14, weight: .bold)).foregroundStyle(colori.testo)
                        + Text(v.map { " " + $0.distanza } ?? "").font(.system(size: 12)).foregroundStyle(colori.tenue))
                        .lineLimit(1)
                        .padding(.top, 1)
                    if let v, !v.righe.isEmpty {
                        ForEach(Array(v.righe.prefix(2).enumerated()), id: \.offset) { _, r in
                            Spacer(minLength: 0)
                            RigaWidget(riga: r, colori: colori, grande: false)
                        }
                    } else {
                        Spacer(minLength: 0)
                        Text(v?.nota ?? testoVecchio(voce)).font(.system(size: 12)).foregroundStyle(colori.tenue).lineLimit(3)
                    }
                }
                .widgetURL(URL(string: v.map { $0.fermataId.isEmpty ? "palina://vicina" : "palina://fermata/\($0.fermataId)" } ?? "palina://vicina"))
            }
        }
        .containerBackground(for: .widget) {
            if famiglia == .accessoryRectangular { Color.clear } else { FondoTabellone(colori: colori, cornice: false) }
        }
    }
}

struct WidgetEsciTra: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "palina-tragitto", intent: SceltaTragitto.self, provider: FornitoreTragitto()) { voce in
            VistaEsciTra(voce: voce)
        }
        .configurationDisplayName(Text("Esci tra"))
        .description(Text("Quando uscire per un tragitto salvato: mezzi, cambi e tempo di viaggio."))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

struct VistaEsciTra: View {
    let voce: VoceWidget
    @Environment(\.colorScheme) private var schema
    @Environment(\.widgetFamily) private var famiglia

    var body: some View {
        let colori = ColoriWidget(scuro: schema == .dark)
        let t = voce.scelta.map { voce.voce?.tragitti?[$0] } ?? voce.voce?.tragitto
        let vuoto = voce.vuoto?.tragitto ?? NSLocalizedString("Salva un tragitto in «Come arrivo» per vedere quando uscire.", comment: "")
        Group {
            switch famiglia {
            case .accessoryInline:
                Text(t.map { "\($0.nome): \($0.esci.lowercased()) \($0.numero) \($0.unita)" } ?? "Palina")
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 1) {
                    Text(t?.nome ?? NSLocalizedString("Esci tra", comment: "")).font(.headline).lineLimit(1).widgetAccentable()
                    if let t {
                        Text("\(t.esci) \(t.numero) \(t.unita)").font(.body.bold()).lineLimit(1)
                        Text(t.viaggio ?? t.sotto).font(.caption2).lineLimit(1)
                    } else {
                        Text(voce.voce == nil ? testoVecchio(voce) : vuoto).font(.caption2).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            case .systemMedium:
                HStack(alignment: .top, spacing: 14) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t?.nome ?? NSLocalizedString("Esci tra", comment: "")).font(.system(size: 13, weight: .bold)).foregroundStyle(colori.testo).lineLimit(2)
                        Spacer(minLength: 0)
                        if let t {
                            Text(t.esci).font(.system(size: 12, weight: .bold)).tracking(1).foregroundStyle(colori.accento).lineLimit(2)
                            HStack(alignment: .firstTextBaseline, spacing: 4) {
                                Text(t.numero).font(cifreDoto(52)).foregroundStyle(colori.accento)
                                Text(t.unita).font(.system(size: 14)).foregroundStyle(colori.accento)
                            }
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        if let t {
                            if let viaggio = t.viaggio, !viaggio.isEmpty {
                                Text(viaggio).font(.subheadline.bold()).foregroundStyle(colori.accento).lineLimit(3)
                            }
                            Text(t.sotto).font(.system(size: 12.5)).foregroundStyle(colori.caldoSotto).lineLimit(2)
                            if let poi = t.poi { Text(poi).font(.caption).foregroundStyle(colori.tenue).lineLimit(1) }
                        } else {
                            Text(voce.voce == nil ? testoVecchio(voce) : vuoto).font(.caption).foregroundStyle(colori.tenue).lineLimit(4)
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .widgetURL(URL(string: t.map { "palina://tragitto/\($0.id)" } ?? "palina://tragitto"))
            default:
                VStack(alignment: .leading, spacing: 4) {
                    Text(t?.nome ?? NSLocalizedString("Esci tra", comment: "")).font(.system(size: 13, weight: .bold)).foregroundStyle(colori.testo).lineLimit(1)
                    Spacer(minLength: 0)
                    if let t {
                        Text(t.esci).font(.system(size: 12, weight: .bold)).tracking(1).foregroundStyle(colori.accento).lineLimit(2)
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(t.numero).font(cifreDoto(52)).foregroundStyle(colori.accento).lineLimit(1).minimumScaleFactor(0.7)
                            Text(t.unita).font(.system(size: 14)).foregroundStyle(colori.accento)
                        }
                        if let viaggio = t.viaggio, !viaggio.isEmpty {
                            Text(viaggio).font(.system(size: 12, weight: .bold)).foregroundStyle(colori.accento).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Text(t.sotto).font(.system(size: 12.5)).foregroundStyle(colori.caldoSotto).lineLimit(2)
                    } else {
                        Text(voce.voce == nil ? testoVecchio(voce) : vuoto).font(.caption).foregroundStyle(colori.tenue).lineLimit(4)
                        Spacer(minLength: 0)
                    }
                }
                .widgetURL(URL(string: t.map { "palina://tragitto/\($0.id)" } ?? "palina://tragitto"))
            }
        }
        .containerBackground(for: .widget) { famiglia == .systemSmall || famiglia == .systemMedium ? colori.caldo : Color.clear }
    }
}
