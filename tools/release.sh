#!/usr/bin/env bash
# Publie une nouvelle version de Di-Allô sur LA publication GitHub unique : on la modifie, on n'en crée pas d'autre.
#
#   tools/release.sh            → compile, enregistre les compteurs, remplace les APK, renomme la publication
#   tools/release.sh --dry-run  → montre seulement ce qui serait fait
#
# Le texte de la publication ne contient QUE les nouveautés : la section « ## <version> » de CHANGELOG.md,
# plus une ligne pour le fichier à télécharger. La présentation et l'installation sont dans le README.
#
# Avant de remplacer les APK, leurs compteurs de téléchargements (remis à zéro par GitHub) sont ajoutés à
# ../myhtml/dl-history.json, que le graphique de la page myhtml combine avec le compteur en direct.
# Ordre des fichiers : l'APK Di-Allô F d'abord, puis Di-Allo-Ancienne-version.apk EN DERNIER : les anciennes
# installations prennent le premier .apk de la liste, que GitHub classe du plus récent au plus ancien.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=soaresden/di-allo-dialerandroid
MYHTML=${MYHTML:-../myhtml}
DRY=${1:-}
run() { if [ "$DRY" = "--dry-run" ]; then echo "  [dry-run] $*"; else "$@"; fi; }

SDK=$(grep '^sdk.dir' local.properties | cut -d= -f2- | sed 's/\\:/:/; s/\\\\/\//g')
AAPT="$(ls -d "$SDK"/build-tools/*/ | tail -1)aapt.exe"

if [ "$DRY" != "--dry-run" ]; then
  ./gradlew.bat assembleGithubRelease assembleLegacyRelease bundlePlayRelease -q
fi
G=app/build/outputs/apk/github/release/app-github-release.apk
V=$("$AAPT" dump badging "$G" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")
P=$(unzip -p app/build/outputs/bundle/playRelease/app-play-release.aab base/manifest/AndroidManifest.xml | strings | grep -o '2026-[0-9-]*p\|20[0-9][0-9]-[0-9-]*p' | head -1)
echo "Version : $V (Play : $P)"

F="Di-Allo-github-$V.apk"; OLDAPK="Di-Allo-Ancienne-version.apk"; AAB="Di-Allo-playstore-$P.aab"
run rm -f Di-Allo-github-*.apk Di-Allo-playstore-*.aab "$OLDAPK"
run cp "$G" "$F"
run cp app/build/outputs/apk/legacy/release/app-legacy-release.apk "$OLDAPK"
run cp app/build/outputs/bundle/playRelease/app-play-release.aab "$AAB"

# texte de la publication : les nouveautés de cette version, prises dans CHANGELOG.md
NOTES=$(mktemp)
{
  echo "## 🆕 Nouveautés de Di-Allô ${V%f}"
  awk -v h="## ${V%f}" '$0==h {on=1; next} /^## / {on=0} on' CHANGELOG.md | sed '/^$/d'
  echo
  echo "📥 Di-Allô F : **\`$F\`**. Présentation, différences avec la version Play Store et installation (Play Protect à suspendre) : [README](https://github.com/$REPO#readme)."
} > "$NOTES"
grep -q '^- ' "$NOTES" || { echo "Pas de section « ## ${V%f} » dans CHANGELOG.md : écris les nouveautés d'abord."; exit 1; }
echo "--- texte de la publication ---"; cat "$NOTES"; echo "-------------------------------"

CUR=$(gh api "repos/$REPO/releases/latest" --jq .tag_name)
echo "Publication actuelle : $CUR"
[ "$CUR" = "v$V" ] && { echo "La publication est déjà en v$V : rien à faire."; exit 1; }

# 1. compteurs de la version remplacée → myhtml/dl-history.json
ROW=$(gh api "repos/$REPO/releases/latest" --jq '{v: (.tag_name|ltrimstr("v")), date: ([.assets[] | select(.name|contains("github")) | .created_at] | first // .published_at), n: ([.assets[] | select(.name|contains("github")) | .download_count] | add // 0), old: ([.assets[] | select(.name|contains("Ancienne")) | .download_count] | add // 0)}')
echo "Compteurs enregistrés : $ROW"
if [ "$DRY" != "--dry-run" ]; then
  python -X utf8 - "$MYHTML/dl-history.json" "$ROW" <<'PY'
import json, sys
p, row = sys.argv[1], json.loads(sys.argv[2])
d = json.load(open(p, encoding='utf-8'))
h = d.setdefault('di-allo-dialerandroid', [])
h[:] = [x for x in h if x['v'] != row['v']] + [row]
open(p, 'w', encoding='utf-8').write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
PY
fi

# 2. nouveau tag, la publication passe dessus, titre et notes
run git tag "v$V"
run git push -q origin "v$V"
run gh release edit "$CUR" --tag "v$V" --title "Di-Allô ${V%f} · Di-Allô F" --notes-file "$NOTES"

# 3. on remplace les fichiers : F d'abord, la version pont en dernier
for a in $(gh api "repos/$REPO/releases/latest" --jq '.assets[].name'); do run gh release delete-asset "v$V" "$a" -y; done
run gh release upload "v$V" "$F"
run gh release upload "v$V" "$OLDAPK"

# numéro de build du jour, avancé par la compilation
run git add version.properties
run git commit -q -m "version.properties ($V)" || true
run git push -q

# 4. l'ancien tag disparaît
run git push -q origin --delete "$CUR"
run git tag -d "$CUR"

# 5. historique des compteurs en ligne
run git -C "$MYHTML" add dl-history.json
run git -C "$MYHTML" commit -q -m "Di-Allô F : compteurs de $(echo "$ROW" | sed -n 's/.*"v":"\([^"]*\)".*/\1/p') enregistrés"
run git -C "$MYHTML" push -q

[ "$DRY" = "--dry-run" ] || gh api "repos/$REPO/releases/latest" --jq '"Publiée : " + .tag_name + " · " + ([.assets[].name] | join(", "))'
echo "AAB pour la Play Console : $AAB"
