package fr.soaresden.diallo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Liste défilante des notes (écran d'appel). */
@Composable
internal fun NoteList(notes: List<NotesRepository.Note>, modifier: Modifier, onDelete: (Long) -> Unit) {
    if (notes.isEmpty()) { Text(tr("Aucune note pour l'instant."), fontSize = 12.5.sp, color = muted(.5f), modifier = Modifier.padding(4.dp)); return }
    LazyColumn(modifier) { items(notes, key = { it.id }) { NoteRow(it, onDelete) } }
}

@Composable
private fun NoteRow(n: NotesRepository.Note, onDelete: (Long) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(fmtWhen(n.date), fontSize = 11.5.sp, color = muted(.55f))
            Text(n.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = { if (confirm) onDelete(n.id) else confirm = true }, modifier = Modifier.size(32.dp)) {
            if (confirm) Text("🗑️", fontSize = 15.sp) else Icon(Icons.Filled.Close, tr("Supprimer"), tint = muted(.4f), modifier = Modifier.size(16.dp))
        }
    }
}

/** Bloc « Notes » des fiches contact / stats : lire, ajouter, supprimer. */
@Composable
internal fun NotesCard(numbers: List<String>, name: String?) {
    val ctx = LocalContext.current
    val keys = remember(numbers) { numbers.map { keyOf(it) }.filter { it.isNotEmpty() }.distinct() }
    if (keys.isEmpty()) return
    val number = numbers.first { keyOf(it).isNotEmpty() }           // les nouvelles notes vont sur le numéro principal
    fun load() = keys.flatMap { NotesRepository.forKey(ctx, it) }.sortedByDescending { it.date }
    var notes by remember(keys) { mutableStateOf(load()) }
    var draft by remember(keys) { mutableStateOf("") }
    SectionCard(tr("📝 Notes") + if (notes.isNotEmpty()) " (${notes.size})" else "", id = "notes_card") {
        OutlinedTextField(
            draft, { draft = it }, Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Ajouter une note…")) }, minLines = 1, maxLines = 4,
            trailingIcon = {
                if (draft.isNotBlank()) IconButton(onClick = { NotesRepository.add(ctx, number, name, draft); draft = ""; notes = load() }) {
                    Icon(Icons.Filled.Save, tr("Enregistrer"), tint = MaterialTheme.colorScheme.primary)
                }
            }
        )
        if (notes.isEmpty()) Text(tr("Les notes prises pendant les appels apparaîtront ici."), fontSize = 12.sp, color = muted(.5f), modifier = Modifier.padding(top = 6.dp))
        notes.take(30).forEach { NoteRow(it) { id -> NotesRepository.delete(ctx, id); notes = load() } }
    }
}
