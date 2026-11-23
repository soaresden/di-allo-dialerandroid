package fr.soaresden.diallo

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Décor dessiné derrière l'appli (voir Decor.kt). */
enum class Decor { NONE, GRID, STARS, CURTAIN, PAPER, VICHY, SCANLINES, BUBBLES, WAVES, RAINBOW, DOTS, SUNBURST, PIXELS, EMBERS, PETALS, DECO, REEF, VICE, LEGEND, QUEST }

/** Forme des touches du clavier. */
enum class KeyShape { CIRCLE, SQUIRCLE, SQUARE, CUT }

/** Rendu des touches : pleine, verre, néon, contour, BD (ombre dure), tampon (pointillés). */
enum class KeyStyle { FILLED, GLASS, NEON, OUTLINE, HARD, STAMP }

/**
 * Un thème : couleurs, polices (logo / titres / chiffres), décor de fond,
 * forme et style des touches, style des cartes.
 */
data class HTheme(
    val id: String, val name: String, val dark: Boolean,
    val primary: Color, val bg: Color, val top: Color,
    val surface: Color, val variant: Color, val text: Color,
    val accent2: Color = primary,
    val title: FontFamily? = null, val head: FontFamily? = null, val num: FontFamily? = null,
    val headScale: Float = 1f, val numScale: Float = 1f,
    val decor: Decor = Decor.NONE,
    val keyShape: KeyShape = KeyShape.CIRCLE, val keyStyle: KeyStyle = KeyStyle.FILLED,
    val radius: Int = 18, val border: Color? = null, val glow: Boolean = false, val cardAlpha: Float = 1f, val decorAlpha: Float = 1f,
    val tag: String = ""
) {
    fun keyShapeOf(): Shape = when (keyShape) {
        KeyShape.CIRCLE -> CircleShape
        KeyShape.SQUIRCLE -> RoundedCornerShape(32)
        KeyShape.SQUARE -> RoundedCornerShape(14)
        KeyShape.CUT -> CutCornerShape(24)
    }
    fun cardShape(): Shape = if (keyShape == KeyShape.CUT) CutCornerShape(radius.coerceAtMost(14).dp) else RoundedCornerShape(radius.dp)
    /** Halo de texte (néon) pour les gros chiffres. */
    fun glowShadow(c: Color = primary): Shadow? = if (glow) Shadow(c.copy(alpha = .9f), blurRadius = 22f) else null
    /** Voile (couleur du fond) posé sur le décor pour que le texte reste lisible : plus fort pour les décors chargés. */
    val scrim: Float get() = when (decor) {
        Decor.NONE -> 0f
        Decor.VICE -> .62f
        Decor.REEF, Decor.GRID, Decor.LEGEND -> .45f
        Decor.QUEST -> .40f
        Decor.CURTAIN, Decor.PIXELS, Decor.STARS, Decor.BUBBLES, Decor.EMBERS, Decor.WAVES -> .30f
        else -> .18f
    }
}

val LocalHTheme = staticCompositionLocalOf { THEMES.first() }

private fun c(v: Long) = Color(v)
private fun ff(id: Int) = FontFamily(Font(id))
private val DARK_TXT = c(0xFF13151B)
private val LIGHT_TXT = c(0xFFF3F5F9)

private val F_ALFA = ff(R.font.alfa_slab_one)
private val F_BEBAS = ff(R.font.bebas_neue)
private val F_CINZEL = ff(R.font.cinzel)
private val F_VIBES = ff(R.font.great_vibes)
private val F_LIME = ff(R.font.limelight)
private val F_LOBSTER = ff(R.font.lobster)
private val F_LUCKY = ff(R.font.luckiest_guy)
private val F_MONOTON = ff(R.font.monoton)
private val F_ORBITRON = ff(R.font.orbitron)
private val F_PACIFICO = ff(R.font.pacifico)
private val F_PIRATA = ff(R.font.pirata_one)
private val F_PLAYFAIR = ff(R.font.playfair_italic)
private val F_PIXEL = ff(R.font.press_start)
private val F_RUSSO = ff(R.font.russo_one)
private val F_TYPE = ff(R.font.special_elite)
private val F_VT = ff(R.font.vt323)
private val F_YELLOW = ff(R.font.yellowtail)

