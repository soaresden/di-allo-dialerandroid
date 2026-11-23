package fr.soaresden.diallo.vvm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import fr.soaresden.diallo.MainActivity
import fr.soaresden.diallo.tr
import fr.soaresden.diallo.trn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Messagerie visuelle Free : identifiants, messages en local, synchronisation IMAP par la 4G. */
internal object Vvm {
    /** Un message vocal. [file] : nom du fichier audio dans le dossier de la messagerie. */
    /** [trashed] : date de mise à la corbeille (0 = dans la liste). La corbeille ne touche pas au serveur de Free. */
    data class Msg(val uid: Long, val from: String, val date: Long, val seconds: Int, val seen: Boolean, val file: String?, val trashed: Long = 0)
    private class Account(val host: String, val port: Int, val user: String, val pass: String, val tui: String?)

    /** Adresse du serveur IMAP de Free (vvm.proxad.net), si le DNS ne répond pas. */
    private const val FREE_IMAP_IP = "212.27.40.201"

    val messages = mutableStateOf<List<Msg>>(emptyList())
    /** Étape en cours (« Connexion à Free… ») ; null quand rien ne tourne. */
    val progress = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)
    val ready = mutableStateOf(false)          // identifiants reçus de Free
    val waitingStatus = mutableStateOf(false)  // SMS STATUS envoyé, réponse attendue
    val lastSync = mutableStateOf(0L)
    /** Vrai quand l'écran Messagerie est affiché (pas de notification dans ce cas). */
    @Volatile var screenOpen = false

    private val lock = ReentrantLock()
    private var loaded = false
    private const val JOB_ID = 51499
    private const val CHANNEL = "vvm"

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "vvm").apply { mkdirs() }   // hors sauvegarde Google
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vvm_state", Context.MODE_PRIVATE)
    fun audioFile(ctx: Context, m: Msg): File? = m.file?.let { File(dir(ctx), it) }?.takeIf { it.isFile && it.length() > 0 }
    /** Nom lisible pour un message exporté : aaaa-mm-jj-hhmmss-NOM.ext (comme les enregistrements d'appels). */
    private fun exportName(m: Msg, who: String, f: File): String {
        val safe = who.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Inconnu" }
        return SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(java.util.Date(m.date)) + "-" + safe + "." + f.extension.ifEmpty { "amr" }
    }
    private fun mimeOf(f: File) = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "audio/*"

    /** Copie le message dans le dossier de travail, sous-dossier Messagerie. Retourne le nom du fichier, ou null (pas de dossier choisi). */
    fun saveToFolder(ctx: Context, m: Msg, who: String): String? {
        val f = audioFile(ctx, m) ?: return null
        val dir = fr.soaresden.diallo.DataFolder.subDir(ctx, "Messagerie") ?: return null
        val name = exportName(m, who, f)
        return try {
            val doc = dir.findFile(name) ?: dir.createFile(mimeOf(f), name) ?: return null
            ctx.contentResolver.openOutputStream(doc.uri, "wt")?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return null
            doc.name ?: name
        } catch (_: Throwable) { null }
    }

    /** Partage le message (WhatsApp, mail, Drive…) sous un nom lisible. */
    fun share(ctx: Context, m: Msg, who: String): Boolean {
        val f = audioFile(ctx, m) ?: return false
        return try {
            val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, exportName(m, who, f))
            f.copyTo(out, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
            val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, tr("Partager le message vocal")))
            true
        } catch (_: Throwable) { false }
    }

    val tuiNumber: String get() = account?.tui?.takeIf { it.any(Char::isDigit) } ?: "666"
    @Volatile private var account: Account? = null

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        account = readAccount(ctx)
        ready.value = account != null
        messages.value = readIndex(ctx)
        lastSync.value = prefs(ctx).getLong("last_sync", 0L)
        // avant la corbeille, « Supprimer » effaçait chez Free : les suppressions pas encore envoyées
        // sont annulées, le message revient à la prochaine synchro s'il est encore chez Free
        if (!prefs(ctx).getBoolean("trash_v1", false)) prefs(ctx).edit().putStringSet("del", emptySet()).putBoolean("trash_v1", true).apply()
    }

    /* ---------- identifiants (fichier privé, hors sauvegarde) ---------- */
    private fun readAccount(ctx: Context): Account? = try {
        val o = JSONObject(File(dir(ctx), "account.json").readText())
        Account(o.getString("host"), o.optInt("port", 993), o.getString("user"), o.getString("pass"), o.optString("tui").ifBlank { null })
    } catch (_: Throwable) { null }

    private fun writeAccount(ctx: Context, a: Account?) {
        val f = File(dir(ctx), "account.json")
        if (a == null) { f.delete(); account = null; ready.value = false; return }
        f.writeText(JSONObject().put("host", a.host).put("port", a.port).put("user", a.user).put("pass", a.pass).put("tui", a.tui ?: "").toString())
        account = a; ready.value = true
    }

    /* ---------- index des messages ---------- */
    private fun readIndex(ctx: Context): List<Msg> = try {
        val a = JSONArray(File(dir(ctx), "index.json").readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Msg(o.getLong("uid"), o.optString("from"), o.optLong("date"), o.optInt("sec"), o.optBoolean("seen"), o.optString("file").ifBlank { null }, o.optLong("trashed"))
        } }.sortedByDescending { it.date }
    } catch (_: Throwable) { emptyList() }

    private fun writeIndex(ctx: Context, list: List<Msg>) {
        val a = JSONArray()
        list.forEach { m -> a.put(JSONObject().put("uid", m.uid).put("from", m.from).put("date", m.date).put("sec", m.seconds).put("seen", m.seen).put("file", m.file ?: "").put("trashed", m.trashed)) }
        File(dir(ctx), "index.json").writeText(a.toString())
        messages.value = list.sortedByDescending { it.date }
    }

    private fun pending(ctx: Context, key: String): MutableSet<String> = prefs(ctx).getStringSet(key, emptySet())!!.toMutableSet()
    private fun setPending(ctx: Context, key: String, s: Set<String>) = prefs(ctx).edit().putStringSet(key, s).apply()

    /* ---------- SMS de Free ---------- */
    fun onStatus(ctx: Context, f: Map<String, String>) {
        load(ctx)
        waitingStatus.value = false
        val st = f["st"].orEmpty(); val rc = f["rc"].orEmpty()
        val host = f["srv"].orEmpty().let { s -> if (Regex("^\\d+:.+").matches(s)) s.substringAfter(':') else s }
        val user = f["u"].orEmpty(); val pass = f["pw"].orEmpty()
        if (rc.isNotEmpty() && rc != "0") { error.value = tr("Free refuse la messagerie visuelle (code {0}).", rc); return }
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            // ligne jamais inscrite (pas d'appli Free avant) : demande d'activation standard, une fois par heure au plus
            val lastAct = prefs(ctx).getLong("last_activate", 0L)
            if (st != "B" && System.currentTimeMillis() - lastAct > 3_600_000L && Omtp.sendActivate(ctx)) {
                prefs(ctx).edit().putLong("last_activate", System.currentTimeMillis()).apply()
                waitingStatus.value = true
                error.value = tr("Messagerie visuelle pas encore active chez Free : demande d'activation envoyée, réponse attendue par SMS.")
                return
            }
            error.value = if (st == "B") tr("Messagerie visuelle bloquée chez Free.") else tr("Messagerie visuelle pas encore active chez Free (état « {0} »).", st.ifBlank { "?" })
            return
        }
        VvmLog.add(ctx, "Identifiants reçus (serveur $host, port ${f["ipt"] ?: "993"})")
        writeAccount(ctx, Account(host, f["ipt"]?.toIntOrNull() ?: 993, user, pass, f["tui"]))
        error.value = null
        scheduleSync(ctx)
    }

    fun onSync(ctx: Context, f: Map<String, String>) {
        load(ctx)
        if (account == null) { Omtp.sendStatus(ctx); return }
        // quel que soit le type annoncé par Free : la notification ne part que s'il y a vraiment un message nouveau
        scheduleSync(ctx)
    }

    /** Bouton « Activer » : demande les identifiants à Free. */
    fun requestStatus(ctx: Context): Boolean {
        error.value = null
        val ok = Omtp.sendStatus(ctx)
        waitingStatus.value = ok
        if (!ok) error.value = tr("Impossible d'envoyer le SMS à Free (SIM Free et autorisation SMS nécessaires).")
        return ok
    }

    /* ---------- actions sur un message ---------- */
    /** Met le message à la corbeille : il reste chez Free et sur le téléphone, on peut le restaurer. */
    fun trash(ctx: Context, m: Msg) {
        writeIndex(ctx, messages.value.map { if (it.uid == m.uid) it.copy(trashed = System.currentTimeMillis()) else it })
    }

    fun restore(ctx: Context, m: Msg) {
        writeIndex(ctx, messages.value.map { if (it.uid == m.uid) it.copy(trashed = 0) else it })
    }

    /** Suppression définitive : sur le téléphone et chez Free (à la prochaine synchro). */
    fun deleteForever(ctx: Context, list: List<Msg>) {
        if (list.isEmpty()) return
        val ids = list.map { it.uid }.toSet()
        list.forEach { audioFile(ctx, it)?.delete() }
        writeIndex(ctx, messages.value.filter { it.uid !in ids })
        setPending(ctx, "del", pending(ctx, "del") + ids.map { it.toString() })
        scheduleSync(ctx)
    }

    fun markSeen(ctx: Context, m: Msg) {
        if (m.seen) return
        writeIndex(ctx, messages.value.map { if (it.uid == m.uid) it.copy(seen = true) else it })
        setPending(ctx, "seen", pending(ctx, "seen") + m.uid.toString())
        cancelNotification(ctx)
    }

    /* ---------- synchronisation ---------- */
    /**
     * Après un appel manqué ou refusé : Free envoie d'habitude un SMS dès qu'un message est laissé, mais s'il se
     * perd, deux vérifications de secours (1 min 30 puis 3 min). Pas toutes les 2 s : chaque connexion au serveur
     * de Free met ~30 s à s'ouvrir.
     */
    fun afterMissedCall(ctx: Context) {
        val app = ctx.applicationContext
        if (!fr.soaresden.diallo.BuildConfig.FULL) return
        load(app)
        if (account == null) return
        VvmLog.add(app, "Appel manqué : vérifications prévues dans 1 min 30 et 3 min")
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        val comp = ComponentName(app, VvmSyncJob::class.java)
        listOf(JOB_ID + 1 to 90_000L, JOB_ID + 2 to 180_000L).forEach { (id, wait) ->
            try { js.schedule(JobInfo.Builder(id, comp).setMinimumLatency(wait).setOverrideDeadline(wait + 30_000).build()) } catch (_: Throwable) { }
        }
    }

    fun scheduleSync(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val comp = ComponentName(ctx, VvmSyncJob::class.java)
        // tâche « urgente » si le quota le permet, sinon tâche normale lancée dans la seconde
        val ok = try { js.schedule(JobInfo.Builder(JOB_ID, comp).setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS } catch (_: Throwable) { false }
        if (!ok) try { js.schedule(JobInfo.Builder(JOB_ID, comp).setOverrideDeadline(1_000).build()) } catch (_: Throwable) { Thread { sync(ctx) }.start() }
    }

    /** Synchro complète (bloquante, ~40 s à cause du serveur de Free). Retourne false si déjà en cours ou en échec. */
    fun sync(ctx: Context): Boolean {
        load(ctx)
        if (!lock.tryLock()) return false
        try {
            val acc = account ?: run { error.value = tr("Messagerie visuelle pas encore activée."); return false }
            error.value = null
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            var last: Throwable? = null
            // 1er essai par la connexion en cours (Wi-Fi, VoWiFi…) : le serveur de Free répond aussi par Internet.
            // Ensuite, par le réseau mobile (si le Wi-Fi bloque le port 993, ou si la connexion lâche en route,
            // « Software caused connection abort »). 3 essais en tout, chacun sur une nouvelle connexion.
            VvmLog.add(ctx, "Synchro : début (réseau ${VvmLog.network(ctx)})")
            for (attempt in 1..3) {
                val active = if (attempt == 1) cm.activeNetwork else null
                var cb: ConnectivityManager.NetworkCallback? = null
                val net = active ?: run {
                    progress.value = tr("Connexion au réseau mobile…")
                    val (n, c) = cellular(cm) ?: run {
                        VvmLog.add(ctx, "Essai $attempt : réseau mobile indisponible")
                        error.value = if (last != null) tr("Échec de la synchro : {0}", last?.message ?: last?.javaClass?.simpleName ?: "?")
                            else tr("Pas de connexion : la messagerie de Free a besoin d'Internet (Wi-Fi ou données mobiles).")
                        return false
                    }
                    cb = c; n
                }
                val t0 = System.currentTimeMillis()
                try {
                    return syncOnce(ctx, acc, net)
                } catch (e: java.io.IOException) {
                    last = e
                    VvmLog.add(ctx, "Essai $attempt (${if (active != null) "connexion en cours" else "réseau mobile"}) : ${e.javaClass.simpleName} ${e.message} après ${(System.currentTimeMillis() - t0) / 1000} s")
                } catch (e: Throwable) {
                    last = e
                    VvmLog.add(ctx, "Essai $attempt : erreur ${e.javaClass.simpleName} ${e.message}")
                    break
                } finally {
                    cb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Throwable) { } }
                }
                if (attempt < 3) { progress.value = tr("Connexion perdue, nouvel essai ({0}/3)…", attempt + 1); if (active == null) Thread.sleep(3000) }
            }
            error.value = tr("Échec de la synchro : {0}", last?.message ?: last?.javaClass?.simpleName ?: "?")
            return false
        } finally {
            progress.value = null
            lock.unlock()
        }
    }

    /** Une synchro complète sur [net]. Lance une IOException si la connexion tombe (on réessaie alors). */
    private fun syncOnce(ctx: Context, acc: Account, net: Network): Boolean {
        progress.value = tr("Connexion à Free… (le serveur met ~30 s à répondre)")
        // le DNS du téléphone ne trouve pas toujours vvm.proxad.net (« No address associated with hostname ») :
        // on retombe sur la dernière adresse qui a marché, sinon sur celle connue du serveur de Free
        val addr = try {
            net.getAllByName(acc.host).first().also { a -> a.hostAddress?.let { prefs(ctx).edit().putString("last_ip", it).apply() } }
        } catch (e: java.net.UnknownHostException) {
            VvmLog.add(ctx, "DNS : ${acc.host} introuvable, adresse de secours")
            android.net.InetAddresses.parseNumericAddress(prefs(ctx).getString("last_ip", null) ?: FREE_IMAP_IP)
        }
        val t0 = System.currentTimeMillis()
        val plain = Imap.connectPlain(net.socketFactory, InetSocketAddress(addr, acc.port))
        VvmLog.add(ctx, "Connecté à ${addr.hostAddress}:${acc.port} en ${System.currentTimeMillis() - t0} ms, ouverture TLS…")
        Imap.open(plain, acc.host, acc.port).use { imap ->
            VvmLog.add(ctx, "TLS ouvert après ${(System.currentTimeMillis() - t0) / 1000} s")
            val login = imap.cmd("LOGIN " + Imap.quote(acc.user) + " " + Imap.quote(acc.pass))
            VvmLog.add(ctx, "Connexion au compte : ${if (login.ok) "OK" else "refusée"}")
            if (!login.ok) {
                // mot de passe changé chez Free : on redemande les identifiants
                writeAccount(ctx, null); Omtp.sendStatus(ctx); waitingStatus.value = true
                error.value = tr("Identifiants refusés : nouvelle demande envoyée à Free.")
                return false
            }
            progress.value = tr("Lecture des messages…")
            imap.ok("SELECT INBOX", "SELECT")
            applyPending(ctx, imap)
            val search = imap.ok("UID SEARCH ALL", "SEARCH")
            val uids = search.untagged.firstOrNull { it.text.startsWith("* SEARCH") }?.text?.removePrefix("* SEARCH")
                ?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull() }.orEmpty().filter { it.toString() !in pending(ctx, "del") }
            val flags = HashMap<Long, Boolean>()
            if (uids.isNotEmpty()) imap.ok("UID FETCH ${uids.joinToString(",")} (UID FLAGS)", "FETCH").untagged.forEach { u ->
                val uid = Regex("UID (\\d+)").find(u.text)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                flags[uid] = Regex("FLAGS \\(([^)]*)\\)").find(u.text)?.groupValues?.get(1)?.contains("\\Seen", true) == true
            }
            val local = messages.value.associateBy { it.uid }
            val result = ArrayList<Msg>()
            var fresh = 0
            uids.forEachIndexed { i, uid ->
                val known = local[uid]
                if (known != null && audioFile(ctx, known) != null) { result += known.copy(seen = known.seen || flags[uid] == true); return@forEachIndexed }
                progress.value = tr("Téléchargement du message {0}/{1}…", i + 1, uids.size)
                val r = imap.ok("UID FETCH $uid (UID INTERNALDATE BODY.PEEK[])", "FETCH $uid")
                val u = r.untagged.firstOrNull { it.literals.isNotEmpty() } ?: return@forEachIndexed
                val m = saveMessage(ctx, uid, u, flags[uid] == true)
                result += if (known != null) m.copy(seen = known.seen || m.seen) else m
                if (known == null && !m.seen) fresh++
            }
            // messages effacés ailleurs (web, appli Free) : on retire le fichier local, sauf ceux de la corbeille
            // (gardés sur le téléphone jusqu'à ce qu'on vide la corbeille)
            messages.value.filter { it.uid !in uids }.forEach { if (it.trashed > 0 && audioFile(ctx, it) != null) result += it else audioFile(ctx, it)?.delete() }
            VvmLog.add(ctx, "Synchro OK : ${uids.size} message(s) chez Free, $fresh nouveau(x), ${result.count { it.trashed > 0 }} à la corbeille")
            writeIndex(ctx, result)
            lastSync.value = System.currentTimeMillis()
            prefs(ctx).edit().putLong("last_sync", lastSync.value).apply()
            // nouveau message et écran Messagerie pas devant les yeux : notification
            if (fresh > 0 && !screenOpen) notifyNew(ctx, result.filter { !it.seen && it.trashed == 0L })
        }
        return true
    }

    /** Suppressions et lectures faites dans Di-Allô, répercutées chez Free. */
    private fun applyPending(ctx: Context, imap: Imap) {
        val del = pending(ctx, "del"); val seen = pending(ctx, "seen") - del
        if (seen.isNotEmpty() && imap.cmd("UID STORE ${seen.joinToString(",")} +FLAGS.SILENT (\\Seen)").ok) setPending(ctx, "seen", emptySet())
        if (del.isNotEmpty()) {
            if (imap.cmd("UID STORE ${del.joinToString(",")} +FLAGS.SILENT (\\Deleted)").ok && imap.cmd("EXPUNGE").ok) setPending(ctx, "del", emptySet())
        }
    }

    private fun cellular(cm: ConnectivityManager): Pair<Network, ConnectivityManager.NetworkCallback>? {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val latch = CountDownLatch(1)
        var net: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { net = network; latch.countDown() }
            override fun onUnavailable() { latch.countDown() }
        }
        try { cm.requestNetwork(req, cb, 30_000) } catch (_: Throwable) { return null }
        latch.await(35, TimeUnit.SECONDS)
        val n = net ?: run { try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) { }; return null }
        return n to cb
    }

    /* ---------- lecture du message (MIME) ---------- */
    private class Part(val headers: Map<String, String>, val body: ByteArray)

    private fun saveMessage(ctx: Context, uid: Long, u: Imap.Untagged, seenOnServer: Boolean): Msg {
        val raw = u.literals.first()
        val top = parsePart(raw)
        val audio = findAudio(top)
        var file: String? = null
        var seconds = (audio?.headers?.get("content-duration") ?: top.headers["content-duration"] ?: top.headers["x-content-duration"])
            ?.trim()?.toIntOrNull() ?: 0
        if (audio != null) {
            val bytes = decode(audio)
            val name = "msg_$uid." + extOf(audio)
            File(dir(ctx), name).writeBytes(bytes)
            file = name
            if (seconds <= 0) seconds = try {
                MediaMetadataRetriever().run { setDataSource(File(dir(ctx), name).path); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); (d / 1000).toInt() }
            } catch (_: Throwable) { 0 }
        }
        val idate = Regex("INTERNALDATE \"([^\"]+)\"").find(u.text)?.groupValues?.get(1)
        return Msg(uid, numberOf(top.headers["from"].orEmpty()), dateOf(top.headers["date"], idate), seconds, seenOnServer, file)
    }

    private fun parsePart(b: ByteArray): Part {
        var end = indexOf(b, "\r\n\r\n".toByteArray(), 0); var skip = 4
        if (end < 0) { end = indexOf(b, "\n\n".toByteArray(), 0); skip = 2 }
        if (end < 0) return Part(emptyMap(), b)
        val head = String(b, 0, end, Charsets.ISO_8859_1).replace(Regex("\r?\n[ \t]+"), " ")
        val h = LinkedHashMap<String, String>()
        head.split(Regex("\r?\n")).forEach { l -> val i = l.indexOf(':'); if (i > 0) h.putIfAbsent(l.substring(0, i).trim().lowercase(), l.substring(i + 1).trim()) }
        return Part(h, b.copyOfRange(end + skip, b.size))
    }

    private fun findAudio(p: Part): Part? {
        val ct = p.headers["content-type"].orEmpty().lowercase()
        if (ct.startsWith("multipart/")) {
            val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(p.headers["content-type"].orEmpty())?.groupValues?.get(1) ?: return null
            val text = String(p.body, Charsets.ISO_8859_1)
            text.split("--$boundary").drop(1).forEach { chunk ->
                if (chunk.startsWith("--")) return@forEach
                val sub = parsePart(chunk.trimStart('\r', '\n').toByteArray(Charsets.ISO_8859_1))
                findAudio(sub)?.let { return it }
            }
            return null
        }
        val disp = p.headers["content-disposition"].orEmpty().lowercase()
        return if (ct.startsWith("audio/") || Regex("\\.(amr|wav|mp3|ogg|3gp|awb)").containsMatchIn(disp + ct)) p else null
    }

    private fun decode(p: Part): ByteArray = when (p.headers["content-transfer-encoding"]?.trim()?.lowercase()) {
        "base64" -> Base64.decode(String(p.body, Charsets.ISO_8859_1).filter { !it.isWhitespace() && it != '-' }, Base64.DEFAULT)
        else -> p.body.let { b -> var n = b.size; while (n > 0 && (b[n - 1] == '\n'.code.toByte() || b[n - 1] == '\r'.code.toByte())) n--; b.copyOf(n) }
    }

    private fun extOf(p: Part): String {
        val ct = p.headers["content-type"].orEmpty().lowercase()
        Regex("name=\"?[^\";]*\\.([a-z0-9]{2,4})", RegexOption.IGNORE_CASE).find(p.headers["content-disposition"].orEmpty() + ";" + ct)?.let { return it.groupValues[1].lowercase() }
        return when {
            "amr-wb" in ct -> "awb"; "amr" in ct -> "amr"; "wav" in ct -> "wav"; "mpeg" in ct || "mp3" in ct -> "mp3"
            "ogg" in ct -> "ogg"; "3gpp" in ct -> "3gp"; else -> "bin"
        }
    }

    private fun numberOf(from: String): String {
        val d = Regex("\\+?\\d{4,}").find(from)?.value ?: return ""
        return if (!d.startsWith("+") && d.startsWith("33") && d.length == 11) "+$d" else d
    }

    private fun dateOf(header: String?, internal: String?): Long {
        header?.let { h -> try { return ZonedDateTime.parse(h.replace(Regex("\\s*\\(.*\\)$"), "").trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Throwable) { } }
        internal?.let { i -> try { return SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US).parse(i.trim())!!.time } catch (_: Throwable) { } }
        return System.currentTimeMillis()
    }

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int): Int {
        outer@ for (i in from..b.size - pat.size) { for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer; return i }
        return -1
    }

    /* ---------- notification ---------- */
    private fun notifyNew(ctx: Context, unread: List<Msg>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Messagerie vocale"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("tab", 7).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_voicemail)
            .setContentTitle(trn(unread.size, "{0} nouveau message vocal", "{0} nouveaux messages vocaux"))
            .setContentText(unread.firstOrNull()?.from?.ifBlank { null } ?: tr("Numéro masqué"))
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(CHANNEL, 1, n) } catch (_: SecurityException) { }
    }

    fun cancelNotification(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(CHANNEL, 1) }
}

/** Synchro en arrière-plan (après un SMS de Free, une suppression…). */
class VvmSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread { try { Vvm.sync(applicationContext) } catch (_: Throwable) { }; jobFinished(params, false) }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
