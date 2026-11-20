package fr.soaresden.diallo

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Un thème : fond en dégradé (top -> bg), surfaces, couleur d'accent. */
data class HTheme(
    val id: String, val name: String, val dark: Boolean,
    val primary: Color, val bg: Color, val top: Color,
    val surface: Color, val variant: Color, val text: Color
)

private fun c(v: Long) = Color(v)
private val DARK_TXT = c(0xFF13151B)
private val LIGHT_TXT = c(0xFFF3F5F9)

val THEMES: List<HTheme> = listOf(
    // --- Clairs ---
    HTheme("clair", "Clair", false, c(0xFF2FC45B), c(0xFFEEF0F3), c(0xFFE2F6E8), c(0xFFFFFFFF), c(0xFFE7EAEF), DARK_TXT),
    HTheme("lavande", "Lavande", false, c(0xFF7C4DFF), c(0xFFF3EFFC), c(0xFFDCCFFB), c(0xFFFFFFFF), c(0xFFEAE3FA), c(0xFF1E1530)),
    HTheme("sakura", "Sakura", false, c(0xFFE0527E), c(0xFFFDF1F5), c(0xFFFAD2E0), c(0xFFFFFFFF), c(0xFFFBE3EC), c(0xFF2B1520)),
    HTheme("menthe", "Menthe", false, c(0xFF12A37E), c(0xFFEDF8F3), c(0xFFC8EEDD), c(0xFFFFFFFF), c(0xFFDDF2E9), c(0xFF10241D)),
    HTheme("peche", "Pêche", false, c(0xFFF0682A), c(0xFFFFF4EC), c(0xFFFFD6BA), c(0xFFFFFFFF), c(0xFFFCE6D6), c(0xFF2A1A10)),
    HTheme("glacier", "Glacier", false, c(0xFF2D7FF9), c(0xFFEFF5FB), c(0xFFCFE4F8), c(0xFFFFFFFF), c(0xFFDFECF7), c(0xFF0F1C2B)),
    HTheme("sable", "Sable", false, c(0xFFB5832F), c(0xFFF7F1E5), c(0xFFEBDCBE), c(0xFFFFFCF5), c(0xFFEFE6D3), c(0xFF2B2416)),
    HTheme("tournesol", "Tournesol", false, c(0xFFE09C00), c(0xFFFFF9E8), c(0xFFFFE7A0), c(0xFFFFFFFF), c(0xFFF8EDC8), c(0xFF2A2410)),
    HTheme("solclair", "Solarized", false, c(0xFF268BD2), c(0xFFFDF6E3), c(0xFFEEE8D5), c(0xFFFFFBEF), c(0xFFEEE8D5), c(0xFF073642)),
    // --- Sombres ---
    HTheme("amoled", "AMOLED", true, c(0xFF2FC45B), c(0xFF000000), c(0xFF000000), c(0xFF111318), c(0xFF1C1F26), LIGHT_TXT),
    HTheme("minuit", "Minuit", true, c(0xFF4C9AFF), c(0xFF15171C), c(0xFF1F2633), c(0xFF1F2229), c(0xFF2A2E37), LIGHT_TXT),
    HTheme("ocean", "Océan", true, c(0xFF2EC4D6), c(0xFF06141F), c(0xFF0C3550), c(0xFF0E2233), c(0xFF15324A), c(0xFFE6F4FA)),
    HTheme("foret", "Forêt", true, c(0xFF5BD88A), c(0xFF0B1610), c(0xFF17382A), c(0xFF132219), c(0xFF1C3325), c(0xFFE7F5EC)),
    HTheme("dracula", "Dracula", true, c(0xFFBD93F9), c(0xFF1E1F29), c(0xFF34294A), c(0xFF2B2D3A), c(0xFF383A4A), c(0xFFF8F8F2)),
    HTheme("nord", "Nord", true, c(0xFF88C0D0), c(0xFF242933), c(0xFF34405A), c(0xFF2E3440), c(0xFF3B4252), c(0xFFECEFF4)),
    HTheme("cyber", "Cyberpunk", true, c(0xFFFF2E97), c(0xFF0A0A12), c(0xFF33104A), c(0xFF17121F), c(0xFF261C33), c(0xFFF4ECFF)),
    HTheme("rubis", "Rubis", true, c(0xFFFF4D6A), c(0xFF160709), c(0xFF45101A), c(0xFF241014), c(0xFF361A20), c(0xFFF9E9EC)),
    HTheme("galaxie", "Galaxie", true, c(0xFF8C7BFF), c(0xFF0B0820), c(0xFF2A1F66), c(0xFF16123A), c(0xFF221C4E), c(0xFFEEEAFF)),
    HTheme("neon", "Néon", true, c(0xFF39FF88), c(0xFF000000), c(0xFF002414), c(0xFF0A140F), c(0xFF12241A), c(0xFFE9FFF2)),
    HTheme("solsombre", "Solarized nuit", true, c(0xFF2AA198), c(0xFF002B36), c(0xFF0B4A58), c(0xFF073642), c(0xFF0F4352), c(0xFFEEE8D5)),
    HTheme("aurore", "Aurore", true, c(0xFF4FE3C1), c(0xFF0C1220), c(0xFF2B2358), c(0xFF151D30), c(0xFF1F2A42), c(0xFFEAF6F7)),
    HTheme("braise", "Braise", true, c(0xFFFF8A2B), c(0xFF140B06), c(0xFF45200A), c(0xFF21140C), c(0xFF33200F), c(0xFFFBEFE6)),
    HTheme("cafe", "Café", true, c(0xFFD4A373), c(0xFF1A1411), c(0xFF33251D), c(0xFF241B17), c(0xFF342822), c(0xFFF3E9E1)),
)

/** "auto" = Clair ou AMOLED selon le système. */
fun resolveTheme(id: String, systemDark: Boolean): HTheme =
    if (id == "auto") THEMES.first { it.id == if (systemDark) "amoled" else "clair" }
    else THEMES.firstOrNull { it.id == id } ?: THEMES.first()

fun HTheme.scheme(): ColorScheme {
    val onP = if (primary.luminance() > 0.45f) Color(0xFF111111) else Color.White
    val container = primary.copy(alpha = if (dark) .28f else .20f).compositeOver(surface)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onP, primaryContainer = container, onPrimaryContainer = text,
        secondary = primary, onSecondary = onP, secondaryContainer = container, onSecondaryContainer = text,
        tertiary = primary, onTertiary = onP,
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

/** Applique le thème (couleurs + barre d'état) et dessine le fond dégradé. */
@Composable
fun HyperTheme(themeId: String, content: @Composable () -> Unit) {
    val t = resolveTheme(themeId, isSystemInDarkTheme())
    val view = LocalView.current
    val ctx = LocalContext.current
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
    MaterialTheme(colorScheme = t.scheme()) {
        Box(Modifier.fillMaxSize().background(t.brush())) { content() }
    }
}
