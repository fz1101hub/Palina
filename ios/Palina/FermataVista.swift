import SwiftUI
import Motore

/** Una fermata: il tabellone con i prossimi passaggi e le linee che ci passano. */
struct FermataVista: View {
    let fermataId: String
    @EnvironmentObject private var archivio: Archivio
    @State private var motore: MotoreFermata?
    @State private var voce: VoceFermata?
    @State private var linee: [VoceLinea] = []
    @State private var tabellone: TabelloneFermata?
    @State private var caricando = true

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
                if let avviso = tabellone?.avviso {
                    Text(avviso).font(.footnote).foregroundStyle(Tavolozza.oro)
                }
                Section {
                    if let righe = tabellone?.righe, !righe.isEmpty {
                        ForEach(righe, id: \.self) { RigaTabellone(riga: $0) }
                    } else if caricando {
                        HStack { ProgressView(); Text("Leggo i tempi…").foregroundStyle(.secondary) }
                    } else {
                        Text("Nessun passaggio nei prossimi 90 minuti.").foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Prossimi passaggi")
                } footer: {
                    if let aggiornato = tabellone?.aggiornato { Text(aggiornato) }
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
        .task(id: archivio.orario != nil) { await segui() }
    }

    /** I tempi si chiedono ogni 30 secondi; in mezzo i minuti si ricalcolano ogni 5. */
    private func segui() async {
        guard let orario = archivio.orario else { return }
        let m = motore ?? MotoreFermata(orario: orario, fermataId: fermataId)
        motore = m
        voce = m.intestazione()
        linee = m.linee()
        var ultimaRichiesta = Date.distantPast
        while !Task.isCancelled {
            if Date().timeIntervalSince(ultimaRichiesta) >= 30 {
                ultimaRichiesta = Date()
                if let nuovo = try? await m.aggiorna() { tabellone = nuovo }
                caricando = false
            } else {
                tabellone = m.tabellone()
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }
}

/** Una riga del tabellone: linea, direzione, "poi 12 · 25 min" e i minuti (in oro se in tempo reale). */
struct RigaTabellone: View {
    let riga: RigaArrivo

    var body: some View {
        HStack(spacing: 10) {
            BadgeLinea(nome: riga.nome, colore: riga.colore, grande: true)
            VStack(alignment: .leading, spacing: 2) {
                Text(riga.capolinea)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(riga.capolineaNoto ? Color.primary : Color.secondary)
                    .lineLimit(3)
                if !riga.poi.isEmpty {
                    Text(riga.poi).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 2) {
                if riga.minuti {
                    Text(riga.attesa + "′")
                        .font(.system(size: 26, weight: .black, design: .rounded))
                        .foregroundStyle(riga.tempoReale ? Tavolozza.oro : Color.secondary)
                } else {
                    Text(riga.attesa)
                        .font(.subheadline.bold())
                        .foregroundStyle(riga.tempoReale || riga.evidenzia ? Tavolozza.oro : Color.secondary)
                }
                Text(riga.sotto)
                    .font(.caption2.weight(riga.tempoReale || riga.evidenzia ? .bold : .regular))
                    .foregroundStyle(riga.tempoReale || riga.evidenzia ? Tavolozza.oro : Color.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(riga.perVoce)
        .accessibilityIdentifier("arrivo")
    }
}
