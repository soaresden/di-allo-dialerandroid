package fr.soaresden.diallo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/*
 * Décors de fond des thèmes, dessinés au Canvas (rien à télécharger).
 * Les décors animés bouclent sur PERIOD ms ; tout le reste est statique.
 */
private const val PERIOD = 24_000
private val MOVING = setOf(Decor.GRID, Decor.STARS, Decor.SCANLINES, Decor.BUBBLES, Decor.WAVES, Decor.EMBERS, Decor.PETALS, Decor.DECO, Decor.PIXELS, Decor.REEF, Decor.VICE)

@Composable
internal fun DecorLayer(t: HTheme, modifier: Modifier, animate: Boolean = true) {
    if (t.decor == Decor.NONE) return
    val phase: State<Float> = if (animate && t.decor in MOVING) {
        rememberInfiniteTransition(label = "decor").animateFloat(0f, 1f, infiniteRepeatable(tween(PERIOD, easing = LinearEasing)), label = "p")
    } else remember { mutableFloatStateOf(.3f) }
    // calque à part : l'animation ne redessine que le décor
    Canvas(modifier.graphicsLayer { alpha = t.decorAlpha }) { drawDecor(t, phase.value) }
}

/** Pseudo-hasard stable (même ciel à chaque image). */
private fun rnd(i: Int, s: Int): Float { val x = sin(i * 12.9898f + s * 78.233f) * 43758.547f; return x - floor(x) }
private fun frac(x: Float) = x - floor(x)
private const val TAU = (2 * PI).toFloat()

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticBezierTo(c.x, c.y, c.x + r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawDecor(t: HTheme, p: Float) {
    val w = size.width; val h = size.height
    val d = density
    when (t.decor) {
        Decor.NONE -> {}
        Decor.REEF -> drawReef(t, p)
        Decor.VICE -> drawVice(t, p)

        // Synthwave : soleil rayé sur l'horizon, grille qui défile vers nous
        Decor.GRID -> {
            val hz = h * .60f
            for (i in 0 until 40) {
                val tw = .3f + .7f * (.5f + .5f * sin(TAU * (p * (2 + i % 4) + rnd(i, 4))))
                drawCircle(Color.White.copy(alpha = .55f * tw), (.6f + rnd(i, 3) * 1.2f) * d, Offset(rnd(i, 1) * w, rnd(i, 2) * hz * .8f))
            }
            val r = w * .30f
            val sc = Offset(w / 2, hz - r * .2f)
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = .35f), Color.Transparent), sc, r * 1.9f), r * 1.9f, sc)
            clipRect(bottom = hz) {
                drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFD86B), Color(0xFFFF8A3D), t.primary), sc.y - r, sc.y + r), r, sc, alpha = .75f)
                for (k in 0 until 6) {
                    val y = sc.y + r * (.05f + k * .13f); val hh = r * .022f * (k + 1)
                    drawRect(t.bg, Offset(sc.x - r, y), Size(2 * r, hh))
                }
            }
            drawRect(Brush.verticalGradient(listOf(t.primary.copy(alpha = .18f), Color.Transparent), hz, h), Offset(0f, hz), Size(w, h - hz))
            val line = t.primary
            for (i in -12..12) drawLine(line.copy(alpha = .35f), Offset(w / 2 + i * w * .045f, hz), Offset(w / 2 + i * w * .42f, h), 1.2f * d)
            val shift = frac(p * 10)
            for (k in 0 until 13) {
                val f = (k + shift) / 13f; val y = hz + (h - hz) * f * f
                drawLine(line.copy(alpha = .12f + .5f * f), Offset(0f, y), Offset(w, y), (1f + f) * d)
            }
            drawLine(t.accent2.copy(alpha = .8f), Offset(0f, hz), Offset(w, hz), 2f * d)
        }

        // Galaxie : nébuleuses, étoiles qui scintillent, étoile filante
        Decor.STARS -> {
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = .28f), Color.Transparent), Offset(w * .82f, h * .16f), w * .75f), w * .75f, Offset(w * .82f, h * .16f))
            drawCircle(Brush.radialGradient(listOf(t.accent2.copy(alpha = .16f), Color.Transparent), Offset(w * .12f, h * .7f), w * .7f), w * .7f, Offset(w * .12f, h * .7f))
            for (i in 0 until 120) {
                val tw = .2f + .8f * (.5f + .5f * sin(TAU * (p * (3 + i % 4) + rnd(i, 4))))
                val c = Offset(rnd(i, 1) * w, rnd(i, 2) * h)
                val s = (.5f + rnd(i, 3) * 1.5f) * d
                if (i % 11 == 0) sparkle(c, s * 3.2f * tw, Color.White.copy(alpha = .8f * tw))
                else drawCircle(Color.White.copy(alpha = .75f * tw), s, c)
            }
            val q = (p - .08f) / .05f
            if (q in 0f..1f) {
                val a = Offset(w * (.95f - .6f * q), h * (.05f + .18f * q))
                drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = .9f * (1 - q)), Color.Transparent), a, Offset(a.x + 70 * d, a.y - 22 * d)), a, Offset(a.x + 70 * d, a.y - 22 * d), 1.6f * d)
            }
        }

        // Cabaret : lambrequin de velours festonné, franges dorées, rideaux, poursuite
        Decor.CURTAIN -> {
            val velvet = t.accent2; val dark = lerp(velvet, Color.Black, .62f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent), Offset(w / 2, h * .42f), w * .75f), w * .75f, Offset(w / 2, h * .42f))
            val dw = w * .035f
            for (side in 0..1) {
                val x0 = if (side == 0) 0f else w - dw
                drawRect(Brush.horizontalGradient(listOf(dark, velvet, dark), x0, x0 + dw), Offset(x0, 0f), Size(dw, h), alpha = .8f)
            }
            val n = 11; val fw = w / n; val vh = h * .085f
            for (i in 0 until n) {
                val x = i * fw
                val g = Brush.horizontalGradient(listOf(dark, velvet, dark), x, x + fw)
                drawRect(g, Offset(x, 0f), Size(fw, vh))
                drawArc(g, 0f, 180f, true, Offset(x, vh - fw / 2), Size(fw, fw))
                // gland doré
                val cx = x + fw / 2; val by = vh + fw / 2
                drawLine(t.primary.copy(alpha = .9f), Offset(cx, by), Offset(cx, by + 9 * d), 1.4f * d)
                drawCircle(t.primary, 3.2f * d, Offset(cx, by + 11 * d))
            }
            drawLine(t.primary, Offset(0f, vh * .12f), Offset(w, vh * .12f), 2.5f * d)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, t.primary.copy(alpha = .14f)), h * .88f, h), Offset(0f, h * .88f), Size(w, h * .12f))
        }

        // Carnet de voyage : papier pointé, vignettage, itinéraire, rose des vents, tache de café
        Decor.PAPER -> {
            val step = 22 * d; val ink = t.text
            var y = step
            while (y < h) { var x = step; while (x < w) { drawCircle(ink.copy(alpha = .09f), .9f * d, Offset(x, y)); x += step }; y += step }
            drawCircle(ink.copy(alpha = .07f), 42 * d, Offset(w * .13f, h * .16f), style = Stroke(7 * d))
            drawCircle(ink.copy(alpha = .04f), 36 * d, Offset(w * .13f + 4 * d, h * .16f + 3 * d), style = Stroke(3 * d))
            val route = Path().apply { moveTo(w * .07f, h * .80f); cubicTo(w * .45f, h * .95f, w * .30f, h * .45f, w * .62f, h * .50f); cubicTo(w * .92f, h * .55f, w * .70f, h * .25f, w * .90f, h * .20f) }
            drawPath(route, t.primary.copy(alpha = .38f), style = Stroke(2.2f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12 * d, 9 * d))))
            drawCircle(t.primary.copy(alpha = .5f), 4 * d, Offset(w * .07f, h * .80f))
            val xp = Offset(w * .90f, h * .20f); val xs = 7 * d
            drawLine(t.accent2.copy(alpha = .7f), xp - Offset(xs, xs), xp + Offset(xs, xs), 3 * d)
            drawLine(t.accent2.copy(alpha = .7f), xp + Offset(-xs, xs), xp + Offset(xs, -xs), 3 * d)
            val rc = Offset(w * .84f, h * .88f); val rr = 34 * d
            drawCircle(ink.copy(alpha = .16f), rr, rc, style = Stroke(1.2f * d))
            for (k in 0 until 4) {
                val a = k * PI.toFloat() / 2
                val tip = rc + Offset(cos(a) * rr * 1.25f, sin(a) * rr * 1.25f)
                val l = rc + Offset(cos(a + .5f) * rr * .25f, sin(a + .5f) * rr * .25f); val r2 = rc + Offset(cos(a - .5f) * rr * .25f, sin(a - .5f) * rr * .25f)
                drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(rc.x, rc.y); lineTo(r2.x, r2.y); close() }, ink.copy(alpha = if (k == 3) .32f else .16f))
            }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xFF6B4A22).copy(alpha = .16f)), Offset(w / 2, h / 2), max(w, h) * .72f))
        }

        // Guinguette : nappe vichy
        Decor.VICHY -> {
            val band = 15 * d; val col = t.primary.copy(alpha = .075f)
            var x = 0f; while (x < w) { drawRect(col, Offset(x, 0f), Size(band, h)); x += band * 2 }
            var y = 0f; while (y < h) { drawRect(col, Offset(0f, y), Size(w, band)); y += band * 2 }
        }

        // Terminal : lignes de balayage, rémanence, vignettage cathodique
        Decor.SCANLINES -> {
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = .10f), Color.Transparent), Offset(w / 2, h * .45f), max(w, h) * .6f), max(w, h) * .6f, Offset(w / 2, h * .45f))
            val st = 4 * d; var y = 0f
            while (y < h) { drawRect(t.primary.copy(alpha = .035f), Offset(0f, y), Size(w, d)); y += st }
            val by = frac(p * 6) * (h + 240 * d) - 120 * d
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, t.primary.copy(alpha = .07f), Color.Transparent), by - 60 * d, by + 60 * d), Offset(0f, by - 60 * d), Size(w, 120 * d))
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f)), Offset(w / 2, h / 2), max(w, h) * .75f))
        }

        // Abysses : rayons de lumière, bulles qui montent
        Decor.BUBBLES -> {
            for (k in 0 until 5) {
                val sway = sin(TAU * (p * 2 + k * .2f)) * w * .03f
                val x0 = w * (.1f + k * .2f) + sway
                drawPath(Path().apply { moveTo(x0, 0f); lineTo(x0 + w * .06f, 0f); lineTo(x0 + w * .2f + sway, h * .75f); lineTo(x0 - w * .06f + sway, h * .75f); close() },
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent), 0f, h * .75f))
            }
            for (i in 0 until 30) {
                val k = 1 + i % 3
                val r = (3 + rnd(i, 5) * 9) * d
                val f = frac(rnd(i, 2) + p * k * 2)
                val y = h + r - f * (h + 2 * r)
                val x = rnd(i, 1) * w + sin(TAU * (p * k * 4 + rnd(i, 3))) * 8 * d
                drawCircle(t.primary.copy(alpha = .30f), r, Offset(x, y), style = Stroke(1.2f * d))
                drawCircle(Color.White.copy(alpha = .35f), r * .25f, Offset(x - r * .35f, y - r * .35f))
            }
        }

        // Bord de mer / Pirate : vagues qui ondulent, soleil ou lune
        Decor.WAVES -> {
            val wc = if (t.dark) Color(0xFF4A8C9C) else t.primary
            if (t.dark) {
                val mc = Offset(w * .80f, h * .12f)
                drawCircle(Brush.radialGradient(listOf(t.text.copy(alpha = .18f), Color.Transparent), mc, 90 * d), 90 * d, mc)
                drawCircle(t.text.copy(alpha = .7f), 24 * d, mc)
                drawCircle(t.bg, 22 * d, mc + Offset(10 * d, -6 * d))
                for (i in 0 until 40) drawCircle(Color.White.copy(alpha = .35f * (.4f + .6f * (.5f + .5f * sin(TAU * (p * (2 + i % 3) + rnd(i, 4)))))), (.5f + rnd(i, 3)) * d, Offset(rnd(i, 1) * w, rnd(i, 2) * h * .5f))
            } else {
                val sc = Offset(w * .82f, h * .11f)
                drawCircle(Brush.radialGradient(listOf(t.accent2.copy(alpha = .45f), Color.Transparent), sc, 110 * d), 110 * d, sc)
                drawCircle(t.accent2.copy(alpha = .85f), 30 * d, sc)
                for (g in 0 until 3) {
                    val gx = w * (.18f + g * .12f); val gy = h * (.10f + g * .03f); val gs = (9 - g * 2) * d
                    drawPath(Path().apply { moveTo(gx - gs, gy); quadraticBezierTo(gx - gs / 2, gy - gs * .6f, gx, gy); quadraticBezierTo(gx + gs / 2, gy - gs * .6f, gx + gs, gy) }, t.text.copy(alpha = .45f), style = Stroke(1.6f * d))
                }
            }
            for (l in 0 until 3) {
                val base = h * (.80f + l * .06f); val amp = (9 + l * 4) * d; val len = w * (.95f - l * .18f)
                val sh = TAU * p * (l + 1) * (if (l % 2 == 0) 1 else -1)
                val path = Path().apply {
                    moveTo(0f, h); lineTo(0f, base)
                    var x = 0f
                    while (x <= w + 8 * d) { lineTo(x, base + sin(x / len * TAU + sh) * amp); x += 8 * d }
                    lineTo(w, h); close()
                }
                drawPath(path, wc.copy(alpha = .10f + l * .07f))
            }
        }

        // Fierté : grand arc-en-ciel + paillettes
        Decor.RAINBOW -> {
            val cols = listOf(0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF004DFF, 0xFF750787)
            val sw = 13 * d; val c = Offset(w / 2, h * .34f); val r0 = w * .62f
            cols.forEachIndexed { k, v ->
                val r = r0 - k * sw
                drawArc(Color(v).copy(alpha = .30f), 180f, 180f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw))
            }
            for (i in 0 until 22) sparkle(Offset(rnd(i, 1) * w, h * (.4f + rnd(i, 2) * .6f)), (3 + rnd(i, 3) * 5) * d, Color(cols[i % 6]).copy(alpha = .35f))
        }

        // Bonbon Pop : trames de points BD en coins opposés
        Decor.DOTS -> {
            val step = 17 * d; val maxR = step * .42f
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    val a = 1f - (x / w * .55f + y / h * .9f); if (a > 0) drawCircle(t.primary.copy(alpha = .16f), maxR * a, Offset(x, y))
                    val b = 1f - ((w - x) / w * .55f + (h - y) / h * .9f); if (b > 0) drawCircle(t.accent2.copy(alpha = .16f), maxR * b, Offset(x, y))
                    x += step
                }
                y += step
            }
        }

        // Rétro 70s : rayons de soleil et arcs à trois bandes
        Decor.SUNBURST -> {
            val c = Offset(w / 2, h * 1.02f); val R = h * 1.3f; val n = 26
            for (k in 0 until n step 2) {
                val a0 = PI.toFloat() + k * PI.toFloat() / n; val a1 = a0 + PI.toFloat() / n
                drawPath(Path().apply { moveTo(c.x, c.y); lineTo(c.x + cos(a0) * R, c.y + sin(a0) * R); lineTo(c.x + cos(a1) * R, c.y + sin(a1) * R); close() }, t.primary.copy(alpha = .06f))
            }
            val bands = listOf(t.accent2, t.primary, Color(0xFF8C3B1F))
            val sw = 15 * d
            bands.forEachIndexed { k, col ->
                val r = w * .42f + k * sw
                drawArc(col.copy(alpha = .55f), 270f, 90f, false, Offset(-r, h * .30f - r), Size(2 * r, 2 * r), style = Stroke(sw))
            }
            val sc = Offset(w * .86f, h * .10f)
            drawCircle(t.accent2.copy(alpha = .55f), 26 * d, sc)
            for (k in 0 until 12) { val a = k * TAU / 12; drawLine(t.accent2.copy(alpha = .45f), sc + Offset(cos(a) * 34 * d, sin(a) * 34 * d), sc + Offset(cos(a) * 44 * d, sin(a) * 44 * d), 3 * d) }
        }

        // Arcade : envahisseurs qui se dandinent, étoiles carrées, immeubles en pixels
        Decor.PIXELS -> {
            val inv = listOf("00100000100", "00010001000", "00111111100", "01101110110", "11111111111", "10111111101", "10100000101", "00011011000")
            val px = 4 * d
            val hop = if (frac(p * 24) < .5f) 0f else px * 2
            val spots = listOf(Offset(.08f, .12f) to t.accent2, Offset(.62f, .08f) to t.primary, Offset(.34f, .22f) to Color(0xFF3DDCFF), Offset(.78f, .30f) to t.accent2, Offset(.14f, .42f) to Color(0xFF7CFF4F))
            spots.forEach { (o, col) ->
                inv.forEachIndexed { r, row -> row.forEachIndexed { cIdx, ch -> if (ch == '1') drawRect(col.copy(alpha = .30f), Offset(o.x * w + cIdx * px + hop, o.y * h + r * px), Size(px, px)) } }
            }
            for (i in 0 until 50) if (frac(p * (4 + i % 3) + rnd(i, 4)) < .7f) drawRect(Color.White.copy(alpha = .45f), Offset(rnd(i, 1) * w, rnd(i, 2) * h * .7f), Size(2 * d, 2 * d))
            val bw = 10 * d; var x = 0f; var i = 0
            while (x < w) { val bh = (22 + rnd(i, 7) * 60) * d; drawRect(t.primary.copy(alpha = .10f), Offset(x, h - bh), Size(bw - d, bh)); x += bw; i++ }
        }

        // Braise : lueur du foyer, étincelles qui montent
        Decor.EMBERS -> {
            val fc = Offset(w / 2, h * 1.05f)
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = .32f), Color.Transparent), fc, h * .6f), h * .6f, fc)
            for (i in 0 until 46) {
                val k = 1 + i % 3
                val f = frac(rnd(i, 2) + p * k * 3)
                val y = h - f * h * 1.05f
                val x = rnd(i, 1) * w + sin(TAU * (f * 2 + rnd(i, 3))) * 16 * d
                val a = (1 - f) * .9f; val r = (1 + rnd(i, 4) * 2) * d * (1 - f * .5f)
                val col = lerp(t.accent2, t.primary, f)
                drawCircle(col.copy(alpha = a * .22f), r * 3.5f, Offset(x, y))
                drawCircle(col.copy(alpha = a), r, Offset(x, y))
            }
        }

        // Sakura : branche fleurie, pétales qui tombent en tournoyant
        Decor.PETALS -> {
            val wood = Color(0xFF5A3A2E).copy(alpha = .35f)
            val b = Path().apply { moveTo(w, h * .03f); cubicTo(w * .85f, h * .05f, w * .72f, h * .04f, w * .55f, h * .12f) }
            drawPath(b, wood, style = Stroke(5 * d))
            drawLine(wood, Offset(w * .78f, h * .05f), Offset(w * .70f, h * .015f), 3 * d)
            listOf(.62f to .10f, .70f to .07f, .80f to .045f, .69f to .02f, .9f to .04f).forEach { (fx, fy) ->
                val c = Offset(w * fx, h * fy)
                for (k in 0 until 5) { val a = k * TAU / 5; drawCircle(Color(0xFFF7A8C4).copy(alpha = .6f), 6 * d, c + Offset(cos(a) * 6 * d, sin(a) * 6 * d)) }
                drawCircle(t.primary.copy(alpha = .7f), 2.5f * d, c)
            }
            for (i in 0 until 26) {
                val k = 1 + i % 2
                val f = frac(rnd(i, 2) + p * k * 2)
                val y = -20 * d + f * (h + 40 * d)
                val x = rnd(i, 1) * w + sin(TAU * (f * 2 + rnd(i, 3))) * 24 * d
                val col = if (i % 3 == 0) t.primary.copy(alpha = .35f) else Color(0xFFF7A8C4).copy(alpha = .55f)
                rotate(360f * (f * 2 + rnd(i, 5)), Offset(x, y)) { drawOval(col, Offset(x - 6 * d, y - 3.5f * d), Size(12 * d, 7 * d)) }
            }
        }

        // Gala : éventails Art déco dorés, filets sur les bords, éclats
        Decor.DECO -> {
            val gold = t.primary
            for (top in listOf(true, false)) {
                val c = Offset(w / 2, if (top) 0f else h)
                for (r in listOf(54f, 84f, 114f)) drawArc(gold.copy(alpha = .28f), if (top) 0f else 180f, 180f, false, Offset(c.x - r * d, c.y - r * d), Size(2 * r * d, 2 * r * d), style = Stroke(d))
                for (k in 1 until 18) {
                    val a = (if (top) 0f else PI.toFloat()) + k * PI.toFloat() / 18
                    drawLine(gold.copy(alpha = .16f), c + Offset(cos(a) * 58 * d, sin(a) * 58 * d), c + Offset(cos(a) * 170 * d, sin(a) * 170 * d), d)
                }
            }
            for (x in listOf(7 * d, 11 * d, w - 7 * d, w - 11 * d)) drawLine(gold.copy(alpha = .30f), Offset(x, 0f), Offset(x, h), d)
            for (x in listOf(9 * d, w - 9 * d)) {
                val m = Offset(x, h / 2); val s = 7 * d
                drawPath(Path().apply { moveTo(m.x, m.y - s * 1.6f); lineTo(m.x + s, m.y); lineTo(m.x, m.y + s * 1.6f); lineTo(m.x - s, m.y); close() }, gold.copy(alpha = .6f))
            }
            for (i in 0 until 14) {
                val tw = (.5f + .5f * sin(TAU * (p * (2 + i % 3) + rnd(i, 4))))
                sparkle(Offset(w * (.08f + rnd(i, 1) * .84f), h * (.12f + rnd(i, 2) * .76f)), (3 + 5 * tw) * d, t.accent2.copy(alpha = .55f * tw))
            }
        }
    }
}

