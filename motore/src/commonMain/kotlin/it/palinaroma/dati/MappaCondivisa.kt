package it.palinaroma.dati

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Una piccola mappa che si legge e si scrive da piu' thread (come ConcurrentHashMap, per le cache). */
@OptIn(ExperimentalAtomicApi::class)
class MappaCondivisa<K, V> {
    private val mappa = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(chiave: K): V? = mappa.load()[chiave]

    operator fun set(chiave: K, valore: V) {
        while (true) {
            val vecchia = mappa.load()
            if (mappa.compareAndSet(vecchia, vecchia + (chiave to valore))) return
        }
    }
}