/** Une police proposée pour remplacer celle d'un thème ; les échelles compensent les polices plus petites ou plus grandes. */
class FontChoice(val id: String, val name: String, val family: FontFamily?, val headScale: Float = 1f, val numScale: Float = 1f)

/**
 * Police choisie thème par thème (« du thème » par défaut). Le choix est gardé pour chaque thème :
 * revenir sur un thème retrouve la police qu'on lui avait donnée.
 */
object ThemeFonts {
    const val THEME = "theme"
    val ALL: List<FontChoice> = listOf(
        FontChoice(THEME, "Du thème", null),
        FontChoice("system", "Système", FontFamily.Default),
        FontChoice("bebas", "Bebas", F_BEBAS, 1.15f, 1.15f),
        FontChoice("russo", "Russo", F_RUSSO, .95f, .9f),
        FontChoice("alfa", "Alfa Slab", F_ALFA, .92f, .85f),
        FontChoice("lucky", "Luckiest", F_LUCKY, 1.05f, .9f),
        FontChoice("orbitron", "Orbitron", F_ORBITRON, .9f, .82f),
        FontChoice("cinzel", "Cinzel", F_CINZEL, 1.05f, 1f),
        FontChoice("playfair", "Playfair", F_PLAYFAIR, 1.1f, 1.05f),
        FontChoice("lime", "Limelight", F_LIME, .95f, .9f),
        FontChoice("lobster", "Lobster", F_LOBSTER, 1.05f, 1f),
        FontChoice("pacifico", "Pacifico", F_PACIFICO, .95f, .85f),
        FontChoice("yellow", "Yellowtail", F_YELLOW, 1.15f, 1.05f),
        FontChoice("vibes", "Great Vibes", F_VIBES, 1.3f, 1.2f),
        FontChoice("pirata", "Pirata", F_PIRATA, 1.2f, 1.05f),
        FontChoice("type", "Machine à écrire", F_TYPE, 1f, 1f),
        FontChoice("monoton", "Monoton", F_MONOTON, .85f, .8f),
        FontChoice("vt", "Terminal", F_VT, 1.35f, 1.3f),
        FontChoice("pixel", "Pixel", F_PIXEL, .62f, .55f),
    )
    /** id du thème → id de la police (absent = celle du thème). Observable : l'appli se redessine au changement. */
    private val chosen = androidx.compose.runtime.mutableStateMapOf<String, String>()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        prefs(c).all.forEach { (k, v) -> if (k.startsWith("font_") && v is String) chosen[k.removePrefix("font_")] = v }
    }
    fun of(themeId: String): String = chosen[themeId] ?: THEME
    fun set(c: Context, themeId: String, fontId: String) {
        if (fontId == THEME) { chosen.remove(themeId); prefs(c).edit().remove("font_$themeId").apply() }
        else { chosen[themeId] = fontId; prefs(c).edit().putString("font_$themeId", fontId).apply() }
    }
    /** Le thème avec la police choisie pour lui. */
    fun apply(t: HTheme): HTheme {
        val f = ALL.firstOrNull { it.id == chosen[t.id] }?.takeIf { it.id != THEME } ?: return t
        return t.copy(title = f.family, head = f.family, num = f.family, headScale = f.headScale, numScale = f.numScale)
    }
}

