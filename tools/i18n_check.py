"""
Vérifie les traductions de Di-Allô.

  python tools/i18n_check.py                 → tout le code
  python tools/i18n_check.py Fichier.kt ...  → seulement ces fichiers

1. Chaque clé passée à tr("…") / trn(n, "…", "…") existe dans app/src/main/assets/i18n/*.json
   avec les 5 langues (en, es, de, it, pt) et les mêmes {0} {1}… que le français.
2. Liste les textes français qui semblent encore affichés sans tr() (indicatif).
3. Vérifie que chaque JSON est valide.
"""
import json, os, re, sys, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/fr/soaresden/diallo")
I18N = os.path.join(ROOT, "app/src/main/assets/i18n")
LANGS = ["en", "es", "de", "it", "pt"]

# littéral Kotlin simple : "..." avec échappements, sans ${...}
LIT = r'"((?:[^"\\$]|\\.|\$(?!\{))*)"'
TR = re.compile(r'\btr\(\s*' + LIT)
TRN = re.compile(r'\btrn\([^,()]*(?:\([^()]*\))?[^,()]*,\s*' + LIT + r'\s*,\s*' + LIT)

def unescape(s):
    return s.encode('utf-8').decode('unicode_escape').encode('latin-1').decode('utf-8') if '\\' in s else s

def ph(s): return sorted(set(re.findall(r'\{\d\}', s)))

def main():
    files = sys.argv[1:] or [os.path.relpath(p, SRC) for p in glob.glob(os.path.join(SRC, "**/*.kt"), recursive=True)]
    table = {}; bad_json = []
    for f in glob.glob(os.path.join(I18N, "*.json")):
        try:
            with open(f, encoding="utf-8") as h: d = json.load(h)
            for k, v in d.items(): table[k] = v
        except Exception as e: bad_json.append(f"{os.path.basename(f)}: {e}")
    missing, wrong_ph, suspects = [], [], []
    for rel in files:
        p = os.path.join(SRC, rel)
        if not os.path.exists(p): print("introuvable :", rel); continue
        src = open(p, encoding="utf-8").read()
        keys = [unescape(m.group(1)) for m in TR.finditer(src)]
        for m in TRN.finditer(src): keys += [unescape(m.group(1)), unescape(m.group(2))]
        for k in keys:
            v = table.get(k)
            if v is None: missing.append(f"{rel}: {k!r}"); continue
            for l in LANGS:
                if not v.get(l): missing.append(f"{rel}: {k!r} [{l}]")
                elif ph(v[l]) != ph(k): wrong_ph.append(f"{rel}: {k!r} [{l}] {v[l]!r}")
        for i, line in enumerate(src.splitlines(), 1):
            if re.search(r'(Text|title|label|text|contentDescription|placeholder)\s*[(=]\s*\{?\s*(Text\()?"[^"]*[A-Za-zÀ-ÿ]{3,}', line) and 'tr(' not in line and 'trn(' not in line:
                suspects.append(f"{rel}:{i}: {line.strip()[:140]}")
    for b in bad_json: print("JSON INVALIDE", b)
    print(f"{len(missing)} clé(s) manquante(s)"); [print("  ", m) for m in missing[:400]]
    print(f"{len(wrong_ph)} placeholder(s) incohérent(s)"); [print("  ", m) for m in wrong_ph[:200]]
    print(f"{len(suspects)} texte(s) peut-être non traduit(s)"); [print("  ", m) for m in suspects[:300]]
    sys.exit(1 if missing or wrong_ph or bad_json else 0)

main()
