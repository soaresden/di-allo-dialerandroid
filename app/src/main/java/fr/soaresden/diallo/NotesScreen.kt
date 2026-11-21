package fr.soaresden.diallo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Onglet Notes : toutes les notes d'appel, avec recherche. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NotesScreen(contacts: List<CallRepository.Contact>, onCall: (String) -> Unit, onDetail: (String) -> Unit) {
    val ctx = LocalContext.current
    val ver by NotesRepository.version
    val all = remember(ver) { NotesRepository.list(ctx) }
    val byKey = remember(contacts) { contacts.associateBy { keyOf(it.number) } }
    var q by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }

    fun nameOf(n: NotesRepository.Note) = byKey[n.key]?.display ?: n.name ?: displayNumber(n.number)
    val shown = remember(all, q, byKey) {
        val t = q.trim(); val digits = t.filter { it.isDigit() }
        if (t.isEmpty()) all else all.filter { n ->
            n.text.contains(t, true) || nameOf(n).contains(t, true) || (digits.length >= 2 && n.number.filter { it.isDigit() }.contains(digits))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text(tr("Chercher dans les notes, un nom, un numéro…")) }, singleLine = true,
            shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, tr("Effacer")) } }
        )
        Spacer(Modifier.height(8.dp))
        Segmented(listOf(tr("🕘 Récentes"), tr("👤 Par contact")), mode, { mode = it })
        Spacer(Modifier.height(6.dp))
        Text(
            if (q.isBlank()) trn(all.size, "{0} note", "{0} notes") else trn(shown.size, "{0} résultat sur {1}", "{0} résultats sur {1}", all.size),
            fontSize = 12.sp, color = muted(.55f), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        if (all.isEmpty()) Empty(tr("Aucune note pour l'instant.\nPrends des notes pendant tes appels 📝"))
        else if (shown.isEmpty()) Empty(tr("Rien trouvé pour « {0} ».", q))
        else LazyColumn(Modifier.fillMaxSize()) {
            if (mode == 0) items(shown, key = { "n${it.id}" }) { n ->
                NoteCard(n, nameOf(n), byKey[n.key]?.photoUri, q, showWho = true, onCall = onCall, onDetail = onDetail)
            } else {
                val groups = shown.groupBy { it.key }.entries.sortedByDescending { e -> e.value.maxOf { it.date } }
                groups.forEach { (k, list) ->
                    val n0 = list.first()
                    stickyHeader(key = "h_$k") {
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable { onDetail(k) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(nameOf(n0), byKey[k]?.photoUri, 34.dp); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nameOf(n0), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(trn(list.size, "{0} note · {1}", "{0} notes · {1}", phoneText(n0.number)), fontSize = 12.sp, color = muted())
                            }
                            Box(Modifier.size(36.dp).clip(CircleShape).background(CallGreen).clickable { onCall(n0.number) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Call, tr("Appeler"), tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    items(list, key = { "g${k}_${it.id}" }) { n -> NoteCard(n, nameOf(n), null, q, showWho = false, onCall = onCall, onDetail = onDetail) }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun NoteCard(n: NotesRepository.Note, who: String, photo: String?, q: String, showWho: Boolean, onCall: (String) -> Unit, onDetail: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { onDetail(n.key) }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showWho) { Avatar(who, photo, 26.dp); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    if (showWho) Text(who, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtWhen(n.date), fontSize = 11.5.sp, color = muted(.55f))
                }
                IconButton(onClick = { if (confirm) NotesRepository.delete(ctx, n.id) else confirm = true }, modifier = Modifier.size(30.dp)) {
                    if (confirm) Text("🗑️", fontSize = 15.sp) else Icon(Icons.Filled.Close, tr("Supprimer"), tint = muted(.35f), modifier = Modifier.size(16.dp))
                }
                if (showWho) IconButton(onClick = { onCall(n.number) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Call, tr("Appeler"), tint = CallGreen, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(highlight(n.text, q, cs.primary), fontSize = 14.5.sp, color = cs.onSurface)
        }
    }
}

private fun highlight(text: String, q: String, color: Color) = buildAnnotatedString {
    val t = q.trim()
    if (t.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val j = text.indexOf(t, i, ignoreCase = true)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold, background = color.copy(alpha = .14f))) { append(text.substring(j, j + t.length)) }
        i = j + t.length
    }
}
