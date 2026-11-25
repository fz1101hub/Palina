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
                .activityBackgroundTint(Color.black.opacity(0.82))
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
            .font(grande ? .headline.bold() : .caption.bold())
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, grande ? 8 : 4)
            .padding(.vertical, grande ? 4 : 1)
            .background(Color(argb: colore), in: RoundedRectangle(cornerRadius: 5))
    }
}

/** Un pulsante della Live Activity: apre Palina con l'azione (palina://segui/...). */
private struct Pulsante: View {
    let testo: String
    let azione: AzioneSegui
    let pieno: Bool

    var body: some View {
        Link(destination: azione.url) {
            Text(testo)
                .font(.subheadline.weight(pieno ? .bold : .medium))
                .foregroundStyle(pieno ? Color.black : Color.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(pieno ? Color.oroPalina : Color.white.opacity(0.14), in: Capsule())
        }
    }
}

/** Sulla schermata di blocco: "Esci ora" (o il mezzo in arrivo, o "Scendi alla prossima"), la barra e i pulsanti. */
private struct SchermataBlocco: View {
    let attributi: SeguiAttributi
    let stato: SeguiAttributi.ContentState
    let scaduto: Bool

    var body: some View {
        if let titolo = stato.titolo {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Linea(nome: attributi.linea, colore: attributi.colore, grande: false)
                    Text(attributi.intestazione.isEmpty ? attributi.fermata : attributi.intestazione)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(1)
                }
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(titolo)
                            .font(.title2.bold())
                            .foregroundStyle(stato.fase == "scendi" || stato.fase == "esci" ? Color.oroPalina : .white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        if let testo = stato.testo {
                            Text(testo).font(.subheadline).foregroundStyle(.white.opacity(0.85)).lineLimit(3)
                        }
                    }
                    Spacer(minLength: 4)
                    if stato.fase != "scegli" {
                        VStack(spacing: 0) {
                            Conto(arrivo: stato.arrivo, tempoReale: stato.tempoReale, testoOra: attributi.testoOra)
                                .font(.system(size: 30, weight: .black, design: .rounded))
                            Text(stato.arrivo, style: .time).font(.caption).foregroundStyle(.white.opacity(0.6))
                        }
                    }
                }
                if let inizio = stato.inizio, let fine = stato.fine, fine > inizio {
                    ProgressView(timerInterval: inizio...fine, countsDown: false) {
                        EmptyView()
                    } currentValueLabel: {
                        EmptyView()
                    }
                    .tint(.oroPalina)
                }
                Text(scaduto ? attributi.testoScaduto : (stato.dettaglio ?? stato.fonte))
                    .font(.caption)
                    .foregroundStyle(stato.tempoReale && !scaduto ? Color.oroPalina : .white.opacity(0.6))
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    if stato.mostraSuccessivo == true { Pulsante(testo: attributi.testoSuccessivo, azione: .successivo, pieno: false) }
                    if stato.mostraUscito == true { Pulsante(testo: attributi.testoUscito, azione: .uscito, pieno: true) }
                    if stato.mostraScegli == true { Pulsante(testo: attributi.testoScegli, azione: .scendi, pieno: true) }
                    if stato.mostraFine == true { Pulsante(testo: attributi.testoFine, azione: .fine, pieno: false) }
                }
            }
            .padding(16)
        } else {
            HStack(spacing: 12) {
                Linea(nome: attributi.linea, colore: attributi.colore, grande: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("→ " + attributi.capolinea).font(.headline).foregroundStyle(.white).lineLimit(1)
                    Text(attributi.fermata).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                    Text(scaduto ? attributi.testoScaduto : stato.fonte)
                        .font(.caption)
                        .foregroundStyle(stato.tempoReale && !scaduto ? Color.oroPalina : .white.opacity(0.6))
                }
                Spacer(minLength: 4)
                VStack(alignment: .trailing, spacing: 0) {
                    Conto(arrivo: stato.arrivo, tempoReale: stato.tempoReale, testoOra: attributi.testoOra)
                        .font(.system(size: 32, weight: .black, design: .rounded))
                    Text(stato.arrivo, style: .time).font(.caption).foregroundStyle(.white.opacity(0.6))
                }
            }
            .padding(16)
        }
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
            etichetta: "PREFERITA", nome: "P.za Venezia", fermataId: "", ora: "18:34",
            righe: [
                RigaWidgetVoce(linea: "64", colore: 0xFF862633, capolinea: "Stazione S. Pietro", poi: "9'", tra: "1'", tempoReale: true),
                RigaWidgetVoce(linea: "40", colore: 0xFF862633, capolinea: "P.za Pia", poi: "13'", tra: "4'", tempoReale: true),
            ],
            nota: ""
        ),
        vicina: WidgetVicinaVoce(
            nome: "Plebiscito", fermataId: "", distanza: "121 m",
            righe: [
                RigaWidgetVoce(linea: "87", colore: 0xFF862633, capolinea: "", poi: "", tra: "5'", tempoReale: true),
                RigaWidgetVoce(linea: "63", colore: 0xFF862633, capolinea: "", poi: "", tra: "6'", tempoReale: true),
            ],
            nota: nil, etichetta: "PIÙ VICINA"
        ),
        tragitto: WidgetTragittoVoce(
            id: "", nome: "Lavoro → Casa", esci: "ESCI TRA", numero: "4", unita: "min",
            sotto: "64 alle 18:41 · arrivi 19:05", linea: "64", colore: 0xFF862633, poi: nil,
            viaggio: "64 → Metro B · 1 cambio · 24 min"
        ),
        fermate: nil, tragitti: nil
    )
}

