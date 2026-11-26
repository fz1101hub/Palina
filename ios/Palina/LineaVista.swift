import SwiftUI
import MapKit
import Motore

/** Una linea: direzione, mappa con il percorso e le vetture GPS, e l'elenco delle fermate. */
struct LineaVista: View {
    let richiesta: RichiestaLinea
    @EnvironmentObject private var archivio: Archivio
    @EnvironmentObject private var posizione: Posizione
    @State private var motore: MotoreLinea?
    @State private var direzioni: [VoceDirezione] = []
    @State private var fermate: [Fermata] = []
    @State private var forma: [CLLocationCoordinate2D] = []
    @State private var formaAltra: [CLLocationCoordinate2D] = []
    @State private var stato: StatoLinea?
    @State private var scelta: String?
    @State private var camera: MapCameraPosition = .automatic
    @State private var giro = 0
    @State private var avvisi: [SchedaAvviso] = []
    @State private var affidabilita: AffidabilitaLinea?

    var body: some View {
        VStack(spacing: 0) {
            if let motore, !motore.haPercorso() {
                Text(tx("Per questa linea l'orario non ha un percorso."))
                    .foregroundStyle(.secondary)
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else if let motore {
                intestazione(motore)
                ZStack(alignment: .bottom) {
                    mappa(motore)
                    scheda(motore)
                }
                .frame(maxHeight: .infinity)
                if let testo = stato?.testo {
                    HStack(spacing: 8) {
                        Circle().fill(Tavolozza.oro).frame(width: 10, height: 10)
                        Text(testo).font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let lat = posizione.lat, let lon = posizione.lon, let vicina = motore.fermataVicina(lat: lat, lon: lon) {
                    NavigationLink(value: Destinazione.fermata(vicina.fermataId)) {
                        HStack(spacing: 8) {
                            Circle().fill(.blue).frame(width: 10, height: 10)
                            Text(vicina.testo).font(.footnote).foregroundStyle(.blue).lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                List {
                    // di solito a quest'ora: quante corse in orario e il ritardo medio
                    if let affidabilita {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(affidabilita.testo).font(.footnote)
                            Text(affidabilita.fonte).font(.caption2).foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("affidabilita")
                    }
                    ForEach(Array(fermate.enumerated()), id: \.offset) { i, f in
                        NavigationLink(value: Destinazione.fermata(f.id)) {
                            RigaPercorso(nome: f.nome, prima: i == 0, ultima: i == fermate.count - 1,
                                         attesa: f.id == richiesta.fermata, colore: Color(argb: motore.colore))
                        }
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    }
                    // gli avvisi di Roma Mobilita' dopo il percorso, che resta in cima
                    SezioneAvvisi(avvisi: avvisi)
                }
                .listStyle(.plain)
                .frame(maxHeight: .infinity)
            } else {
                ProgressView().frame(maxHeight: .infinity)
            }
        }
        .navigationTitle(motore.map { tx("Linea {0}", $0.nome) } ?? tx("Linea"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if direzioni.count > 1 {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(tx("Cambia direzione"), systemImage: "arrow.left.arrow.right") {
                        motore?.cambiaDirezione()
                        ricarica()
                    }
                }
            }
        }
        .task(id: archivio.orario != nil) { prepara() }
        .task(id: giro) { await seguiVetture() }
        .task { avvisi = (try? await AvvisiServizioKt.avvisiPerLinea(lineaId: richiesta.lineaId)) ?? [] }
        .task { affidabilita = try? await AffidabilitaKt.affidabilitaLinea(lineaId: richiesta.lineaId, adessoMs: Int64(Date().timeIntervalSince1970 * 1000)) }
    }

    private func intestazione(_ motore: MotoreLinea) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tx("Direzione").uppercased()).font(.caption2.bold()).foregroundStyle(.secondary)
            if direzioni.count > 2 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(direzioni, id: \.self) { d in
                            Button(d.testo) {
                                motore.scegli(direzione: d.direzione)
                                ricarica()
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(d.scelta ? Tavolozza.oro : Color(.tertiarySystemFill))
                            .foregroundStyle(d.scelta ? Tavolozza.inchiostro : .primary)
                        }
                    }
                }
            } else {
                Text(direzioni.first(where: { $0.scelta })?.testo ?? motore.sottotitolo()).font(.headline).lineLimit(2)
                if direzioni.count == 1 {
                    Text(tx("Nell'orario questa linea ha una sola direzione (per esempio è circolare)."))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
    }

    private func mappa(_ motore: MotoreLinea) -> some View {
        let colore = Color(argb: motore.colore)
        return Map(position: $camera) {
            if formaAltra.count >= 2 {
                MapPolyline(coordinates: formaAltra).stroke(.gray.opacity(0.5), lineWidth: 3)
            }
            if forma.count >= 2 {
                MapPolyline(coordinates: forma).stroke(colore, lineWidth: 5)
            }
            ForEach(Array(fermate.enumerated()), id: \.offset) { _, f in
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon), anchor: .center) {
                    Circle()
                        .fill(f.id == richiesta.fermata ? Tavolozza.oro : .white)
                        .stroke(colore, lineWidth: 2)
                        .frame(width: f.id == richiesta.fermata ? 14 : 9, height: f.id == richiesta.fermata ? 14 : 9)
                        .accessibilityLabel(f.nome)
                }
            }
            ForEach(stato?.vetture ?? [], id: \.chiave) { v in
                Annotation(v.etichetta, coordinate: CLLocationCoordinate2D(latitude: v.lat, longitude: v.lon), anchor: .center) {
                    IconaVettura(nome: motore.nome, voce: v, colore: colore)
                        .onTapGesture { scelta = v.chiave }
                }
            }
            UserAnnotation()
        }
        .mapControls { MapUserLocationButton(); MapCompass() }
    }

    @ViewBuilder
    private func scheda(_ motore: MotoreLinea) -> some View {
        if let chiave = scelta ?? stato?.vetture.first(where: { $0.attesa })?.chiave, let s = motore.scheda(chiave: chiave) {
            VStack(alignment: .leading, spacing: 3) {
                Text(s.titolo).font(.headline)
                Text(s.rilevata).font(.caption).foregroundStyle(s.vecchia ? Tavolozza.oro : .secondary)
                ForEach(s.avvisi, id: \.self) { Text($0).font(.caption).foregroundStyle(Tavolozza.oro) }
                if let altra = s.altraDirezione {
                    Button(altra) {
                        motore.cambiaDirezione()
                        ricarica()
                    }
                    .font(.caption.bold())
                }
                if let arrivo = s.arrivo { Text(arrivo).font(.caption).lineLimit(2) }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding(10)
        } else if let senzaGps = stato?.senzaGps {
            VStack(alignment: .leading, spacing: 3) {
                Text(tx("La tua corsa non ha una posizione GPS")).font(.headline)
                Text(senzaGps).font(.caption).foregroundStyle(.secondary)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding(10)
        }
    }

    private func prepara() {
        guard motore == nil, let orario = archivio.orario else { return }
        motore = MotoreLinea(
            orario: orario,
            lineaId: richiesta.lineaId,
            direzioneIniziale: Int32(richiesta.direzione),
            corsaEvidenziata: richiesta.corsa,
            fermataEvidenziata: richiesta.fermata
        )
        ricarica()
    }

    /** Fermate, tracciati e vetture della direzione scelta. */
    private func ricarica() {
        guard let motore else { return }
        direzioni = motore.direzioni()
        fermate = motore.fermate()
        forma = motore.forma().map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        formaAltra = motore.formaAltra().map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        stato = motore.stato()
        scelta = nil
        // senza tracciato la mappa resta su Roma (non sul mondo intero)
        camera = forma.count >= 2 ? .automatic : .region(MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 41.8958, longitude: 12.4826),
            span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.2)
        ))
        giro += 1
    }

    /** Le vetture si leggono ogni 15 secondi. */
    private func seguiVetture() async {
        guard let motore else { return }
        while !Task.isCancelled {
            if let nuovo = try? await motore.aggiorna() { stato = nuovo }
            try? await Task.sleep(nanoseconds: 15_000_000_000)
        }
    }
}

/** Una vettura sulla mappa: il numero della linea in un cerchio (sbiadito se lontana dal percorso o vecchia). */
struct IconaVettura: View {
    let nome: String
    let voce: VoceVettura
    let colore: Color

