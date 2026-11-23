# 📜 Nouveautés de Di-Allô

Les versions sont numérotées `AAAA-MM-JJ-NN` (date du build, puis numéro du build du jour).
Chaque version est téléchargeable dans les [Releases](../../releases).

## 2026-11-23-15
- 🔔 Messagerie : la notification d'un nouveau message vocal arrive bien, même quand Di-Allô est resté sur l'onglet Messagerie en arrière-plan
- 📞 Après un appel manqué ou refusé, Di-Allô va voir s'il y a un message (en plus de l'alerte que Free envoie)
- 📝 **Journal de la messagerie** : bouton « Envoyer le journal » (étapes, durées, erreurs ; jamais d'identifiant, de mot de passe ni de numéro) pour aider à corriger un souci
- Ligne Free jamais passée par l'appli de Free : Di-Allô demande lui-même l'activation de la messagerie visuelle

## 2026-11-23-11
- 📼 **Messagerie vocale visuelle Free dans Di-Allô** (nouvel onglet « Messagerie », avec une SIM Free) : la liste de tes messages vocaux, l'écoute directe, Rappeler. Remplace l'appli « Messagerie Vocale Visuelle » de Free, qui ne chargeait plus les messages (le serveur de Free met ~30 s à répondre, l'appli abandonnait avant)
- « Activer » demande l'accès à Free par un SMS gratuit au 2051, puis les messages se téléchargent par Internet (Wi-Fi ou réseau mobile, avec nouvel essai automatique si la connexion lâche)
- 🔔 Notification et pastille sur l'onglet quand un nouveau message arrive
- ⬇️ **Enregistrer** un message dans le dossier de travail (sous-dossier Messagerie) et **Partager** (WhatsApp, mail…)
- 🗑️ **Corbeille** : « Supprimer » met le message de côté sans l'effacer chez Free ; Restaurer, ou Supprimer définitivement (aussi chez Free)

## 2026-11-23-02
- 🎙️ Correction : le service d'enregistrement ne se lançait plus (« Le service ne répond pas ») sur certains téléphones après une mise à jour du système
- 🏪 Première version préparée pour le Play Store (bêta fermée), centrée sur les appels

## 2026-11-23-01
- 🐞 Correction : plantage possible avec le thème Carnet de voyage (touches « tampon ») sur certains téléphones

## 2026-11-22-21
- 🌍 **Di-Allô s'adapte au pays où tu es** (réseau connecté, sinon la SIM) : en Belgique, « 0470 12 34 56 » est lu comme un numéro belge (🇧🇪 +32 470 12 34 56, Mobile), plus comme un numéro français
- Le drapeau du réseau connecté s'affiche en haut de l'appli (touche-le pour le détail)
- Mobile / fixe / numéro spécial reconnus aussi en Belgique, aux Pays-Bas, en Allemagne, en Suisse, au Luxembourg, au Royaume-Uni, en Espagne, en Italie et au Portugal
- Stats : « pays étrangers » = hors du pays où tu es

## 2026-11-22-20
- 🔤 **Police au choix pour chaque thème** : dans le volet des thèmes, bouton **Aa** pour essayer 18 polices en direct (Bebas, Pixel, Machine à écrire, Great Vibes…). « Du thème » remet celle d'origine ; chaque thème garde la sienne

## 2026-11-22-19
- 🌍 Plus de mention « Étranger » sur l'écran d'appel ni dans les fiches contact : avec une SIM d'un autre pays ou deux SIM, c'était souvent faux. Le drapeau du pays reste affiché

## 2026-11-22-18
- 📞 Les numéros sont composés **sans espaces ni tirets** (« +32 470 12 34 56 » part en « +32470123456 »), exactement comme le téléphone de Google. Évite chez la personne appelée l'alerte rouge de Google « Ce n'est peut-être pas … » quand le numéro du contact était enregistré avec des espaces

## 2026-11-22-17
- 🐞 Correction : plantage possible de l'écran d'appel pendant que ça sonne
- 📞 La notification d'appel entrant affiche le **nom du contact** dès la première sonnerie
- La notification d'appel ne reste plus affichée toute seule après la fin de l'appel ou un plantage

## 2026-11-22-16
- 🎵 **Notes tenues** dans le mode composeur : garde le doigt sur une touche, la note dure (« 3- » = 2 temps, « 3-- » = 3 temps…). Le son tient tant que tu appuies (vents, cordes, orgue, synthés, 8-bit ; le piano et la guitare s'éteignent d'eux-mêmes)
- Les mélodies gardent la durée de chaque note et la rejouent ; dans le recueil, les « - » font vraiment durer les notes

## 2026-11-22-15
- ⬆️ **Mises à jour en arrière-plan** : Di-Allô vérifie GitHub toutes les ~6 h, même fermée, et envoie une notification quand une nouvelle version sort (un appui : nouveautés et installation)
- La mise à jour trouvée n'est plus oubliée quand on relance l'appli

## 2026-11-22-14
- 🏺 Nouvel instrument : **Ocarina** (son rond et pur, souffle à l'attaque, vibrato qui arrive doucement). 38 instruments

## 2026-11-22-13
- ⭐ **Appui long sur un numéro = favori** : dans la fiche d'un contact, un appui long sur un numéro l'ajoute aux favoris (ou l'en retire), avec une petite vibration
- ⭐ Récents : « Ajouter aux favoris » / « Retirer des favoris » dans le menu de l'appui long

## 2026-11-22-12
- ⬆️ **Mises à jour automatiques** : Di-Allô prévient quand une nouvelle version sort sur GitHub, montre les nouveautés, la télécharge et lance l'installation (désactivable dans Réglages › À propos)
- 🎨 **Choix du thème en direct** : un volet fin à droite, l'appli reste visible à gauche (le clavier d'abord). Boutons pour voir les autres écrans, ▶ pour les faire défiler, ↺ pour annuler
- 🗡️ Deux thèmes d'aventure : **Légende** (plaine au crépuscule, château, fées et notes d'ocarina) et **Quête 8-bit** (vue de dessus en pixels, arbres, mare, cœurs)
- 📊 **Stats** : toucher une barre la **sélectionne** (couleur différente qui brille) au lieu de changer de vue ; 🔍 Zoomer à la demande ; la période et les filtres actifs restent toujours en haut
- ⭐ **Favoris numéro par numéro** : ☆ à côté de chaque numéro dans Contacts, ✖ pour en retirer un. Les favoris du téléphone ne sont jamais modifiés
- 🧩 Widget Favoris : vue **Perso** (tes contacts, dans ton ordre)
- 😎 Toucher le logo : l'animation, puis **les nouveautés** juste en dessous

## 2026-11-22-10
- ⚡ **Clavier sans saccade** : la recherche T9 tourne en arrière-plan, les sons des 12 touches se calculent en parallèle, le chiffre s'allume sans recalculer l'écran
- 📊 **Stats interactives** : touche une barre (heure, jour, période), un sens d'appel, un pays, un type de ligne ou une région, toute la page se filtre. Les filtres s'empilent en puces en haut, et une carte 🔎 Détail liste les appels correspondants. Zoom jusqu'à l'heure dans une journée
- ☑️ **Fiche contact** : sélection multiple dans « Derniers appels » pour en supprimer plusieurs, et « Voir tous les appels »
- 🎙️ **REC** : état de la configuration tout en haut (Setup ✅, à terminer, désactivé), un appui pour la régler
- 🧩 **Widgets** : aperçu en direct sur un faux fond d'écran, texte « Afficher les prénoms » enfin lisible
- 🎨 Texte lisible dans toutes les cartes, quel que soit le thème

## 2026-11-22-09
- 🌍 **6 langues** : français, anglais, espagnol, allemand, italien, portugais (automatique ou au choix dans Réglages › Langue)
- 🧩 Réglages › Widgets : widget Favoris réglable (source, nombre, prénoms, style) et nouveau **widget contact 1×1**
- 📌 Envoyer un contact sur l'écran d'accueil depuis Récents ou Contacts
- ⚡ **Numérotation rapide** : appui long sur 2…9
- ☑️ Récents : sélection multiple pour supprimer plusieurs appels
- ✨ Page de présentation des fonctionnalités

## 2026-11-22-05
- 🎙️ Enregistrements : date complète, heure et durée sur chaque ligne et dans le lecteur

## 2026-11-22-04
- En-tête Di-Allô et numéro de version sur tous les onglets, clavier compris
- Sections des réglages repliables (Restaurer, Pendant l'appel…)

## 2026-11-22-02
- 🎨 **21 thèmes avec du caractère** : polices, forme et style des touches, décors animés (Plongée avec requin-baleine, mantas et tortues ; Ocean Drive au coucher du soleil ; Synthwave, Cabaret, Arcade, Terminal…)
- 😎 Nouveau logo 3D, avec une petite animation quand on le touche
- 🎙️ REC : suppression des enregistrements, tri par contact ou par date
- 📞 Numéros au format international (+33 6 12 34 56 78) avec mobile / fixe
- 🗺️ Carte : adresses mieux placées (code postal vérifié)
- 🎁 Wrapped disponible pour chaque année de l'historique
- 🎹 37 instruments, choix plus compact

## 2026-11-21-04
- 🎙️ **Enregistrement des appels en MP3**, les deux voix, sans root ni appli en plus : bouton 🔴 pendant l'appel ou automatique, fichiers rangés dans `Rec/` (`aaaa-mm-jj-hhmmss-NOM.mp3`)
- Nouvel onglet **REC** : enregistrements regroupés par contact, recherche, lecteur
- Fiche contact : ses enregistrements ; 🎙️ à côté des appels enregistrés dans Récents
- Correction : plantage à l'arrivée d'un appel sur Android 17

## 2026-11-21-01
- 🎵 **Mode composeur** : les mélodies prennent la place de la liste, les touches jouées s'écrivent, ␣ pause, 💾 garder, ⌫ corriger
- Lecture façon karaoké : la note en cours s'allume, barre d'avancement
- Mélodies modifiables, recueil repliable
- Récents : appui long → fiche du contact ou supprimer cet appel

## 2026-11-20-23
- Les touches jouées s'écrivent à l'écran, touche pause, ⌫ pour effacer la dernière note
- Mélodie modifiable à la fin, rythme joué ou régulier (tempo réglable)

## 2026-11-20-20
- Mélodies : colle directement « Nom : 22223 444445 … »
- 📖 Recueil de 10 airs du domaine public (Au clair de la lune, Frère Jacques, Ode à la joie…)
- Aide-mémoire touche → note

## 2026-11-20-17
- 🎼 Enregistreur de mélodies sur le clavier, avec le rythme
- Création en chiffres avec réglage du tempo, lecture animée, partage

## 2026-11-20-15
- Vraie animation d'appui sur les touches (enfoncement, rebond, halo, onde)
- Écran des sauvegardes avec un tableau Date / Appels / SMS
- Signé « by Soaresden »

## 2026-11-20-13
- Onglet Notes : plantage corrigé
- Son, vibration et chiffre dès que le doigt touche
- Rapport de plantage affiché au lancement suivant (Copier / Partager)

## 2026-11-20-11
- Restauration simplifiée : les sauvegardes du dossier de travail en un appui

## 2026-11-20-09
- 🤙 **Première version publique** : clavier musical et T9, écran d'appel complet (double appel, conférence, notes, historique, refus par SMS), haut-parleur automatique, décrocher à l'oreille, retourner pour couper la sonnerie, onglet Notes, stats et Wrapped, carte des contacts, widget Favoris, sauvegarde automatique appels + SMS/MMS
