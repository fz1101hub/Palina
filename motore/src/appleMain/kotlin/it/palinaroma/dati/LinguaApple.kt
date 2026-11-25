package it.palinaroma.dati

/** tx() per Swift, che non passa bene i vararg: i valori arrivano gia' come testi. */
fun testoTradotto(italiano: String, valori: List<String>): String = tx(italiano, *valori.toTypedArray())

/** txn() per Swift. */
fun testoTradottoN(uno: String, molti: String, n: Int, altri: List<String>): String = txn(uno, molti, n, *altri.toTypedArray())
