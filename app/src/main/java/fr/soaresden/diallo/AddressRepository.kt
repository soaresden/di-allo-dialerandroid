package fr.soaresden.diallo

import android.content.Context
import android.location.Geocoder
import android.provider.ContactsContract
import java.util.Locale

/**
 * Adresses postales des contacts + géocodage (adresse -> coordonnées) via le Geocoder d'Android.
 * Les résultats sont mis en cache : seul le premier passage interroge le réseau.
 */
object AddressRepository {

    data class Raw(val contactId: Long, val name: String, val photo: String?, val address: String, val type: String?)
    data class Place(val raw: Raw, val lat: Double, val lon: Double)

    private fun cache(ctx: Context) = ctx.getSharedPreferences("geocache2", Context.MODE_PRIVATE)
    private fun norm(a: String) = a.replace(Regex("\\s+"), " ").trim().lowercase()

    fun rawAddresses(ctx: Context): List<Raw> {
        val out = ArrayList<Raw>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID,
                    ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredPostal.PHOTO_URI,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.LABEL
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(3)?.replace('\n', ',')?.replace(Regex(",\\s*,"), ",")?.trim(' ', ',') ?: continue
                    if (addr.length < 4) continue
                    val type = try { ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(ctx.resources, c.getInt(4), c.getString(5)).toString() } catch (_: Throwable) { null }
                    out.add(Raw(c.getLong(0), c.getString(1) ?: "?", c.getString(2), addr, type))
                }
            }
        } catch (_: Throwable) { }
        return out.distinctBy { it.contactId.toString() + "|" + norm(it.address) }
    }

    /** Ce qui est déjà en cache (instantané). */
    fun cached(ctx: Context, raws: List<Raw>): Pair<List<Place>, List<Raw>> {
        val pref = cache(ctx); val places = ArrayList<Place>(); val todo = ArrayList<Raw>()
        for (r in raws) {
            when (val v = pref.getString(norm(r.address), null)) {
                null -> todo.add(r)
                "x" -> {}                                   // déjà tenté, introuvable
                else -> v.split(',').let { p -> places.add(Place(r, p[0].toDouble(), p[1].toDouble())) }
            }
        }
        return places to todo
    }

    private val CP = Regex("""(?<!\d)(\d{5})(?!\d)""")

    /**
     * Le résultat est-il assez précis et cohérent ? Rejette les réponses « France » / région seule
     * (point au milieu du pays) et celles dont le code postal n'est pas dans le même département.
     */
    private fun plausible(a: android.location.Address, cp: String?): Boolean {
        if (a.locality == null && a.postalCode == null && a.thoroughfare == null && a.subLocality == null) return false
        if (cp != null) {
            val got = a.postalCode
            if (got != null) return got.take(2) == cp.take(2)
            // pas de code postal dans la réponse : au moins une ville
            return a.locality != null
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun ask(g: Geocoder, q: String, cp: String?): android.location.Address? =
        g.getFromLocationName(q, 5)?.firstOrNull { plausible(it, cp) }

    /** Géocode une adresse (appel réseau, à faire hors thread UI). Sans résultat fiable : la ville, puis le code postal. */
    fun geocode(ctx: Context, r: Raw): Place? {
        val pref = cache(ctx)
        val cp = CP.findAll(r.address).lastOrNull()?.value
        val res = try {
            if (!Geocoder.isPresent()) null else {
                val g = Geocoder(ctx, Locale.FRANCE)
                ask(g, r.address, cp) ?: cp?.let { code ->
                    // « 60800 Crépy-en-Valois » : ce qui suit le code postal est la ville
                    val town = r.address.substringAfter(code).trim(' ', ',').substringBefore(',').trim()
                    (if (town.length >= 2) ask(g, "$code $town, France", code) else null) ?: ask(g, "$code, France", code)
                }
            }
        } catch (_: Throwable) { return null }               // erreur réseau : on réessaiera plus tard
        return if (res != null) {
            pref.edit().putString(norm(r.address), "${res.latitude},${res.longitude}").apply()
            Place(r, res.latitude, res.longitude)
        } else { pref.edit().putString(norm(r.address), "x").apply(); null }
    }

    fun clearCache(ctx: Context) { cache(ctx).edit().clear().apply() }
}
