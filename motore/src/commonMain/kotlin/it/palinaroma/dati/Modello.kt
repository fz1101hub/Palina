package it.palinaroma.dati

data class Fermata(val id: String, val codice: String, val nome: String, val lat: Double, val lon: Double)

data class Linea(
    val id: String,
    val nome: String,
    val descrizione: String,
    val tipo: Int,
    val colore: String,
    val coloreTesto: String,
)

data class Percorso(val lineaId: String, val direzione: Int, val capolinea: String, val forma: String)

data class LineaFermata(val linea: Linea, val direzione: Int, val capolinea: String)

data class InfoCorsa(val lineaId: String, val direzione: Int, val capolinea: String)

data class Punto(val lat: Double, val lon: Double)
