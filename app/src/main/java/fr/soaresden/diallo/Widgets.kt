package fr.soaresden.diallo

import androidx.compose.ui.graphics.Color
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ======================= Contacts : liste simple pour choisir ======================= */

/** Un numéro de contact à choisir (widget 1×1, numérotation rapide). */
data class Pick(val name: String, val number: String, val photo: String?) {
    fun encode() = listOf(name, number, photo ?: "").joinToString("\u001F")
    companion object {
        fun decode(s: String?): Pick? = s?.split('\u001F')?.takeIf { it.size >= 2 }?.let { Pick(it[0], it[1], it.getOrNull(2)?.ifEmpty { null }) }
    }
}

object Picks {
    fun all(ctx: Context): List<Pick> {
        val out = ArrayList<Pick>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { c -> while (c.moveToNext()) { val n = c.getString(1) ?: continue; out.add(Pick(c.getString(0) ?: n, n, c.getString(2))) } }
        } catch (_: Throwable) { }
        return out.distinctBy { it.name + "|" + keyOf(it.number) }
    }
}

/** Fenêtre de choix d'un contact (recherche par nom ou numéro). */
@Composable
internal fun ContactPickerDialog(title: String, onDismiss: () -> Unit, onPick: (Pick) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<Pick>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { Picks.all(ctx) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.85f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("Fermer")) }
                }
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(tr("Nom ou numéro")) }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                Spacer(Modifier.height(8.dp))
                val list = all
                if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else {
                    val t = q.trim(); val digits = t.filter { it.isDigit() }
                    val shown = remember(list, t) { list.filter { t.isEmpty() || it.name.contains(t, true) || (digits.length >= 2 && it.number.filter { c -> c.isDigit() }.contains(digits)) } }
                    val state = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            items(shown, key = { it.name + "|" + it.number }) { p ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(p.name, p.photo, 38.dp); Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(flagOf(p.number) + " " + displayNumber(p.number), fontSize = 12.5.sp, color = muted())
                                    }
                                }
                            }
                        }
                        LazyScrollbar(state, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

/* ======================= Réglages des widgets ======================= */

object WidgetPrefs {
    private fun p(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    /** 0 = favoris puis les plus appelés, 1 = favoris seulement, 2 = les plus appelés. */
    fun source(c: Context) = p(c).getInt("fav_source", 0)
    fun count(c: Context) = p(c).getInt("fav_count", 8)
    fun names(c: Context) = p(c).getBoolean("fav_names", true)
    /** 0 = vert, 1 = sombre, 2 = clair, 3 = transparent. */
    fun style(c: Context) = p(c).getInt("style", 0)
    fun set(c: Context, key: String, v: Any) {
        p(c).edit().apply { when (v) { is Int -> putInt(key, v); is Boolean -> putBoolean(key, v) } }.apply()
        FavoritesWidget.refresh(c); ContactWidget.refreshAll(c)
    }
    fun bg(style: Int) = when (style) { 1 -> R.drawable.widget_bg_dark; 2 -> R.drawable.widget_bg_light; 3 -> R.drawable.widget_bg_clear; else -> R.drawable.widget_bg }
    fun fg(style: Int): Int = if (style == 2) 0xFF1B1D22.toInt() else 0xFFFFFFFF.toInt()

    // contact de chaque widget 1×1
    fun contact(c: Context, id: Int) = Pick.decode(p(c).getString("cw_$id", null))
    fun setContact(c: Context, id: Int, pick: Pick) { p(c).edit().putString("cw_$id", pick.encode()).apply() }
    fun pending(c: Context) = Pick.decode(p(c).getString("cw_pending", null))
    fun setPending(c: Context, pick: Pick?) { p(c).edit().apply { if (pick == null) remove("cw_pending") else putString("cw_pending", pick.encode()) }.apply() }
    fun remove(c: Context, id: Int) { p(c).edit().remove("cw_$id").apply() }

    /** Demande au lanceur de poser le widget Favoris. */
    fun pinFavorites(c: Context): Boolean = pin(c, FavoritesWidget::class.java)

    /** Pose un widget 1×1 « appeler [pick] » sur l'écran d'accueil. */
    fun pinContact(c: Context, pick: Pick): Boolean { setPending(c, pick); return pin(c, ContactWidget::class.java) }

    private fun pin(c: Context, cls: Class<*>): Boolean {
        val mgr = AppWidgetManager.getInstance(c) ?: return false
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return try { mgr.requestPinAppWidget(ComponentName(c, cls), null, null) } catch (_: Throwable) { false }
    }
}

/* ======================= Widget 1×1 : un contact, un appui = un appel ======================= */

class ContactWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                // widget posé depuis l'appli : on lui donne le contact choisi
                ids.forEach { id -> if (WidgetPrefs.contact(ctx, id) == null) WidgetPrefs.pending(ctx)?.let { WidgetPrefs.setContact(ctx, id, it); WidgetPrefs.setPending(ctx, null) } }
                ids.forEach { render(ctx, mgr, it) }
            } finally { pending.finish() }
        }.start()
    }
    override fun onDeleted(ctx: Context, ids: IntArray) { ids.forEach { WidgetPrefs.remove(ctx, it) } }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ContactWidget::class.java))
            if (ids.isEmpty()) return
            val app = ctx.applicationContext
            Thread { ids.forEach { try { render(app, mgr, it) } catch (_: Throwable) { } } }.start()
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_contact)
            val style = WidgetPrefs.style(ctx)
            v.setInt(R.id.root, "setBackgroundResource", WidgetPrefs.bg(style))
            v.setTextColor(R.id.name, WidgetPrefs.fg(style))
            val p = WidgetPrefs.contact(ctx, id)
            if (p == null) {
                v.setImageViewResource(R.id.img, R.drawable.logo_hand)
                v.setTextViewText(R.id.name, tr("Choisir"))
                val cfg = Intent(ctx, ContactWidgetConfig::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 5000 + id, cfg, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                v.setImageViewBitmap(R.id.img, FavoritesWidget.avatar(ctx, p.name, p.photo, 160))
                v.setTextViewText(R.id.name, p.name.substringBefore(' ').take(12))
                val call = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", p.number, null))
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 6000 + id, call, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            mgr.updateAppWidget(id, v)
        }
    }
}