/* ======================= Plongée : le récif et sa faune ======================= */

/** Dessine [block] avec l'origine en (x, y), tourné vers la droite (ou la gauche si [flip]). */
private inline fun DrawScope.at(x: Float, y: Float, flip: Boolean, block: DrawScope.() -> Unit) =
    withTransform({ translate(x, y); scale(if (flip) -1f else 1f, 1f, Offset.Zero) }) { block() }

/** Position d'un animal qui traverse l'écran ([k] tours par cycle, pour une boucle sans à-coup). */
private fun cross(p: Float, k: Int, off: Float, w: Float, len: Float) = -len * .6f + frac(p * k + off) * (w + len * 1.2f)

private fun DrawScope.whaleShark(L: Float) {
    val top = Color(0xFF34607E); val belly = Color(0xFFA7C6D3)
    val body = Path().apply {
        moveTo(.47f * L, -.03f * L)
        quadraticBezierTo(.50f * L, .05f * L, .40f * L, .085f * L)
        cubicTo(.15f * L, .14f * L, -.20f * L, .10f * L, -.36f * L, .035f * L)
        lineTo(-.47f * L, .15f * L); lineTo(-.44f * L, .02f * L); lineTo(-.55f * L, -.22f * L); lineTo(-.36f * L, -.02f * L)
        cubicTo(-.20f * L, -.09f * L, .10f * L, -.125f * L, .30f * L, -.10f * L)
        quadraticBezierTo(.45f * L, -.085f * L, .47f * L, -.03f * L)
        close()
    }
    val fins = Path().apply {
        moveTo(.04f * L, -.11f * L); lineTo(-.07f * L, -.21f * L); lineTo(-.12f * L, -.10f * L); close()
        moveTo(.20f * L, .09f * L); lineTo(.07f * L, .22f * L); lineTo(.05f * L, .11f * L); close()
        moveTo(-.24f * L, -.075f * L); lineTo(-.29f * L, -.115f * L); lineTo(-.30f * L, -.06f * L); close()
    }
    drawPath(fins, top)
    drawPath(body, Brush.verticalGradient(listOf(top, top, belly), -.12f * L, .12f * L))
    clipPath(body) {
        for (k in 0 until 7) { val x = (.24f - k * .085f) * L; drawLine(Color.White.copy(alpha = .16f), Offset(x, -.14f * L), Offset(x - .02f * L, .03f * L), .007f * L) }
        for (i in 0 until 80) drawCircle(Color.White.copy(alpha = .6f), (.005f + rnd(i, 13) * .004f) * L, Offset((-.36f + rnd(i, 11) * .80f) * L, (-.13f + rnd(i, 12) * .17f) * L))
        for (k in 0 until 4) { val x = (.31f - k * .025f) * L; drawLine(Color(0xFF1D3B52), Offset(x, -.06f * L), Offset(x - .01f * L, .05f * L), .005f * L) }
    }
    drawLine(Color(0xFF15293A), Offset(.485f * L, .005f * L), Offset(.37f * L, .04f * L), .008f * L, StrokeCap.Round)
    drawCircle(Color(0xFF0D1A26), .009f * L, Offset(.42f * L, -.035f * L))
}

