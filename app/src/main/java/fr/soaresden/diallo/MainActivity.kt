package fr.soaresden.diallo

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        I18n.init(this)
        DataFolder.init(this)
        takeTab(intent)
        setContent { App() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); takeTab(intent) }
    override fun onResume() {
        super.onResume()
        if (!HyperInCallService.inCall) DataFolder.autoBackupIfDue(this)
        FavoritesWidget.refresh(this)
    }
    private fun takeTab(i: Intent?) { i?.getIntExtra("tab", -1)?.takeIf { it >= 0 }?.let { openTab.intValue = it } }

    companion object {
        /** Onglet demandé de l'extérieur (écran d'appel « Ajouter », widget…). */
        val openTab = mutableIntStateOf(-1)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var theme by rememberSaveable { mutableStateOf(loadTheme(ctx)) }
    LaunchedEffect(Unit) { Synth.setInstrument(loadInst(ctx)) }
    HyperTheme(theme) { Root(theme) { next -> theme = next; saveTheme(ctx, next) } }
}

@Composable
private fun Root(theme: String, onTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    var refresh by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val askTab by MainActivity.openTab
    LaunchedEffect(askTab) { if (askTab >= 0) { tab = askTab; MainActivity.openTab.intValue = -1 } }
    var wrapped by remember { mutableStateOf(false) }
    FolderPrompt { (ctx as? android.app.Activity)?.recreate() }
    CrashReport()
    var pending by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerms(ctx); refresh++ }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(PERMS) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    var isDefault by remember { mutableStateOf(isDefaultDialer(ctx)) }
    LaunchedEffect(refresh) { isDefault = isDefaultDialer(ctx) }

    var data by remember { mutableStateOf(CallRepository.Data(emptyList(), emptyList(), emptyMap())) }
    LaunchedEffect(granted, refresh) { if (granted) data = withContext(Dispatchers.IO) { CallRepository.load(ctx) } }
    // Rechargement uniquement au retour dans l'app (ex. après un appel), pas à chaque onglet
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var first = true
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { if (first) first = false else refresh++ } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val nameMap = remember(data) { data.contacts.associateBy({ keyOf(it.number) }, { it.display }) }
    fun displayOf(num: String) = nameMap[keyOf(num)] ?: num
    val frequent = remember(data) {
        val since = System.currentTimeMillis() - 120L * 86_400_000L
        val recent = data.calls.filter { it.date >= since && it.number.any(Char::isDigit) }.groupBy { keyOf(it.number) }.entries.sortedByDescending { it.value.size }.mapNotNull { data.stats[it.key] }
        (recent + data.stats.values.sortedByDescending { it.count }).distinctBy { keyOf(it.number) }.filter { it.number.any(Char::isDigit) }.take(8)
    }
    val confirm: (String) -> Unit = { pending = it }

    fun requestDefault() {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
    }

    pending?.let { num ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Filled.Call, null, tint = CallGreen) },
            title = { Text(tr("Appeler ?")) },
            text = { Column { Text(displayOf(num), fontWeight = FontWeight.SemiBold, fontSize = 17.sp); if (displayOf(num) != num) Text(phoneText(num), fontSize = 13.sp, color = muted()) } },
            confirmButton = { Button(onClick = { placeCall(ctx, num); pending = null }, colors = ButtonDefaults.buttonColors(containerColor = CallGreen, contentColor = Color.White)) { Icon(Icons.Filled.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Appeler")) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("Annuler")) } }
        )
    }
    if (wrapped) { WrappedScreen(data.calls, data.contacts) { wrapped = false }; return }
    detail?.let { k -> ContactStatsSheet(k, data.calls, onCall = { detail = null; pending = it }, onDismiss = { detail = null }, onDeleted = { refresh++ }) }
    // enregistrements d'appels (dossier Rec) : relus à chaque rechargement du journal
    val recFolder by Recordings.folderVersion
    LaunchedEffect(data.calls, recFolder) { if (data.calls.isNotEmpty()) withContext(Dispatchers.IO) { Recordings.refresh(ctx, data) } }
    val recAll by Recordings.all
    val showRec = remember(recFolder, recAll) { recAll.isNotEmpty() || fr.soaresden.diallo.rec.CallRecorder.enabled(ctx) }
    RecordingPlayer()
    // enregistreur intégré : tout est-il en règle ? (relance le service au besoin)
    val recProblem by fr.soaresden.diallo.rec.RecHealth.problem
    val recWizard by fr.soaresden.diallo.rec.RecHealth.wizard
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { fr.soaresden.diallo.rec.RecHealth.check(ctx.applicationContext) } }
    if (recWizard) fr.soaresden.diallo.rec.RecSetupWizard { fr.soaresden.diallo.rec.RecHealth.wizard.value = false; withContextRecCheck(ctx) }

    // nouvelle version sur GitHub ? (au plus toutes les 6 h)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Updater.check(ctx.applicationContext) } }
    val update by Updater.available
    UpdateDialog()
    val picking by ThemePicker.open
    LaunchedEffect(picking) { if (picking) tab = 0 }   // le clavier d'abord : c'est l'écran qui compte
    Row(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                // id d'onglet stable (le widget ouvre 2, l'appel en cours 0) ; REC = 6, affiché avant Réglages s'il sert
                val tabs = listOfNotNull(
                    Triple(0, tr("Clavier"), Icons.Filled.Dialpad), Triple(1, tr("Récents"), Icons.Filled.Schedule), Triple(2, tr("Contacts"), Icons.Filled.Person),
                    Triple(3, tr("Notes"), Icons.Filled.EditNote), Triple(4, tr("Stats"), Icons.Filled.BarChart),
                    if (showRec) Triple(6, "REC", Icons.Filled.Mic) else null, Triple(5, tr("Réglages"), Icons.Filled.Settings)
                )
                tabs.forEach { (i, label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            // thèmes sombres : icônes non sélectionnées bien lisibles
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f), unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f)
                        )
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            CallBanner()
            Header()
            if (!isDefault) Banner(MaterialTheme.colorScheme.primaryContainer, tr("Définir Di-Allô comme téléphone par défaut"), tr("Activer")) { requestDefault() }
            if (!granted) Banner(MaterialTheme.colorScheme.surfaceVariant, tr("Autoriser le journal d'appels et les contacts"), tr("Autoriser")) { permLauncher.launch(PERMS) }
            recProblem?.let { Banner(MaterialTheme.colorScheme.surfaceVariant, it, tr("Régler")) { fr.soaresden.diallo.rec.RecHealth.wizard.value = true } }
            update?.let { u -> Banner(MaterialTheme.colorScheme.primaryContainer, "⬆️ " + tr("Mise à jour disponible : {0}", u.version), tr("Voir")) { Updater.showDialog.value = true } }
            when (tab) {
                0 -> DialScreen(confirm, { callVoicemail(ctx) }, data.contacts, frequent)
                1 -> RecentsList(data.calls, data.contacts, confirm, onDeleted = { refresh++ }) { detail = it }
                2 -> ContactsScreen(data.contacts, data.groups, confirm) { detail = it }
                3 -> NotesScreen(data.contacts, confirm) { detail = it }
                4 -> StatsScreen(data.calls, data.contacts, onWrapped = { wrapped = true }) { detail = it }
                6 -> RecScreen(data.contacts, confirm) { settingsSub.value = "rec"; tab = 5 }
                else -> SettingsScreen(theme, onTheme, granted, { permLauncher.launch(PERMS) }, isDefault, { requestDefault() }, data.calls.size, { refresh++ })
            }
        }
    }
    if (picking) ThemePanel(theme, onTheme, tab, { tab = it }) { ThemePicker.open.value = false }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        var show by remember { mutableStateOf(false) }
        if (show) LogoShow { show = false }
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.logo_hand), "Di-Allô", Modifier.size(30.dp).clip(CircleShape).clickable { show = true }); Spacer(Modifier.width(8.dp))
        val th = LocalHTheme.current
        Text("Di-Allô", fontWeight = if (th.title != null) FontWeight.Normal else FontWeight.Bold, fontSize = (19f * th.headScale.coerceIn(.75f, 1.2f)).sp, fontFamily = th.title,
            style = androidx.compose.ui.text.TextStyle(shadow = th.glowShadow()), color = if (th.title != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
        Text(" by Soaresden", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = muted(.45f), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        val ctx = LocalContext.current
        Text("v" + remember { appVersion(ctx) }, fontSize = 11.sp, color = muted(.45f), modifier = Modifier.padding(top = 4.dp))
    }
}

/** Appel en cours alors qu'on est revenu dans l'appli : bandeau vert pour y retourner. */
@Composable
private fun CallBanner() {
    val call by HyperInCallService.current
    val state by HyperInCallService.callState
    val contact by HyperInCallService.contact
    val c = call
    if (c == null || state == android.telecom.Call.STATE_DISCONNECTED || state == android.telecom.Call.STATE_DISCONNECTING) return
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val ct = c.details?.connectTimeMillis ?: 0L
    val number = c.details?.handle?.schemeSpecificPart ?: ""
    val name = contact?.first ?: if (number.isBlank()) tr("Numéro masqué") else displayNumber(number)
    val t = when {
        state == android.telecom.Call.STATE_RINGING -> tr("appel entrant")
        state == android.telecom.Call.STATE_HOLDING -> tr("en attente")
        state == android.telecom.Call.STATE_ACTIVE && ct > 0 -> ((now - ct) / 1000).coerceAtLeast(0).let { "%02d:%02d".format(it / 60, it % 60) }
        else -> tr("appel…")
    }
    Surface(
        color = CallGreen, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .clickable { ctx.startActivity(Intent(ctx, OngoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("En cours · {0} · touche pour revenir", t), color = Color.White.copy(alpha = .85f), fontSize = 12.sp, maxLines = 1)
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(Red).clickable { HyperInCallService.hangup() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CallEnd, tr("Raccrocher"), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Banner(bg: Color, text: String, action: String, onClick: () -> Unit) {
    Surface(color = bg, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

/* ---------- Récents groupés par période ---------- */
private val RECENT_LABELS = listOf("Aujourd'hui", "7 derniers jours", "30 derniers jours", "Dernier trimestre", "Cette année", "Plus ancien")
private fun bucket(ts: Long): Int { val d = daysAgo(ts); return when { d <= 0 -> 0; d <= 7 -> 1; d <= 31 -> 2; d <= 92 -> 3; d <= 366 -> 4; else -> 5 } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentsList(calls: List<CallRepository.CallEntry>, contacts: List<CallRepository.Contact>, onConfirm: (String) -> Unit, onDeleted: () -> Unit, onDetail: (String) -> Unit) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    // numéros étoilés dans le téléphone (base des favoris)
    val starredKeys = remember(contacts) { contacts.filter { it.starred || it.groups.contains("⭐ Favoris") }.map { keyOf(it.number) }.toSet() }
    val favVer = FavNumbers.version.intValue
    val favSnap = remember(favVer) { FavNumbers.snap(ctx) }
    val scope = rememberCoroutineScope()
    // appels supprimés : masqués tout de suite, avant le rechargement du journal
    val gone = remember { mutableStateListOf<Long>() }
    val shownCalls = remember(calls, gone.size) { if (gone.isEmpty()) calls else calls.filter { it.id !in gone } }
    if (shownCalls.isEmpty()) { Empty(tr("Aucun appel dans le journal.")); return }
    val groups = remember(shownCalls) { shownCalls.groupBy { bucket(it.date) }.toSortedMap() }
    // Les périodes anciennes sont repliées par défaut (des milliers d'appels depuis 2008)
    val states = RECENT_LABELS.indices.map { i -> rememberCollapsed("recent_$i", default = i >= 3) }
    // sélection multiple pour supprimer plusieurs appels d'un coup
    val sel = remember { mutableStateListOf<Long>() }
    val selecting = sel.isNotEmpty()
    var askDelete by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting) { sel.clear() }
    fun toggle(id: Long) { if (id < 0) return; if (id in sel) sel.remove(id) else sel.add(id) }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(trn(sel.size, "Supprimer {0} appel ?", "Supprimer {0} appels ?")) },
        text = { Text(tr("Ils seront effacés du journal du téléphone.")) },
        confirmButton = {
            Button(onClick = {
                askDelete = false
                val ids = sel.toList(); sel.clear(); gone.addAll(ids)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ids.count { CallRepository.deleteCall(ctx, it) } }
                    if (ok == 0) { gone.removeAll(ids); android.widget.Toast.makeText(ctx, tr("Impossible : Di-Allô doit être l'appli Téléphone par défaut"), android.widget.Toast.LENGTH_LONG).show() }
                    else { android.widget.Toast.makeText(ctx, trn(ok, "{0} appel supprimé", "{0} appels supprimés"), android.widget.Toast.LENGTH_SHORT).show(); onDeleted() }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text(tr("Supprimer")) }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(tr("Annuler")) } }
    )
    Column(Modifier.fillMaxSize()) {
    if (selecting) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sel.clear() }) { Icon(Icons.Filled.Close, tr("Annuler")) }
            Text(trn(sel.size, "{0} sélectionné", "{0} sélectionnés"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = { askDelete = true }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Supprimer"))
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        groups.forEach { (b, list) ->
            val st = states[b]
            val missed = list.count { it.type == android.provider.CallLog.Calls.MISSED_TYPE || it.type == android.provider.CallLog.Calls.REJECTED_TYPE }
            stickyHeader(key = "h$b") {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = .94f)).padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp)).clickable { st.value = !st.value }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selecting) {
                        val ids = list.map { it.id }.filter { it >= 0 }
                        val all = ids.isNotEmpty() && ids.all { it in sel }
                        Checkbox(all, { on -> if (on) ids.forEach { if (it !in sel) sel.add(it) } else sel.removeAll(ids) }, Modifier.size(32.dp))
                    }
                    Text(tr(RECENT_LABELS[b]), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (missed > 0) Text(trn(missed, "{0} manqué", "{0} manqués") + "  ", fontSize = 11.5.sp, color = Red)
                    Text("${list.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted())
                    Spacer(Modifier.width(4.dp)); Chevron(st.value)
                }
            }
            if (!st.value) items(list.size, key = { i -> "c$b-${list[i].id}-${list[i].date}-$i" }) { i ->
                val row = list[i]
                val isMissed = row.type == android.provider.CallLog.Calls.MISSED_TYPE || row.type == android.provider.CallLog.Calls.REJECTED_TYPE
                var menu by remember { mutableStateOf(false) }
                Box {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("📊 Fiche de {0}", row.display)) }, onClick = { menu = false; onDetail(keyOf(row.number).ifEmpty { row.number }) })
                    Recordings.byCall.value[row.id]?.let { r -> DropdownMenuItem(text = { Text(tr("🎙️ Écouter l'enregistrement")) }, onClick = { menu = false; Recordings.open.value = r to row.display }) }
                    if (row.number.any { it.isDigit() }) {
                        val star = keyOf(row.number) in starredKeys
                        val fav = favSnap.isFav(row.number, star)
                        DropdownMenuItem(text = { Text(if (fav) "☆ " + tr("Retirer des favoris") else "⭐ " + tr("Ajouter aux favoris")) }, onClick = { menu = false; FavNumbers.toggle(ctx, row.number, star, view) })
                    }
                    if (row.number.any { it.isDigit() }) DropdownMenuItem(text = { Text("📌 " + tr("Sur l'écran d'accueil")) }, onClick = {
                        menu = false
                        if (!WidgetPrefs.pinContact(ctx, Pick(row.display, row.number, row.photoUri))) android.widget.Toast.makeText(ctx, tr("Ton écran d'accueil ne permet pas d'ajouter le widget depuis l'appli : appui long sur l'écran d'accueil › Widgets › Di-Allô."), android.widget.Toast.LENGTH_LONG).show()
                    })
                    DropdownMenuItem(text = { Text("☑️ " + tr("Sélectionner pour supprimer"), color = Red) }, enabled = row.id >= 0, onClick = { menu = false; toggle(row.id) })
                }
                val picked = row.id in sel
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .combinedClickable(onClick = { if (selecting) toggle(row.id) else onConfirm(row.number) }, onLongClick = { if (selecting) toggle(row.id) else menu = true })
                    .padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) { Checkbox(picked, { toggle(row.id) }, Modifier.size(32.dp), enabled = row.id >= 0); Spacer(Modifier.width(4.dp)) }
                    Avatar(row.display, row.photoUri); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.display, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isMissed) Red else MaterialTheme.colorScheme.onBackground)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when { isMissed -> Icons.Filled.CallMissed; row.type == android.provider.CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade; else -> Icons.Filled.CallReceived }, null, tint = if (isMissed) Red else muted(.55f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(flagOf(row.number) + " " + row.dirLabel + if (row.duration > 0) " · " + fmtDur(row.duration) else "", fontSize = 13.sp, color = muted())
                        }
                    }
                    RecBadge(row.id, row.display)
                    Text(fmtWhen(row.date), fontSize = 12.sp, color = muted())
                }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
    }
}

/* ---------- Helpers ---------- */
private val PERMS = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS)
private fun hasPerms(c: Context): Boolean = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
private fun isDefaultDialer(c: Context): Boolean = c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
internal fun placeCall(c: Context, number: String) { try { val a = if (ContextCompat.checkSelfPermission(c, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL; c.startActivity(Intent(a, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} }
private fun callVoicemail(c: Context) { try { val a = if (ContextCompat.checkSelfPermission(c, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL; c.startActivity(Intent(a, Uri.fromParts("voicemail", "", null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} }

/** Revérifie l'enregistreur après l'assistant (hors fil principal). */
private fun withContextRecCheck(ctx: android.content.Context) { Thread { fr.soaresden.diallo.rec.RecHealth.check(ctx.applicationContext) }.start() }