/** Choix du contact quand on pose le widget 1×1 depuis la liste des widgets (ou en le touchant s'il est vide). */
class ContactWidgetConfig : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            HyperTheme(loadTheme(this)) {
                ContactPickerDialog(tr("Contact du widget"), onDismiss = { finish() }) { p ->
                    WidgetPrefs.setContact(this, id, p)
                    ContactWidget.render(this, AppWidgetManager.getInstance(this), id)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

/* ======================= Numérotation rapide : appui long sur 2…9 ======================= */

object SpeedDial {
    private fun p(c: Context) = c.getSharedPreferences("speeddial", Context.MODE_PRIVATE)
    val version = mutableIntStateOf(0)
    fun get(c: Context, digit: String): Pick? = Pick.decode(p(c).getString(digit, null))
    fun set(c: Context, digit: String, pick: Pick?) {
        p(c).edit().apply { if (pick == null) remove(digit) else putString(digit, pick.encode()) }.apply()
        version.intValue++
    }
    val DIGITS = listOf("2", "3", "4", "5", "6", "7", "8", "9")
}

/** Réglages › Numérotation rapide. */
@Composable
internal fun SpeedDialSettings() {
    val ctx = LocalContext.current
    val ver by SpeedDial.version
    var editing by remember { mutableStateOf<String?>(null) }
    Text(tr("Appui long sur une touche du clavier (2 à 9) pour appeler directement le contact choisi. 1 = messagerie."), fontSize = 12.5.sp, color = muted(), modifier = Modifier.padding(bottom = 10.dp))
    SpeedDial.DIGITS.forEach { d ->
        val p = remember(ver, d) { SpeedDial.get(ctx, d) }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable { editing = d }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(d, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            if (p == null) Text(tr("Non attribuée · touche pour choisir"), color = muted(), modifier = Modifier.weight(1f))
            else {
                Avatar(p.name, p.photo, 34.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(displayNumber(p.number), fontSize = 12.sp, color = muted())
                }
                IconButton(onClick = { SpeedDial.set(ctx, d, null) }) { Icon(Icons.Filled.Close, tr("Retirer")) }
            }
        }
    }
    editing?.let { d -> ContactPickerDialog(tr("Touche {0}", d), onDismiss = { editing = null }) { p -> SpeedDial.set(ctx, d, p); editing = null } }
}

/** Réglages › Widgets. */
@Composable
internal fun WidgetSettings() {
    val ctx = LocalContext.current
    var source by remember { mutableIntStateOf(WidgetPrefs.source(ctx)) }
    var count by remember { mutableIntStateOf(WidgetPrefs.count(ctx)) }
    var names by remember { mutableStateOf(WidgetPrefs.names(ctx)) }
    var style by remember { mutableIntStateOf(WidgetPrefs.style(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    SectionCard(tr("⭐ Widget Favoris")) {
        Text(tr("Contacts affichés"), fontSize = 13.sp, color = muted(), modifier = Modifier.padding(bottom = 6.dp))
        Segmented(listOf(tr("Favoris + fréquents"), tr("Favoris"), tr("Fréquents")), source, { source = it; WidgetPrefs.set(ctx, "fav_source", it) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(tr("Nombre de contacts"), fontSize = 13.sp, color = muted(), modifier = Modifier.padding(bottom = 6.dp))
        Segmented(listOf("4", "8"), if (count == 4) 0 else 1, { count = if (it == 0) 4 else 8; WidgetPrefs.set(ctx, "fav_count", count) }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Afficher les prénoms"), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(names, { names = it; WidgetPrefs.set(ctx, "fav_names", it) })
        }
        TextButton(onClick = { msg = if (WidgetPrefs.pinFavorites(ctx)) null else tr("Ton écran d'accueil ne permet pas d'ajouter le widget depuis l'appli : appui long sur l'écran d'accueil › Widgets › Di-Allô.") }) {
            Text("➕ " + tr("Ajouter le widget à l'écran d'accueil"))
        }
    }
    SectionCard(tr("👤 Widget contact (1×1)")) {
        Text(tr("Une seule photo sur l'écran d'accueil : un appui appelle ce contact. Aussi depuis un numéro : appui long › Sur l'écran d'accueil."), fontSize = 12.5.sp, color = muted())
        TextButton(onClick = { picking = true }) { Text("➕ " + tr("Choisir un contact et l'ajouter")) }
    }
    SectionCard(tr("🎨 Apparence des widgets")) {
        Segmented(listOf(tr("Vert"), tr("Sombre"), tr("Clair"), tr("Transparent")), style, { style = it; WidgetPrefs.set(ctx, "style", it) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        val favs by produceState(emptyList<Pick>(), source, count) {
            value = withContext(Dispatchers.IO) { try { FavoritesWidget.favorites(ctx).map { Pick(it.name, it.number, it.photo) } } catch (_: Throwable) { emptyList() } }
        }
        WidgetPreview(style, names, count, favs)
    }
    msg?.let { Text(it, fontSize = 12.5.sp, color = Amber, modifier = Modifier.padding(4.dp)) }
    if (picking) ContactPickerDialog(tr("Contact du widget"), onDismiss = { picking = false }) { p ->
        picking = false
        msg = if (WidgetPrefs.pinContact(ctx, p)) null else tr("Ton écran d'accueil ne permet pas d'ajouter le widget depuis l'appli : appui long sur l'écran d'accueil › Widgets › Di-Allô.")
    }
}


/** Aperçu des widgets tels qu'ils apparaîtront, posés sur un faux fond d'écran. */
@Composable
private fun WidgetPreview(style: Int, names: Boolean, count: Int, favs: List<Pick>) {
    val fg = Color(WidgetPrefs.fg(style))
    val bg: androidx.compose.ui.graphics.Brush = when (style) {
        1 -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xEE1A1C22), Color(0xEE0E0F13)))
        2 -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xF2FFFFFF), Color(0xF2EEF0F4)))
        3 -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        else -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xE6116B57), Color(0xE60E1A24)))
    }
    val wallpaper = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF5B7FD6), Color(0xFFB06AB3), Color(0xFFF2A65A)))
    val shown = favs.take(count)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(wallpaper).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(tr("Aperçu"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .85f))
        // widget Favoris
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).padding(12.dp)) {
            Text("⭐ " + tr("Favoris"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            if (shown.isEmpty()) Text(tr("Ajoute des favoris ⭐ dans tes contacts"), fontSize = 12.sp, color = fg.copy(alpha = .8f), modifier = Modifier.padding(4.dp))
            shown.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { f ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(f.name, f.photo, 46.dp)
                            if (names) Text(f.name.substringBefore(' ').take(12), fontSize = 11.sp, color = fg, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // widget contact 1×1
        shown.firstOrNull()?.let { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Avatar(f.name, f.photo, 44.dp)
                    Text(f.name.substringBefore(' ').take(10), fontSize = 10.5.sp, color = fg, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(tr("Widget contact 1×1"), fontSize = 12.sp, color = Color.White.copy(alpha = .9f))
            }
        }
    }
}