val THEMES: List<HTheme> = listOf(
    // ---------- Clairs ----------
    HTheme("clair", "Clair", false, c(0xFF2FC45B), c(0xFFEEF0F3), c(0xFFE2F6E8), c(0xFFFFFFFF), c(0xFFE7EAEF), DARK_TXT,
        tag = "Sobre et net"),
    HTheme("voyage", "Carnet de voyage", false, c(0xFFA0522D), c(0xFFF3E9D2), c(0xFFE8D6AE), c(0xFFFBF4E3), c(0xFFEADBB9), c(0xFF3B2A1A),
        accent2 = c(0xFF2F6F7E), title = F_TYPE, head = F_TYPE, num = F_PLAYFAIR, numScale = 1.05f,
        decor = Decor.PAPER, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.STAMP, radius = 6, border = c(0x553B2A1A), cardAlpha = .94f,
        tag = "Papier, encre et tampons"),
    HTheme("bonbon", "Bonbon Pop", false, c(0xFFFF3D8B), c(0xFFFFF0F7), c(0xFFFFD1E6), c(0xFFFFFFFF), c(0xFFFFE3F0), c(0xFF2A0F22),
        accent2 = c(0xFF00C2D1), title = F_LUCKY, head = F_LUCKY, num = F_LUCKY, headScale = 1.05f, numScale = .9f,
        decor = Decor.DOTS, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.HARD, radius = 22, border = c(0xFF2A0F22),
        tag = "BD, trames et ombres dures"),
    HTheme("guinguette", "Guinguette", false, c(0xFFD62828), c(0xFFFFF8F0), c(0xFFFFE1DA), c(0xFFFFFFFF), c(0xFFFBE6E1), c(0xFF2B1010),
        accent2 = c(0xFF1D3557), title = F_ALFA, head = F_ALFA, num = F_ALFA, headScale = .95f, numScale = .85f,
        decor = Decor.VICHY, keyShape = KeyShape.SQUARE, keyStyle = KeyStyle.HARD, radius = 10, border = c(0xFF2B1010),
        tag = "Nappe vichy et bal musette"),
    HTheme("retro", "Rétro 70s", false, c(0xFFE76F2E), c(0xFFFBEBD3), c(0xFFF6C987), c(0xFFFFF6E8), c(0xFFF4DDBB), c(0xFF3D2314),
        accent2 = c(0xFFE9B23B), title = F_YELLOW, head = F_ALFA, num = F_ALFA, headScale = .9f, numScale = .85f,
        decor = Decor.SUNBURST, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.FILLED, radius = 26,
        tag = "Soleil, rayures et pattes d'eph"),
    HTheme("fierte", "Fierté", false, c(0xFF8E3FD8), c(0xFFFBF7FF), c(0xFFF1E6FF), c(0xFFFFFFFF), c(0xFFF0E8FA), c(0xFF22142E),
        accent2 = c(0xFFFF8C00), title = F_PACIFICO, head = F_RUSSO, num = F_RUSSO, headScale = .95f, numScale = .9f,
        decor = Decor.RAINBOW, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.GLASS, radius = 22, cardAlpha = .94f,
        tag = "Arc-en-ciel et paillettes"),
    HTheme("sakura", "Sakura", false, c(0xFFE0527E), c(0xFFFDF1F5), c(0xFFFAD2E0), c(0xFFFFFFFF), c(0xFFFBE3EC), c(0xFF2B1520),
        accent2 = c(0xFF7A9E7E), title = F_VIBES, head = F_PLAYFAIR, num = F_PLAYFAIR, headScale = 1.1f, numScale = 1.05f,
        decor = Decor.PETALS, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.GLASS, radius = 24, cardAlpha = .94f,
        tag = "Pétales qui tombent"),
    HTheme("plage", "Bord de mer", false, c(0xFF1E88C7), c(0xFFEFF8FC), c(0xFFBFE6F5), c(0xFFFFFFFF), c(0xFFDDEFF7), c(0xFF0D2433),
        accent2 = c(0xFFF2B950), title = F_LOBSTER, head = F_LOBSTER, num = F_BEBAS, headScale = 1.05f, numScale = 1.15f,
        decor = Decor.WAVES, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.GLASS, radius = 22, cardAlpha = .94f,
        tag = "Vagues et sable chaud"),

    // ---------- Sombres ----------
    HTheme("amoled", "AMOLED", true, c(0xFF2FC45B), c(0xFF000000), c(0xFF000000), c(0xFF111318), c(0xFF1C1F26), LIGHT_TXT,
        keyStyle = KeyStyle.OUTLINE, tag = "Noir pur, économe"),
    HTheme("minuit", "Minuit", true, c(0xFF4C9AFF), c(0xFF15171C), c(0xFF1F2633), c(0xFF1F2229), c(0xFF2A2E37), LIGHT_TXT,
        tag = "Sombre et sobre"),
    HTheme("synthwave", "Synthwave", true, c(0xFFFF2E97), c(0xFF0D0221), c(0xFF2A0845), c(0xFF1A0B33), c(0xFF2A1450), c(0xFFFBE9FF),
        accent2 = c(0xFF2DE2E6), title = F_MONOTON, head = F_ORBITRON, num = F_ORBITRON, headScale = .9f, numScale = .82f,
        decor = Decor.GRID, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.NEON, radius = 14, border = c(0x88FF2E97), glow = true, cardAlpha = .94f,
        tag = "Néon, soleil couchant et grille 80s"),
    HTheme("cabaret", "Cabaret", true, c(0xFFE8B54A), c(0xFF1C0306), c(0xFF4A0710), c(0xFF2C070C), c(0xFF3D0D14), c(0xFFFCEFD9),
        accent2 = c(0xFFB3122E), title = F_LIME, head = F_LIME, num = F_LIME, headScale = .95f, numScale = .9f,
        decor = Decor.CURTAIN, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.OUTLINE, radius = 20, border = c(0x88E8B54A), glow = true, cardAlpha = .94f,
        tag = "Rideau de velours, dorures"),
    HTheme("gala", "Gala", true, c(0xFFD4AF37), c(0xFF0A0A0A), c(0xFF1E1A10), c(0xFF151412), c(0xFF22201A), c(0xFFF6EED8),
        accent2 = c(0xFFFFF3C4), title = F_CINZEL, head = F_CINZEL, num = F_CINZEL, numScale = .95f,
        decor = Decor.DECO, keyShape = KeyShape.CUT, keyStyle = KeyStyle.OUTLINE, radius = 4, border = c(0x99D4AF37), cardAlpha = .94f,
        tag = "Noir et or, Art déco"),
    HTheme("arcade", "Arcade", true, c(0xFFFFD60A), c(0xFF0B0B2B), c(0xFF1A1A55), c(0xFF15153F), c(0xFF21215A), c(0xFFF0F0FF),
        accent2 = c(0xFFFF4FD8), title = F_PIXEL, head = F_PIXEL, num = F_PIXEL, headScale = .62f, numScale = .55f,
        decor = Decor.PIXELS, keyShape = KeyShape.SQUARE, keyStyle = KeyStyle.HARD, radius = 2, border = c(0xFFFFD60A),
        tag = "Pixels et envahisseurs"),
    HTheme("terminal", "Terminal", true, c(0xFF33FF66), c(0xFF020A04), c(0xFF06200D), c(0xFF07140A), c(0xFF0C2212), c(0xFFB8FFC9),
        accent2 = c(0xFFFFB000), title = F_VT, head = F_VT, num = F_VT, headScale = 1.35f, numScale = 1.3f,
        decor = Decor.SCANLINES, keyShape = KeyShape.SQUARE, keyStyle = KeyStyle.NEON, radius = 2, border = c(0x8833FF66), glow = true,
        tag = "Écran cathodique vert"),
    HTheme("pirate", "Pirate", true, c(0xFFE3B95F), c(0xFF0C1A22), c(0xFF22343B), c(0xFF1C2328), c(0xFF2A3338), c(0xFFF2E6CC),
        accent2 = c(0xFFB33A3A), title = F_PIRATA, head = F_PIRATA, num = F_PIRATA, headScale = 1.2f, numScale = 1.05f,
        decor = Decor.WAVES, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.STAMP, radius = 10, border = c(0x77E3B95F), cardAlpha = .94f,
        tag = "Mer sombre et parchemin"),
    HTheme("galaxie", "Galaxie", true, c(0xFF9D8CFF), c(0xFF07051A), c(0xFF22165C), c(0xFF15113A), c(0xFF211B50), c(0xFFEEEAFF),
        accent2 = c(0xFFFF7AD9), title = F_RUSSO, head = F_RUSSO, num = F_ORBITRON, numScale = .85f,
        decor = Decor.STARS, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.GLASS, radius = 22, glow = true, cardAlpha = .94f,
        tag = "Étoiles qui scintillent"),
    HTheme("plongee", "Plongée", true, c(0xFF3FD8E8), c(0xFF031628), c(0xFF0E5F80), c(0xFF0A2236), c(0xFF12324A), c(0xFFE3F6FA),
        accent2 = c(0xFFFF8A65), title = F_PACIFICO, head = F_RUSSO, num = F_BEBAS, numScale = 1.2f,
        decor = Decor.REEF, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.GLASS, radius = 24, cardAlpha = .94f, decorAlpha = .85f,
        tag = "Requin-baleine, mantas, tortues, murène et récif"),
    HTheme("oceandrive", "Ocean Drive", true, c(0xFFFF3EA5), c(0xFF140C26), c(0xFF2B1055), c(0xFF221437), c(0xFF301C4A), c(0xFFFFF0F7),
        accent2 = c(0xFF2DE2C8), title = F_YELLOW, head = F_BEBAS, num = F_BEBAS, headScale = 1.25f, numScale = 1.2f,
        decor = Decor.VICE, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.NEON, radius = 18, border = c(0x88FF3EA5), glow = true, cardAlpha = .94f,
        tag = "Coucher de soleil, palmiers et néons de Floride"),
    HTheme("abysses", "Abysses", true, c(0xFF2EE6D6), c(0xFF02101A), c(0xFF06344A), c(0xFF0A2232), c(0xFF113247), c(0xFFE2F7FA),
        accent2 = c(0xFF6FA8FF), title = F_BEBAS, head = F_BEBAS, num = F_BEBAS, headScale = 1.25f, numScale = 1.2f,
        decor = Decor.BUBBLES, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.GLASS, radius = 24, cardAlpha = .94f,
        tag = "Bulles et rayons sous l'eau"),
    HTheme("braise", "Braise", true, c(0xFFFF7A1A), c(0xFF120805), c(0xFF3D1607), c(0xFF22120B), c(0xFF34200F), c(0xFFFFEEDD),
        accent2 = c(0xFFFFD166), title = F_BEBAS, head = F_BEBAS, num = F_BEBAS, headScale = 1.25f, numScale = 1.2f,
        decor = Decor.EMBERS, keyShape = KeyShape.SQUIRCLE, keyStyle = KeyStyle.NEON, radius = 16, glow = true, cardAlpha = .94f,
        tag = "Étincelles qui montent"),
    // ---------- Aventure ----------
    HTheme("legende", "Légende", true, c(0xFFE8C15A), c(0xFF0B1630), c(0xFF1F3A2E), c(0xFF132235), c(0xFF1C3148), c(0xFFF6EFD8),
        accent2 = c(0xFF7FE7FF), title = F_CINZEL, head = F_CINZEL, num = F_CINZEL, headScale = 1.05f, numScale = 1.05f,
        decor = Decor.LEGEND, keyShape = KeyShape.CIRCLE, keyStyle = KeyStyle.OUTLINE, radius = 14, border = c(0x99E8C15A), glow = true, cardAlpha = .94f,
        tag = "Plaine au crépuscule, fées et notes d'ocarina"),
    HTheme("quete", "Quête 8-bit", false, c(0xFF2F7D32), c(0xFFDDF2C6), c(0xFFB9E39A), c(0xFFF8FCEB), c(0xFFE6F3D2), c(0xFF1F2A14),
        accent2 = c(0xFFE04848), title = F_PIXEL, head = F_PIXEL, num = F_VT, headScale = .8f, numScale = 1.35f,
        decor = Decor.QUEST, keyShape = KeyShape.SQUARE, keyStyle = KeyStyle.HARD, radius = 4, border = c(0xFF1F2A14), cardAlpha = .95f,
        tag = "Herbe, arbres et cœurs en pixels, vue de dessus"),
)