private fun DrawScope.greatWhite(L: Float) {
    val body = Path().apply {
        moveTo(.50f * L, 0f)
        quadraticBezierTo(.46f * L, -.09f * L, .26f * L, -.115f * L)
        lineTo(.10f * L, -.125f * L); lineTo(-.02f * L, -.29f * L); lineTo(-.06f * L, -.115f * L)
        cubicTo(-.20f * L, -.09f * L, -.32f * L, -.05f * L, -.38f * L, -.025f * L)
        lineTo(-.53f * L, -.22f * L); lineTo(-.46f * L, 0f); lineTo(-.50f * L, .13f * L); lineTo(-.38f * L, .025f * L)
        cubicTo(-.25f * L, .08f * L, -.05f * L, .115f * L, .12f * L, .105f * L)
        lineTo(.07f * L, .25f * L); lineTo(.19f * L, .095f * L)
        quadraticBezierTo(.42f * L, .08f * L, .50f * L, 0f)
        close()
    }
    drawPath(body, Color(0xFF6F8396))
    clipPath(body) {
        val belly = Path().apply {
            moveTo(.52f * L, .015f * L)
            var x = .52f; var up = true
            while (x > -.42f) { x -= .04f; lineTo(x * L, (.035f + if (up) -.012f else .012f) * L); up = !up }
            lineTo(-.45f * L, .4f * L); lineTo(.52f * L, .4f * L); close()
        }
        drawPath(belly, Color(0xFFE9EEF2))
    }
    for (k in 0 until 5) { val x = (.27f - k * .022f) * L; drawLine(Color(0xFF4A5B6B), Offset(x, -.05f * L), Offset(x - .012f * L, .05f * L), .005f * L) }
    drawCircle(Color.Black, .013f * L, Offset(.37f * L, -.035f * L))
    drawLine(Color(0xFF3A2A2A), Offset(.47f * L, .035f * L), Offset(.32f * L, .065f * L), .008f * L, StrokeCap.Round)
}

