import MapKit
import SwiftUI
import Motore

/** La ricerca dei tragitti: resta viva passando dall'elenco al dettaglio e alla scelta dei luoghi. */
@MainActor
final class Tragitti: ObservableObject {
    @Published private(set) var stato: StatoTragitti?
    /** In attesa della posizione per partire (o arrivare) da "La mia posizione". */
    @Published private(set) var aspettoPosizione = false
    @Published private(set) var inCorso = false
    @Published private(set) var caricandoAltri = false
    private(set) var motore: MotoreTragitti?
    private var orario: Orario?
    private let memoria = UserDefaults.standard

    /** Con l'orario aperto (o con quello nuovo, dopo un aggiornamento). */
    func prepara(orario: Orario) {
        guard self.orario !== orario else { return }
        self.orario = orario
        motore = MotoreTragitti(orario: orario)
        aggiorna()
    }

    func aggiorna() { stato = motore?.stato() }

    func versoFermata(_ id: String) {
        motore?.versoFermata(fermataId: id)
        aggiorna()
    }

    func scegli(_ luogo: Luogo, campo: String) {
        guard let motore else { return }
        memoria.set(motore.ricorda(salvati: memoria.string(forKey: "luoghi_recenti") ?? "", luogo: luogo), forKey: "luoghi_recenti")
        if campo == "da" { motore.scegliDa(luogo: luogo) } else { motore.scegliA(luogo: luogo) }
        aggiorna()
    }

    var recenti: [Luogo] { motore?.recenti(salvati: memoria.string(forKey: "luoghi_recenti") ?? "") ?? [] }

    func inverti() { motore?.inverti(); aggiorna() }
    func adesso() { motore?.adesso(); aggiorna() }
    func parti(_ data: Date) { motore?.parti(istante: Int64(data.timeIntervalSince1970 * 1000)); aggiorna() }
    func arriva(_ data: Date) { motore?.arriva(istante: Int64(data.timeIntervalSince1970 * 1000)); aggiorna() }

    /** Cerca se e' cambiato qualcosa o se i risultati hanno piu' di due minuti. */
    func cercaSeServe(posizione: Posizione) async {
        guard let motore, motore.serveRicerca() else { return }
        await cerca(posizione: posizione)
    }

    func cerca(posizione: Posizione) async {
        guard let motore else { return }
        if motore.servePosizione(), posizione.lat == nil, !posizione.negata {
            // la posizione arriva tra poco: la ricerca riparte quando c'e'
            aspettoPosizione = true
            return
        }
        aspettoPosizione = false
        inCorso = true
        let nuovo = try? await motore.cerca(lat: posizione.lat ?? .nan, lon: posizione.lon ?? .nan)
        inCorso = false
        stato = nuovo ?? motore.stato()
    }

    func altri(dopo: Bool) async {
        guard let motore, !caricandoAltri else { return }
        caricandoAltri = true
        let nuovo = try? await motore.altri(dopo: dopo)
        caricandoAltri = false
        stato = nuovo ?? motore.stato()
    }
}

/** I campi Da e A, quando partire e i tragitti trovati. */
struct TragittiVista: View {
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione
    @EnvironmentObject private var tragitti: Tragitti
    @EnvironmentObject private var navigazione: Navigazione
    @State private var sceltaOrario: Bool? = nil

