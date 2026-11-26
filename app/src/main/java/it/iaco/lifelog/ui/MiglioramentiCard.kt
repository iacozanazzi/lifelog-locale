package it.iaco.lifelog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import it.iaco.lifelog.data.Miglioramenti
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scheda per Oggi: le proposte di Claude per migliorare l'app (al massimo 3), con Sì e No.
 * Legge i dati da sola; se non c'è niente da decidere non mostra nulla.
 */
@Composable
fun MiglioramentiCard(tick: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Miglioramenti.Proposta>()) }
    LaunchedEffect(tick / 15) { list = withContext(Dispatchers.IO) { Miglioramenti.open(ctx) } }
    MiglioramentiView(list, modifier) { p, yes ->
        Miglioramenti.answer(ctx, p.id, yes)
        list = list - p
    }
}

@Composable
fun MiglioramentiView(list: List<Miglioramenti.Proposta>, modifier: Modifier = Modifier, onAnswer: (Miglioramenti.Proposta, Boolean) -> Unit) {
    if (list.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        TodaySection("Idee per l'app")
        list.forEachIndexed { i, p ->
            TodayActionRow(
                icon = "✦", title = p.titolo, meta = "", preview = p.perche, first = i == 0,
                actions = listOf(
                    Triple(if (p.chi == "iaco") "Ok" else "Sì, fallo", { onAnswer(p, true) }, true),
                    Triple("No", { onAnswer(p, false) }, false),
                ),
            )
        }
    }
}
