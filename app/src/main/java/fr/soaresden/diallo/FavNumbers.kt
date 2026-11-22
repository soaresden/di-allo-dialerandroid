package fr.soaresden.diallo

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Favoris de Di-Allô, numéro par numéro.
 * Point de départ : les contacts étoilés du téléphone (jamais modifiés ici).
 * Par-dessus : les numéros retirés (« hidden ») et les numéros ajoutés à la main (« added »).
 * Clé = keyOf(numéro) (9 derniers chiffres), donc 06… et +33 6… sont le même numéro.
 */
object FavNumbers {
    val version = mutableIntStateOf(0)
    private fun p(c: Context) = c.getSharedPreferences("favnums", Context.MODE_PRIVATE)
    fun hidden(c: Context): Set<String> = p(c).getStringSet("hidden", emptySet()) ?: emptySet()
    fun added(c: Context): Set<String> = p(c).getStringSet("added", emptySet()) ?: emptySet()

    /** Instantané pour filtrer une liste sans relire les préférences à chaque ligne. */
    class Snap(private val hidden: Set<String>, private val added: Set<String>) {
        fun isFav(number: String, phoneStarred: Boolean): Boolean {
            val k = keyOf(number)
            return k.isNotEmpty() && (k in added || (phoneStarred && k !in hidden))
        }
    }
    fun snap(c: Context) = Snap(hidden(c), added(c))

    fun set(c: Context, number: String, on: Boolean, phoneStarred: Boolean) {
        val k = keyOf(number); if (k.isEmpty()) return
        val h = hidden(c).toMutableSet(); val a = added(c).toMutableSet()
        if (on) { h.remove(k); if (!phoneStarred) a.add(k) }
        else { a.remove(k); if (phoneStarred) h.add(k) }
        p(c).edit().putStringSet("hidden", h).putStringSet("added", a).apply()
        version.intValue++
        FavoritesWidget.refresh(c)
    }
}
