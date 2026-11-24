import SwiftUI
import Motore

/** La Home: le fermate vicine e la ricerca. */
struct HomeVista: View {
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione
    @State private var testo = ""
    @State private var vicine: [VoceFermata] = []
    @State private var trovate: [VoceFermata] = []

    var body: some View {
        List {
            if let errore = archivio.errore {
                Text(errore).foregroundStyle(.secondary)
            } else if archivio.orario == nil {
                HStack { ProgressView(); Text("Apro l'orario…").foregroundStyle(.secondary) }
            } else if !testo.trimmingCharacters(in: .whitespaces).isEmpty {
                Section("Fermate") {
                    if trovate.isEmpty { Text("Nessuna fermata trovata.").foregroundStyle(.secondary) }
                    ForEach(trovate, id: \.id) { voce in
                        NavigationLink(value: voce.id) { RigaFermata(voce: voce) }
                    }
                }
            } else {
                Section("Vicino a te") {
                    if posizione.negata {
                        Text("Per le fermate vicine serve la posizione: puoi permetterla nelle Impostazioni.")
                            .foregroundStyle(.secondary)
                    } else if posizione.lat == nil {
                        HStack { ProgressView(); Text("Cerco dove sei…").foregroundStyle(.secondary) }
                    } else if vicine.isEmpty {
                        Text("Nessuna fermata entro 700 metri.").foregroundStyle(.secondary)
                    }
                    ForEach(vicine, id: \.id) { voce in
                        NavigationLink(value: voce.id) { RigaFermata(voce: voce) }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Palina")
        .searchable(text: $testo, prompt: "Cerca una fermata o un codice")
        .onAppear { posizione.avvia() }
        .task(id: chiaveVicine) { aggiornaVicine() }
        .task(id: testo) { cerca() }
    }

    private var chiaveVicine: String {
        "\(archivio.orario != nil)-\(posizione.lat ?? 0)-\(posizione.lon ?? 0)"
    }

    private func aggiornaVicine() {
        guard let orario = archivio.orario, let lat = posizione.lat, let lon = posizione.lon else { return }
        vicine = PresentazioneKt.vociVicine(orario: orario, lat: lat, lon: lon, raggio: 700)
    }

    private func cerca() {
        guard let orario = archivio.orario else { return }
        let t = testo.trimmingCharacters(in: .whitespaces)
        trovate = t.isEmpty ? [] : PresentazioneKt.vociCercate(orario: orario, testo: t)
    }
}
