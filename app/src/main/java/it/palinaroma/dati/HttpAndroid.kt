package it.palinaroma.dati

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Le richieste HTTP del motore con i client OkHttp dell'app (stessi tempi di prima). */
object HttpAndroid : Http {
    override suspend fun richiedi(
        url: String,
        intestazioni: Map<String, String>,
        attesa: Attesa,
        corpo: ByteArray?,
        tipoCorpo: String?,
    ): RispostaHttp = withContext(Dispatchers.IO) {
        val richiesta = Request.Builder().url(url)
        intestazioni.forEach { (nome, valore) -> richiesta.header(nome, valore) }
        if (corpo != null) richiesta.post(corpo.toRequestBody(tipoCorpo?.toMediaType()))
        val cliente = when (attesa) {
            Attesa.BREVE -> Archivio.httpBreve
            Attesa.MEDIA -> Archivio.httpMedio
            Attesa.LUNGA -> Archivio.http
        }
        cliente.newCall(richiesta.build()).execute().use { risposta ->
            RispostaHttp(
                risposta.code,
                risposta.body.bytes(),
                risposta.headers.names().associateWith { risposta.header(it).orEmpty() },
            )
        }
    }
}
