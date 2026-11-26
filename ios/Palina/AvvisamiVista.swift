import SwiftUI
import Motore

/**
 * Il foglio "Avvisami per il 64", come nel disegno: la linea grande, cosa
 * propone l'app (il successivo, se il prossimo non lo prendi in tempo), le tre
 * scelte di "quando", "Seguilo sulla schermata di blocco" e "Poi avvisami quando
 * scendere", Annulla e Attiva avviso. Come su Android: tutto dal motore.
 */
struct AvvisamiVista: View {
    let fermataId: String
    let lineaId: String
    let capolinea: String
    @EnvironmentObject private var seguito: Seguito
    @EnvironmentObject private var posizione: Posizione
    @Environment(\.dismiss) private var chiudi
    @AppStorage("cifre_led") private var cifreLed = true
    @State private var quando = "uscire"
    @State private var proposta: PropostaAvviso?
    @State private var caricando = true
    @State private var scendi = false
    @State private var blocco = true
    @State private var attivando = false
    /** "Ripeti ogni settimana…": il promemoria ricorrente per questa linea. */
    @State private var promemoria: PromemoriaAperto?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let p = proposta {
                    HStack(spacing: 12) {
                        Text(p.linea)
                            .font(Tavolozza.cifre(25, aPunti: cifreLed))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                            .padding(.horizontal, 6)
                            .frame(minWidth: 52, minHeight: 40)
                            .background(Color(argb: p.colore), in: RoundedRectangle(cornerRadius: 9))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.titolo).font(.system(size: 20, weight: .bold))
                            Text(p.sotto).font(.system(size: 13.5)).foregroundStyle(.secondary)
                        }
                    }
                    if let nota = p.nota {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "figure.walk").font(.system(size: 20)).foregroundStyle(Tavolozza.oro)
                            Text(nota).font(.system(size: 14))
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Tavolozza.oro.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(tx("Quando").uppercased())
                            .font(.system(size: 12, weight: .bold))
                            .tracking(1.6)
                            .foregroundStyle(Tavolozza.oro)
                        // le tre scelte in griglia, come nel disegno
                        HStack(spacing: 8) {
                            ForEach(p.opzioni, id: \.codice) { o in
                                let scelta = o.codice == p.scelta
                                Button { quando = o.codice } label: {
                                    Text(o.etichetta)
                                        .font(.system(size: 14, weight: .medium))
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                        .frame(maxWidth: .infinity, minHeight: 48)
                                        .foregroundStyle(scelta ? Tavolozza.oro : (o.abilitata ? Color.primary : Color.secondary.opacity(0.5)))
                                        .background(scelta ? Tavolozza.oro.opacity(0.14) : Color.clear, in: RoundedRectangle(cornerRadius: 14))
                                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(scelta ? Tavolozza.oro : Color.secondary.opacity(0.35), lineWidth: 1.5))
                                }
                                .buttonStyle(.plain)
                                .disabled(!o.abilitata)
                                .accessibilityAddTraits(scelta ? AccessibilityTraits.isSelected : AccessibilityTraits())
                                .accessibilityIdentifier("quando-" + o.codice)
                            }
                        }
                        Text(p.spiegazione).font(.system(size: 14))
                    }
                    VStack(spacing: 0) {
                        Divider()
                        interruttore(tx("Seguilo sulla schermata di blocco"), tx("quanto manca, senza aprire l'app"), $blocco)
                            .accessibilityIdentifier("interruttore-blocco")
                        Divider()
                        interruttore(tx("Poi avvisami quando scendere"), tx("scegli la fermata quando sei a bordo"), $scendi)
                            .accessibilityIdentifier("interruttore-scendi")
                    }
                    HStack(spacing: 12) {
                        Button { chiudi() } label: {
                            Text(tx("Annulla"))
                                .font(.system(size: 15, weight: .medium))
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .foregroundStyle(Tavolozza.oro)
                                .overlay(Capsule().stroke(Tavolozza.oro.opacity(0.4), lineWidth: 1.5))
                        }
                        .buttonStyle(.plain)
                        Button {
                            attivando = true
                            Task {
                                await seguito.avvisami(
                                    fermataId: fermataId, lineaId: lineaId, capolinea: capolinea,
                                    quando: p.scelta, scendi: scendi, blocco: blocco, posizione: coordinate
                                )
                                attivando = false
                                chiudi()
                            }
                        } label: {
                            Label(tx("Attiva avviso"), systemImage: "bell")
                                .font(.system(size: 15, weight: .bold))
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .foregroundStyle(Tavolozza.inchiostro)
                                .background(Tavolozza.oro, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .layoutPriority(1)
                        .frame(maxWidth: .infinity)
                        .disabled(!p.possibile || attivando)
                        .opacity(!p.possibile || attivando ? 0.5 : 1)
                        .accessibilityIdentifier("attiva-avviso")
                    }
                    Button(tx("Ripeti ogni settimana…")) {
                        promemoria = PromemoriaAperto(
                            promemoria: PromemoriaKt.promemoriaPerLinea(
                                fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, nomeLinea: p.linea, quando: p.scelta,
                                minuti: PromemoriaKt.minutiProposti(adessoMs: Int64(Date().timeIntervalSince1970 * 1000))
                            ),
                            esistente: false
                        )
                    }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Tavolozza.oro)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("ripeti-promemoria")
                    Text(tx("La prima volta l'iPhone ti chiede il permesso per le notifiche."))
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                } else if caricando {
                    HStack { ProgressView(); Text(tx("Carico i tempi…")).foregroundStyle(.secondary) }
                } else {
                    Text(tx("Non riesco a leggere i tempi d'attesa. Controlla la connessione.")).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .padding(.bottom, 24)
        }
        .presentationDragIndicator(.visible)
        .sheet(item: $promemoria) { PromemoriaVista(aperto: $0) }
        .task(id: quando) {
            let p = await seguito.proposta(fermataId: fermataId, lineaId: lineaId, capolinea: capolinea, quando: quando, posizione: coordinate)
            proposta = p
            caricando = false
            if let p, p.scelta != quando { quando = p.scelta }
        }
    }

    /** Un interruttore del disegno: titolo, spiegazione, l'interruttore d'oro. */
    private func interruttore(_ titolo: String, _ sotto: String, _ valore: Binding<Bool>) -> some View {
        Toggle(isOn: valore) {
            VStack(alignment: .leading, spacing: 2) {
                Text(titolo).font(.system(size: 15))
                Text(sotto).font(.system(size: 12.5)).foregroundStyle(.secondary)
            }
        }
        .tint(Tavolozza.oro)
        .frame(minHeight: 60)
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
                if !carta.ultima.isEmpty {
                    Text(carta.ultima.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1).foregroundStyle(Tavolozza.oro)
                }
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
