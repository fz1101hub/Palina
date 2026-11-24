import SwiftUI
import Motore

/** Una fermata: il nome e le linee che ci passano (i tempi arrivano con il prossimo passo del motore). */
struct FermataVista: View {
    let fermataId: String
    @EnvironmentObject private var archivio: Archivio
    @State private var voce: VoceFermata?
    @State private var linee: [VoceLinea] = []

    var body: some View {
        List {
            if let voce {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(voce.nome).font(.title2.bold())
                        Text(voce.sotto).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                Section("Linee") {
                    ForEach(linee, id: \.self) { l in
                        HStack(spacing: 10) {
                            BadgeLinea(nome: l.nome, colore: l.colore, grande: true)
                            Text("→ \(l.capolinea)").lineLimit(2)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Linea \(l.nome) verso \(l.capolinea)")
                    }
                }
            } else if archivio.orario != nil {
                Text("Fermata non trovata.").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(voce?.nome ?? "Fermata")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: archivio.orario != nil) { carica() }
    }

    private func carica() {
        guard let orario = archivio.orario else { return }
        voce = PresentazioneKt.intestazioneFermata(orario: orario, fermataId: fermataId)
        linee = PresentazioneKt.lineeDellaFermata(orario: orario, fermataId: fermataId)
    }
}
