import ActivityKit
import SwiftUI
import WidgetKit

/** I widget dell'iPhone: per ora la Live Activity di "Segui". */
@main
struct PalinaWidgets: WidgetBundle {
    var body: some Widget {
        SeguiLiveActivity()
    }
}

/**
 * "Segui" una linea: il conto alla rovescia fino al passaggio, sulla schermata
 * di blocco, nella Dynamic Island e (con watchOS 11) nello Smart Stack
 * dell'Apple Watch. Si aggiorna da solo: l'app sposta l'arrivo col tempo reale.
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
                    Text("→ " + a.capolinea).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(a.fermata + " · " + s.fonte)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
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

/** Sulla schermata di blocco: linea, capolinea, fermata e il conto alla rovescia. */
private struct SchermataBlocco: View {
    let attributi: SeguiAttributi
    let stato: SeguiAttributi.ContentState
    let scaduto: Bool

    var body: some View {
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
