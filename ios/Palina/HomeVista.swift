import SwiftUI
import Motore

/** La Home: la preferita a cui sei vicino, le altre preferite e le fermate vicine, con i minuti; e la ricerca. */
struct HomeVista: View {
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione
    @EnvironmentObject private var preferiti: Preferiti
    @State private var testo = ""
    @State private var vicine: [VoceFermata] = []
    @State private var stato: StatoHome?
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
                        NavigationLink(value: Destinazione.fermata(voce.id)) { RigaFermata(voce: voce) }
                    }
                }
            } else {
                contenuto
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Palina")
        .searchable(text: $testo, prompt: "Cerca una fermata o un codice")
        .onAppear { posizione.avvia() }
        .task(id: chiave) { await seguiTempi() }
        .task(id: testo) { cerca() }
    }

    @ViewBuilder
    private var contenuto: some View {
        if let avviso = stato?.avviso {
            Text(avviso).font(.footnote).foregroundStyle(Tavolozza.oro)
        }
        if let evidenza = stato?.evidenza {
            Section {
                SchedaPreferitaVistaSwift(scheda: evidenza)
            } header: {
                IntestazioneConAggiornamento(titolo: "Sei vicino a", aggiornato: stato?.aggiornato)
            }
        }
        if let titolo = stato?.titoloPreferite, let preferite = stato?.preferite {
            Section {
                ForEach(preferite, id: \.id) { SchedaPreferitaVistaSwift(scheda: $0) }
            } header: {
                IntestazioneConAggiornamento(titolo: titolo, aggiornato: stato?.evidenza == nil ? stato?.aggiornato : nil)
            }
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
            ForEach(righeVicine, id: \.voce.id) { riga in
                NavigationLink(value: Destinazione.fermata(riga.voce.id)) { RigaFermataConTempi(riga: riga) }
            }
        }
        if let suggerimento = stato?.suggerimento {
            Text(suggerimento).font(.footnote).foregroundStyle(.secondary)
        }
    }

    /** Le righe con i tempi, o quelle senza finche' i tempi non arrivano. */
    private var righeVicine: [FermataConTempi] {
        if let vicineConTempi = stato?.vicine, !vicineConTempi.isEmpty { return vicineConTempi }
        return vicine.map { FermataConTempi(voce: $0, pillole: [], altre: 0, perVoce: $0.perVoce) }
    }

    private var chiave: String {
        "\(archivio.home != nil)-\(posizione.lat ?? 0)-\(posizione.lon ?? 0)-\(preferiti.fermate.sorted())-\(preferiti.fissate.sorted())"
    }

    /** Preferite, vicine e tempi: i tempi si chiedono ogni 30 secondi, i minuti si ricalcolano ogni 5. */
    private func seguiTempi() async {
        guard let home = archivio.home else { return }
        home.impostaPreferiti(preferiti: preferiti.fermate, fissate: preferiti.fissate)
        if let lat = posizione.lat, let lon = posizione.lon {
            home.posizione(lat: lat, lon: lon)
            vicine = home.vicine(lat: lat, lon: lon)
        }
        stato = home.stato(vicine: vicine)
        var ultimaRichiesta = Date.distantPast
        while !Task.isCancelled {
            if Date().timeIntervalSince(ultimaRichiesta) >= 30 {
                ultimaRichiesta = Date()
                if let nuovo = try? await home.aggiorna(vicine: vicine) { stato = nuovo }
            } else {
                stato = home.stato(vicine: vicine)
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

/** Il titoletto di una sezione con "aggiornato 20 s fa" a destra. */
struct IntestazioneConAggiornamento: View {
    let titolo: String
    let aggiornato: String?

    var body: some View {
        HStack {
            Text(titolo)
            Spacer()
            if let aggiornato { Text(aggiornato).textCase(nil).font(.caption) }
        }
    }
}

/** Una preferita come piccolo tabellone: le linee fissate (o le prime tre) grandi, le altre in breve. */
struct SchedaPreferitaVistaSwift: View {
    let scheda: SchedaPreferitaVista

    var body: some View {
        NavigationLink(value: Destinazione.fermata(scheda.id)) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline) {
                    Text(scheda.nome).font(.headline).lineLimit(2)
                    Spacer()
                    Text(scheda.sotto).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .accessibilityElement(children: .combine)
                ForEach(scheda.righe, id: \.self) { RigaTabellone(riga: $0) }
                if let messaggio = scheda.messaggio {
                    Text(messaggio).font(.footnote).foregroundStyle(.secondary)
                }
                if !scheda.pillole.isEmpty {
                    DisposizioneACapo(spazio: 6) {
                        Text("Altre:").font(.footnote).foregroundStyle(.secondary)
                        ForEach(Array(scheda.pillole.enumerated()), id: \.offset) { _, p in Pillola(pillola: p) }
                        if scheda.inPiu > 0 {
                            Text("+\(scheda.inPiu)").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .accessibilityIdentifier("preferita")
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
