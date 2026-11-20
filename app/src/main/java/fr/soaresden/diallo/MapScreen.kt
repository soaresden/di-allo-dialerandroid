package fr.soaresden.diallo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/** Carte des adresses de tes contacts (OpenStreetMap). [onOpen] ouvre la fiche du contact. */
@Composable
internal fun ContactsMap(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    var places by remember { mutableStateOf<List<AddressRepository.Place>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var todo by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(0) }
    var missing by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<List<AddressRepository.Place>?>(null) }
    var fitRequest by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val raws = withContext(Dispatchers.IO) { AddressRepository.rawAddresses(ctx) }
        total = raws.size
        val (known, rest) = withContext(Dispatchers.IO) { AddressRepository.cached(ctx, raws) }
        places = known; todo = rest.size; fitRequest++
        // Géocodage progressif : les points apparaissent au fur et à mesure
        val found = ArrayList(known)
        for (r in rest) {
            val p = withContext(Dispatchers.IO) { AddressRepository.geocode(ctx, r) }
            done++
            if (p != null) { found.add(p); if (done % 5 == 0 || done == rest.size) places = found.toList() } else missing++
        }
        places = found.toList()
        if (rest.isNotEmpty()) fitRequest++
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    // Plusieurs contacts à la même adresse -> un seul point
    val groups = remember(places) { places.groupBy { "%.4f,%.4f".format(it.lat, it.lon) }.values.toList() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                Configuration.getInstance().apply {
                    load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                    userAgentValue = c.packageName
                    osmdroidBasePath = c.cacheDir; osmdroidTileCache = java.io.File(c.cacheDir, "tiles")
                }
                MapView(c).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = 2.5; isTilesScaledToDpi = true
                    controller.setZoom(5.5); controller.setCenter(GeoPoint(46.6, 2.4))   // France
                }
            },
            update = { map ->
                map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
                // (re)crée les marqueurs si les groupes ont changé
                if (map.getTag(R_TAG) !== groups) {
                    map.overlays.removeAll { it is Marker }
                    groups.forEach { g ->
                        val p = g.first()
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(p.lat, p.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = pin(map.context, primary, if (g.size > 1) "${g.size}" else initialOf(p.raw.name))
                            title = g.joinToString { it.raw.name }
                            setOnMarkerClickListener { _, _ -> selected = g; true }
                        })
                    }
                    map.setTag(R_TAG, groups)
                    map.invalidate()
                }
                if (map.getTag(R_FIT) != fitRequest && groups.isNotEmpty()) {
                    map.setTag(R_FIT, fitRequest)
                    val pts = groups.map { GeoPoint(it.first().lat, it.first().lon) }
                    map.post {
                        if (pts.size == 1) { map.controller.setZoom(14.0); map.controller.setCenter(pts[0]) }
                        else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.25f), false)
                    }
                }
            },
            onRelease = { it.onDetach() }
        )

        // ---- Barre du haut ----
        Surface(Modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f), shadowElevation = 4.dp) {
            Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = MaterialTheme.colorScheme.onSurface) }
                Column(Modifier.weight(1f)) {
                    Text("🗺️ Carte des contacts", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            total == 0 -> "Aucune adresse dans tes contacts"
                            done < todo -> "Localisation des adresses… $done / $todo"
                            else -> "${places.size} adresses sur la carte" + if (missing > 0) " · $missing introuvables" else ""
                        }, fontSize = 11.5.sp, color = muted()
                    )
                }
                if (done < todo) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = { fitRequest++ }) { Icon(Icons.Filled.ZoomOutMap, "Tout voir", tint = MaterialTheme.colorScheme.primary) }
            }
        }

        // ---- Fiche du point touché ----
        selected?.let { g ->
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📍 " + g.first().raw.address, Modifier.weight(1f), fontSize = 12.5.sp, color = muted(.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { selected = null }) { Icon(Icons.Filled.Close, "Fermer", tint = muted()) }
                    }
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(g.size) { i ->
                            val p = g[i]
                            Row(Modifier.fillMaxWidth().clickable { onOpen(p.raw.contactId) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p.raw.name, p.raw.photo, 38.dp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.raw.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(p.raw.type ?: "Adresse", fontSize = 12.sp, color = muted())
                                }
                                Text("Fiche ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val R_TAG = 0x7f0a0001
private const val R_FIT = 0x7f0a0002

private fun initialOf(name: String) = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** Pastille ronde colorée avec une initiale (ou le nombre de contacts). */
private fun pin(ctx: Context, color: Int, label: String): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (label.length > 1 && label.all { it.isDigit() }) 34 else 30) * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x55000000; cv.drawCircle(size / 2f, size / 2f + d, size / 2f - d, p)
    p.color = android.graphics.Color.WHITE; cv.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * d, p)
    p.color = color; cv.drawCircle(size / 2f, size / 2f, size / 2f - 3.5f * d, p)
    p.color = android.graphics.Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
    p.textSize = size * (if (label.length > 2) .32f else .42f)
    cv.drawText(label, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(ctx.resources, bmp)
}
