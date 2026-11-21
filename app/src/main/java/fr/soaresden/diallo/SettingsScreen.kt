package fr.soaresden.diallo

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Demande d'ouverture d'un sous-écran des réglages depuis un autre onglet. */
internal val settingsSub = mutableStateOf<String?>(null)

@Composable
internal fun SettingsScreen(
    themeId: String, onTheme: (String) -> Unit, granted: Boolean, onGrant: () -> Unit,
    isDefault: Boolean, onSetDefault: () -> Unit, callCount: Int, onRefresh: () -> Unit
) {
    val ctx = LocalContext.current
    var inst by remember { mutableStateOf(Synth.currentId) }
    var smsDefault by remember { mutableStateOf(isDefaultSms(ctx)) }
    LaunchedEffect(callCount) { smsDefault = isDefaultSms(ctx) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }   // sous-écran : "theme" / "sound" / "autospk" / "gestures" / "qsms" / "rec"
    val askSub by settingsSub
    LaunchedEffect(askSub) { askSub?.let { sub = it; settingsSub.value = null } }
    BackHandler(enabled = sub != null) { sub = null }

    when (sub) {
        "theme" -> SubScreen("Thème", onBack = { sub = null }) {
            val all = listOf<HTheme?>(null) + THEMES
            all.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> ThemeSwatch(t, selected = (t?.id ?: "auto") == themeId, modifier = Modifier.weight(1f)) { onTheme(t?.id ?: "auto") } }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        "sound" -> SubScreen("Son des touches", onBack = { sub = null }) {
            Text("Touche un instrument pour l'écouter et le choisir.", fontSize = 12.5.sp, color = muted(), modifier = Modifier.padding(bottom = 10.dp))
            // grille compacte : 4 par ligne
            Synth.instruments.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i ->
                        val sel = inst == i.id
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable { inst = i.id; Synth.setInstrument(i.id); saveInst(ctx, i.id); Synth.play("5") }
                                .padding(vertical = 7.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(i.emoji, fontSize = 20.sp)
                            Text(i.name, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        "autospk" -> SubScreen("Haut-parleur auto", onBack = { sub = null }) { AutoSpeakerSettings() }
        "gestures" -> SubScreen("Gestes", onBack = { sub = null }) { GestureSettings() }
        "qsms" -> SubScreen("Réponses rapides", onBack = { sub = null }) { QuickReplySettings() }
        "rec" -> SubScreen("Enregistrement des appels", onBack = { sub = null }) { RecordingSettings() }
        "backup" -> SubScreen("Sauvegardes", onBack = { sub = null }) {
            SectionCard("Données") {
                val fv by DataFolder.folderVersion
                val folder = remember(fv, sub) { DataFolder.name(ctx) }
                val last = remember(fv, sub) { DataFolder.lastBackup(ctx) }
                NavRow("Sauvegardes & restauration",
                    if (folder == null) "⚠️ Aucun dossier de travail choisi" else "📁 $folder" + (if (last > 0) " · dernière ${fmtWhen(last)}" else ""),
                    leading = {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (folder == null) Amber.copy(alpha = .25f) else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("💾", fontSize = 18.sp) }
                    }) { sub = "backup" }
            }
            Spacer(Modifier.height(16.dp))
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionCard("Apparence & son") {
                val t = THEMES.firstOrNull { it.id == themeId }
                NavRow("Thème", t?.name ?: "Auto (suit le système)", leading = {
                    val sh = t ?: resolveTheme("auto", androidx.compose.foundation.isSystemInDarkTheme())
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(sh.brush())) { Box(Modifier.size(12.dp).clip(CircleShape).background(sh.primary).align(Alignment.BottomEnd)) }
                }) { sub = "theme" }
                val i = Synth.instruments.firstOrNull { it.id == inst }
                NavRow("Son des touches", i?.name ?: "", leading = {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(i?.emoji ?: "🎵", fontSize = 18.sp) }
                }) { sub = "sound" }
            }
            SectionCard("Pendant l'appel") {
                val ac = remember(sub) { AutoSpeaker.cfg(ctx) }
                NavRow("Haut-parleur auto", autoSpeakerSummary(ac), leading = {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (ac.on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("🔊", fontSize = 18.sp) }
                }) { sub = "autospk" }
                val gc = remember(sub) { RingGestures.cfg(ctx) }
                NavRow("Gestes pendant la sonnerie", gesturesSummary(gc), leading = {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("🤚", fontSize = 18.sp) }
                }) { sub = "gestures" }
                val qn = remember(sub) { QuickReplies.load(ctx).size }
                NavRow("Réponses rapides par SMS", "$qn messages pour refuser un appel", leading = {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("💬", fontSize = 18.sp) }
                }) { sub = "qsms" }
                val recN = Recordings.all.value.size
                val recOn = remember(sub) { fr.soaresden.diallo.rec.CallRecorder.enabled(ctx) }
                NavRow("Enregistrement des appels", (if (recOn) "Activé" else "Désactivé") + " · $recN enregistrement${if (recN > 1) "s" else ""}", leading = {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (recOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("🎙️", fontSize = 18.sp) }
                }) { sub = "rec" }
            }
            SectionCard("Téléphone") {
                SettingRow("Application Téléphone par défaut", if (isDefault) "Di-Allô" else "Non défini", if (isDefault) null else "Définir", onSetDefault); Spacer(Modifier.height(8.dp))
                SettingRow("Accès journal / contacts", if (granted) "Accordé" else "Refusé", if (granted) null else "Autoriser", onGrant); Spacer(Modifier.height(8.dp))
                Text("Appels chargés : $callCount", fontSize = 12.5.sp, color = muted())
            }
            if (smsDefault) SectionCard("⚠️ Di-Allô est l'app SMS par défaut", collapsible = false) {
                Text("C'était seulement pour restaurer les SMS. Remets Google Messages par défaut pour recevoir correctement SMS et MMS.", fontSize = 12.5.sp, color = muted(.75f))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openDefaultApps(ctx) }) { Text("Remettre Messages par défaut") }
            }
            FolderCard()
            BackupSection(ctx, isDefault, onRefresh) { smsDefault = isDefaultSms(ctx) }
            SectionCard("À propos", defaultCollapsed = true) {
                Text("Di-Allô — version ${appVersion(ctx)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("by Soaresden · github.com/soaresden/di-allo-dialerandroid", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium); Spacer(Modifier.height(6.dp))
                Text("Dialer avec clavier musical.", fontSize = 12.5.sp, color = muted()); Spacer(Modifier.height(6.dp))
                Text("🎙️ Enregistrement des appels en MP3 via le débogage sans fil (Réglages › Pendant l'appel).", fontSize = 12.sp, color = muted()); Spacer(Modifier.height(6.dp))
                Text("Bibliothèques : libadb-android (Apache-2.0), Conscrypt (Apache-2.0), jump3r / LAME (LGPL), osmdroid (Apache-2.0), Coil (Apache-2.0). Emoji 3D Fluent (Microsoft, MIT). Polices Google Fonts (SIL OFL / Apache-2.0).", fontSize = 11.5.sp, color = muted(.55f))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal fun appVersion(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = MaterialTheme.colorScheme.onBackground) }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), content = content)
    }
}

