import SwiftUI
import Motore

/** Aspetto, cifre a punti, orario e da dove vengono i dati: gli stessi testi dell'app Android. */
struct ImpostazioniVista: View {
    @EnvironmentObject private var archivio: Archivio
    @AppStorage("aspetto") private var aspetto = "AUTOMATICO"
    @AppStorage("cifre_led") private var cifreLed = true
    @AppStorage("lingua") private var lingua = ""
    @State private var provato = false
    private let testi = TestiImpostazioni.shared

    var body: some View {
        List {
            Section {
                ForEach(testi.aspetti, id: \.chiave) { scelta in
                    Button {
                        aspetto = scelta.chiave
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(scelta.nome).foregroundStyle(Color.primary)
                                Text(scelta.descrizione).font(.footnote).foregroundStyle(Color.secondary)
                            }
                            Spacer()
                            if aspetto == scelta.chiave {
                                Image(systemName: "checkmark").foregroundStyle(Tavolozza.oro).bold()
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .contentShape(Rectangle())
                    .accessibilityAddTraits(aspetto == scelta.chiave ? .isSelected : [])
                }
                Toggle(isOn: $cifreLed) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(testi.CIFRE)
                        Text(testi.CIFRE_SOTTO).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .tint(Tavolozza.oro)
                HStack {
                    Text(tx("Esempio")).foregroundStyle(.secondary)
                    Spacer()
                    Text("7′").font(Tavolozza.cifre(26, aPunti: cifreLed)).foregroundStyle(Tavolozza.oro)
                }
                .accessibilityHidden(true)
            } header: {
                Text(testi.ASPETTO)
            } footer: {
                Text(testi.grandezzaTesto(sistema: "iOS"))
            }

            Section(testi.LINGUA) {
                ForEach(testi.lingue, id: \.chiave) { scelta in
                    Button {
                        lingua = scelta.chiave
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(scelta.nome).foregroundStyle(Color.primary)
                                if !scelta.descrizione.isEmpty {
                                    Text(scelta.descrizione).font(.footnote).foregroundStyle(Color.secondary)
                                }
                            }
                            Spacer()
                            if lingua == scelta.chiave {
                                Image(systemName: "checkmark").foregroundStyle(Tavolozza.oro).bold()
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(lingua == scelta.chiave ? .isSelected : [])
                }
            }

            Section(testi.ORARIO) {
                if let orario = archivio.orario {
                    Text(testi.statoOrario(orario: orario, creato: archivio.creato, incluso: archivio.incluso)
                        + testi.aggiornamentoIPhone(megabyte: Int32(archivio.megabyte)))
                        .foregroundStyle(.secondary)
                }
                switch archivio.aggiornamento {
                case .inCorso(let fase):
                    HStack(spacing: 10) { ProgressView(); Text(fase) }
                case .errore(let messaggio):
                    Text(testi.nonRiuscito(messaggio: messaggio)).foregroundStyle(Tavolozza.oro)
                case .completato:
                    Text(testi.AGGIORNATO).foregroundStyle(.secondary)
                case .giaAggiornato:
                    Text(testi.GIA_AGGIORNATO).foregroundStyle(.secondary)
                case .fermo:
                    EmptyView()
                }
                Button(testi.AGGIORNA) {
                    Task { await archivio.controlla(automatico: false) }
                }
                .disabled({ if case .inCorso = archivio.aggiornamento { return true } else { return false } }())
            }

            ForEach(testi.schede, id: \.titolo) { scheda in
                Section(scheda.titolo) {
                    Text(scheda.testo).font(.subheadline).foregroundStyle(.secondary)
                }
            }

            Section {
                Text(testi.versione(numero: versione)).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(testi.TITOLO)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: archivio.versione) {
            // prove: scarica l'orario pubblicato nella release, come "Aggiorna ora" (una volta, a orario aperto)
            if Prova.aggiorna, archivio.orario != nil, !provato {
                provato = true
                await archivio.controlla(automatico: false)
            }
        }
    }

    private var versione: String {
        let breve = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        return tx("{0} per iPhone", breve)
    }
}
