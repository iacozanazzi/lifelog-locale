package it.iaco.lifelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================== Briefing (dati puri)

/** Una persona che vedrai, con al massimo due cose da ricordare (ognuna con giorno e ora). */
data class PersonNote(val name: String, val notes: List<String>)

data class AgendaRow(val time: String, val title: String, val place: String, val people: List<PersonNote>, val past: Boolean = false)

/** Preparazione a un incontro di domani, mostrata la sera. */
data class PrepCard(val whenLabel: String, val title: String, val people: List<PersonNote>, val points: List<String>)

data class ProposalRow(val id: String, val title: String, val whenLabel: String, val source: String)

data class DraftRow(val id: String, val to: String, val text: String, val source: String)

data class BriefingUi(
    val agenda: List<AgendaRow> = emptyList(),
    /** Il permesso Calendario non è stato dato: si mostra l'invito a darlo. */
    val calendarMissing: Boolean = false,
    val due: List<TodoItem> = emptyList(),
    val tomorrow: List<PrepCard> = emptyList(),
    val proposals: List<ProposalRow> = emptyList(),
    val drafts: List<DraftRow> = emptyList(),
) {
    val hasTop get() = agenda.isNotEmpty() || due.isNotEmpty() || calendarMissing
}

class BriefingActions(
    val onPerson: (String) -> Unit = {},
    val onGrantCalendar: () -> Unit = {},
    val onToggleDue: (String, Boolean) -> Unit = { _, _ -> },
    val onAddEvent: (String) -> Unit = {},
    val onSkipEvent: (String) -> Unit = {},
    val onCopyDraft: (String) -> Unit = {},
    val onSendDraft: (String) -> Unit = {},
    val onDraftDone: (String) -> Unit = {},
)