private fun DrawScope.manta(S: Float, f: Float) {
    val b = Path().apply {
        moveTo(.20f * S, -.06f * S)
        lineTo(.31f * S, -.09f * S); lineTo(.25f * S, -.025f * S); lineTo(.25f * S, .025f * S); lineTo(.31f * S, .09f * S); lineTo(.20f * S, .06f * S)
        cubicTo(.14f * S, .22f * S * f, -.02f * S, .42f * S * f, -.10f * S, .50f * S * f)
        cubicTo(-.10f * S, .32f * S * f, -.18f * S, .14f * S, -.20f * S, .05f * S)
        lineTo(-.58f * S, .006f * S); lineTo(-.58f * S, -.006f * S); lineTo(-.20f * S, -.05f * S)
        cubicTo(-.18f * S, -.14f * S, -.10f * S, -.32f * S * f, -.10f * S, -.50f * S * f)
        cubicTo(-.02f * S, -.42f * S * f, .14f * S, -.22f * S * f, .20f * S, -.06f * S)
        close()
    }
    drawPath(b, Brush.horizontalGradient(listOf(Color(0xFF16222F), Color(0xFF2A3E52)), -.25f * S, .3f * S))
    clipPath(b) { for (s in listOf(-1f, 1f)) drawOval(Color.White.copy(alpha = .35f), Offset(-.02f * S, (s * .15f * f - .05f) * S), Size(.14f * S, .10f * S)) }
}

