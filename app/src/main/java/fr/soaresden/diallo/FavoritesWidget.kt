package fr.soaresden.diallo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.View
import android.widget.RemoteViews
import kotlin.math.abs

/** Widget d'écran d'accueil : tes favoris (⭐ dans les contacts), un appui = un appel. */
class FavoritesWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try { render(ctx, mgr, ids) } finally { pending.finish() }
        }.start()
    }

    companion object {
        private const val SLOTS = 8
        private val IMG = intArrayOf(R.id.img0, R.id.img1, R.id.img2, R.id.img3, R.id.img4, R.id.img5, R.id.img6, R.id.img7)
        private val NAME = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5, R.id.name6, R.id.name7)
        private val SLOT = intArrayOf(R.id.slot0, R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5, R.id.slot6, R.id.slot7)
        private val COLORS = intArrayOf(0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFF14B8A6.toInt(), 0xFFEF4444.toInt(), 0xFF6366F1.toInt())

        /** Met à jour tous les widgets posés (appelé au retour dans l'appli). */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FavoritesWidget::class.java))
            if (ids.isEmpty()) return
            val app = ctx.applicationContext
            Thread { try { render(app, mgr, ids) } catch (_: Throwable) { } }.start()
        }

        private data class Fav(val name: String, val number: String, val photo: String?, val calls: Int)

        private fun favorites(ctx: Context): List<Fav> {
            // nombre d'appels par numéro (les 3000 derniers)
            val counts = HashMap<String, Int>()
            try {
                ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER), null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n++ < 3000) { val k = keyOf(c.getString(0) ?: continue); if (k.isNotEmpty()) counts.merge(k, 1, Int::plus) }
                }
            } catch (_: Throwable) { }
            val starred = ArrayList<Fav>()
            val everyone = HashMap<String, Fav>()
            try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.STARRED),
                    null, null, null
                )?.use { c ->
                    val best = HashMap<Long, Fav>()
                    while (c.moveToNext()) {
                        val num = c.getString(2) ?: continue
                        val f = Fav(c.getString(1) ?: num, num, c.getString(3), counts[keyOf(num)] ?: 0)
                        everyone.putIfAbsent(keyOf(num), f)
                        if (c.getInt(4) == 1) { val id = c.getLong(0); val cur = best[id]; if (cur == null || f.calls > cur.calls) best[id] = f }
                    }
                    starred.addAll(best.values)
                }
            } catch (_: Throwable) { }
            val list = starred.sortedWith(compareByDescending<Fav> { it.calls }.thenBy { it.name.lowercase() })
            if (list.size >= SLOTS) return list.take(SLOTS)
            // complète avec les plus appelés
            val more = counts.entries.sortedByDescending { it.value }.mapNotNull { everyone[it.key] }.filter { m -> list.none { keyOf(it.number) == keyOf(m.number) } }
            return (list + more).take(SLOTS)
        }

        private fun avatar(ctx: Context, name: String, photo: String?, px: Int = 144): Bitmap {
            val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val src = photo?.let { u -> try { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null } }
            if (src != null) {
                val s = minOf(src.width, src.height)
                val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
                p.shader = BitmapShader(Bitmap.createScaledBitmap(sq, px, px, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                c.drawCircle(px / 2f, px / 2f, px / 2f, p)
            } else {
                p.color = COLORS[abs(name.hashCode()) % COLORS.size]
                c.drawCircle(px / 2f, px / 2f, px / 2f, p)
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = px * 0.44f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
                val ch = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                c.drawText(ch, px / 2f, px / 2f - (t.descent() + t.ascent()) / 2, t)
            }
            return out
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val favs = favorites(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.header, open)
            views.setViewVisibility(R.id.empty, if (favs.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.row0, if (favs.isEmpty()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.row1, if (favs.size > 4) View.VISIBLE else View.GONE)
            for (i in 0 until SLOTS) {
                val f = favs.getOrNull(i)
                if (f == null) { views.setViewVisibility(SLOT[i], View.INVISIBLE); continue }
                views.setViewVisibility(SLOT[i], View.VISIBLE)
                views.setImageViewBitmap(IMG[i], avatar(ctx, f.name, f.photo))
                views.setTextViewText(NAME[i], f.name.substringBefore(' ').take(12))
                val call = PendingIntent.getActivity(ctx, 100 + i, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", f.number, null)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(SLOT[i], call)
            }
            views.setOnClickPendingIntent(R.id.empty, open)
            mgr.updateAppWidget(ids, views)
        }
    }
}
