import SwiftUI
import Motore

/**
 * Il foglio "Avvisami per il 64": quando avvisare (ora di uscire, a 5 o a 2
 * minuti), cosa propone l'app (il successivo, se il prossimo non lo prendi in
 * tempo) e "poi avvisami quando scendere". Come su Android: tutto dal motore.
 */
struct AvvisamiVista: View {
    let fermataId: String
    let lineaId: String
    let capolinea: String
    @EnvironmentObject private var seguito: Seguito
    @EnvironmentObject private var posizione: Posizione
    @Environment(\.dismiss) private var chiudi
    @State private var quando = "uscire"
    @State private var proposta: PropostaAvviso?
    @State private var caricando = true
    @State private var scendi = false
    @State private var attivando = false

    var body: some View {
        NavigationStack {
            Group {
                if let p = proposta {
                    Form {
                        Section {
                            HStack(spacing: 12) {
                                BadgeLinea(nome: p.linea, colore: p.colore, grande: true)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.titolo).font(.title3.bold())
                                    Text(p.sotto).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                            if let nota = p.nota {
                                Label {
                                    Text(nota).font(.subheadline)
                                } icon: {
                                    Image(systemName: "figure.walk").foregroundStyle(Tavolozza.oro)
                                }
                                .listRowBackground(Tavolozza.oro.opacity(0.14))
                            }
                        }
                        Section {
                            Picker(tx("Quando"), selection: $quando) {
                                ForEach(p.opzioni.filter { $0.abilitata }, id: \.codice) { o in
                                    Text(o.etichetta).tag(o.codice)
                                }
                            }
                            .pickerStyle(.segmented)
                            .accessibilityIdentifier("quando")
                            Text(p.spiegazione).font(.subheadline)
                        } header: {
                            Text(tx("Quando"))
                        }
                        Section {
                            Toggle(isOn: $scendi) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(tx("Poi avvisami quando scendere"))
                                    Text(tx("scegli la fermata quando sei a bordo")).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .tint(Tavolozza.oro)
                        }
                        Section {
                            Button {
                                attivando = true
                                Task {
                                    await seguito.avvisami(
                                        fermataId: fermataId, lineaId: lineaId, capolinea: capolinea,
                                        quando: p.scelta, scendi: scendi, posizione: coordinate
                                    )
                                    attivando = false
                                    chiudi()
                                }
                            } label: {
                                Label(tx("Attiva avviso"), systemImage: "bell.fill")
                                    .font(.body.bold())
                                    .frame(maxWidth: .infinity)
                            }
                            .disabled(!p.possibile || attivando)
                            .buttonStyle(.borderedProminent)
                            .tint(Tavolozza.oro)
                            .foregroundStyle(Tavolozza.inchiostro)
                            .listRowBackground(Color.clear)
                            .accessibilityIdentifier("attiva-avviso")
                        }
                    }
                } else if caricando {
                    HStack { ProgressView(); Text(tx("Carico i tempi…")).foregroundStyle(.secondary) }
                } else {
                    Text(tx("Non riesco a leggere i tempi d'attesa. Controlla la connessione.")).foregroundStyle(.secondary).padding()
                }
            }
            .navigationTitle(tx("Avvisami"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tx("Annulla")) { chiudi() }
                }
            }
        }
        .task(id: quando) {
            let p = await seguito.proposta(fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, quando: quando, posizione: coordinate)
            proposta = p
            caricando = false
            if let p, p.scelta != quando { quando = p.scelta }
        }
    }

    private var coordinate: (Double, Double)? {
        guard let lat = posizione.lat, let lon = posizione.lon else { return nil }
        return (lat, lon)
    }
}

/** L'avviso in corso (in Home): come nella Live Activity, con i pulsanti. */
struct SchedaSeguito: View {
    let stato: StatoSegui
    @EnvironmentObject private var seguito: Seguito
    @EnvironmentObject private var navigazione: Navigazione
    @AppStorage("cifre_led") private var cifreLed = true

    var body: some View {
        let conto = Date(timeIntervalSince1970: Double(stato.contoMs) / 1000)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                BadgeLinea(nome: stato.linea, colore: stato.colore, grande: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(stato.titoloScheda).font(.headline).foregroundStyle(Tavolozza.oro).lineLimit(2)
                    Text(stato.testoScheda).font(.footnote).foregroundStyle(.secondary).lineLimit(3)
                }
                Spacer()
                if stato.contoMs > 0 {
                    Group {
                        if conto > Date() {
                            Text(timerInterval: Date()...conto, countsDown: true)
                        } else {
                            Text(tx("ora§adesso"))
                        }
                    }
                    .font(.title2.bold())
                    .monospacedDigit()
                    .foregroundStyle(stato.tempoReale ? Tavolozza.oro : .primary)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(stato.titoloScheda + ". " + stato.testoScheda)
            if stato.fineMs > stato.inizioMs {
                ProgressView(
                    timerInterval: Date(timeIntervalSince1970: Double(stato.inizioMs) / 1000)...Date(timeIntervalSince1970: Double(stato.fineMs) / 1000),
                    countsDown: false
                ) { EmptyView() } currentValueLabel: { EmptyView() }
                .tint(Tavolozza.oro)
            }
            Text(stato.dettaglio).font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 14) {
                Spacer(minLength: 0)
                if stato.mostraUscito {
                    Button(tx("Sto uscendo")) { seguito.stoUscendo() }.buttonStyle(.borderless)
                }
                if stato.mostraSuccessivo {
                    Button(tx("Prendo il successivo")) { Task { await seguito.prendiSuccessivo() } }.buttonStyle(.borderless)
                }
                if stato.mostraScegli {
                    Button(tx("Scegli dove scendi")) { navigazione.percorso.append(.scendi) }
                        .buttonStyle(.borderedProminent)
                        .tint(Tavolozza.oro)
                        .foregroundStyle(Tavolozza.inchiostro)
                }
                Button(role: .destructive) { seguito.smetti() } label: {
                    Text(stato.mostraFine ? tx("Fine viaggio") : tx("Smetti"))
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.red)
            }
            .font(.subheadline)
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("seguito")
    }
}