/** Anciens identifiants → nouveaux thèmes. */
private val ALIAS = mapOf(
    "lavande" to "fierte", "menthe" to "plage", "peche" to "retro", "glacier" to "plage", "sable" to "voyage", "tournesol" to "retro",
    "solclair" to "voyage", "ocean" to "plongee", "foret" to "terminal", "dracula" to "galaxie", "nord" to "minuit", "cyber" to "synthwave",
    "rubis" to "cabaret", "neon" to "synthwave", "solsombre" to "abysses", "aurore" to "galaxie", "cafe" to "pirate"
)

/** "auto" = Clair ou AMOLED selon le système. */
fun resolveTheme(id: String, systemDark: Boolean): HTheme {
    val real = if (id == "auto") (if (systemDark) "amoled" else "clair") else ALIAS[id] ?: id
    return THEMES.firstOrNull { it.id == real } ?: THEMES.first()
}

fun HTheme.scheme(): ColorScheme {
    val onP = if (primary.luminance() > 0.45f) Color(0xFF111111) else Color.White
    val container = primary.copy(alpha = if (dark) .28f else .20f).compositeOver(surface)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onP, primaryContainer = container, onPrimaryContainer = text,
        secondary = accent2, onSecondary = if (accent2.luminance() > 0.45f) Color(0xFF111111) else Color.White, secondaryContainer = container, onSecondaryContainer = text,
        tertiary = accent2, onTertiary = onP,
        background = bg, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = variant, onSurfaceVariant = text.copy(alpha = .72f),
        surfaceTint = primary, outline = text.copy(alpha = .28f), outlineVariant = text.copy(alpha = .12f),
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = variant, surfaceBright = surface, surfaceDim = variant,
        inverseSurface = text, inverseOnSurface = surface
    )
}