private fun DrawScope.turtle(L: Float, a: Float) {
    val skin = Color(0xFF93A066); val shell = Color(0xFF5A4528); val shell2 = Color(0xFF94733D)
    for (s in listOf(-1f, 1f)) {
        drawPath(Path().apply {
            moveTo(.18f * L, s * .16f * L)
            quadraticBezierTo((.26f + .1f * a) * L, s * .45f * L, (-.02f + .14f * a) * L, s * .60f * L)
            quadraticBezierTo(.02f * L, s * .30f * L, .04f * L, s * .17f * L); close()
        }, skin)
        drawPath(Path().apply { moveTo(-.24f * L, s * .13f * L); lineTo((-.42f - .05f * a) * L, s * .28f * L); lineTo(-.33f * L, s * .09f * L); close() }, skin)
    }
    drawCircle(skin, .11f * L, Offset(.40f * L, 0f))
    for (s in listOf(-1f, 1f)) drawCircle(Color.Black, .016f * L, Offset(.45f * L, s * .05f * L))
    drawOval(Brush.radialGradient(listOf(shell2, shell), Offset.Zero, .36f * L), Offset(-.33f * L, -.25f * L), Size(.68f * L, .50f * L))
    val ln = Color.Black.copy(alpha = .28f); val sw = .012f * L
    drawLine(ln, Offset(-.22f * L, 0f), Offset(.24f * L, 0f), sw)
    for (x in listOf(-.10f, .07f)) drawLine(ln, Offset(x * L, -.22f * L), Offset(x * L, .22f * L), sw)
    drawOval(ln, Offset(-.33f * L, -.25f * L), Size(.68f * L, .50f * L), style = Stroke(sw * 1.5f))
}

