import SwiftUI
import Motore

/** Un treno: tutte le fermate con orari, ritardo e binario; in evidenza la tua stazione. */
struct CorsaVista: View {
    let treno: String
    @EnvironmentObject private var archivio: Archivio
    @State private var stato: StatoCorsa?

    var body: some View {
        ScrollViewReader { scorri in
            List {
                if let stato {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(stato.titolo).font(.title2.bold())
                            if let verso = stato.verso { Text(verso).foregroundStyle(.secondary) }
                            if let fonte = stato.fonte { Text(fonte).font(.footnote).foregroundStyle(Tavolozza.oro) }
                        }
                    }
                    if let errore = stato.errore {
                        Text(errore).foregroundStyle(.secondary)
                    }
                    Section {
                        ForEach(Array(stato.righe.enumerated()), id: \.offset) { i, r in
                            RigaFermataTreno(riga: r, prima: i == 0, ultima: i == stato.righe.count - 1)
                                .id(i)
                                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        }
                    }
                } else {
                    HStack { ProgressView(); Text("Cerco il treno…").foregroundStyle(.secondary) }
                }
            }
            .listStyle(.insetGrouped)
            .onChange(of: stato?.qui) { _, qui in
                if let qui, qui >= 0 { withAnimation { scorri.scrollTo(Int(qui), anchor: .center) } }
            }
        }
        .navigationTitle(stato?.titolo ?? "Treno")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: archivio.orario != nil) { await segui() }
    }

    /** Il treno si ricarica ogni minuto (ritardi e binari cambiano). */
    private func segui() async {
        guard let orario = archivio.orario else { return }
        let motore = MotoreCorsa(orario: orario, treno: treno)
        while !Task.isCancelled {
            if let nuovo = try? await motore.aggiorna() { stato = nuovo }
            try? await Task.sleep(nanoseconds: 60_000_000_000)
        }
    }
}

/** Una fermata del treno: la linea a sinistra, nome, binario, orario (barrato quello programmato se cambia). */
struct RigaFermataTreno: View {
    let riga: RigaCorsa
    let prima: Bool
    let ultima: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                VStack(spacing: 0) {
                    Rectangle().fill(prima ? .clear : Color.secondary.opacity(0.5)).frame(width: 3)
                    Rectangle().fill(ultima ? .clear : Color.secondary.opacity(0.5)).frame(width: 3)
                }
                Circle()
                    .fill(riga.qui ? Tavolozza.oro : Color(.systemBackground))
                    .stroke(riga.passata ? Color.secondary : Tavolozza.oro, lineWidth: 2.5)
                    .frame(width: riga.qui ? 16 : 12, height: riga.qui ? 16 : 12)
            }
            .frame(width: 20, height: 54)
            VStack(alignment: .leading, spacing: 2) {
                Text(riga.nome)
                    .font(riga.qui ? .body.bold() : .body)
                    .strikethrough(riga.soppressa)
                    .foregroundStyle(riga.passata ? .secondary : .primary)
                    .lineLimit(2)
                if riga.soppressa {
                    Text("fermata soppressa").font(.caption).foregroundStyle(Tavolozza.oro)
                } else if let binario = riga.binario {
                    Text(binario + (riga.binarioCambiato ? " (cambiato)" : ""))
                        .font(.caption)
                        .foregroundStyle(riga.binarioCambiato ? Tavolozza.oro : .secondary)
                }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 1) {
                Text(riga.ora)
                    .font(.body.monospacedDigit().weight(riga.qui ? .bold : .regular))
                    .foregroundStyle(riga.oraPrevista != nil ? Tavolozza.oro : (riga.passata ? .secondary : .primary))
                if let prevista = riga.oraPrevista {
                    Text(prevista).font(.caption.monospacedDigit()).strikethrough().foregroundStyle(.secondary)
                } else if let ritardo = riga.ritardo {
                    Text(ritardo).font(.caption.bold()).foregroundStyle(Tavolozza.oro)
                }
                if riga.stimato {
                    Text("stimato").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(riga.perVoce)
    }
}
