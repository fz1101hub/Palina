import SwiftUI
import Motore

/** Aspetto, cifre a punti, orario e da dove vengono i dati: gli stessi testi dell'app Android. */
struct ImpostazioniVista: View {
    @EnvironmentObject private var archivio: Archivio
    @AppStorage("aspetto") private var aspetto = "AUTOMATICO"
    @AppStorage("cifre_led") private var cifreLed = true
    @AppStorage("lingua") private var lingua = ""
    @AppStorage("avvisi_linee") private var avvisiLinee = true
    @EnvironmentObject private var promemorie: Promemorie
    @State private var promemoriaAperto: PromemoriaAperto?
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

            // notifiche anche ad app chiusa: avvisi sulle tue linee (e scioperi), promemoria ricorrenti
            Section {
                Toggle(isOn: $avvisiLinee) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tx("Avvisi sulle tue linee"))
                        Text(tx("Deviazioni e lavori sulle linee fissate, dei tragitti salvati e dei promemoria; gli scioperi la sera prima."))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .tint(Tavolozza.oro)
                Text(tx("Promemoria ricorrenti")).font(.subheadline.weight(.semibold))
                let voci = promemorie.voci()
                if voci.isEmpty {
                    Text(tx("Nessuno: li aggiungi da «Avvisami» su una linea («Ripeti ogni settimana…») o dal menu di un tragitto salvato."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(voci, id: \.id) { v in
                    HStack {
                        Button {
                            if let p = promemorie.elenco.first(where: { $0.id == v.id }) {
                                promemoriaAperto = PromemoriaAperto(promemoria: p, esistente: true)
                            }
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(v.titolo).foregroundStyle(Color.primary)
                                Text(v.sotto).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { v.attivo },
                            set: { acceso in
                                if let p = promemorie.elenco.first(where: { $0.id == v.id }) {
                                    promemorie.salva(PromemoriaKt.promemoriaModificato(
                                        p: p, giorni: PromemoriaKt.giorniDelPromemoria(p: p), minuti: p.minuti, quando: p.quando, attivo: acceso
                                    ))
                                }
                            }
                        ))
                        .labelsHidden()
                        .tint(Tavolozza.oro)
                    }
                    .accessibilityIdentifier("promemoria")
                }
            } header: {
                Text(tx("Notifiche"))
            }
            .sheet(item: $promemoriaAperto) { PromemoriaVista(aperto: $0) }
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
