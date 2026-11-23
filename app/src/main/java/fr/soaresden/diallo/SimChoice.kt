package fr.soaresden.diallo

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.CallLog
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.PhoneAccountSuggestion
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Double SIM : Di-Allô demande la SIM à chaque appel, sauf si une SIM est retenue pour ce contact (« Se souvenir »).
 * En voiture (Android Auto), personne ne peut répondre sur le téléphone : la SIM est choisie toute seule.
 */
internal object SimChoice {
    data class Sim(val handle: PhoneAccountHandle, val label: String, val carrier: String?, val iso: String?, val slot: Int) {
        /** « SIM 1 · Orange 🇧🇪 » */
        val title: String get() = listOfNotNull(if (slot >= 0) "SIM ${slot + 1}" else null, label.takeIf { it.isNotBlank() }).distinct().joinToString(" · ") +
            (iso?.takeIf { it.length == 2 }?.let { " " + Country(it, it).flag } ?: "")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sim_choice", Context.MODE_PRIVATE)
    private fun keyFor(number: String) = "c_" + keyOf(number).ifEmpty { number.filter { it.isDigit() } }

    /** Les SIM qui peuvent appeler (vide sans autorisation téléphone). */
    fun sims(ctx: Context): List<Sim> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return emptyList()
            val subs = ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            tm.callCapablePhoneAccounts.mapNotNull { h ->
                val acc = tm.getPhoneAccount(h) ?: return@mapNotNull null
                if (!acc.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) return@mapNotNull null
                val sub = subFor(ctx, h, subs)
                Sim(h, sub?.displayName?.toString()?.takeIf { it.isNotBlank() } ?: acc.label?.toString().orEmpty(),
                    sub?.carrierName?.toString(), sub?.countryIso?.uppercase(), sub?.simSlotIndex ?: -1)
            }.sortedBy { if (it.slot < 0) 99 else it.slot }
        } catch (_: Throwable) { emptyList() }
    }

    private fun subFor(ctx: Context, h: PhoneAccountHandle, subs: List<SubscriptionInfo>): SubscriptionInfo? {
        if (Build.VERSION.SDK_INT >= 30) try {
            val id = ctx.getSystemService(TelephonyManager::class.java).getSubscriptionId(h)
            subs.firstOrNull { it.subscriptionId == id }?.let { return it }
        } catch (_: Throwable) { }
        return subs.firstOrNull { it.subscriptionId.toString() == h.id || (it.iccId?.isNotEmpty() == true && it.iccId == h.id) }
    }

    /** SIM retenue pour ce numéro (« Se souvenir »), si elle est toujours là. */
    fun remembered(ctx: Context, number: String, among: List<Sim> = sims(ctx)): Sim? {
        if (number.none { it.isDigit() }) return null
        val id = prefs(ctx).getString(keyFor(number), null) ?: return null
        return among.firstOrNull { it.handle.id == id }
    }
    fun remember(ctx: Context, number: String, sim: Sim?) {
        if (number.none { it.isDigit() }) return
        prefs(ctx).edit().apply { if (sim == null) remove(keyFor(number)) else putString(keyFor(number), sim.handle.id) }.apply()
        version.intValue++
    }
    /** Numéro en attente du choix de la SIM (fenêtre affichée par l'écran principal). */
    val ask = mutableStateOf<String?>(null)

    /** Incrémenté quand une SIM retenue change (menus qui l'affichent). */
    val version = mutableIntStateOf(0)

    /** Faut-il demander la SIM avant d'appeler ce numéro ? */
    fun needsAsk(ctx: Context, number: String): Boolean { val s = sims(ctx); return s.size >= 2 && remembered(ctx, number, s) == null }

    /** Appelle avec cette SIM (null = celle du système). */
    fun call(ctx: Context, number: String, sim: Sim?) {
        try {
            val i = Intent(Intent.ACTION_CALL, telUri(number)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != null) i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
            ctx.startActivity(i)
        } catch (_: Throwable) { }
    }

    fun isCarMode(ctx: Context): Boolean = try {
        ctx.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    } catch (_: Throwable) { false }

    /** SIM proposées par Android pour cet appel (en attente de choix). */
    fun forCall(ctx: Context, call: Call): List<Sim> {
        val all = sims(ctx)
        @Suppress("DEPRECATION")
        val avail = try { call.details?.extras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS) } catch (_: Throwable) { null }
        return if (avail.isNullOrEmpty()) all else all.filter { s -> avail.any { it.id == s.handle.id } }.ifEmpty { all }
    }

    /**
     * Appel parti d'ailleurs (Android Auto, widget, autre appli) et Android demande la SIM :
     * SIM retenue pour le contact, sinon en voiture la plus probable ; sinon le choix s'affiche sur l'écran d'appel.
     */
    fun onSelectNeeded(ctx: Context, call: Call) {
        val number = HyperInCallService.numberOf(call)
        val sims = forCall(ctx, call)
        val pick = remembered(ctx, number, sims) ?: if (isCarMode(ctx)) likely(ctx, call, number, sims) else null
        pick?.let { try { call.phoneAccountSelected(it.handle, false) } catch (_: Throwable) { } }
    }

    /** La SIM la plus probable, sans rien demander (voiture). */
    fun likely(ctx: Context, call: Call?, number: String, sims: List<Sim>): Sim? {
        if (sims.isEmpty()) return null
        if (sims.size == 1) return sims[0]
        // 1. ce que l'utilisateur a réglé dans Android pour ce contact
        @Suppress("DEPRECATION")
        val sugg = try { call?.details?.extras?.getParcelableArrayList<PhoneAccountSuggestion>(Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS) } catch (_: Throwable) { null }.orEmpty()
        sugg.firstOrNull { it.shouldAutoSelect() || it.reason == PhoneAccountSuggestion.REASON_USER_SET }
            ?.let { s -> sims.firstOrNull { it.handle.id == s.phoneAccountHandle.id } }?.let { return it }
        // 2. numéro étranger : la SIM de ce pays (numéro belge → SIM belge)
        countryOf(number)?.iso?.uppercase()?.let { iso -> sims.filter { it.iso == iso }.singleOrNull()?.let { return it } }
        // 3. la SIM du dernier appel avec ce numéro
        lastUsed(ctx, number)?.let { id -> sims.firstOrNull { it.handle.id == id }?.let { return it } }
        // 4. suggestion d'Android (même opérateur, appels fréquents…)
        sugg.firstOrNull()?.let { s -> sims.firstOrNull { it.handle.id == s.phoneAccountHandle.id } }?.let { return it }
        // 5. SIM par défaut pour les appels, sinon la première
        val def = try { SubscriptionManager.getDefaultVoiceSubscriptionId() } catch (_: Throwable) { -1 }
        if (def >= 0) {
            val subs = ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            sims.firstOrNull { subFor(ctx, it.handle, subs)?.subscriptionId == def }?.let { return it }
        }
        return sims[0]
    }

    private fun lastUsed(ctx: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val key = keyOf(number).ifEmpty { return null }
        return try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.PHONE_ACCOUNT_ID), null, null, CallLog.Calls.DATE + " DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) if (keyOf(c.getString(0) ?: "") == key) return@use c.getString(1)?.takeIf { it.isNotBlank() }
                null
            }
        } catch (_: Throwable) { null }
    }
}

