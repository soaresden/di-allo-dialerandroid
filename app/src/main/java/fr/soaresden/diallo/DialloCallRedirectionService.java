package fr.soaresden.diallo;

import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;

/**
 * Rôle « redirection d'appel » : Android passe ici CHAQUE appel sortant, même ceux lancés depuis Android Auto,
 * un widget ou une autre appli, avant de le composer.
 * - Numéro composé sans espaces ni tirets (sinon l'alerte « Ce n'est peut-être pas … » de Google chez l'appelé).
 * - En voiture avec deux SIM : SIM retenue pour le contact, sinon la plus probable.
 * En Java : Android peut passer un compte null (« demander à chaque fois »), ce que Kotlin refuserait.
 */
public class DialloCallRedirectionService extends CallRedirectionService {
    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle initialPhoneAccount, boolean allowInteractiveResponse) {
        try {
            if (handle == null || !"tel".equals(handle.getScheme())) { placeCallUnmodified(); return; }
            String number = handle.getSchemeSpecificPart() == null ? "" : handle.getSchemeSpecificPart();
            String clean = PhoneNumberUtils.stripSeparators(number);
            if (clean == null || clean.isEmpty()) clean = number;
            PhoneAccountHandle target = SimChoiceKt.carSimFor(this, number);
            if (target == null) target = initialPhoneAccount;
            boolean changed = !clean.equals(number)
                    || (target != null && (initialPhoneAccount == null || !target.getId().equals(initialPhoneAccount.getId())));
            if (!changed || target == null) placeCallUnmodified();
            else redirectCall(Uri.fromParts("tel", clean, null), target, false);
        } catch (Throwable t) {
            try { placeCallUnmodified(); } catch (Throwable ignored) { }
        }
    }
}
