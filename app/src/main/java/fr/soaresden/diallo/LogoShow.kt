package fr.soaresden.diallo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Petit spectacle quand on touche le logo : le smiley décroche, met ses lunettes,
 * dit « Di-Allô ! » puis fait un clin d'oeil. (Emoji 3D Fluent, Microsoft, licence MIT.)
 * Touche n'importe où pour fermer.
 */
@Composable
internal fun LogoShow(onDone: () -> Unit) {
    val th = LocalHTheme.current
    val face = remember { Animatable(0f) }      // apparition du visage
    val phone = remember { Animatable(0f) }     // le combiné arrive à l'oreille
    val glasses = remember { Animatable(0f) }   // les lunettes descendent
    val bubble = remember { Animatable(0f) }    // bulle « Di-Allô ! »
    val wink = remember { Animatable(0f) }      // lunettes relevées + clin d'oeil
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        face.animateTo(1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow))
        phone.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        Synth.play("1")
        glasses.animateTo(1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessLow))
        launch { Synth.play("5"); delay(170); Synth.play("8"); delay(170); Synth.play("#") }
        bubble.animateTo(1f, spring(dampingRatio = .42f, stiffness = Spring.StiffnessMediumLow))
        delay(650)
        wink.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black.copy(alpha = .62f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onDone() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // l'animation, remontée en haut
            Box(Modifier.padding(top = 36.dp).size(250.dp), contentAlignment = Alignment.Center) {
                // visage : souriant, puis clin d'oeil
                val faceMod = Modifier.size(170.dp).graphicsLayer {
                    scaleX = face.value; scaleY = face.value
                    rotationZ = -8f * wink.value
                }
                Image(painterResource(R.drawable.fluent_grin), null, faceMod.graphicsLayer { alpha = 1f - wink.value })
                Image(painterResource(R.drawable.fluent_wink), null, faceMod.graphicsLayer { alpha = wink.value })
                // combiné qui monte à l'oreille
                Image(
                    painterResource(R.drawable.fluent_receiver), null,
                    Modifier.size(122.dp)
                        .offset(x = (-78).dp - 160.dp * (1 - phone.value), y = 24.dp + 260.dp * (1 - phone.value))
                        .graphicsLayer { rotationZ = -50f * (1 - phone.value) - 6f * wink.value; alpha = phone.value.coerceIn(0f, 1f) }
                )
                // lunettes qui tombent sur le nez, puis remontent sur le front
                Image(
                    painterResource(R.drawable.fluent_glasses), null,
                    Modifier.size(154.dp)
                        .offset(y = (-15).dp - 420.dp * (1 - glasses.value) - 50.dp * wink.value)
                        .graphicsLayer { rotationZ = -12f * wink.value; alpha = if (glasses.value <= 0f) 0f else 1f }
                )
                // bulle
                Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 4.dp),
                color = Color.White, shadowElevation = 8.dp,
                modifier = Modifier.offset(x = 70.dp, y = (-105).dp).graphicsLayer {
                    scaleX = bubble.value; scaleY = bubble.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                }
            ) {
                Text(
                    tr("Di-Allô !"), fontFamily = th.title, fontWeight = if (th.title == null) FontWeight.ExtraBold else FontWeight.Normal,
                    fontSize = 26.sp, color = Color(0xFF1B1B1F), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
                }
            }
            // juste en dessous : les nouveautés de chaque version
            Surface(
                Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
                    .graphicsLayer { alpha = bubble.value.coerceIn(0f, 1f) }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { },
                shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📜 " + tr("Les nouveautés"), fontFamily = th.head, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDone) { Icon(Icons.Filled.Close, tr("Fermer")) }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Changelog()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}
