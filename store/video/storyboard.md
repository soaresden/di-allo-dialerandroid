# 🎬 Vidéo Play Store : storyboard (FR / EN)

**Format** : 16:9, 1920×1080, **~45 s**, musique jouée sur le clavier de Di-Allô (mélodie originale, ocarina puis piano), pas de voix off : des **sous-titres à l'écran**, un fichier FR et un fichier EN.
**Mise en ligne** : Google Play ne prend qu'un **lien YouTube** (public ou non répertorié, publicités désactivées, pas de restriction d'âge).

> ⚠️ **À ne pas montrer dans la vidéo Play Store** :
> - l'**enregistrement des appels**, qui est mal vu par les règles de Google Play (la version Play n'aura sans doute pas le REC) ;
> - de **vrais contacts**. Il faut un *mode démo* avec de faux contacts et un faux journal d'appels avant de filmer.

| # | Durée | À l'écran | Texte FR | Text EN |
|---|---|---|---|---|
| 1 | 0–4 s | Logo 😎 qui fait son animation, le clavier joue 4 notes à l'ocarina | **Di-Allô** · Le téléphone qui dit allô. | **Di-Allô** · The phone app that says hello. |
| 2 | 4–10 s | On compose un numéro : chaque touche s'allume, s'enfonce et joue sa note | Chaque touche est une note. | Every key is a note. |
| 3 | 10–14 s | Choix de l'instrument : ocarina, piano, violon, 8-bit… | 38 instruments, du piano à l'ocarina. | 38 instruments, from piano to ocarina. |
| 4 | 14–19 s | Mode composeur : on tape une mélodie, elle se rejoue façon karaoké | Compose ta mélodie, partage-la. | Compose a tune, share it. |
| 5 | 19–26 s | Volet des thèmes à droite, ▶ fait défiler : Plongée, Ocean Drive, Synthwave, Légende, Quête 8-bit… | 23 thèmes, avec des décors animés. | 23 themes with animated backgrounds. |
| 6 | 26–31 s | Écran d'appel : double appel, notes, historique de la personne | Un écran d'appel complet. | A complete call screen. |
| 7 | 31–35 s | Icônes animées : téléphone loin de l'oreille → haut-parleur ; retourné → silence | Haut-parleur auto. Retourne-le pour couper la sonnerie. | Auto speaker. Flip it to silence the ringer. |
| 8 | 35–40 s | Stats : on touche une barre, elle brille, la page se filtre ; puis le Wrapped | Tes appels en stats. Et ton Wrapped de l'année. | Your calls in charts. And your yearly Wrapped. |
| 9 | 40–43 s | Widgets sur l'écran d'accueil, numérotation rapide, ⭐ sur un numéro | Widgets, numérotation rapide, favoris. | Widgets, speed dial, favourites. |
| 10 | 43–45 s | Logo + 6 drapeaux + badge Google Play | En 6 langues. Disponible sur Google Play. | In 6 languages. Get it on Google Play. |

## Fabrication prévue (sans rien installer)
1. **Mode démo dans l'appli** (réglage caché) : faux contacts (« Maman », « Léa », « Garage Martin »…), faux journal d'appels, fausses stats.
2. **Prises sur le téléphone** avec `adb screenrecord` (MP4 natif, 1 plan par scène).
3. **Montage dans une page HTML** : les plans dans un cadre de téléphone, les textes animés, la musique synthétisée comme dans l'appli. La page enregistre elle-même la vidéo (MediaRecorder) en `Di-Allo-promo-fr.mp4` et `Di-Allo-promo-en.mp4`.
4. Tu la mets sur YouTube (non répertoriée), et le lien va dans la fiche Play.

## Pour plus tard, même fiche Play
- Icône 512×512 (on a `docs/icon.png`)
- Bannière 1024×500 (feature graphic)
- 4 à 8 captures d'écran téléphone (elles viendront aussi du mode démo)
- Description courte (80 caractères) et longue, FR et EN