@Composable
private fun NavRow(title: String, value: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        leading(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(value, fontSize = 12.5.sp, color = muted())
        }
        Icon(Icons.Filled.ChevronRight, null, tint = muted(.5f))
    }
}

@Composable
private fun ThemeSwatch(t: HTheme?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shown = t ?: resolveTheme("auto", androidx.compose.foundation.isSystemInDarkTheme())
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.5.dp else 1.dp, outline, RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        // aperçu : fond + décor, une touche dans le style du thème, le nom dans sa police
        Box(Modifier.fillMaxWidth().height(96.dp).background(shown.brush())) {
            DecorLayer(shown, Modifier.matchParentSize(), animate = false)
            Text(
                if (t == null) "Auto" else "Di-Allô", fontFamily = shown.title, fontSize = (15f * shown.headScale.coerceIn(.7f, 1.2f)).sp,
                fontWeight = if (shown.title == null) FontWeight.Bold else FontWeight.Normal, color = if (shown.title != null) shown.primary else shown.text,
                style = androidx.compose.ui.text.TextStyle(shadow = shown.glowShadow()), maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp)
            )
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1", "2").forEach { d ->
                    Box(Modifier.size(30.dp).themedKey(shown, shown.keyShapeOf(), 0f), contentAlignment = Alignment.Center) {
                        Text(d, fontFamily = shown.num, fontSize = (14f * shown.numScale).sp, color = if (shown.keyStyle == KeyStyle.NEON) shown.primary else shown.text,
                            style = androidx.compose.ui.text.TextStyle(shadow = shown.glowShadow()))
                    }
                }
                Box(Modifier.size(30.dp).clip(shown.keyShapeOf()).background(CallGreen), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t?.name ?: "Auto", fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (t == null) "Clair ou AMOLED selon le système" else shown.tag, fontSize = 9.5.sp, lineHeight = 11.sp, color = muted(.55f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, action: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground); Text(value, fontSize = 12.5.sp, color = muted()) }
        if (action != null) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(action, fontSize = 13.sp) }
    }
}