private fun DrawScope.trigger(L: Float, tail: Float) {
    val dark = Color(0xFF1B2333)
    val b = Path().apply { moveTo(.50f * L, .02f * L); lineTo(.12f * L, -.36f * L); lineTo(-.28f * L, -.10f * L); lineTo(-.28f * L, .10f * L); lineTo(.12f * L, .36f * L); close() }
    drawPath(Path().apply { moveTo(-.26f * L, 0f); lineTo(-.50f * L, (-.20f + tail) * L); lineTo(-.45f * L, 0f); lineTo(-.50f * L, (.20f + tail) * L); close() }, dark)
    drawLine(dark, Offset(.12f * L, -.34f * L), Offset(.0f * L, -.50f * L), .03f * L, StrokeCap.Round)
    drawPath(b, dark)
    clipPath(b) {
        listOf(Offset(-.05f, .22f), Offset(.15f, .27f), Offset(.05f, .12f), Offset(-.18f, .10f)).forEachIndexed { i, o -> drawCircle(Color.White.copy(alpha = .9f), (.075f + i % 2 * .02f) * L, Offset(o.x * L, o.y * L)) }
        for (i in 0 until 8) drawCircle(Color(0xFFF5C330), .022f * L, Offset((-.05f + rnd(i, 21) * .25f) * L, (-.22f + rnd(i, 22) * .12f) * L))
        drawCircle(Color(0xFFF5C330), .13f * L, Offset(.48f * L, .02f * L))
    }
    drawCircle(Color.White, .035f * L, Offset(.27f * L, -.07f * L)); drawCircle(Color.Black, .018f * L, Offset(.28f * L, -.07f * L))
}

private fun DrawScope.platax(L: Float) {
    val c = Color(0xFFDCD5BA)
    val fin = Path().apply {
        moveTo(.20f * L, -.22f * L); cubicTo(.10f * L, -.72f * L, -.20f * L, -.72f * L, -.30f * L, -.42f * L)
        lineTo(-.22f * L, -.05f * L); lineTo(-.22f * L, .05f * L); lineTo(-.30f * L, .42f * L)
        cubicTo(-.20f * L, .72f * L, .10f * L, .72f * L, .20f * L, .22f * L); close()
    }
    val all = Path().apply { addPath(fin); addOval(Rect(-.25f * L, -.27f * L, .33f * L, .27f * L)) }
    drawPath(Path().apply { moveTo(-.24f * L, 0f); lineTo(-.42f * L, -.13f * L); lineTo(-.40f * L, .13f * L); close() }, c.copy(alpha = .8f))
    drawPath(fin, Color(0xFFB9B092))
    drawOval(c, Offset(-.25f * L, -.27f * L), Size(.58f * L, .54f * L))
    clipPath(all) {
        drawLine(Color(0xFF3B3427).copy(alpha = .75f), Offset(.22f * L, -.4f * L), Offset(.20f * L, .4f * L), .07f * L)
        drawLine(Color(0xFF3B3427).copy(alpha = .75f), Offset(-.03f * L, -.8f * L), Offset(-.06f * L, .8f * L), .08f * L)
    }
    drawCircle(Color.Black, .025f * L, Offset(.24f * L, -.07f * L))
}

private fun DrawScope.starfish(c: Offset, r: Float, rot: Float, col: Color) {
    val path = Path()
    for (k in 0 until 10) {
        val a = rot + k * PI.toFloat() / 5 - PI.toFloat() / 2; val rr = if (k % 2 == 0) r else r * .42f
        val pt = c + Offset(cos(a) * rr, sin(a) * rr)
        if (k == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    path.close()
    drawPath(path, col)
    drawPath(path, col, style = Stroke(r * .22f, join = StrokeJoin.Round))
    for (k in 0 until 5) { val a = rot + k * TAU / 5 - PI.toFloat() / 2; for (j in 1..3) drawCircle(Color.White.copy(alpha = .45f), r * .05f, c + Offset(cos(a) * r * .22f * j, sin(a) * r * .22f * j)) }
}

private fun DrawScope.hermit(L: Float, a: Float) {
    val red = Color(0xFFD9573B)
    for (k in 0 until 3) {
        val bx = (.04f + k * .08f) * L; val sw = (if (k % 2 == 0) a else -a) * .05f * L
        drawLine(red, Offset(bx, 0f), Offset(bx + .08f * L + sw, .20f * L), .03f * L, StrokeCap.Round)
    }
    drawOval(red, Offset(.02f * L, -.12f * L), Size(.24f * L, .16f * L))
    drawLine(red, Offset(.20f * L, -.10f * L), Offset(.23f * L, -.27f * L), .015f * L); drawCircle(Color.Black, .025f * L, Offset(.23f * L, -.28f * L))
    drawLine(red, Offset(.24f * L, -.09f * L), Offset(.31f * L, -.24f * L), .015f * L); drawCircle(Color.Black, .025f * L, Offset(.31f * L, -.25f * L))
    val sc = Offset(-.14f * L, -.12f * L)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF3DDB8), Color(0xFFC59B6A)), sc, .27f * L), .26f * L, sc)
    val sp = Path()
    for (i in 0..70) { val ang = i * .3f; val rr = .23f * L * (1 - i / 78f); val pt = sc + Offset(cos(ang) * rr, sin(ang) * rr); if (i == 0) sp.moveTo(pt.x, pt.y) else sp.lineTo(pt.x, pt.y) }
    drawPath(sp, Color(0xFF7A5634).copy(alpha = .7f), style = Stroke(.018f * L))
    drawCircle(Color(0xFFE8694A), .075f * L, Offset(.30f * L, .03f * L))
}

