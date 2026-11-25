import SwiftUI
import WidgetKit

/**
 * Le complicazioni e lo Smart Stack dell'Apple Watch: la prima fermata con
 * passaggi (la preferita piu' vicina) e i prossimi minuti, come il widget di
 * Wear OS. Le voci, minuto per minuto, le prepara il motore nell'app.
 */
@main
struct PalinaComplicazioni: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "palina", provider: Fornitore()) { voce in
            VistaComplicazione(voce: voce)
                .containerBackground(for: .widget) { Color.black }
        }
        .configurationDisplayName("Palina")
        .description("La prossima linea alla fermata preferita più vicina.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

struct VoceWidget: TimelineEntry {
    let date: Date
    let voce: VoceComplicazione?
}

struct Fornitore: TimelineProvider {
    func placeholder(in context: Context) -> VoceWidget {
        VoceWidget(date: Date(), voce: Self.esempio)
    }

    func getSnapshot(in context: Context, completion: @escaping (VoceWidget) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : voci().first ?? VoceWidget(date: Date(), voce: nil))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VoceWidget>) -> Void) {
        let elenco = voci()
        // finite le voci (un'ora) resta "apri Palina"; l'app le rinnova a ogni tabellone
        let fine = VoceWidget(date: (elenco.last?.date ?? Date()).addingTimeInterval(60), voce: nil)
        completion(Timeline(entries: elenco.isEmpty ? [VoceWidget(date: Date(), voce: nil)] : elenco + [fine], policy: .never))
    }

    private func voci() -> [VoceWidget] {
        let adesso = Date()
        let tutte = (Gruppo.leggi()?.voci ?? []).map {
            VoceWidget(date: Date(timeIntervalSince1970: Double($0.alle) / 1000), voce: $0)
        }
        // dalla voce di questo minuto in poi
        let passate = tutte.filter { $0.date <= adesso }
        return (passate.last.map { [$0] } ?? []) + tutte.filter { $0.date > adesso }
    }

    static let esempio = VoceComplicazione(
        alle: 0, titolo: "Piazza Venezia",
        righe: [RigaComplicazione(linea: "64", colore: 0xFF862633, passaggi: "3' · 12'", tempoReale: true)],
        nota: nil, linea: "64", tra: "3'", colore: 0xFF862633, inline: "64 · 3' · Piazza Venezia"
    )
}

struct VistaComplicazione: View {
    let voce: VoceWidget
    @Environment(\.widgetFamily) private var famiglia

    var body: some View {
        let v = voce.voce
        switch famiglia {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(v?.linea ?? "Palina").font(.system(size: 11, weight: .bold)).lineLimit(1).minimumScaleFactor(0.5)
                    Text(v?.tra ?? "–").font(.system(size: 18, weight: .black, design: .rounded)).foregroundStyle(Color.oroPalina)
                        .widgetAccentable()
                }
                .padding(2)
            }
        case .accessoryCorner:
            Text(v?.tra ?? "–")
                .font(.system(size: 20, weight: .black, design: .rounded))
                .foregroundStyle(Color.oroPalina)
                .widgetLabel(v.map { ($0.linea ?? "") + " · " + $0.titolo } ?? "Palina")
        case .accessoryInline:
            Text(v?.inline ?? "Palina")
        default:
            VStack(alignment: .leading, spacing: 1) {
                Text(v?.titolo ?? "Palina").font(.headline).lineLimit(1).widgetAccentable()
                if let v, !v.righe.isEmpty {
                    ForEach(v.righe.prefix(2), id: \.self) { r in
                        HStack(spacing: 4) {
                            Text(r.linea).font(.caption.bold()).lineLimit(1)
                            Text(r.passaggi).font(.caption).foregroundStyle(r.tempoReale ? Color.oroPalina : .secondary).lineLimit(1)
                        }
                    }
                } else {
                    Text(v?.nota ?? NSLocalizedString("Apri Palina per chiedere i tempi all'iPhone", comment: "")).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