/** Fenêtre « Avec quelle SIM ? » pour un appel lancé dans Di-Allô sans passer par « Appeler ? ». */
@Composable
internal fun SimAskDialog() {
    val ctx = LocalContext.current
    val num = SimChoice.ask.value ?: return
    val sims = remember(num) { SimChoice.sims(ctx) }
    val guess = remember(num) { SimChoice.likely(ctx, null, num, sims) }
    AlertDialog(
        onDismissRequest = { SimChoice.ask.value = null },
        title = { Text(tr("Appeler avec quelle SIM ?")) },
        text = {
            Column {
                Text(phoneText(num), fontSize = 14.sp, color = muted())
                Spacer(Modifier.height(10.dp))
                SimButtons(num, sims, guess) { s -> SimChoice.ask.value = null; SimChoice.call(ctx, num, s) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { SimChoice.ask.value = null }) { Text(tr("Annuler")) } }
    )
}

/** Choix de la SIM : un bouton par SIM + « Se souvenir pour ce contact ». */
@Composable
internal fun SimButtons(number: String, sims: List<SimChoice.Sim>, suggested: SimChoice.Sim?, onPick: (SimChoice.Sim) -> Unit) {
    val ctx = LocalContext.current
    var keep by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        sims.forEach { s ->
            val main = s == suggested
            val content: @Composable RowScope.() -> Unit = {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
            val act = { if (keep) SimChoice.remember(ctx, number, s); onPick(s) }
            if (main) Button(onClick = act, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = ButtonDefaults.buttonColors(containerColor = CallGreen, contentColor = androidx.compose.ui.graphics.Color.White), content = content)
            else OutlinedButton(onClick = act, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), content = content)
        }
        if (number.any { it.isDigit() }) Row(Modifier.fillMaxWidth().clickable { keep = !keep }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(keep, { keep = it })
            Text(tr("Se souvenir pour ce contact"), fontSize = 14.sp)
        }
    }
}

/**
 * Pour [DialloCallRedirectionService] (Java) : SIM à imposer à un appel sortant, ou null pour garder celle d'Android.
 * En voiture avec deux SIM : SIM retenue pour le contact, sinon la plus probable (personne ne peut répondre sur le téléphone).
 */
fun carSimFor(ctx: Context, number: String): PhoneAccountHandle? {
    if (!SimChoice.isCarMode(ctx)) return null
    val sims = SimChoice.sims(ctx)
    if (sims.size < 2) return null
    return (SimChoice.remembered(ctx, number, sims) ?: SimChoice.likely(ctx, null, number, sims))?.handle
}