/** Dove scendi: le fermate della linea dopo quella dove sei salito. */
struct ScendiVista: View {
    @EnvironmentObject private var seguito: Seguito
    @EnvironmentObject private var navigazione: Navigazione
    @State private var fermate: [FermataDiscesa]?

    var body: some View {
        List {
            if seguito.stato == nil {
                Text(tx("Nessun avviso in corso.")).foregroundStyle(.secondary)
            } else if let fermate {
                if fermate.isEmpty {
                    Text(tx("Non trovo le fermate di questa linea.")).foregroundStyle(.secondary)
                } else {
                    Section {
                        ForEach(fermate, id: \.id) { f in
                            Button {
                                Task {
                                    await seguito.scendiA(f.id)
                                    if !navigazione.percorso.isEmpty { navigazione.percorso.removeLast() }
                                }
                            } label: {
                                HStack {
                                    Text(f.nome).foregroundStyle(.primary)
                                    Spacer()
                                    if !f.ora.isEmpty { Text(f.ora).monospacedDigit().foregroundStyle(Tavolozza.oro) }
                                }
                            }
                        }
                    } footer: {
                        Text(tx("Ti avviso quando il mezzo lascia la fermata prima: «Scendi alla prossima»."))
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(tx("Dove scendi?"))
        .navigationBarTitleDisplayMode(.inline)
        .task { fermate = await seguito.fermateDiscesa() }
    }
}

/** Gli avvisi di Roma Mobilita': i primi due, poi "mostra tutti"; il testo si apre toccandoli. */
struct SezioneAvvisi: View {
    let avvisi: [SchedaAvviso]
    @State private var tutti = false
    @State private var aperto: String?

    var body: some View {
        if !avvisi.isEmpty {
            Section {
                ForEach(tutti ? avvisi : Array(avvisi.prefix(2)), id: \.id) { a in
                    VStack(alignment: .leading, spacing: 3) {
                        let sopra = [a.tipo, a.linee.isEmpty ? nil : a.linee.joined(separator: ", ")].compactMap { $0 }.joined(separator: " · ")
                        if !sopra.isEmpty { Text(sopra).font(.caption.bold()).foregroundStyle(Tavolozza.oro) }
                        Text(a.titolo).font(.subheadline.weight(.medium)).foregroundStyle(a.futuro ? .secondary : .primary)
                            .lineLimit(aperto == a.id ? nil : 3)
                        if let periodo = a.periodo { Text(periodo).font(.caption).foregroundStyle(.secondary) }
                        if aperto == a.id {
                            if !a.testo.isEmpty { Text(a.testo).font(.caption) }
                            if let url = a.url.flatMap({ URL(string: $0) }) {
                                Link(tx("Dettagli su Roma Mobilità"), destination: url).font(.caption.bold())
                            }
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { aperto = aperto == a.id ? nil : a.id }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(a.perVoce)
                }
                if avvisi.count > 2 {
                    Button(tutti ? tx("Mostra meno") : tx("Mostra tutti ({0})", avvisi.count)) { tutti.toggle() }
                }
            } header: {
                Label(tx("Avvisi di Roma Mobilità"), systemImage: "exclamationmark.triangle.fill").foregroundStyle(Tavolozza.oro)
            }
            .accessibilityIdentifier("avvisi-roma")
        }
    }
}

/** Un tragitto salvato in Home: "Lavoro → Casa · ESCI TRA 4 min". */
struct RigaTragittoSalvato: View {
    let carta: CartaSalvato

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(carta.nome).font(.headline).lineLimit(1)
                if !carta.viaggio.isEmpty {
                    Text(carta.viaggio).font(.footnote.bold()).foregroundStyle(Tavolozza.oro).lineLimit(2)
                }
                Text(carta.sotto).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                if let poi = carta.poi { Text(poi).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 4)
            if !carta.esci.isEmpty {
                VStack(alignment: .trailing, spacing: 0) {
                    Text(carta.esci).font(.caption2.bold()).foregroundStyle(Tavolozza.oro)
                    if !carta.numero.isEmpty {
                        HStack(alignment: .firstTextBaseline, spacing: 2) {
                            Text(carta.numero).font(Tavolozza.cifre(28, aPunti: true)).foregroundStyle(Tavolozza.oro)
                            Text(carta.unita).font(.caption).foregroundStyle(Tavolozza.oro)
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(carta.perVoce)
        .accessibilityIdentifier("tragitto-salvato")
    }
}