private fun DrawScope.drawReef(t: HTheme, p: Float) {
    val w = size.width; val h = size.height; val d = density
    // rayons de lumière depuis la surface
    for (k in 0 until 5) {
        val sway = sin(TAU * (p * 2 + k * .2f)) * w * .03f
        val x0 = w * (.08f + k * .21f) + sway
        drawPath(Path().apply { moveTo(x0, 0f); lineTo(x0 + w * .07f, 0f); lineTo(x0 + w * .22f + sway, h * .8f); lineTo(x0 - w * .06f + sway, h * .8f); close() },
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.Transparent), 0f, h * .8f))
    }
    // au loin : le requin-baleine, lent et majestueux
    run { val L = w * .9f
        at(cross(p, 1, .10f, w, L), h * .34f + sin(TAU * p * 2) * 6 * d, false) { whaleShark(L) } }
    // le grand blanc, de droite à gauche
    run { val L = w * .46f; at(w - cross(p, 2, .55f, w, L), h * .16f + sin(TAU * p * 4) * 5 * d, true) { greatWhite(L) } }
    // raie manta qui « vole »
    run { val S = w * .52f; at(w - cross(p, 1, .62f, w, S), h * .54f + sin(TAU * p * 3) * 10 * d, true) { manta(S, .72f + .28f * sin(TAU * p * 10)) } }
    // tortue
    run { val L = w * .17f; at(cross(p, 1, .45f, w, L), h * .44f + sin(TAU * p * 3 + 1f) * 8 * d, false) { turtle(L, sin(TAU * p * 9)) } }
    // platax (poissons-chauves-souris)
    for (i in 0 until 2) { val L = w * .10f; at(w - cross(p, 2, .2f + i * .07f, w, L), h * (.26f + i * .05f) + sin(TAU * (p * 6 + i * .3f)) * 4 * d, true) { platax(L) } }
    // banc de balistes
    for (i in 0 until 3) { val L = w * .085f; at(cross(p, 3, .3f + i * .05f, w, L), h * (.64f + i * .035f) + sin(TAU * (p * 9 + i * .2f)) * 4 * d, false) { trigger(L, .06f * sin(TAU * (p * 24 + i * .3f))) } }

    // fond de sable
    val sandTop = h * .90f
    drawPath(Path().apply {
        moveTo(0f, h); lineTo(0f, sandTop)
        var x = 0f; while (x <= w + 10 * d) { lineTo(x, sandTop + sin(x / w * TAU * 1.5f) * 6 * d); x += 10 * d }
        lineTo(w, h); close()
    }, Brush.verticalGradient(listOf(Color(0xFFC9AE7E).copy(alpha = .55f), Color(0xFF8C774F).copy(alpha = .55f)), sandTop, h))
    // algues qui ondulent
    for (i in 0 until 6) {
        val bx = w * (.18f + i * .14f + rnd(i, 31) * .05f); val hh = h * (.06f + rnd(i, 32) * .07f)
        val sp = Path(); var j = 0
        while (j <= 12) { val f = j / 12f; val pt = Offset(bx + sin(TAU * (p * 3 + i * .17f) + f * 3f) * 8 * d * f, sandTop + 4 * d - hh * f); if (j == 0) sp.moveTo(pt.x, pt.y) else sp.lineTo(pt.x, pt.y); j++ }
        drawPath(sp, Color(0xFF3E8E5A).copy(alpha = .75f), style = Stroke(4 * d, cap = StrokeCap.Round))
    }
    // gorgone (corail en éventail)
    run {
        val base = Offset(w * .80f, sandTop + 6 * d); val col = Color(0xFFE0668A).copy(alpha = .8f)
        for (k in 0 until 7) {
            val a = -PI.toFloat() / 2 + (k - 3) * .22f; val len = h * .09f
            val tip = base + Offset(cos(a) * len, sin(a) * len)
            drawLine(col, base, tip, 2.5f * d, StrokeCap.Round)
            for (j in 1..2) { val mid = base + (tip - base) * (j / 3f); drawLine(col, mid, mid + Offset(cos(a + .5f) * len * .25f, sin(a + .5f) * len * .25f), 1.6f * d, StrokeCap.Round); drawLine(col, mid, mid + Offset(cos(a - .5f) * len * .25f, sin(a - .5f) * len * .25f), 1.6f * d, StrokeCap.Round) }
        }
    }
    // rocher et murène qui sort de son trou
    run {
        val rc = Offset(w * .06f, h * .93f); val rr = w * .17f
        drawPath(Path().apply {
            moveTo(rc.x - rr, h); lineTo(rc.x - rr, rc.y - rr * .2f); cubicTo(rc.x - rr * .6f, rc.y - rr * 1.1f, rc.x + rr * .7f, rc.y - rr * 1.0f, rc.x + rr, rc.y - rr * .1f); lineTo(rc.x + rr * 1.1f, h); close()
        }, Brush.verticalGradient(listOf(Color(0xFF3D4A55), Color(0xFF1F272E)), rc.y - rr, h))
        val hole = Offset(rc.x + rr * .45f, rc.y - rr * .45f)
        drawOval(Color(0xFF0B1015), Offset(hole.x - rr * .2f, hole.y - rr * .13f), Size(rr * .4f, rr * .26f))
        val ext = w * .13f * (.75f + .25f * sin(TAU * p * 2))
        val pts = (0..16).map { j -> val f = j / 16f; Offset(hole.x + f * ext, hole.y - f * ext * .55f + sin(j * .8f + TAU * p * 4) * 4 * d * f) }
        val body = Path().apply { pts.forEachIndexed { j, o -> if (j == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val eel = Color(0xFF8E9C3E)
        drawPath(body, eel, style = Stroke(w * .035f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        pts.forEachIndexed { j, o -> if (j % 2 == 1) drawCircle(Color(0xFF4F5A1E), w * .005f, o + Offset(0f, w * .006f)) }
        val head = pts.last()
        drawPath(Path().apply { moveTo(head.x + w * .012f, head.y - w * .002f); lineTo(head.x + w * .032f, head.y - w * .010f); lineTo(head.x + w * .030f, head.y + w * .006f); close() }, Color(0xFF5A1E22))
        drawCircle(Color.White, w * .006f, head + Offset(-w * .002f, -w * .010f)); drawCircle(Color.Black, w * .003f, head + Offset(-w * .001f, -w * .010f))
    }
    // étoiles de mer
    starfish(Offset(w * .62f, h * .955f), w * .045f, .3f, Color(0xFFF07B3F))
    starfish(Offset(w * .93f, h * .975f), w * .032f, 1.1f, Color(0xFFD9425E))
    // bernard-l'hermite qui se promène
    run { val L = w * .10f; at(cross(p, 1, .8f, w, L), sandTop + 2 * d, false) { hermit(L, sin(TAU * p * 40)) } }
    // bulles
    for (i in 0 until 18) {
        val k = 1 + i % 3; val r = (2 + rnd(i, 5) * 6) * d
        val f = frac(rnd(i, 2) + p * k * 2)
        val y = h + r - f * (h + 2 * r); val x = rnd(i, 1) * w + sin(TAU * (p * k * 4 + rnd(i, 3))) * 6 * d
        drawCircle(Color.White.copy(alpha = .28f), r, Offset(x, y), style = Stroke(1.1f * d))
    }
}

/* ======================= Ocean Drive : Floride au coucher du soleil ======================= */

private fun DrawScope.palm(base: Offset, hgt: Float, lean: Float, sway: Float, col: Color) {
    val top = Offset(base.x + lean * hgt * .5f + sway * hgt * .02f, base.y - hgt)
    val ctrl = Offset(base.x + lean * hgt * .05f, base.y - hgt * .55f)
    val tw = hgt * .022f
    drawPath(Path().apply {
        moveTo(base.x - tw, base.y); quadraticBezierTo(ctrl.x - tw * .6f, ctrl.y, top.x - tw * .35f, top.y)
        lineTo(top.x + tw * .35f, top.y); quadraticBezierTo(ctrl.x + tw * .6f, ctrl.y, base.x + tw, base.y); close()
    }, col)
    for (k in 0 until 8) {
        val ang = PI.toFloat() * (-1f + k / 7f) + sway * .08f
        val L = hgt * (.26f + rnd(k, 41) * .08f)
        val end = top + Offset(cos(ang) * L, sin(ang) * L * .45f + L * .38f)
        val c1 = top + Offset(cos(ang) * L * .55f, sin(ang) * L * .7f - L * .12f)
        val c2 = c1 + Offset(0f, L * .14f)
        drawPath(Path().apply { moveTo(top.x, top.y); quadraticBezierTo(c1.x, c1.y, end.x, end.y); quadraticBezierTo(c2.x, c2.y, top.x, top.y + L * .03f); close() }, col)
    }
    for (k in 0 until 3) drawCircle(col, hgt * .018f, top + Offset((k - 1) * hgt * .02f, hgt * .025f))
}

private fun DrawScope.drawVice(t: HTheme, p: Float) {
    val w = size.width; val h = size.height; val d = density
    val hz = h * .50f
    drawRect(Brush.verticalGradient(0f to Color(0xFF2B1055), .35f to Color(0xFFD53A9D), .70f to Color(0xFFFF8E53), 1f to Color(0xFFFFC371), startY = 0f, endY = hz), size = Size(w, hz))
    val sc = Offset(w * .5f, hz - w * .05f); val r = w * .25f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE29A).copy(alpha = .5f), Color.Transparent), sc, r * 2f), r * 2f, sc)
    clipRect(bottom = hz) { drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFF4B0), Color(0xFFFF7BAC)), sc.y - r, sc.y + r), r, sc) }
    // immeubles art déco
    val bw = w / 16f
    for (i in 0 until 16) {
        val bh = h * (.04f + rnd(i, 51) * .10f) * (if (i in 6..9) .6f else 1f)
        val x = i * bw
        drawRect(Color(0xFF3A1D4F), Offset(x, hz - bh), Size(bw - d, bh))
        if (i % 3 == 0) drawRect(t.accent2.copy(alpha = .85f), Offset(x, hz - bh), Size(bw - d, 2 * d))
        if (i % 4 == 1) drawRect(t.primary.copy(alpha = .9f), Offset(x + bw * .2f, hz - bh - 4 * d), Size(bw * .5f, 2 * d))
        var wy = hz - bh + 6 * d
        while (wy < hz - 4 * d) { for (c in 0 until 2) if (rnd(i * 31 + (wy / d).toInt(), c + 7) < .45f) drawRect(Color(0xFFFFD27A).copy(alpha = .7f), Offset(x + bw * (.22f + c * .36f), wy), Size(bw * .18f, 2.5f * d)); wy += 7 * d }
    }
    // la mer et le reflet du soleil
    drawRect(Brush.verticalGradient(listOf(Color(0xFF6A2C7A), Color(0xFF2B1A4A), t.bg), hz, h), Offset(0f, hz), Size(w, h - hz))
    for (k in 0 until 16) {
        val y = hz + 6 * d + k * k * .9f * d + k * 7 * d
        val wd = r * (1.7f - k * .08f) * (.75f + .25f * sin(TAU * (p * 6 + k * .13f)))
        drawRect(Color(0xFFFFB27A).copy(alpha = .5f * (1 - k / 16f)), Offset(sc.x - wd / 2, y), Size(wd, 2.5f * d))
    }
    // palmiers en ombre chinoise
    val sil = Color(0xFF120820)
    val sway = sin(TAU * p * 3)
    palm(Offset(w * .22f, hz + 2 * d), h * .17f, .15f, sway, Color(0xFF2A1238))
    palm(Offset(w * .04f, h + 10 * d), h * .66f, .38f, sway, sil)
    palm(Offset(w * .97f, h + 10 * d), h * .52f, -.32f, -sway, sil)
}
