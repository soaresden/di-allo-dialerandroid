package fr.soaresden.diallo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

internal fun autoSpeakerSummary(c: AutoSpeaker.Cfg) =
    if (!c.on) tr("Désactivé") else tr("Activé") + " · " + (if (c.flat) tr("posé à plat") else tr("loin de l'oreille")) + " · ${"%.1f".format(I18n.locale, c.delayMs / 1000.0)} s"

/** Sous-écran des réglages : haut-parleur automatique (ex-appli « Auto Haut-Parleur »). */
@Composable
internal fun AutoSpeakerSettings() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var cfg by remember { mutableStateOf(AutoSpeaker.cfg(ctx)) }
    fun set(c: AutoSpeaker.Cfg) { cfg = c; AutoSpeaker.save(ctx, c) }

    // Test en direct des capteurs tant que l'écran est ouvert (sans toucher à l'audio)
    DisposableEffect(Unit) { AutoSpeaker.startTest(ctx); onDispose { AutoSpeaker.stopTest() } }
    val near by AutoSpeaker.near
    val rest by AutoSpeaker.resting

    Text(
        tr("👂 À l'oreille → écouteur.  🖐️ Éloigné → haut-parleur, tout seul.\nC'est intégré à Di-Allô : plus besoin de l'appli Auto Haut-Parleur (désactive son service d'accessibilité pour qu'elles ne se battent pas)."),
        fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(bottom = 10.dp)
    )

    Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.clickable { set(cfg.copy(on = !cfg.on)) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Haut-parleur automatique"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
                Text(if (cfg.on) tr("Actif à chaque appel décroché") else tr("Tu peux aussi l'activer pendant un appel (bouton « Auto HP »)"), fontSize = 12.sp, color = muted())
            }
            Switch(cfg.on, { set(cfg.copy(on = it)) })
        }
    }

    SectionCard(tr("Quand passer en haut-parleur ?"), id = "Quand passer en haut-parleur ?", collapsible = false) {
        Choice(tr("🖐️ Dès que je l'éloigne de l'oreille"), tr("Même tenu en main ou incliné"), !cfg.flat) { set(cfg.copy(flat = false)) }
        Spacer(Modifier.height(8.dp))
        Choice(tr("📱 Seulement posé à plat et immobile"), tr("Sur une table, écran vers le haut"), cfg.flat) { set(cfg.copy(flat = true)) }
    }

    SectionCard(tr("Délai avant haut-parleur"), id = "Délai avant haut-parleur", collapsible = false, action = { Text("${"%.1f".format(I18n.locale, cfg.delayMs / 1000.0)} s", fontWeight = FontWeight.Bold, color = cs.primary) }) {
        Slider(cfg.delayMs.toFloat(), { set(cfg.copy(delayMs = (it / 100f).roundToLong() * 100)) }, valueRange = 200f..3000f, steps = 27)
        Text(tr("Plus court = plus réactif ; plus long = évite les bascules quand tu bouges un peu."), fontSize = 12.sp, color = muted())
    }

    SectionCard(tr("Retour à l'écouteur"), id = "Retour à l'écouteur", collapsible = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Dès que je le porte à l'oreille"), fontSize = 14.sp, color = cs.onSurface)
                Text(tr("Sinon le haut-parleur reste jusqu'à ce que tu le coupes"), fontSize = 12.sp, color = muted())
            }
            Switch(cfg.back, { set(cfg.copy(back = it)) })
        }
    }

    SectionCard(tr("🧪 Test en direct"), id = "🧪 Test en direct", collapsible = false) {
        Text(tr("Couvre le haut de l'écran avec ta main, pose le téléphone, reprends-le…"), fontSize = 12.sp, color = muted(), modifier = Modifier.padding(bottom = 8.dp))
        TestLine(tr("Proximité"), when (near) { true -> tr("👂 À l'oreille / couvert"); false -> tr("🖐️ Loin de l'oreille"); null -> tr("⏳ Capteur…") })
        TestLine(tr("Position"), if (rest) tr("📱 À plat et immobile") else tr("🤚 En main / en mouvement"))
        val result = when {
            near == true -> tr("Écouteur")
            near == null -> "—"
            !cfg.flat || rest -> tr("Haut-parleur (après {0} s)", "%.1f".format(I18n.locale, cfg.delayMs / 1000.0))
            else -> tr("Écouteur (pas posé à plat)")
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer).padding(10.dp)) {
            Text(tr("En appel → {0}", result), fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Choice(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(desc, fontSize = 12.sp, color = muted())
        }
        RadioButton(selected, onClick)
    }
}

@Composable
private fun TestLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontSize = 13.sp, color = muted(), modifier = Modifier.width(90.dp))
        Text(v, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