/* ---------- Backup : appels ET SMS (détection automatique du fichier) ---------- */
@Composable
private fun BackupSection(ctx: Context, isDefault: Boolean, onRefresh: () -> Unit, onSmsRoleChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var calls by remember { mutableStateOf<BackupRepository.Analysis?>(null) }
    var sms by remember { mutableStateOf<BackupRepository.SmsAnalysis?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmCalls by remember { mutableStateOf(false) }
    var confirmSms by remember { mutableStateOf(false) }
    var pendingSmsUri by remember { mutableStateOf<Uri?>(null) }
    var smsImported by remember { mutableStateOf(false) }

    fun analyzeSms(uri: Uri) {
        busy = "Lecture des SMS du backup…"
        scope.launch {
            val parsed = withContext(Dispatchers.IO) { BackupRepository.parseSms(ctx, uri) }
            busy = "Comparaison avec le téléphone (${parsed.sms.size} SMS, ${parsed.mms.size} MMS)…"
            sms = withContext(Dispatchers.IO) { BackupRepository.analyzeSms(ctx, parsed) }
            busy = null
        }
    }
    fun runSmsImport() {
        val a = sms ?: return
        val miss = a.missing; val tot = a.missingCount
        busy = "Import des SMS… 0 / $tot"
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                BackupRepository.importSms(ctx, miss) { done -> scope.launch { busy = "Import des SMS… $done / $tot" } } +
                    BackupRepository.importMms(ctx, a.missingMms) { done -> scope.launch { busy = "Import des MMS… ${miss.size + done} / $tot" } }
            }
            busy = null; sms = null; smsImported = true
            msg = if (n > 0) "✅ $n SMS restaurés." else "❌ Aucun SMS écrit (Di-Allô doit être l'app SMS par défaut)."
            onSmsRoleChanged()
        }
    }

    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val u = pendingSmsUri; pendingSmsUri = null
        if (ok && u != null) analyzeSms(u) else if (!ok) msg = "Permission SMS refusée : impossible de comparer."
    }
    val smsRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onSmsRoleChanged()
        if (isDefaultSms(ctx)) runSmsImport() else msg = "Di-Allô n'a pas été choisi comme app SMS : import annulé."
    }
    fun openBackup(uri: Uri) {
        calls = null; sms = null; msg = null; smsImported = false
        busy = "Analyse du fichier…"
        scope.launch {
            when (withContext(Dispatchers.IO) { BackupRepository.detect(ctx, uri) }) {
                BackupRepository.Kind.CALLS -> {
                    val b = withContext(Dispatchers.IO) { BackupRepository.parse(ctx, uri) }
                    calls = withContext(Dispatchers.IO) { BackupRepository.analyze(ctx, b) }; busy = null
                }
                BackupRepository.Kind.SMS -> {
                    busy = null
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) analyzeSms(uri)
                    else { pendingSmsUri = uri; smsPerm.launch(Manifest.permission.READ_SMS) }
                }
                else -> { busy = null; msg = "Fichier non reconnu : il faut une sauvegarde d'appels (calls-….xml) ou de SMS (sms-….xml)." }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) openBackup(uri) }
    val fv by DataFolder.folderVersion
    val bk by DataFolder.busy
    val saved by produceState(emptyList<DataFolder.Backup>(), fv, bk) { value = withContext(Dispatchers.IO) { DataFolder.backups(ctx) } }
    var showAll by remember { mutableStateOf(false) }
    val rows = remember(saved) { pairBackups(saved) }

    SectionCard("♻️ Restaurer appels & SMS") {
        Text("Choisis une sauvegarde : Di-Allô la compare avec le téléphone et ne rajoute que ce qui manque.", fontSize = 12.5.sp, color = muted(.7f))
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) Text(if (DataFolder.uri(ctx) == null) "Choisis un dossier de travail (au-dessus) pour avoir des sauvegardes automatiques." else "Pas encore de sauvegarde dans le dossier : touche « Sauvegarder » au-dessus.", fontSize = 12.5.sp, color = muted(.6f))
        else BackupTable(if (showAll) rows else rows.take(6), enabled = busy == null) { openBackup(it.uri) }
        if (rows.size > 6) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Moins" else "Voir les ${rows.size} sauvegardes") }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { picker.launch(arrayOf("text/xml", "application/xml", "text/*", "*/*")) }, enabled = busy == null) { Text("Autre fichier .xml…") }
        Text("Accepte aussi les anciens fichiers de SMS Backup & Restore (même format).", fontSize = 11.5.sp, color = muted(.5f), modifier = Modifier.padding(top = 4.dp))
        busy?.let { Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp, color = muted(.75f)) } }
        calls?.let { a ->
            Spacer(Modifier.height(10.dp))
            BackupSummary("📞 Appels", a.total, a.present, a.missingCount, null)
            if (a.missingCount > 0) {
                Spacer(Modifier.height(8.dp))
                if (!isDefault) Text("⚠️ Définis Di-Allô comme application Téléphone par défaut pour écrire dans le journal.", fontSize = 12.sp, color = Amber)
                else Button(onClick = { confirmCalls = true }) { Text("Importer ${a.missingCount} appels manquants") }
            }
        }
        sms?.let { a ->
            Spacer(Modifier.height(10.dp))
            BackupSummary("💬 SMS", a.total, a.present, a.missingCount, if (a.mmsCount > 0) "dont ${a.mmsCount} MMS (photos, groupes)" else null)
            if (a.missingCount > 0) { Spacer(Modifier.height(8.dp)); Button(onClick = { confirmSms = true }) { Text("Restaurer ${a.missingCount} SMS manquants") } }
        }
        msg?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        if (smsImported && isDefaultSms(ctx)) { Spacer(Modifier.height(8.dp)); Button(onClick = { openDefaultApps(ctx) }) { Text("Remettre Google Messages par défaut") } }
    }

    if (confirmCalls) {
        val a = calls
        AlertDialog(
            onDismissRequest = { confirmCalls = false },
            icon = { Icon(Icons.Filled.Restore, null) },
            title = { Text("Compléter le journal ?") },
            text = { Text("${a?.missingCount ?: 0} appels du backup seront ajoutés au journal d'appels. Difficile à annuler ensuite.") },
            confirmButton = {
                Button(onClick = {
                    confirmCalls = false
                    val miss = a?.missing ?: emptyList()
                    busy = "Import des appels…"
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BackupRepository.importMissing(ctx, miss) }
                        msg = "✅ $n appels importés dans le journal."; calls = null; busy = null; onRefresh()
                    }
                }) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = { confirmCalls = false }) { Text("Annuler") } }
        )
    }
    if (confirmSms) {
        val already = isDefaultSms(ctx)
        AlertDialog(
            onDismissRequest = { confirmSms = false },
            icon = { Icon(Icons.Filled.Restore, null) },
            title = { Text("Restaurer ${sms?.missingCount ?: 0} SMS ?") },
            text = {
                Text(
                    if (already) "Les SMS manquants vont être ajoutés à tes conversations. Difficile à annuler ensuite."
                    else "Android n'autorise que l'app SMS par défaut à écrire des SMS.\n\n1. Choisis Di-Allô comme app SMS (temporairement)\n2. L'import se lance tout seul\n3. Remets Google Messages par défaut ensuite (bouton fourni).\n\nPendant ce court moment, les SMS reçus sont quand même enregistrés."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmSms = false
                    if (already) runSmsImport()
                    else {
                        val rm = ctx.getSystemService(RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) smsRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                        else msg = "Le rôle SMS n'est pas disponible sur ce téléphone."
                    }
                }) { Text(if (already) "Restaurer" else "Continuer") }
            },
            dismissButton = { TextButton(onClick = { confirmSms = false }) { Text("Annuler") } }
        )
    }
}