/** I colori dei widget: tabellone nero e oro col tema scuro, carta e ambra col chiaro. */
private struct ColoriWidget {
    let scuro: Bool
    var fondo: Color { scuro ? Color(red: 0.02, green: 0.016, blue: 0.016) : .white }
    var caldo: Color { scuro ? Color(red: 0.165, green: 0.122, blue: 0.071) : Color(red: 0.984, green: 0.937, blue: 0.827) }
    var accento: Color { scuro ? .oroPalina : Color(red: 0.541, green: 0.353, blue: 0) }
    var testo: Color { scuro ? Color(red: 0.957, green: 0.937, blue: 0.918) : Color(red: 0.11, green: 0.09, blue: 0.082) }
    var tenue: Color { scuro ? Color(red: 0.725, green: 0.694, blue: 0.671) : Color(red: 0.373, green: 0.341, blue: 0.318) }
}

private struct RigaWidget: View {
    let riga: RigaWidgetVoce
    let colori: ColoriWidget
    let conCapolinea: Bool
    let cifre: CGFloat

    var body: some View {
        HStack(spacing: 8) {
            Text(riga.linea)
                .font(.system(size: cifre * 0.62, weight: .black, design: .rounded))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(minWidth: 36)
                .padding(.horizontal, 4)
                .padding(.vertical, 3)
                .background(Color(argb: riga.colore), in: RoundedRectangle(cornerRadius: 6))
            if conCapolinea {
                (Text(riga.capolinea).foregroundStyle(colori.testo)
                    + Text(riga.poi.isEmpty ? "" : " · " + riga.poi).foregroundStyle(colori.tenue))
                    .font(.subheadline)
                    .lineLimit(1)
            }
            Spacer(minLength: 2)
            Text(riga.tra)
                .font(.system(size: cifre, weight: .black, design: .rounded))
                .foregroundStyle(riga.tempoReale ? colori.accento : colori.tenue)
                .lineLimit(1)
        }
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
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(t?.etichetta ?? "PALINA").font(.caption.bold()).tracking(1.2).foregroundStyle(colori.accento)
                        Text(t?.nome ?? "Palina Roma").font(.footnote).foregroundStyle(colori.tenue).lineLimit(1)
                        Spacer(minLength: 2)
                        Text(t?.ora ?? "").font(.system(size: 16, weight: .black, design: .rounded)).foregroundStyle(colori.accento)
                    }
                    Divider().overlay(colori.tenue.opacity(0.4))
                    if let t, !t.righe.isEmpty {
                        ForEach(t.righe.prefix(famiglia == .systemLarge ? 4 : 2), id: \.self) { r in
                            RigaWidget(riga: r, colori: colori, conCapolinea: true, cifre: 26)
                            Spacer(minLength: 0)
                        }
                    } else {
                        Spacer(minLength: 0)
                    }
                    Text(voce.voce == nil ? testoVecchio(voce) : (t?.nota ?? testoVecchio(voce)))
                        .font(.caption2)
                        .foregroundStyle(colori.tenue)
                        .lineLimit(2)
                }
                .widgetURL(URL(string: (t?.fermataId ?? voce.scelta).map { $0.isEmpty ? "palina://home" : "palina://fermata/\($0)" } ?? "palina://home"))
            }
        }
        .containerBackground(for: .widget) { famiglia == .accessoryRectangular ? Color.clear : colori.fondo }
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
                VStack(alignment: .leading, spacing: 6) {
                    Text(v?.etichetta ?? "PALINA").font(.caption2.bold()).tracking(1.1).foregroundStyle(colori.accento)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(v?.nome ?? "Palina").font(.subheadline.bold()).foregroundStyle(colori.testo).lineLimit(1)
                        Text(v?.distanza ?? "").font(.caption).foregroundStyle(colori.tenue).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if let v, !v.righe.isEmpty {
                        ForEach(v.righe.prefix(2), id: \.self) { r in
                            RigaWidget(riga: r, colori: colori, conCapolinea: false, cifre: 22)
                        }
                    } else {
                        Text(v?.nota ?? testoVecchio(voce)).font(.caption2).foregroundStyle(colori.tenue).lineLimit(3)
                    }
                }
                .widgetURL(URL(string: v.map { $0.fermataId.isEmpty ? "palina://vicina" : "palina://fermata/\($0.fermataId)" } ?? "palina://vicina"))
            }
        }
        .containerBackground(for: .widget) { famiglia == .accessoryRectangular ? Color.clear : colori.fondo }
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
                        Text(t?.nome ?? NSLocalizedString("Esci tra", comment: "")).font(.footnote.bold()).foregroundStyle(colori.testo).lineLimit(2)
                        Spacer(minLength: 0)
                        if let t {
                            Text(t.esci).font(.caption.bold()).tracking(1).foregroundStyle(colori.accento)
                            HStack(alignment: .firstTextBaseline, spacing: 4) {
                                Text(t.numero).font(.system(size: 50, weight: .black, design: .rounded)).foregroundStyle(colori.accento)
                                Text(t.unita).font(.subheadline).foregroundStyle(colori.accento)
                            }
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        if let t {
                            if let viaggio = t.viaggio, !viaggio.isEmpty {
                                Text(viaggio).font(.subheadline.bold()).foregroundStyle(colori.accento).lineLimit(3)
                            }
                            Text(t.sotto).font(.footnote).foregroundStyle(colori.testo).lineLimit(2)
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
                    Text(t?.nome ?? NSLocalizedString("Esci tra", comment: "")).font(.footnote.bold()).foregroundStyle(colori.testo).lineLimit(1)
                    Spacer(minLength: 0)
                    if let t {
                        Text(t.esci).font(.caption.bold()).tracking(1).foregroundStyle(colori.accento)
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(t.numero).font(.system(size: 50, weight: .black, design: .rounded)).foregroundStyle(colori.accento)
                            Text(t.unita).font(.subheadline).foregroundStyle(colori.accento)
                        }
                        Spacer(minLength: 0)
                        if let viaggio = t.viaggio, !viaggio.isEmpty {
                            Text(viaggio).font(.caption.bold()).foregroundStyle(colori.accento).lineLimit(2)
                        }
                        Text(t.sotto).font(.caption2).foregroundStyle(colori.testo).lineLimit(2)
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
