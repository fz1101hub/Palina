import SwiftUI
import UserNotifications
import Motore

/** Un promemoria aperto nel foglio: nuovo o gia' salvato. */
struct PromemoriaAperto: Identifiable {
    let promemoria: Promemoria
    let esistente: Bool
    var id: String { promemoria.id }
}

/**
 * Il foglio di un promemoria ricorrente, come su Android: i giorni, l'ora e,
 * per una linea, quando avvisare. I testi e le regole sono del motore.
 */
struct PromemoriaVista: View {
    let aperto: PromemoriaAperto
    @EnvironmentObject private var promemorie: Promemorie
    @Environment(\.dismiss) private var chiudi
    @State private var giorni: Set<Int> = []
    @State private var ora = Date()
    @State private var quando = ""
    @State private var pronto = false

    private var p: Promemoria { aperto.promemoria }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(promemorie.titolo(p)).font(.headline)
                }
                Section {
                    HStack(spacing: 6) {
                        ForEach(Array(PromemoriaKt.inizialiGiorni().enumerated()), id: \.offset) { i, lettera in
                            let giorno = i + 1
                            let scelto = giorni.contains(giorno)
                            Button {
                                if scelto { giorni.remove(giorno) } else { giorni.insert(giorno) }
                            } label: {
                                Text(lettera)
                                    .font(.body.bold())
                                    .frame(maxWidth: .infinity, minHeight: 40)
                                    .foregroundStyle(scelto ? Tavolozza.inchiostro : Color.primary)
                                    .background(scelto ? Tavolozza.oro : Color.clear, in: Circle())
                                    .overlay(Circle().stroke(scelto ? Tavolozza.oro : Color.secondary.opacity(0.4), lineWidth: 1.5))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(scelto ? AccessibilityTraits.isSelected : AccessibilityTraits())
                        }
                    }
                    HStack {
                        Button(tx("Giorni feriali")) { giorni = [1, 2, 3, 4, 5] }.buttonStyle(.borderless)
                        Spacer()
                        Button(tx("Tutti i giorni")) { giorni = [1, 2, 3, 4, 5, 6, 7] }.buttonStyle(.borderless)
                    }
                    .font(.subheadline)
                } header: {
                    Text(tx("Giorni"))
                }
                Section {
                    DatePicker(tx("Ora"), selection: $ora, displayedComponents: .hourAndMinute)
                        .accessibilityIdentifier("ora-promemoria")
                } footer: {
                    Text(PromemoriaKt.testoQuandoPromemoria(p: PromemoriaKt.promemoriaModificato(
                        p: p, giorni: giorni.sorted().map { KotlinInt(int: Int32($0)) }, minuti: Int32(minutiScelti), quando: quando, attivo: true
                    )))
                }
                if p.perLinea {
                    Section {
                        let codici = PromemoriaKt.codiciScelteePromemoria()
                        let etichette = PromemoriaKt.etichetteScelteePromemoria()
                        Picker(tx("Avvisami"), selection: $quando) {
                            ForEach(Array(codici.enumerated()), id: \.offset) { i, codice in
                                Text(etichette[i]).tag(codice)
                            }
                        }
                        .pickerStyle(.segmented)
                    } header: {
                        Text(tx("Avvisami"))
                    } footer: {
                        Text(tx("All'ora scelta arriva una notifica con i tempi: tocca «Avvisami» per il conto alla rovescia."))
                    }
                } else {
                    Section {
                        Text(tx("All'ora scelta arriva una notifica: quando uscire, con che mezzi e a che ora arrivi."))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if aperto.esistente {
                    Section {
                        Button(tx("Elimina"), role: .destructive) {
                            promemorie.togli(p.id)
                            chiudi()
                        }
                    }
                }
            }
            .navigationTitle(tx("Promemoria ricorrente"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tx("Annulla")) { chiudi() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tx("Salva")) {
                        Task {
                            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                            promemorie.salva(PromemoriaKt.promemoriaModificato(
                                p: p, giorni: giorni.sorted().map { KotlinInt(int: Int32($0)) },
                                minuti: Int32(minutiScelti), quando: quando, attivo: true
                            ))
                            chiudi()
                        }
                    }
                    .bold()
                    .disabled(giorni.isEmpty)
                    .accessibilityIdentifier("salva-promemoria")
                }
            }
            .onAppear {
                guard !pronto else { return }
                pronto = true
                giorni = Set(PromemoriaKt.giorniDelPromemoria(p: p).map { Int(truncating: $0) })
                quando = p.quando
                var parti = Calendar.current.dateComponents([.year, .month, .day], from: Date())
                parti.hour = Int(p.minuti) / 60
                parti.minute = Int(p.minuti) % 60
                ora = Calendar.current.date(from: parti) ?? Date()
            }
        }
    }

    /** L'ora scelta in minuti dalla mezzanotte (l'ora del telefono, come quella di Roma per chi e' a Roma). */
    private var minutiScelti: Int {
        let parti = Calendar.current.dateComponents([.hour, .minute], from: ora)
        return (parti.hour ?? 0) * 60 + (parti.minute ?? 0)
    }
}