/** Une ligne = un moment de sauvegarde ; appels et SMS du même moment côte à côte. */
private data class BackupRow(val date: Long, val calls: DataFolder.Backup?, val sms: DataFolder.Backup?)

private fun pairBackups(list: List<DataFolder.Backup>): List<BackupRow> {
    val rows = ArrayList<BackupRow>()
    list.sortedByDescending { it.date }.forEach { b ->
        val i = rows.indexOfFirst { kotlin.math.abs(it.date - b.date) <= 10 * 60_000L && (if (b.calls) it.calls == null else it.sms == null) }
        if (i >= 0) rows[i] = if (b.calls) rows[i].copy(calls = b) else rows[i].copy(sms = b)
        else rows.add(BackupRow(b.date, b.takeIf { it.calls }, b.takeIf { !it.calls }))
    }
    return rows
}

private fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> "${(b / 1024).coerceAtLeast(1)} Ko"
    else -> String.format(java.util.Locale.FRANCE, "%.1f Mo", b / (1024.0 * 1024.0))
}

@Composable
private fun BackupTable(rows: List<BackupRow>, enabled: Boolean, onPick: (DataFolder.Backup) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Date", Modifier.weight(1.25f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted(.8f))
            Text("📞 Appels", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted(.8f), textAlign = TextAlign.Center)
            Text("💬 SMS / MMS", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted(.8f), textAlign = TextAlign.Center)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.25f).padding(start = 4.dp)) {
                    Text(java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.FRANCE).format(java.util.Date(r.date)), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)
                    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(r.date)), fontSize = 11.5.sp, color = muted())
                }
                BackupCell(r.calls, enabled, Modifier.weight(1f).fillMaxHeight(), onPick)
                BackupCell(r.sms, enabled, Modifier.weight(1f).fillMaxHeight(), onPick)
            }
        }
    }
}

@Composable
private fun BackupCell(b: DataFolder.Backup?, enabled: Boolean, m: Modifier, onPick: (DataFolder.Backup) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (b == null) { Box(m, contentAlignment = Alignment.Center) { Text("—", color = muted(.35f), fontSize = 14.sp) }; return }
    Column(
        m.padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp)).background(cs.primary.copy(alpha = .10f))
            .clickable(enabled = enabled) { onPick(b) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("Restaurer", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        fmtSize(b.size).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 10.5.sp, color = muted(.6f)) }
    }
}

@Composable
private fun BackupSummary(title: String, total: Int, present: Int, missing: Int, note: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Stat("Backup", total, Modifier.weight(1f), MaterialTheme.colorScheme.onBackground)
            Stat("Déjà là", present, Modifier.weight(1f), MaterialTheme.colorScheme.onBackground)
            Stat("Manquants", missing, Modifier.weight(1f), if (missing > 0) Amber else MaterialTheme.colorScheme.primary)
        }
        note?.let { Text(it, fontSize = 11.5.sp, color = muted(.6f), modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun Stat(k: String, v: Int, m: Modifier, c: Color) { Column(m) { Text(k, fontSize = 11.sp, color = muted()); Text("$v", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c) } }
