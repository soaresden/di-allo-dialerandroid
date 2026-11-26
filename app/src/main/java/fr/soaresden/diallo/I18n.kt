package fr.soaresden.diallo

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.Locale

/**
 * Traductions de l'appli. Le texte français du code sert de clé :
 *   tr("Appeler")                      → "Call" en anglais
 *   tr("Appeler {0}", nom)               → les {0}, {1}… sont remplacés par les arguments
 *   trn(n, "{0} appel", "{0} appels")  → singulier / pluriel selon la langue
 * Les traductions sont dans assets/i18n/<Fichier>.json : { "texte français": { "en": "...", "es": "...", ... } }.
 * Une clé absente reste en français.
 */
object I18n {
    /** Langues proposées (code → nom dans sa langue). */
    val LANGS = listOf("fr" to "Français", "en" to "English", "es" to "Español", "de" to "Deutsch", "it" to "Italiano", "pt" to "Português (Brasil)")

    /** Langue affichée (état Compose : changer la langue redessine l'appli). */
    val lang = mutableStateOf("fr")
    private var table: Map<String, String> = emptyMap()
    private var all: Map<String, JSONObject>? = null
    private var rev: Map<String, String> = emptyMap()

    /** Choix enregistré : "auto" (langue du téléphone) ou un code de LANGS. */
    fun choice(ctx: Context): String = prefs(ctx).getString("lang", "auto") ?: "auto"

    private var ready = false
    fun init(ctx: Context) { Home.detect(ctx.applicationContext); apply(ctx.applicationContext, choice(ctx)); ready = true }
    /** À appeler partout où l'appli peut démarrer (écran d'appel, notification…). */
    fun ensure(ctx: Context) { if (!ready) init(ctx) }

    fun set(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).apply()
        apply(ctx.applicationContext, code)
    }

    private fun apply(ctx: Context, code: String) {
        val sys = Locale.getDefault().language
        val real = if (code == "auto") (LANGS.firstOrNull { it.first == sys }?.first ?: "en") else code
        if (all == null) all = load(ctx)
        table = if (real == "fr") emptyMap() else HashMap<String, String>().also { m ->
            all!!.forEach { (k, o) -> o.optString(real).takeIf { it.isNotEmpty() }?.let { m[k] = it } }
        }
        rev = HashMap<String, String>().also { r -> table.forEach { (k, v) -> r.putIfAbsent(v, k) } }
        lang.value = real
    }

    private fun load(ctx: Context): Map<String, JSONObject> {
        val m = HashMap<String, JSONObject>()
        try {
            ctx.assets.list("i18n")?.filter { it.endsWith(".json") }?.forEach { f ->
                try {
                    val o = JSONObject(ctx.assets.open("i18n/$f").use { it.readBytes().toString(Charsets.UTF_8) })
                    o.keys().forEach { k -> o.optJSONObject(k)?.let { m[k] = it } }
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        return m
    }

    /** Locale pour les dates / nombres. */
    val locale: Locale get() = when (lang.value) { "fr" -> Locale.FRANCE; else -> Locale(lang.value) }

    /** Retrouve la clé française d'un texte traduit (identifiants stables, ex. état replié des cartes). */
    fun key(shown: String): String = rev[shown] ?: shown

    fun t(fr: String): String { val l = lang.value; return if (l == "fr") fr else table[fr] ?: fr }
}

/** Texte traduit ; {0}, {1}… remplacés par [args]. */
fun tr(fr: String, vararg args: Any?): String {
    var s = I18n.t(fr)
    args.forEachIndexed { i, v -> s = s.replace("{$i}", v.toString()) }
    return s
}

/** Singulier / pluriel ({0} = n). En français 0 et 1 sont au singulier, ailleurs seul 1 l'est. */
fun trn(n: Number, one: String, other: String, vararg args: Any?): String {
    val v = n.toLong()
    val single = if (I18n.lang.value == "fr") kotlin.math.abs(v) <= 1 else kotlin.math.abs(v) == 1L
    return tr(if (single) one else other, n, *args)
}