    var body: some View {
        List {
            if let stato = tragitti.stato {
                Section {
                    ModuloLuoghi(stato: stato)
                }
                Section {
                    SceltaQuando(stato: stato, scegli: { arrivo in
                        if let arrivo { sceltaOrario = arrivo } else { tragitti.adesso() }
                    })
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                    .listRowBackground(Color.clear)
                }
                if tragitti.motore?.servePosizione() == true && posizione.negata {
                    Section {
                        Text(TragittiKt.avvisoPermessoTragitti(negatoPerSempre: true)).foregroundStyle(Tavolozza.oro)
                        Button("Apri le impostazioni") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }
                risultati(stato)
            } else {
                HStack { ProgressView(); Text("Apro l'orario…").foregroundStyle(.secondary) }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Tragitto")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await tragitti.cerca(posizione: posizione) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("Calcola di nuovo")
                .disabled(tragitti.stato?.a == nil)
            }
        }
        .onAppear {
            if let orario = archivio.orario { tragitti.prepara(orario: orario) }
            posizione.avvia()
        }
        .task(id: chiave) {
            await tragitti.cercaSeServe(posizione: posizione)
            if Prova.apriPrimoTragitto, tragitti.stato?.carte.isEmpty == false, navigazione.percorso.last == .tragitti {
                navigazione.percorso.append(.dettaglioTragitto(0))
            }
        }
        .sheet(item: Binding(
            get: { sceltaOrario.map { OrarioDaScegliere(arrivo: $0) } },
            set: { sceltaOrario = $0?.arrivo }
        )) { scelta in
            DialogoOrario(arrivo: scelta.arrivo, iniziale: Date(timeIntervalSince1970: Double(tragitti.stato?.istante ?? 0) / 1000)) { data in
                if scelta.arrivo { tragitti.arriva(data) } else { tragitti.parti(data) }
                sceltaOrario = nil
            }
            .presentationDetents([.medium])
        }
    }

    /** Ricerca di nuovo quando cambiano luoghi, orario o arriva la posizione. */
    private var chiave: String {
        guard let s = tragitti.stato else { return "" }
        return "\(s.da?.inRigaSwift ?? "")#\(s.a?.inRigaSwift ?? "")#\(s.quando)#\(s.quando == 0 ? 0 : s.istante)#\(posizione.lat != nil)"
    }

    @ViewBuilder
    private func risultati(_ stato: StatoTragitti) -> some View {
        if tragitti.aspettoPosizione {
            HStack { ProgressView(); Text("Cerco dove sei…").foregroundStyle(.secondary) }
        } else if tragitti.inCorso || stato.inCorso {
            HStack(spacing: 12) { ProgressView(); Text("Calcolo i tragitti…").foregroundStyle(.secondary) }
        } else {
            if let messaggio = stato.messaggio {
                Section {
                    Text(messaggio).foregroundStyle(stato.riprova ? Tavolozza.oro : .secondary)
                    if stato.riprova {
                        Button("Riprova") { Task { await tragitti.cerca(posizione: posizione) } }
                    }
                }
            }
            if stato.prima {
                Button("Partenze precedenti") { Task { await tragitti.altri(dopo: false) } }
                    .disabled(tragitti.caricandoAltri)
            }
            if !stato.carte.isEmpty {
                Section {
                    ForEach(Array(stato.carte.enumerated()), id: \.offset) { i, carta in
                        NavigationLink(value: Destinazione.dettaglioTragitto(i)) { CartaTragittoVista(carta: carta) }
                            .accessibilityIdentifier("tragitto")
                    }
                }
            }
            if stato.dopo {
                Button(tragitti.caricandoAltri ? "Cerco…" : "Partenze successive") { Task { await tragitti.altri(dopo: true) } }
                    .disabled(tragitti.caricandoAltri)
            }
            if let aPiedi = stato.aPiedi {
                Section {
                    NavigationLink(value: Destinazione.dettaglioTragitto(-1)) {
                        HStack(spacing: 12) {
                            Image(systemName: "figure.walk").font(.title3).foregroundStyle(.secondary)
                            VStack(alignment: .leading) {
                                Text(aPiedi.orari).font(.body.weight(.semibold))
                                if !aPiedi.cambiEPiedi.isEmpty {
                                    Text(aPiedi.cambiEPiedi).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(aPiedi.perVoce)
                    }
                }
            }
            if let calcolato = stato.calcolato {
                Text(calcolato).font(.footnote).foregroundStyle(.secondary)
            }
            if !stato.carte.isEmpty || stato.aPiedi != nil {
                Attribuzione()
            }
        }
    }
}

private struct OrarioDaScegliere: Identifiable {
    let arrivo: Bool
    var id: Bool { arrivo }
}

extension Luogo {
    /** Il luogo su una riga (per riconoscere un cambio di partenza o arrivo). */
    var inRigaSwift: String { "\(tipo.name)|\(lat)|\(lon)|\(nome)" }
}

/** Da e A, con il pulsante per scambiarli. */
private struct ModuloLuoghi: View {
    let stato: StatoTragitti
    @EnvironmentObject private var tragitti: Tragitti

    var body: some View {
        HStack {
            VStack(spacing: 0) {
                NavigationLink(value: Destinazione.luogo(campo: "da", testo: "")) {
                    CampoLuogo(etichetta: "Da", luogo: stato.da, vuoto: "Scegli la partenza", partenza: true)
                }
                .accessibilityIdentifier("campo-da")
                Divider().padding(.leading, 30)
                NavigationLink(value: Destinazione.luogo(campo: "a", testo: "")) {
                    CampoLuogo(etichetta: "A", luogo: stato.a, vuoto: "Dove vuoi andare?", partenza: false)
                }
                .accessibilityIdentifier("campo-a")
            }
            Button {
                tragitti.inverti()
            } label: {
                Image(systemName: "arrow.up.arrow.down").font(.title3.bold()).foregroundStyle(Tavolozza.oro)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Scambia partenza e arrivo")
        }
    }
}

private struct CampoLuogo: View {
    let etichetta: String
    let luogo: Luogo?
    let vuoto: String
    let partenza: Bool

    var body: some View {
        HStack(spacing: 14) {
            PallinoTappa(partenza: partenza)
            VStack(alignment: .leading, spacing: 1) {
                Text(etichetta).font(.caption).foregroundStyle(.secondary)
                Text(luogo?.nome ?? vuoto)
                    .font(luogo == nil ? .body : .body.weight(.semibold))
                    .foregroundStyle(luogo == nil ? .secondary : .primary)
                    .lineLimit(1)
                if let dettaglio = luogo?.dettaglio, !dettaglio.isEmpty {
                    Text(dettaglio).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/** Cerchio bianco bordato di porpora per la partenza, pieno d'oro per l'arrivo. */
struct PallinoTappa: View {
    let partenza: Bool
    var lato: CGFloat = 16

    var body: some View {
        Circle()
            .fill(partenza ? Color.white : Tavolozza.oro)
            .overlay(Circle().stroke(partenza ? Tavolozza.porpora : Tavolozza.inchiostro, lineWidth: 3))
            .frame(width: lato, height: lato)
    }
}

/** Adesso / Parti alle… / Arriva entro… */
private struct SceltaQuando: View {
    let stato: StatoTragitti
    let scegli: (Bool?) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("Adesso", scelto: stato.quando == 0) { scegli(nil) }
                chip(stato.etichettaParti, scelto: stato.quando == 1) { scegli(false) }
                chip(stato.etichettaArriva, scelto: stato.quando == 2) { scegli(true) }
            }
        }
    }

    private func chip(_ testo: String, scelto: Bool, azione: @escaping () -> Void) -> some View {
        Button(action: azione) {
            Text(testo)
                .font(.subheadline.weight(scelto ? .semibold : .regular))
                .foregroundStyle(scelto ? Tavolozza.inchiostro : .primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(scelto ? Tavolozza.oro : Color(.secondarySystemGroupedBackground), in: Capsule())
                .overlay(Capsule().stroke(Color(.separator), lineWidth: scelto ? 0 : 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(scelto ? .isSelected : [])
    }
}

/** Oggi o domani e l'ora, per partire o arrivare. */
private struct DialogoOrario: View {
    let arrivo: Bool
    let conferma: (Date) -> Void
    @Environment(\.dismiss) private var chiudi
    @State private var ora: Date
    @State private var domani: Bool

    init(arrivo: Bool, iniziale: Date, conferma: @escaping (Date) -> Void) {
        self.arrivo = arrivo
        self.conferma = conferma
        let inizio = iniziale < Date().addingTimeInterval(-60) ? Date() : iniziale
        _ora = State(initialValue: inizio)
        _domani = State(initialValue: Calendar.current.isDateInTomorrow(inizio))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                Picker("Giorno", selection: $domani) {
                    Text("Oggi").tag(false)
                    Text("Domani").tag(true)
                }
                .pickerStyle(.segmented)
                DatePicker("Ora", selection: $ora, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                    .environment(\.locale, Locale(identifier: "it_IT"))
                Spacer()
            }
            .padding()
            .navigationTitle(arrivo ? "Vuoi arrivare entro le…" : "Vuoi partire alle…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { chiudi() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Conferma") {
                        let calendario = Calendar.current
                        let parti = calendario.dateComponents([.hour, .minute], from: ora)
                        let giorno = calendario.date(byAdding: .day, value: domani ? 1 : 0, to: calendario.startOfDay(for: Date())) ?? Date()
                        let scelta = calendario.date(bySettingHour: parti.hour ?? 0, minute: parti.minute ?? 0, second: 0, of: giorno) ?? Date()
                        conferma(scelta)
                    }
                    .bold()
                }
            }
        }
    }
}

/** Una carta nell'elenco: orari, durata, la sequenza dei mezzi, il primo mezzo, cambi e tempo reale. */
struct CartaTragittoVista: View {
    let carta: CartaTragitto

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(carta.orari).font(.title3.bold()).monospacedDigit()
                Spacer()
                Text(carta.durata).font(.title3.bold())
            }
            SequenzaPezzi(pezzi: carta.pezzi)
            if let primo = carta.primoMezzo {
                Text(primo).font(.subheadline).lineLimit(2)
            }
            HStack {
                Text(carta.cambiEPiedi).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                if carta.soppressa {
                    Text("Corsa soppressa").font(.footnote.bold()).foregroundStyle(Tavolozza.oro)
                } else if carta.tempoReale {
                    Label("tempo reale", systemImage: "dot.radiowaves.up.forward")
                        .font(.caption).foregroundStyle(Tavolozza.oro)
                } else {
                    Text("da orario").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(carta.perVoce)
    }
}

/** [a piedi 5] › [714] › [Metro B] */
struct SequenzaPezzi: View {
    let pezzi: [PezzoTragitto]

    var body: some View {
        DisposizioneACapo(spazio: 4) {
            ForEach(Array(pezzi.enumerated()), id: \.offset) { i, pezzo in
                if i > 0 { Text("›").font(.subheadline.bold()).foregroundStyle(.secondary) }
                if pezzo.aPiedi {
                    HStack(spacing: 1) {
                        Image(systemName: "figure.walk").font(.footnote)
                        Text(pezzo.testo).font(.footnote)
                    }
                    .foregroundStyle(.secondary)
                } else {
                    BadgeLinea(nome: pezzo.testo, colore: pezzo.colore)
                }
            }
        }
    }
}

/** Attribuzione richiesta da Transitous, con il collegamento alle fonti. */
struct Attribuzione: View {
    var body: some View {
        Link(destination: URL(string: Transitous.shared.FONTI)!) {
            Text(TragittiKt.attribuzioneTransitous())
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.leading)
        }
    }
}

// MARK: - Scelta di partenza e arrivo

struct SceltaLuogoVista: View {
    let campo: String
    @State var testo: String
    @EnvironmentObject private var tragitti: Tragitti
    @EnvironmentObject private var preferiti: Preferiti
    @EnvironmentObject private var posizione: Posizione
    @Environment(\.dismiss) private var chiudi
    @State private var trovati: LuoghiTrovati?
    @State private var cercando = false
    @FocusState private var fuoco: Bool

    var body: some View {
        List {
            Section {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Indirizzo, luogo o fermata", text: $testo)
                        .focused($fuoco)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                    if !testo.isEmpty {
                        Button { testo = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Cancella")
                    }
                }
                if cercando { ProgressView().frame(maxWidth: .infinity) }
            }
            if testo.trimmingCharacters(in: .whitespaces).count < 3 {
                Section {
                    riga(Luogo.companion.posizione(), icona: "location.fill", dettaglio: "Dal telefono: va a Transitous solo per calcolare il tragitto")
                }
                let recenti = tragitti.recenti
                if !recenti.isEmpty {
                    Section("Recenti") { ForEach(Array(recenti.enumerated()), id: \.offset) { _, l in riga(l) } }
                }
                let preferite = tragitti.motore?.preferite(ids: preferiti.fermate) ?? []
                if !preferite.isEmpty {
                    Section("Fermate preferite") {
                        ForEach(Array(preferite.enumerated()), id: \.offset) { _, l in riga(l, icona: "heart.fill") }
                    }
                }
            } else if let trovati {
                if !trovati.fermate.isEmpty {
                    Section("Fermate") { ForEach(Array(trovati.fermate.enumerated()), id: \.offset) { _, l in riga(l) } }
                }
                if !trovati.luoghi.isEmpty {
                    Section("Indirizzi e luoghi") { ForEach(Array(trovati.luoghi.enumerated()), id: \.offset) { _, l in riga(l) } }
                }
                if let errore = trovati.errore { Text(errore).foregroundStyle(.secondary) }
                if let nessuno = trovati.nessuno, !cercando { Text(nessuno).foregroundStyle(.secondary) }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(campo == "da" ? "Da dove parti?" : "Dove vai?")
        .navigationBarTitleDisplayMode(.inline)
        // con un testo gia' scritto (dalla Home) si vedono subito i risultati, senza tastiera
        .onAppear { fuoco = testo.isEmpty }
        .task(id: "\(testo)#\(tragitti.stato != nil)") { await cerca() }
    }

    private func cerca() async {
        let t = testo.trimmingCharacters(in: .whitespaces)
        guard t.count >= 3, let motore = tragitti.motore else {
            trovati = nil
            cercando = false
            return
        }
        try? await Task.sleep(nanoseconds: 400_000_000)
        if Task.isCancelled { return }
        trovati = LuoghiTrovati(fermate: motore.fermateTrovate(testo: t), luoghi: [], errore: nil, nessuno: nil)
        cercando = true
        let risultato = try? await motore.cercaLuoghi(testo: t, lat: posizione.lat ?? .nan, lon: posizione.lon ?? .nan)
        if Task.isCancelled { return }
        if let risultato { trovati = risultato }
        cercando = false
    }

    private func riga(_ luogo: Luogo, icona: String? = nil, dettaglio: String? = nil) -> some View {
        Button {
            tragitti.scegli(luogo, campo: campo)
            chiudi()
        } label: {
            HStack(spacing: 12) {
                Group {
                    if luogo.tipo == TipoLuogo.fermata && icona == nil {
                        Circle().fill(.white).overlay(Circle().stroke(Tavolozza.porpora, lineWidth: 3)).frame(width: 14, height: 14)
                    } else {
                        Image(systemName: icona ?? (luogo.tipo == TipoLuogo.posizione ? "location.fill" : "mappin.and.ellipse"))
                            .foregroundStyle(icona != nil || luogo.tipo == TipoLuogo.posizione ? Tavolozza.oro : .secondary)
                    }
                }
                .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(luogo.nome).font(.body.weight(.semibold)).lineLimit(2).foregroundStyle(Color.primary)
                    let sotto = dettaglio ?? luogo.dettaglio
                    if !sotto.isEmpty { Text(sotto).font(.footnote).foregroundStyle(Color.secondary).lineLimit(1) }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("luogo")
    }
}

// MARK: - Dettaglio di un tragitto

struct DettaglioTragittoVista: View {
    let indice: Int
    @EnvironmentObject private var tragitti: Tragitti
    @State private var vetture: [VetturaTragitto] = []
    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        let dettaglio = tragitti.motore?.dettaglio(indice: Int32(indice))
        Group {
            if let dettaglio {
                GeometryReader { g in
                VStack(spacing: 0) {
                    mappa
                        .frame(height: g.size.height * 0.45)
                    List {
                        RigaEstremo(partenza: true, testo: dettaglio.partenza, ora: dettaglio.oraPartenza)
                        ForEach(Array(dettaglio.blocchi.enumerated()), id: \.offset) { i, blocco in
                            if blocco.aPiedi {
                                BloccoAPiedi(blocco: blocco)
                            } else {
                                BloccoMezzo(indice: indice, numero: i, blocco: blocco, vettura: vetture.first { $0.blocco == Int32(i) })
                            }
                        }
                        RigaEstremo(partenza: false, testo: dettaglio.arrivo, ora: dettaglio.oraArrivo)
                        Attribuzione()
                    }
                    .listStyle(.plain)
                }
                }
            } else {
                Text("Questo tragitto non è più disponibile: torna indietro e cercalo di nuovo.")
                    .foregroundStyle(.secondary)
                    .padding(24)
            }
        }
        .navigationTitle(dettaglio?.titolo ?? "Tragitto")
        .navigationBarTitleDisplayMode(.inline)
        // sopra la mappa il titolo resta leggibile
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(dettaglio?.titolo ?? "Tragitto").font(.headline).monospacedDigit()
                    if let sotto = dettaglio?.sottotitolo {
                        Text(sotto).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .task { await seguiVetture() }
    }

    private var mappa: some View {
        let pezzi = tragitti.motore?.mappa(indice: Int32(indice)) ?? []
        let estremi = tragitti.motore?.estremi(indice: Int32(indice)) ?? []
        return Map(position: $camera) {
            ForEach(Array(pezzi.enumerated()), id: \.offset) { _, pezzo in
                // a piedi tratteggiato, i mezzi a tinta piena nel colore della linea
                MapPolyline(coordinates: pezzo.punti.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) })
                    .stroke(
                        pezzo.aPiedi ? Color.gray : Color(argb: pezzo.colore),
                        style: pezzo.aPiedi
                            ? StrokeStyle(lineWidth: 4, lineCap: .round, dash: [1, 7])
                            : StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round)
                    )
            }
            if estremi.count == 2 {
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: estremi[0].lat, longitude: estremi[0].lon), anchor: .center) {
                    PallinoTappa(partenza: true, lato: 18)
                }
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: estremi[1].lat, longitude: estremi[1].lon), anchor: .center) {
                    PallinoTappa(partenza: false, lato: 18)
                }
            }
            ForEach(Array(vetture.enumerated()), id: \.offset) { _, v in
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: v.lat, longitude: v.lon), anchor: .center) {
                    Text(v.linea)
                        .font(.caption2.bold())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Color(argb: v.colore), in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                        .accessibilityLabel(v.testo)
                }
            }
            UserAnnotation()
        }
        .mapControls { MapUserLocationButton() }
    }

    /** Le vetture dei bus e tram del tragitto, ogni 15 secondi. */
    private func seguiVetture() async {
        guard let motore = tragitti.motore else { return }
        while !Task.isCancelled {
            if let trovate = try? await motore.vetture(indice: Int32(indice)) { vetture = trovate }
            try? await Task.sleep(nanoseconds: 15_000_000_000)
        }
    }
}

/** "Parti da Casa  19:12" / "Arrivi a Colosseo  19:45". */
private struct RigaEstremo: View {
    let partenza: Bool
    let testo: String
    let ora: String

    var body: some View {
        HStack(spacing: 12) {
            PallinoTappa(partenza: partenza, lato: 18).frame(width: 24)
            Text(testo).font(.body.bold()).lineLimit(2)
            Spacer()
            Text(ora).font(.body.bold()).monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }
}

private struct BloccoAPiedi: View {
    let blocco: BloccoTragitto
    @State private var aperto = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Puntini().frame(width: 24)
            VStack(alignment: .leading, spacing: 4) {
                Label(blocco.titolo, systemImage: "figure.walk").foregroundStyle(.secondary)
                if !blocco.passi.isEmpty {
                    Button(aperto ? "Nascondi le indicazioni" : "Indicazioni a piedi") { aperto.toggle() }
                        .buttonStyle(.borderless)
                        .foregroundStyle(Tavolozza.oro)
                    if aperto {
                        ForEach(Array(blocco.passi.enumerated()), id: \.offset) { _, passo in
                            Text(passo).font(.footnote)
                        }
                    }
                }
            }
            .padding(.vertical, 6)
        }
    }
}

/** La linea tratteggiata dei tratti a piedi. */
private struct Puntini: View {
    var body: some View {
        GeometryReader { g in
            Path { p in
                p.move(to: CGPoint(x: g.size.width / 2, y: 0))
                p.addLine(to: CGPoint(x: g.size.width / 2, y: g.size.height))
            }
            .stroke(Color.secondary, style: StrokeStyle(lineWidth: 4, lineCap: .round, dash: [0.5, 8]))
        }
    }
}

private struct BloccoMezzo: View {
    let indice: Int
    let numero: Int
    let blocco: BloccoTragitto
    let vettura: VetturaTragitto?
    @EnvironmentObject private var tragitti: Tragitti
    @State private var aperto = false
    @State private var salita: VoceFermata?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 3)
                .fill(Color(argb: blocco.colore))
                .frame(width: 6)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    BadgeLinea(nome: blocco.linea, colore: blocco.colore, grande: true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(blocco.titolo).font(.body.weight(.semibold)).lineLimit(2)
                        if !blocco.gestore.isEmpty { Text(blocco.gestore).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                if blocco.soppresso {
                    Text("Corsa soppressa").bold().foregroundStyle(Tavolozza.oro)
                }
                if let vettura {
                    Label(vettura.testo, systemImage: "dot.radiowaves.up.forward")
                        .font(.footnote)
                        .foregroundStyle(Tavolozza.oro)
                        .padding(8)
                        .background(Tavolozza.oro.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
                }
                if let sali = blocco.sali { RigaTappaVista(riga: sali) }
                Button {
                    aperto.toggle()
                } label: {
                    HStack(spacing: 4) {
                        Text(blocco.fermate)
                        if !blocco.intermedie.isEmpty { Image(systemName: aperto ? "chevron.up" : "chevron.down").font(.caption) }
                    }
                }
                .buttonStyle(.borderless)
                .foregroundStyle(blocco.intermedie.isEmpty ? Color.secondary : Tavolozza.oro)
                .disabled(blocco.intermedie.isEmpty)
                if aperto {
                    ForEach(Array(blocco.intermedie.enumerated()), id: \.offset) { _, tappa in
                        HStack {
                            Text(tappa.nome).font(.footnote).lineLimit(1)
                            Spacer()
                            Text(tappa.ora).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                }
                if let scendi = blocco.scendi { RigaTappaVista(riga: scendi) }
                ForEach(Array(blocco.avvisi.enumerated()), id: \.offset) { _, avviso in
                    Label(avviso, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(Tavolozza.oro)
                        .padding(8)
                        .background(Tavolozza.oro.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
                }
                if let salita {
                    NavigationLink(value: Destinazione.fermata(salita.id)) {
                        Text("Tempi d'attesa a \(salita.nome) ›").bold().foregroundStyle(Tavolozza.oro)
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.vertical, 8)
        }
        .task { salita = try? await tragitti.motore?.fermataSalita(indice: Int32(indice), blocco: Int32(numero)) }
    }
}

private struct RigaTappaVista: View {
    let riga: RigaTappa

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 1) {
                Text(riga.testo).lineLimit(2)
                if !riga.dettagli.isEmpty { Text(riga.dettagli).font(.footnote).foregroundStyle(.secondary) }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text(riga.ora).bold().monospacedDigit().foregroundStyle(riga.evidenzia ? Tavolozza.oro : .primary)
                Text(riga.stato).font(.caption).foregroundStyle(riga.evidenzia ? Tavolozza.oro : .secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
