@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package it.palinaroma.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import it.palinaroma.dati.Archivio
import it.palinaroma.dati.Promemoria
import it.palinaroma.dati.VocePromemoria
import it.palinaroma.dati.inizialiGiorni
import it.palinaroma.dati.sceltePromemoria
import it.palinaroma.dati.testoMinutiDelGiorno
import it.palinaroma.dati.testoQuandoPromemoria
import it.palinaroma.dati.tx
import it.palinaroma.sfondo.Promemorie

/**
 * Il foglio di un promemoria ricorrente: i giorni, l'ora e, per una linea,
 * quando avvisare. [titolo] e' quello del motore ("Lavoro · 64 → S. Pietro").
 */
@Composable
fun FoglioPromemoria(iniziale: Promemoria, titolo: String, esistente: Boolean, chiudi: () -> Unit) {
    val context = LocalContext.current
    var giorni by remember { mutableStateOf(iniziale.giorni) }
    var minuti by remember { mutableStateOf(iniziale.minuti) }
    var quando by remember { mutableStateOf(iniziale.quando) }
    var sceltaOra by remember { mutableStateOf(false) }
    val stato = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun salva() {
        Archivio.salvaPromemoria(iniziale.copy(giorni = giorni, minuti = minuti, quando = quando, attivo = true))
        chiudi()
    }
    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { salva() }

    if (sceltaOra) {
        val orologio = rememberTimePickerState(minuti / 60, minuti % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { sceltaOra = false },
            confirmButton = {
                TextButton(onClick = {
                    minuti = orologio.hour * 60 + orologio.minute
                    sceltaOra = false
                }) { Text(tx("Fatto"), color = Accento) }
            },
            dismissButton = { TextButton(onClick = { sceltaOra = false }) { Text(tx("Annulla"), color = Secondario) } },
            text = { TimePicker(state = orologio) },
        )
    }

    ModalBottomSheet(onDismissRequest = chiudi, sheetState = stato, containerColor = Superficie) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .semantics { testTagsAsResourceId = true }
                .testTag("foglio-promemoria"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tx("Promemoria ricorrente"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(titolo, color = Secondario, style = MaterialTheme.typography.bodyMedium)
            }
            Intestazione(tx("Giorni").uppercase(), rientro = 0.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                inizialiGiorni().forEachIndexed { i, lettera ->
                    val giorno = i + 1
                    val scelto = giorno in giorni
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(if (scelto) Oro else Color.Transparent)
                            .border(1.5.dp, if (scelto) Oro else Bordo, CircleShape)
                            .toggleable(value = scelto, role = Role.Checkbox) { giorni = if (it) giorni + giorno else giorni - giorno }
                            .semantics { contentDescription = lettera },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(lettera, color = if (scelto) Inchiostro else Testo, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { giorni = Promemoria.FERIALI }) { Text(tx("Giorni feriali"), color = Accento) }
                TextButton(onClick = { giorni = Promemoria.TUTTI }) { Text(tx("Tutti i giorni"), color = Accento) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Intestazione(tx("Ora").uppercase(), rientro = 0.dp)
                    Text(testoQuandoPromemoria(iniziale.copy(giorni = giorni, minuti = minuti)), color = Secondario, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { sceltaOra = true },
                    border = BorderStroke(1.5.dp, Accento.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("ora-promemoria"),
                ) {
                    Text(testoMinutiDelGiorno(minuti), fontFamily = CarattereCifre, fontSize = 22.sp, color = Accento)
                }
            }
            if (iniziale.perLinea) {
                Intestazione(tx("Avvisami").uppercase(), rientro = 0.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sceltePromemoria().forEach { (codice, etichetta) ->
                        val scelta = codice == quando
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (scelta) AccentoFondo else Color.Transparent)
                                .border(1.5.dp, if (scelta) Accento else Bordo, RoundedCornerShape(14.dp))
                                .selectable(selected = scelta, role = Role.RadioButton) { quando = codice },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(etichetta, color = if (scelta) Accento else Testo, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
                Text(
                    tx("All'ora scelta arriva una notifica con i tempi: tocca «Avvisami» per il conto alla rovescia."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                )
            } else {
                Text(
                    tx("All'ora scelta arriva una notifica: quando uscire, con che mezzi e a che ora arrivi."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondario,
                )
            }
            if (!Promemorie.oraEsatta(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(AccentoFondo, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        tx("Senza il permesso per sveglie e promemoria la notifica può arrivare qualche minuto dopo."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)),
                            )
                        }
                    }) { Text(tx("Consenti"), color = Accento, fontWeight = FontWeight.Bold) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (esistente) {
                    TextButton(onClick = {
                        Archivio.togliPromemoria(iniziale.id)
                        chiudi()
                    }) { Text(tx("Elimina"), color = Accento) }
                } else {
                    OutlinedButton(
                        onClick = chiudi,
                        border = BorderStroke(1.5.dp, Accento.copy(alpha = 0.4f)),
                        modifier = Modifier.height(52.dp),
                    ) { Text(tx("Annulla"), color = Accento) }
                }
                Button(
                    onClick = {
                        val serve = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (serve) permesso.launch(Manifest.permission.POST_NOTIFICATIONS) else salva()
                    },
                    enabled = giorni.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Oro, contentColor = Inchiostro),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("salva-promemoria"),
                ) { Text(tx("Salva promemoria"), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            }
        }
    }
}

/** Un promemoria nell'elenco (Impostazioni): titolo, quando, acceso o spento. */
@Composable
fun RigaPromemoria(voce: VocePromemoria, apri: () -> Unit, accendi: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = tx("modifica"), onClick = apri)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("promemoria"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(voce.titolo, fontWeight = FontWeight.Medium)
            Text(voce.sotto, style = MaterialTheme.typography.bodySmall, color = Secondario)
        }
        Switch(
            checked = voce.attivo,
            onCheckedChange = accendi,
            colors = SwitchDefaults.colors(checkedTrackColor = Oro, checkedThumbColor = Inchiostro, checkedBorderColor = Oro),
        )
    }
    HorizontalDivider(color = Divisore, modifier = Modifier.padding(horizontal = 16.dp))
}

/** Il pulsante piccolo "Ripeti ogni settimana…" sotto "Avvisami". */
@Composable
fun PulsanteRipeti(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("ripeti-promemoria")) {
        Text(tx("Ripeti ogni settimana…"), color = Accento, fontWeight = FontWeight.Medium)
    }
}
