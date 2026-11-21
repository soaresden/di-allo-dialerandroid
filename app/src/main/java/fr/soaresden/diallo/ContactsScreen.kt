package fr.soaresden.diallo

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.Normalizer

private val SORTS = listOf(
    "A → Z" to "Ordre alphabétique",
    "Z → A" to "Alphabétique inversé",
    "Récents" to "Dernier appel le plus récent d'abord",
    "Anciens" to "Ceux que tu n'as pas appelés depuis longtemps d'abord",
    "Intelligent" to "Fréquence × récence : qui compte vraiment en ce moment"
)

private fun letterOf(name: String): String {
    val ch = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").firstOrNull()?.uppercaseChar() ?: '#'
    return if (ch in 'A'..'Z') ch.toString() else "#"
}
private fun sortKey(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsScreen(contacts: List<CallRepository.Contact>, groups: List<String>, onConfirm: (String) -> Unit, onDetail: (String) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val qDigits = q.filter { it.isDigit() }

    // Une personne = un contact ; ses numéros sont triés par nombre d'appels décroissant
    val people = remember(contacts) {
        contacts.groupBy { if (it.contactId >= 0) "id${it.contactId}" else "n" + it.number }.values
            .map { nums -> nums.sortedByDescending { it.calls } }
    }
    val ctx = LocalContext.current
    var sort by remember { mutableStateOf(prefs(ctx).getInt("csort", 0)) }
    var sortMenu by remember { mutableStateOf(false) }
    val alpha = sort <= 1
    val filtered = remember(people, q, group, sort) {
        val f = people.filter { nums ->
            val c = nums.first()
            (group == null || nums.any { it.groups.contains(group) }) &&
                (q.isBlank() || c.display.contains(q, true) || (qDigits.isNotEmpty() && nums.any { it.digits.contains(qDigits) || it.t9.contains(qDigits) }))
        }
        val az = compareBy<List<CallRepository.Contact>> { (if (letterOf(it.first().display) == "#") "~" else "") + sortKey(it.first().display) }
        fun last(p: List<CallRepository.Contact>) = p.maxOf { it.last }
        fun score(p: List<CallRepository.Contact>) = p.sumOf { it.score } + (if (p.any { it.starred }) 1.5 else 0.0)
        when (sort) {
            1 -> f.sortedWith(az.reversed())
            2 -> f.sortedWith(compareByDescending<List<CallRepository.Contact>> { last(it) }.then(az))
            3 -> f.sortedWith(compareBy<List<CallRepository.Contact>> { if (last(it) == 0L) Long.MAX_VALUE else last(it) }.then(az))
            4 -> f.sortedWith(compareByDescending<List<CallRepository.Contact>> { score(it) }.then(az))
            else -> f.sortedWith(az)
        }
    }
    var chooser by remember { mutableStateOf<List<CallRepository.Contact>?>(null) }
    // lignes : en-têtes de lettre + contacts ; index de chaque lettre pour le défilement rapide
    // En-têtes : lettres (tri alphabétique), périodes du dernier appel (tri par date), rangs (tri intelligent)
    fun headerOf(p: List<CallRepository.Contact>, i: Int): String = when (sort) {
        0, 1 -> letterOf(p.first().display)
        2, 3 -> { val l = p.maxOf { it.last }; if (l == 0L) "Jamais appelé" else { val d = daysAgo(l); when { d <= 7 -> "Cette semaine"; d <= 31 -> "Ce mois-ci"; d <= 92 -> "Ces 3 derniers mois"; d <= 366 -> "Cette année"; d <= 3 * 365 -> "Il y a 1 à 3 ans"; else -> "Il y a plus de 3 ans" } } }
        else -> when { i < 10 -> "Tes 10 essentiels"; i < 30 -> "Souvent"; p.sumOf { it.score } > 0.05 -> "De temps en temps"; else -> "Rarement / jamais" }
    }
    val rows = remember(filtered) { buildList<Any> { var cur = ""; filtered.forEachIndexed { i, p -> val l = headerOf(p, i); if (l != cur) { add(l); cur = l }; add(p) } } }
    val letterIndex = remember(rows) { rows.withIndex().filter { it.value is String }.associate { (it.value as String) to it.index } }
    val letters = remember(letterIndex) { letterIndex.keys.toList() }
    val groupCounts = remember(people) { people.flatMap { nums -> nums.flatMap { it.groups }.distinct() }.groupingBy { it }.eachCount() }

    val listState = rememberLazyListState()
    var bubble by remember { mutableStateOf<String?>(null) }

    if (showMap) ContactsMap(onBack = { showMap = false }, onOpen = { id ->
        val found = people.firstOrNull { nums -> nums.first().contactId == id }
        if (found != null) chooser = found
        else try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, id))) } catch (_: Throwable) {}
    })
    else Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Rechercher (nom, numéro ou T9)") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, "Effacer") } },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.surface)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (group != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface).clickable { showGroups = true }.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                val on = if (group != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                Icon(Icons.Filled.Label, null, tint = on, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(group ?: "Tous les libellés", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                Icon(Icons.Filled.ArrowDropDown, null, tint = on)
            }
            if (group != null) IconButton(onClick = { group = null }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Close, "Retirer le filtre", tint = muted()) }
            Spacer(Modifier.width(8.dp))
            Box {
                Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface).clickable { sortMenu = true }.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Sort, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(SORTS[sort].first, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SORTS.forEachIndexed { i, (name, desc) ->
                        DropdownMenuItem(
                            text = { Column { Text(name, fontWeight = if (i == sort) FontWeight.Bold else FontWeight.Normal); Text(desc, fontSize = 11.sp, color = muted()) } },
                            onClick = { sort = i; sortMenu = false; prefs(ctx).edit().putInt("csort", i).apply(); scope.launch { listState.scrollToItem(0) } },
                            trailingIcon = { if (i == sort) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${filtered.size}", fontSize = 12.sp, color = muted(.55f))
            IconButton(onClick = { showMap = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Map, "Carte des contacts", tint = MaterialTheme.colorScheme.primary) }
        }
        if (filtered.isEmpty()) { Empty("Aucun contact."); return@Column }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = if (alpha) 30.dp else 12.dp)) {
                rows.forEach { row ->
                    if (row is String) stickyHeader(key = "h_$row") {
                        Text(row, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = .92f)).padding(start = 6.dp, top = 8.dp, bottom = 4.dp))
                    } else {
                        @Suppress("UNCHECKED_CAST") val nums = row as List<CallRepository.Contact>
                        val c = nums.first()
                        item(key = "c_" + c.number + c.contactId) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { chooser = nums }, onLongClick = { onDetail(keyOf(c.number).ifEmpty { c.number }) }).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.display, c.photoUri); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.display, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                                    Text(flagOf(c.number) + " " + (c.label?.let { "$it · " } ?: "") + displayNumber(c.number) + (lineLabel(c.number)?.let { "  " + it.substringBefore(" ") } ?: "") + (if (nums.size > 1) "  +${nums.size - 1} n°" else "") + (if (!alpha) nums.maxOf { it.last }.let { l -> if (l > 0) "  ·  " + agoText(l) else "" } else ""), fontSize = 12.5.sp, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val gs = nums.flatMap { it.groups }.distinct()
                                    if (gs.isNotEmpty()) Text(gs.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (c.calls > 0) Text("${nums.sumOf { it.calls }}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            // ---- Index alphabétique à faire glisser ----
            if (alpha && letters.size > 1) {
                var h by remember { mutableStateOf(1) }
                fun pick(y: Float) {
                    val i = (y / h * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    val l = letters[i]
                    if (l != bubble) { bubble = l; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    letterIndex[l]?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp).padding(vertical = 6.dp)
                        .onSizeChanged { h = it.height.coerceAtLeast(1) }
                        .pointerInput(letters) {
                            awaitEachGesture {
                                val down = awaitFirstDown(); pick(down.position.y); down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull() ?: break
                                    if (!ch.pressed) break
                                    pick(ch.position.y); ch.consume()
                                }
                                bubble = null
                            }
                        },
                    verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    letters.forEach { l -> Text(l, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (l == bubble) MaterialTheme.colorScheme.primary else muted(.55f)) }
                }
            }
            bubble?.let { l ->
                Box(Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text(l, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    // ---- Fiche contact : tous ses numéros (étiquette, pays), triés par nombre d'appels ----
    chooser?.let { nums ->
        val c0 = nums.first()
        ModalBottomSheet(onDismissRequest = { chooser = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c0.display, c0.photoUri, 60.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (nums.any { it.starred }) "⭐ " else "") + c0.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val total = nums.sumOf { it.calls }; val last = nums.maxOf { it.last }
                        Text("$total appel${if (total > 1) "s" else ""}" + (if (last > 0) " · dernier il y a ${agoText(last)}" else ""), fontSize = 12.5.sp, color = muted())
                    }
                }
                val gs = nums.flatMap { it.groups }.distinct()
                if (gs.isNotEmpty()) Text("🏷️ " + gs.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                Text(if (nums.size > 1) "${nums.size} numéros · du plus appelé au moins appelé" else "Numéro", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted(.6f), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                nums.forEach { n ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable { chooser = null; onConfirm(n.number) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(flagOf(n.number), fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(displayNumber(n.number), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                            Text((n.label ?: "Numéro") + " · " + (lineLabel(n.number) ?: lineType(n.number)) + (if (n.calls > 0) " · ${n.calls} appel${if (n.calls > 1) "s" else ""}" else " · jamais appelé"), fontSize = 12.sp, color = muted())
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(CallGreen), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, "Appeler", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp)) }
                    }
                }
                if (nums.any { it.calls > 0 }) OutlinedButton(onClick = { val k = keyOf(nums.first { it.calls > 0 }.number); chooser = null; onDetail(k) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Icon(Icons.Filled.BarChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Statistiques des appels")
                }
                Spacer(Modifier.height(10.dp))
                NotesCard(nums.map { it.number }, c0.display)
            }
        }
    }


    if (showGroups) {
        ModalBottomSheet(onDismissRequest = { showGroups = false }, containerColor = MaterialTheme.colorScheme.background) {
            var gq by remember { mutableStateOf("") }
            Text("Libellés Google", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 20.dp))
            if (groups.size > 8) OutlinedTextField(gq, { gq = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Filtrer les libellés") }, singleLine = true, shape = RoundedCornerShape(14.dp), leadingIcon = { Icon(Icons.Filled.Search, null) })
            val shown = groups.filter { gq.isBlank() || it.contains(gq, true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 8.dp)) {
                item { GroupRow("Tous les contacts", people.size, group == null) { group = null; showGroups = false } }
                items(shown) { g -> GroupRow(g, groupCounts[g] ?: 0, group == g) { group = g; showGroups = false } }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (groups.isEmpty()) Text("Aucun libellé trouvé sur ce téléphone.", color = muted(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
    }
}

@Composable
private fun GroupRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", fontSize = 13.sp, color = muted())
        if (selected) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
    }
}
