import SwiftUI
import Motore

/** Una fermata: il tabellone (in cima le linee fissate) e le linee che ci passano. */
struct FermataVista: View {
    let fermataId: String
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var preferiti: Preferiti
    @EnvironmentObject private var navigazione: Navigazione
    @EnvironmentObject private var tragitti: Tragitti
    @EnvironmentObject private var seguito: Seguito
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
                tempi
                Section("Linee") {
                    ForEach(linee, id: \.self) { l in
                        NavigationLink(value: Destinazione.linea(RichiestaLinea(lineaId: l.lineaId, direzione: Int(l.direzione), fermata: fermataId))) {
                            HStack(spacing: 10) {
                                BadgeLinea(nome: l.nome, colore: l.colore, grande: true)
                                Text("→ \(l.capolinea)").lineLimit(2)
                            }
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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    tragitti.versoFermata(fermataId)
                    navigazione.percorso.append(.tragitti)
                } label: {
                    Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                }
                .accessibilityLabel("Come arrivo qui")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    preferiti.alternaPreferita(fermataId)
                } label: {
                    Image(systemName: preferiti.eUnaPreferita(fermataId) ? "heart.fill" : "heart")
                }
                .accessibilityLabel(preferiti.eUnaPreferita(fermataId) ? "Togli dai preferiti" : "Aggiungi ai preferiti")
            }
        }
        .task(id: archivio.orario != nil) { await segui() }
        .onChange(of: preferiti.fissate) { _, nuove in
            motore?.impostaFissate(chiavi: nuove)
            tabellone = motore?.tabellone()
        }
    }

    @ViewBuilder
    private var tempi: some View {
        let t = tabellone
        if let t, t.conFissate {
            Section("Le tue linee") {
                ForEach(t.fissate, id: \.self) { riga(for: $0) }
                ForEach(t.fissateSenzaPassaggi, id: \.self) { nome in
                    Text("\(nome) · non passa nei prossimi 90 minuti").foregroundStyle(.secondary)
                }
            }
        }
        Section {
            if let t, !t.altre.isEmpty {
                ForEach(t.altre, id: \.self) { riga(for: $0) }
            } else if caricando {
                HStack { ProgressView(); Text("Leggo i tempi…").foregroundStyle(.secondary) }
            } else if t?.fissate.isEmpty ?? true {
                Text("Nessun passaggio nei prossimi 90 minuti.").foregroundStyle(.secondary)
            }
        } header: {
            Text(t?.conFissate == true ? "Altre linee" : "Prossimi passaggi")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if let suggerimento = t?.suggerimento { Text(suggerimento) }
                if let aggiornato = t?.aggiornato { Text(aggiornato) }
            }
        }
    }

    /** Una riga: si apre la linea con la sua vettura (o il treno); scorrendo si fissa o si toglie. */
    private func riga(for riga: RigaArrivo) -> some View {
        // un treno apre tutte le sue fermate; un bus la linea con la sua vettura
        NavigationLink(value: riga.treno.map { Destinazione.corsa($0) } ?? Destinazione.linea(RichiestaLinea(
            lineaId: riga.lineaId,
            direzione: Int(riga.direzione),
            corsa: riga.corsaId.isEmpty ? nil : riga.corsaId,
            fermata: fermataId
        ))) {
            RigaTabellone(riga: riga)
        }
        .swipeActions(edge: .trailing) {
            Button {
                if let chiave = motore?.chiaveDaFissare(lineaId: riga.lineaId) { preferiti.alternaFissata(chiave) }
            } label: {
                Label(riga.fissata ? "Togli" : "Fissa", systemImage: riga.fissata ? "pin.slash" : "pin")
            }
            .tint(Tavolozza.oro)
            Button {
                Task { await seguito.segui(fermataId: fermataId, lineaId: riga.lineaId, capolinea: riga.capolinea) }
            } label: {
                Label("Segui", systemImage: "bell")
            }
            .tint(Tavolozza.porpora)
        }
        .contextMenu {
            Button {
                Task { await seguito.segui(fermataId: fermataId, lineaId: riga.lineaId, capolinea: riga.capolinea) }
            } label: {
                Label("Segui il \(riga.nome): avviso 2 minuti prima", systemImage: "bell")
            }
            Button {
                if let chiave = motore?.chiaveDaFissare(lineaId: riga.lineaId) { preferiti.alternaFissata(chiave) }
            } label: {
                Label(riga.fissata ? "Togli dalle tue linee" : "Fissa in cima", systemImage: riga.fissata ? "pin.slash" : "pin")
            }
        }
        .accessibilityAction(named: riga.fissata ? "Togli il \(riga.nome) dalle tue linee" : "Fissa il \(riga.nome) in cima") {
            if let chiave = motore?.chiaveDaFissare(lineaId: riga.lineaId) { preferiti.alternaFissata(chiave) }
        }
        .accessibilityAction(named: "Segui il \(riga.nome)") {
            Task { await seguito.segui(fermataId: fermataId, lineaId: riga.lineaId, capolinea: riga.capolinea) }
        }
    }

    /** I tempi si chiedono ogni 30 secondi; in mezzo i minuti si ricalcolano ogni 5. */
    private func segui() async {
        guard let orario = archivio.orario else { return }
        let m = motore ?? MotoreFermata(orario: orario, fermataId: fermataId)
        motore = m
        m.impostaFissate(chiavi: preferiti.fissate)
        voce = m.intestazione()
        linee = m.linee()
        var ultimaRichiesta = Date.distantPast
        while !Task.isCancelled {
            if Date().timeIntervalSince(ultimaRichiesta) >= 30 {
                ultimaRichiesta = Date()
                if let nuovo = try? await m.aggiorna() { tabellone = nuovo }
                if caricando, Prova.apriPrimoTreno,
                   let treno = ((tabellone?.fissate ?? []) + (tabellone?.altre ?? [])).compactMap({ $0.treno }).first {
                    navigazione.percorso.append(.corsa(treno))
                }
                // prove: -provaSegui YES segue la prima linea del tabellone
                if caricando, Prova.segui, let prima = ((tabellone?.fissate ?? []) + (tabellone?.altre ?? [])).first {
                    await seguito.segui(fermataId: fermataId, lineaId: prima.lineaId, capolinea: prima.capolinea)
                }
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
    @AppStorage("cifre_led") private var cifreLed = true

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
            if riga.fissata {
                Image(systemName: "pin.fill").font(.caption).foregroundStyle(Tavolozza.oro).accessibilityHidden(true)
            }
            VStack(alignment: .trailing, spacing: 2) {
                if riga.minuti {
                    Text(riga.attesa + "′")
                        .font(Tavolozza.cifre(26, aPunti: cifreLed))
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
