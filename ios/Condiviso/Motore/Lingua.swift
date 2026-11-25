import Foundation
import Motore

/**
 * Un testo nella lingua dell'app, dal motore (gli stessi cataloghi di Android):
 * tx("Partenze precedenti"), tx("Palina {0}", codice). Il testo italiano e' la
 * chiave; i valori vanno in {0}, {1}, ...
 */
func tx(_ italiano: String, _ valori: Any...) -> String {
    LinguaAppleKt.testoTradotto(italiano: italiano, valori: valori.map { "\($0)" })
}

/** Singolare o plurale secondo [n], che va in {0}: txn("{0} fermata", "{0} fermate", n). */
func txn(_ uno: String, _ molti: String, _ n: Int, _ altri: Any...) -> String {
    LinguaAppleKt.testoTradottoN(uno: uno, molti: molti, n: Int32(n), altri: altri.map { "\($0)" })
}

enum LinguaApp {
    /** La lingua del telefono (tra quelle dell'app) e quella scelta nelle impostazioni. */
    static func imposta() {
        let sistema = Locale.preferredLanguages.first
        let scelta = UserDefaults.standard.string(forKey: "lingua")
        Lingue.shared.imposta(sistema: sistema, codiceScelto: scelta)
    }
}
