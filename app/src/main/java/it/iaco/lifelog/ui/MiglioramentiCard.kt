package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val c = Life.c
    Column(modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.fill).padding(18.dp)) {
        Text("Idee per l'app", style = Type.meta.copy(fontWeight = FontWeight.Bold), color = c.text)
        list.forEachIndexed { i, p ->
            if (i > 0) Hairline(Modifier.padding(top = 14.dp))
            Text(p.titolo, style = Type.rowTitle, color = c.text, modifier = Modifier.padding(top = 12.dp))
            if (p.perche.isNotBlank()) Text(p.perche, style = Type.text, color = c.second, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TextAction(if (p.chi == "iaco") "Ok" else "Sì, fallo", { onAnswer(p, true) })
                TextAction("No", { onAnswer(p, false) }, color = c.second)
            }
        }
    }
}