    var body: some View {
        let sfondo: Color = voce.attesa ? Tavolozza.porpora : (voce.altraDirezione ? .gray : Tavolozza.oro)
        ZStack {
            if voce.rotta >= 0 {
                Image(systemName: "location.north.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(sfondo)
                    .offset(y: -17)
                    .rotationEffect(.degrees(voce.rotta))
            }
            Text(nome)
                .font(.caption2.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(voce.attesa ? .white : Tavolozza.inchiostro)
                .frame(width: 28, height: 28)
                .background(sfondo, in: Circle())
                .overlay(Circle().stroke(.white, lineWidth: 2))
        }
        .opacity(voce.fuoriPercorso || voce.vecchia ? 0.45 : 1)
        .accessibilityLabel(voce.attesa ? tx("La tua vettura {0}", voce.etichetta) : tx("Vettura {0}", voce.etichetta))
    }
}

/** Una fermata nell'elenco del percorso, con la linea colorata a sinistra. */
struct RigaPercorso: View {
    let nome: String
    let prima: Bool
    let ultima: Bool
    let attesa: Bool
    let colore: Color

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                VStack(spacing: 0) {
                    Rectangle().fill(prima ? .clear : colore).frame(width: 4)
                    Rectangle().fill(ultima ? .clear : colore).frame(width: 4)
                }
                Circle()
                    .fill(attesa ? Tavolozza.oro : Color(.systemBackground))
                    .stroke(colore, lineWidth: 3)
                    .frame(width: 14, height: 14)
            }
            .frame(width: 20, height: 48)
            Text(nome)
                .font(prima || ultima || attesa ? .body.bold() : .body)
                .lineLimit(2)
        }
    }
}
