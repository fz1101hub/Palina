import SwiftUI
import Motore

/** La Home: le fermate vicine con i prossimi passaggi, e la ricerca. */
struct HomeVista: View {
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione
    @State private var testo = ""
    @State private var vicine: [VoceFermata] = []
    @State private var righe: [FermataConTempi] = []
    @State private var avviso: String?
    @State private var trovate: [VoceFermata] = []

    var body: some View {
        List {
            if let errore = archivio.errore {
                Text(errore).foregroundStyle(.secondary)
            } else if archivio.home == nil {
                HStack { ProgressView(); Text("Apro l'orario…").foregroundStyle(.secondary) }
            } else if !testo.trimmingCharacters(in: .whitespaces).isEmpty {
                Section("Fermate") {
                    if trovate.isEmpty { Text("Nessuna fermata trovata.").foregroundStyle(.secondary) }
                    ForEach(trovate, id: \.id) { voce in
                        NavigationLink(value: voce.id) { RigaFermata(voce: voce) }
                    }
                }
            } else {
                if let avviso {
                    Text(avviso).font(.footnote).foregroundStyle(Tavolozza.oro)
                }
                Section("Vicino a te") {
                    if posizione.negata {
                        Text("Per le fermate vicine serve la posizione: puoi permetterla nelle Impostazioni.")
                            .foregroundStyle(.secondary)
                    } else if posizione.lat == nil {
                        HStack { ProgressView(); Text("Cerco dove sei…").foregroundStyle(.secondary) }
                    } else if vicine.isEmpty {
                        Text("Nessuna fermata entro 700 metri.").foregroundStyle(.secondary)
                    }
                    ForEach(elenco, id: \.voce.id) { riga in
                        NavigationLink(value: riga.voce.id) { RigaFermataConTempi(riga: riga) }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Palina")
        .searchable(text: $testo, prompt: "Cerca una fermata o un codice")
        .onAppear { posizione.avvia() }
        .task(id: chiaveVicine) { await seguiTempi() }
        .task(id: testo) { cerca() }
    }

    /** Le righe con i tempi, o quelle senza finche' i tempi non arrivano. */
    private var elenco: [FermataConTempi] {
        if !righe.isEmpty { return righe }
        return vicine.map { FermataConTempi(voce: $0, pillole: [], altre: 0, perVoce: $0.perVoce) }
    }

    private var chiaveVicine: String {
        "\(archivio.home != nil)-\(posizione.lat ?? 0)-\(posizione.lon ?? 0)"
    }

    /** Fermate vicine e tempi: i tempi si chiedono ogni 30 secondi, i minuti si ricalcolano ogni 5. */
    private func seguiTempi() async {
        guard let home = archivio.home, let lat = posizione.lat, let lon = posizione.lon else { return }
        vicine = home.vicine(lat: lat, lon: lon)
        righe = []
        var ultimaRichiesta = Date.distantPast
        while !Task.isCancelled {
            if Date().timeIntervalSince(ultimaRichiesta) >= 30 {
                ultimaRichiesta = Date()
                if let nuove = try? await home.aggiorna(voci: vicine) { righe = nuove }
                avviso = home.avviso()
            } else {
                righe = home.righe(voci: vicine)
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }

    private func cerca() {
        guard let home = archivio.home else { return }
        let t = testo.trimmingCharacters(in: .whitespaces)
        trovate = t.isEmpty ? [] : home.cerca(testo: t)
    }
}

/** Una fermata in Home: nome, distanza e i prossimi passaggi ("64 · 3′", in oro quelli in tempo reale). */
struct RigaFermataConTempi: View {
    let riga: FermataConTempi

    var body: some View {
        if riga.pillole.isEmpty {
            RigaFermata(voce: riga.voce)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline) {
                    Text(riga.voce.nome).font(.body.bold()).lineLimit(2)
                    Spacer()
                    Text(riga.voce.sotto + (riga.voce.distanza.map { " · \($0)" } ?? ""))
                        .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                DisposizioneACapo(spazio: 6) {
                    ForEach(Array(riga.pillole.enumerated()), id: \.offset) { _, p in
                        Pillola(pillola: p)
                    }
                    if riga.altre > 0 {
                        Text("+\(riga.altre)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(riga.perVoce)
            .accessibilityIdentifier("riga-fermata")
        }
    }
}

struct Pillola: View {
    let pillola: PillolaPassaggio

    var body: some View {
        Text(pillola.testo)
            .font(.footnote.weight(pillola.tempoReale ? .bold : .medium))
            .foregroundStyle(pillola.tempoReale ? Tavolozza.oro : Color.secondary)
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(
                pillola.tempoReale ? Tavolozza.oro.opacity(0.16) : Color(.tertiarySystemFill),
                in: RoundedRectangle(cornerRadius: 13)
            )
    }
}
