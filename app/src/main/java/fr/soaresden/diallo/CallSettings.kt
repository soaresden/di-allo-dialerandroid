package fr.soaresden.diallo

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Réponses SMS rapides proposées sur un appel entrant (DEFAULT en français : traduites à l'affichage / à l'envoi via tr). */
object QuickReplies {
    val DEFAULT = listOf(
        "Je ne peux pas répondre, je te rappelle.",
        "En réunion, je te rappelle plus tard.",
        "Je suis au volant, je te rappelle.",
        "Envoie-moi un SMS stp.",
        "J'arrive !"
    )
    fun load(ctx: Context): List<String> = prefs(ctx).getString("qsms", null)?.split('\n')?.filter { it.isNotBlank() } ?: DEFAULT
    fun save(ctx: Context, l: List<String>) { prefs(ctx).edit().putString("qsms", l.joinToString("\n") { it.replace('\n', ' ') }).apply() }
}

internal fun gesturesSummary(c: RingGestures.Cfg): String {
    val on = listOfNotNull(if (c.raise) tr("décrocher à l'oreille") else null, if (c.flip) tr("retourner = silence") else null)
    return if (on.isEmpty()) tr("Désactivés") else on.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** Sous-écran : gestes pendant la sonnerie. */
@Composable
internal fun GestureSettings() {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(RingGestures.cfg(ctx)) }
    fun set(n: RingGestures.Cfg) { c = n; RingGestures.save(ctx, n) }
    Text(tr("Ça marche pendant que le téléphone sonne, sans toucher l'écran."), fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(bottom = 10.dp))
    SectionCard(tr("📞 Décrocher"), id = "📞 Décrocher", collapsible = false) {
        SwitchLine(tr("Porter à l'oreille pour décrocher"), tr("Sors-le de la poche ou prends-le, porte-le à l'oreille : c'est décroché. Ne se déclenche pas s'il reste dans la poche."), c.raise) { set(c.copy(raise = it)) }
    }
    SectionCard(tr("🔕 Couper la sonnerie"), id = "🔕 Couper la sonnerie", collapsible = false) {
        SwitchLine(tr("Retourner le téléphone"), tr("Écran contre la table : sonnerie et vibration coupées. L'appel continue en silence."), c.flip) { set(c.copy(flip = it)) }
        Spacer(Modifier.height(8.dp))
        SwitchLine(tr("Double vibration quand c'est fini"), tr("Quand l'appel coupé est terminé, 2 petites vibrations : tu peux le reprendre."), c.flipEndVib) { set(c.copy(flipEndVib = it)) }
    }
    SectionCard(tr("📳 Fin d'appel"), id = "📳 Fin d'appel", collapsible = false) {
        SwitchLine(tr("Double vibration à la fin de chaque appel"), tr("Pratique quand l'autre raccroche alors que le téléphone est à l'oreille."), c.endVib) { set(c.copy(endVib = it)) }
        TextButton(onClick = { RingGestures.doubleVibrate(ctx) }) { Text(tr("Tester la vibration")) }
    }
    Spacer(Modifier.height(16.dp))
}

/** Sous-écran : réponses SMS rapides. */
@Composable
internal fun QuickReplySettings() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(QuickReplies.load(ctx)) }
    var add by remember { mutableStateOf("") }
    fun set(l: List<String>) { list = l; QuickReplies.save(ctx, l) }
    Text(tr("Proposées sur un appel entrant (bouton 💬). L'appel est refusé et le SMS part via ton appli Messages."), fontSize = 12.5.sp, color = muted(.75f), modifier = Modifier.padding(bottom = 10.dp))
    SectionCard(tr("💬 Mes réponses"), id = "💬 Mes réponses", collapsible = false) {
        list.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(s), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = { set(list.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, tr("Supprimer"), tint = muted(.45f), modifier = Modifier.size(18.dp)) }
            }
        }
        OutlinedTextField(
            add, { add = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text(tr("Nouvelle réponse…")) }, singleLine = true, shape = RoundedCornerShape(14.dp),
            trailingIcon = { if (add.isNotBlank()) IconButton(onClick = { set(list + add.trim()); add = "" }) { Icon(Icons.Filled.Add, tr("Ajouter"), tint = MaterialTheme.colorScheme.primary) } }
        )
        TextButton(onClick = { set(QuickReplies.DEFAULT) }) { Text(tr("Remettre les réponses par défaut")) }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
internal fun SwitchLine(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 12.sp, color = muted())
        }
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange)
    }
}
