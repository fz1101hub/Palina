package it.palinaroma.dati

import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.timeIntervalSince1970

private val SEGNI = setOf(CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK)

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun senzaAccenti(testo: String): String {
    val scomposto = (testo as NSString).decomposedStringWithCanonicalMapping
    return scomposto.filter { it.category !in SEGNI }
}

actual fun adessoMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
