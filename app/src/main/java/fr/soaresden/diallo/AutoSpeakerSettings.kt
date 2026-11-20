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
    if (!c.on) "Désactivé" else "Activé · " + (if (c.flat) "posé à plat" else "loin de l'oreille") + " · ${"%.1f".format(c.delayMs / 1000.0)} s"

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
        "👂 À l'oreille → écouteur.  🖐️ Éloigné → haut-parleur, tout seul.\nC'est intégré à Di-Allô : plus besoin de l'appli Auto Haut-Parleur (désactive son service d'accessibilité pour qu'elles ne se battent pas).",
        fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(bottom = 10.dp)
    )

    Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.clickable { set(cfg.copy(on = !cfg.on)) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Haut-parleur automatique", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
                Text(if (cfg.on) "Actif à chaque appel décroché" else "Tu peux aussi l'activer pendant un appel (bouton « Auto HP »)", fontSize = 12.sp, color = muted())
            }
            Switch(cfg.on, { set(cfg.copy(on = it)) })
        }
    }

    SectionCard("Quand passer en haut-parleur ?", collapsible = false) {
        Choice("🖐️ Dès que je l'éloigne de l'oreille", "Même tenu en main ou incliné", !cfg.flat) { set(cfg.copy(flat = false)) }
        Spacer(Modifier.height(8.dp))
        Choice("📱 Seulement posé à plat et immobile", "Sur une table, écran vers le haut", cfg.flat) { set(cfg.copy(flat = true)) }
    }

    SectionCard("Délai avant haut-parleur", collapsible = false, action = { Text("${"%.1f".format(cfg.delayMs / 1000.0)} s", fontWeight = FontWeight.Bold, color = cs.primary) }) {
        Slider(cfg.delayMs.toFloat(), { set(cfg.copy(delayMs = (it / 100f).roundToLong() * 100)) }, valueRange = 200f..3000f, steps = 27)
        Text("Plus court = plus réactif ; plus long = évite les bascules quand tu bouges un peu.", fontSize = 12.sp, color = muted())
    }

    SectionCard("Retour à l'écouteur", collapsible = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dès que je le porte à l'oreille", fontSize = 14.sp, color = cs.onSurface)
                Text("Sinon le haut-parleur reste jusqu'à ce que tu le coupes", fontSize = 12.sp, color = muted())
            }
            Switch(cfg.back, { set(cfg.copy(back = it)) })
        }
    }

    SectionCard("🧪 Test en direct", collapsible = false) {
        Text("Couvre le haut de l'écran avec ta main, pose le téléphone, reprends-le…", fontSize = 12.sp, color = muted(), modifier = Modifier.padding(bottom = 8.dp))
        TestLine("Proximité", when (near) { true -> "👂 À l'oreille / couvert"; false -> "🖐️ Loin de l'oreille"; null -> "⏳ Capteur…" })
        TestLine("Position", if (rest) "📱 À plat et immobile" else "🤚 En main / en mouvement")
        val result = when {
            near == true -> "Écouteur"
            near == null -> "—"
            !cfg.flat || rest -> "Haut-parleur (après ${"%.1f".format(cfg.delayMs / 1000.0)} s)"
            else -> "Écouteur (pas posé à plat)"
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer).padding(10.dp)) {
            Text("En appel → $result", fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer, fontSize = 14.sp)
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
