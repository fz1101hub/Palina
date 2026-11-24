package it.palinaroma.dati

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val dispatcherDati: CoroutineDispatcher = Dispatchers.IO