/** Fond en dégradé propre au thème. */
fun HTheme.brush(): Brush = Brush.verticalGradient(0f to top, 0.55f to bg, 1f to bg)

internal fun prefs(c: Context) = c.getSharedPreferences("hyperdial", Context.MODE_PRIVATE)
internal fun loadTheme(c: Context) = prefs(c).getString("theme", "auto") ?: "auto"
internal fun saveTheme(c: Context, v: String) { prefs(c).edit().putString("theme", v).apply() }
internal fun loadInst(c: Context) = prefs(c).getString("inst", "piano") ?: "piano"
internal fun saveInst(c: Context, v: String) { prefs(c).edit().putString("inst", v).apply() }

/** Applique le thème (couleurs, barre d'état), dessine le fond et son décor. */
@Composable
fun HyperTheme(themeId: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.remember { I18n.ensure(ctx); ThemeFonts.load(ctx) }
    val t = ThemeFonts.apply(resolveTheme(themeId, isSystemInDarkTheme()))
    val view = LocalView.current
    SideEffect {
        (ctx as? Activity)?.window?.let { w ->
            w.statusBarColor = t.top.toArgb()
            w.navigationBarColor = t.surface.toArgb()
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !t.dark
                isAppearanceLightNavigationBars = !t.dark
            }
        }
    }
    CompositionLocalProvider(LocalHTheme provides t) {
        MaterialTheme(colorScheme = t.scheme()) {
            Box(Modifier.fillMaxSize().background(t.brush())) {
                DecorLayer(t, Modifier.fillMaxSize())
                if (t.scrim > 0f) Box(Modifier.fillMaxSize().background(t.bg.copy(alpha = t.scrim)))
                // ombre douce sous le texte quand un décor sombre est derrière
                val legible = if (t.dark && t.decor != Decor.NONE) androidx.compose.material3.LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(0f, 1.5f), 6f))
                    else androidx.compose.material3.LocalTextStyle.current
                // Android 15+ avec une appli qui le vise (version Play) : l'écran passe sous les barres système,
                // on garde le décor en plein écran mais le contenu (et le clavier virtuel) hors des barres
                val edge = androidx.compose.runtime.remember { ctx.applicationInfo.targetSdkVersion >= 35 && android.os.Build.VERSION.SDK_INT >= 35 }
                Box(if (edge) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxSize()) {
                    CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides legible, androidx.compose.material3.LocalContentColor provides t.text) { content() }
                }
            }
        }
    }
}
