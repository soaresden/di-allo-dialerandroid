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

@Composable
internal fun SettingsScreen(
    themeId: String, onTheme: (String) -> Unit, granted: Boolean, onGrant: () -> Unit,
    isDefault: Boolean, onSetDefault: () -> Unit, callCount: Int, onRefresh: () -> Unit
) {
    val ctx = LocalContext.current
    var inst by remember { mutableStateOf(Synth.currentId) }
    var smsDefault by remember { mutableStateOf(isDefaultSms(ctx)) }
    LaunchedEffect(callCount) { smsDefault = isDefaultSms(ctx) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }   // sous-écran : "theme" / "sound" / "autospk" / "gestures" / "qsms"
    BackHandler(enabled = sub != null) { sub = null }

    when (sub) {
        "theme" -> SubScreen("Thème", onBack = { sub = null }) {
            val all = listOf<HTheme?>(null) + THEMES
            all.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> ThemeSwatch(t, selected = (t?.id ?: "auto") == themeId, modifier = Modifier.weight(1f)) { onTheme(t?.id ?: "auto") } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        "sound" -> SubScreen("Son des touches", onBack = { sub = null }) {
            Text("Touche un instrument pour l'écouter et le choisir.", fontSize = 12.5.sp, color = muted(), modifier = Modifier.padding(bottom = 10.dp))
            Synth.instruments.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { i ->
                        val sel = inst == i.id
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable { inst = i.id; Synth.setInstrument(i.id); saveInst(ctx, i.id); Synth.play("5") }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(i.emoji, fontSize = 26.sp)
                            Text(i.name, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        "autospk" -> SubScreen("Haut-parleur auto", onBack = { sub = null }) { AutoSpeakerSettings() }
        "gestures" -> SubScreen("Gestes", onBack = { sub = null }) { GestureSettings() }
        "qsms" -> SubScreen("Réponses rapides", onBack = { sub = null }) { QuickReplySettings() }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionCard("Apparence & son", collapsible = false) {
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
            SectionCard("Pendant l'appel", collapsible = false) {
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
                Text("Di-Allô — version ${appVersion(ctx)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.height(6.dp))
                Text("Dialer avec clavier musical.", fontSize = 12.5.sp, color = muted()); Spacer(Modifier.height(6.dp))
                Text("⚠️ L'enregistrement d'appel n'est pas possible : réservé aux apps système (constructeur).", fontSize = 12.sp, color = muted())
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
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp).background(shown.brush())) {
            Box(Modifier.padding(8.dp).fillMaxWidth(.7f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(shown.surface).align(Alignment.TopStart))
            Box(Modifier.padding(8.dp).size(20.dp).clip(CircleShape).background(shown.primary).align(Alignment.BottomEnd))
            if (t == null) Text("AUTO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = shown.text, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Text(t?.name ?: "Auto", fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp))
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

    SectionCard("♻️ Restaurer appels & SMS", id = "restore") {
        Text("Choisis une sauvegarde : Di-Allô la compare avec le téléphone et ne rajoute que ce qui manque.", fontSize = 12.5.sp, color = muted(.7f))
        Spacer(Modifier.height(10.dp))
        if (saved.isEmpty()) Text(if (DataFolder.uri(ctx) == null) "Choisis un dossier de travail (au-dessus) pour avoir des sauvegardes automatiques." else "Pas encore de sauvegarde dans le dossier : touche « Sauvegarder » au-dessus.", fontSize = 12.5.sp, color = muted(.6f))
        (if (showAll) saved else saved.take(4)).forEach { b ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = busy == null) { openBackup(b.uri) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (b.calls) "📞" else "💬", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (b.calls) "Journal d'appels" else "SMS & MMS", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(fmtWhen(b.date), fontSize = 12.sp, color = muted())
                }
                Text("Restaurer ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        if (saved.size > 4) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Moins" else "Voir les ${saved.size} sauvegardes") }
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
