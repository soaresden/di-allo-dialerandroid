package fr.soaresden.diallo

/**
 * Origine des numéros : pays (drapeau), type (mobile / fixe / box / spécial) et région française.
 * Région par défaut : France (les numéros sans indicatif sont considérés français).
 */
data class Country(val iso: String, val frName: String) {
    val flag: String get() = iso.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
    /** Nom affiché dans la langue choisie (le français du tableau sinon). */
    val name: String get() = if (I18n.lang.value == "fr") frName else when (iso) {
        "US" -> tr("États-Unis / Canada")
        "RE" -> if (frName.contains("/")) tr("La Réunion / Mayotte") else localName()
        else -> localName()
    }
    private fun localName(): String {
        val s = try { java.util.Locale("", iso).getDisplayCountry(I18n.locale) } catch (_: Throwable) { "" }
        return if (s.isNotEmpty() && s != iso) s else frName
    }
}

private val CODES: Map<String, Country> = listOf(
    "1" to ("US" to "États-Unis / Canada"), "7" to ("RU" to "Russie"), "20" to ("EG" to "Égypte"), "27" to ("ZA" to "Afrique du Sud"),
    "30" to ("GR" to "Grèce"), "31" to ("NL" to "Pays-Bas"), "32" to ("BE" to "Belgique"), "33" to ("FR" to "France"), "34" to ("ES" to "Espagne"),
    "36" to ("HU" to "Hongrie"), "39" to ("IT" to "Italie"), "40" to ("RO" to "Roumanie"), "41" to ("CH" to "Suisse"), "43" to ("AT" to "Autriche"),
    "44" to ("GB" to "Royaume-Uni"), "45" to ("DK" to "Danemark"), "46" to ("SE" to "Suède"), "47" to ("NO" to "Norvège"), "48" to ("PL" to "Pologne"),
    "49" to ("DE" to "Allemagne"), "51" to ("PE" to "Pérou"), "52" to ("MX" to "Mexique"), "53" to ("CU" to "Cuba"), "54" to ("AR" to "Argentine"),
    "55" to ("BR" to "Brésil"), "56" to ("CL" to "Chili"), "57" to ("CO" to "Colombie"), "58" to ("VE" to "Venezuela"), "60" to ("MY" to "Malaisie"),
    "61" to ("AU" to "Australie"), "62" to ("ID" to "Indonésie"), "63" to ("PH" to "Philippines"), "64" to ("NZ" to "Nouvelle-Zélande"),
    "65" to ("SG" to "Singapour"), "66" to ("TH" to "Thaïlande"), "81" to ("JP" to "Japon"), "82" to ("KR" to "Corée du Sud"), "84" to ("VN" to "Vietnam"),
    "86" to ("CN" to "Chine"), "90" to ("TR" to "Turquie"), "91" to ("IN" to "Inde"), "92" to ("PK" to "Pakistan"), "93" to ("AF" to "Afghanistan"),
    "94" to ("LK" to "Sri Lanka"), "95" to ("MM" to "Birmanie"), "98" to ("IR" to "Iran"),
    "211" to ("SS" to "Soudan du Sud"), "212" to ("MA" to "Maroc"), "213" to ("DZ" to "Algérie"), "216" to ("TN" to "Tunisie"), "218" to ("LY" to "Libye"),
    "220" to ("GM" to "Gambie"), "221" to ("SN" to "Sénégal"), "222" to ("MR" to "Mauritanie"), "223" to ("ML" to "Mali"), "224" to ("GN" to "Guinée"),
    "225" to ("CI" to "Côte d'Ivoire"), "226" to ("BF" to "Burkina Faso"), "227" to ("NE" to "Niger"), "228" to ("TG" to "Togo"), "229" to ("BJ" to "Bénin"),
    "230" to ("MU" to "Maurice"), "231" to ("LR" to "Liberia"), "232" to ("SL" to "Sierra Leone"), "233" to ("GH" to "Ghana"), "234" to ("NG" to "Nigeria"),
    "235" to ("TD" to "Tchad"), "236" to ("CF" to "Centrafrique"), "237" to ("CM" to "Cameroun"), "238" to ("CV" to "Cap-Vert"), "240" to ("GQ" to "Guinée équatoriale"),
    "241" to ("GA" to "Gabon"), "242" to ("CG" to "Congo"), "243" to ("CD" to "RD Congo"), "244" to ("AO" to "Angola"), "248" to ("SC" to "Seychelles"),
    "250" to ("RW" to "Rwanda"), "251" to ("ET" to "Éthiopie"), "252" to ("SO" to "Somalie"), "253" to ("DJ" to "Djibouti"), "254" to ("KE" to "Kenya"),
    "255" to ("TZ" to "Tanzanie"), "256" to ("UG" to "Ouganda"), "257" to ("BI" to "Burundi"), "258" to ("MZ" to "Mozambique"), "260" to ("ZM" to "Zambie"),
    "261" to ("MG" to "Madagascar"), "262" to ("RE" to "La Réunion / Mayotte"), "263" to ("ZW" to "Zimbabwe"), "264" to ("NA" to "Namibie"),
    "265" to ("MW" to "Malawi"), "266" to ("LS" to "Lesotho"), "267" to ("BW" to "Botswana"), "269" to ("KM" to "Comores"),
    "297" to ("AW" to "Aruba"), "298" to ("FO" to "Féroé"), "299" to ("GL" to "Groenland"),
    "350" to ("GI" to "Gibraltar"), "351" to ("PT" to "Portugal"), "352" to ("LU" to "Luxembourg"), "353" to ("IE" to "Irlande"), "354" to ("IS" to "Islande"),
    "355" to ("AL" to "Albanie"), "356" to ("MT" to "Malte"), "357" to ("CY" to "Chypre"), "358" to ("FI" to "Finlande"), "359" to ("BG" to "Bulgarie"),
    "370" to ("LT" to "Lituanie"), "371" to ("LV" to "Lettonie"), "372" to ("EE" to "Estonie"), "373" to ("MD" to "Moldavie"), "374" to ("AM" to "Arménie"),
    "375" to ("BY" to "Biélorussie"), "376" to ("AD" to "Andorre"), "377" to ("MC" to "Monaco"), "378" to ("SM" to "Saint-Marin"), "380" to ("UA" to "Ukraine"),
    "381" to ("RS" to "Serbie"), "382" to ("ME" to "Monténégro"), "383" to ("XK" to "Kosovo"), "385" to ("HR" to "Croatie"), "386" to ("SI" to "Slovénie"),
    "387" to ("BA" to "Bosnie-Herzégovine"), "389" to ("MK" to "Macédoine du Nord"), "420" to ("CZ" to "Tchéquie"), "421" to ("SK" to "Slovaquie"),
    "423" to ("LI" to "Liechtenstein"), "501" to ("BZ" to "Belize"), "502" to ("GT" to "Guatemala"), "503" to ("SV" to "Salvador"), "504" to ("HN" to "Honduras"),
    "505" to ("NI" to "Nicaragua"), "506" to ("CR" to "Costa Rica"), "507" to ("PA" to "Panama"), "508" to ("PM" to "Saint-Pierre-et-Miquelon"),
    "509" to ("HT" to "Haïti"), "590" to ("GP" to "Guadeloupe"), "591" to ("BO" to "Bolivie"), "592" to ("GY" to "Guyana"), "593" to ("EC" to "Équateur"),
    "594" to ("GF" to "Guyane"), "595" to ("PY" to "Paraguay"), "596" to ("MQ" to "Martinique"), "597" to ("SR" to "Suriname"), "598" to ("UY" to "Uruguay"),
    "599" to ("CW" to "Curaçao"), "673" to ("BN" to "Brunei"), "675" to ("PG" to "Papouasie"), "676" to ("TO" to "Tonga"), "678" to ("VU" to "Vanuatu"),
    "679" to ("FJ" to "Fidji"), "681" to ("WF" to "Wallis-et-Futuna"), "685" to ("WS" to "Samoa"), "687" to ("NC" to "Nouvelle-Calédonie"),
    "689" to ("PF" to "Polynésie française"), "852" to ("HK" to "Hong Kong"), "853" to ("MO" to "Macao"), "855" to ("KH" to "Cambodge"), "856" to ("LA" to "Laos"),
    "880" to ("BD" to "Bangladesh"), "886" to ("TW" to "Taïwan"), "960" to ("MV" to "Maldives"), "961" to ("LB" to "Liban"), "962" to ("JO" to "Jordanie"),
    "963" to ("SY" to "Syrie"), "964" to ("IQ" to "Irak"), "965" to ("KW" to "Koweït"), "966" to ("SA" to "Arabie saoudite"), "967" to ("YE" to "Yémen"),
    "968" to ("OM" to "Oman"), "970" to ("PS" to "Palestine"), "971" to ("AE" to "Émirats arabes unis"), "972" to ("IL" to "Israël"), "973" to ("BH" to "Bahreïn"),
    "974" to ("QA" to "Qatar"), "975" to ("BT" to "Bhoutan"), "976" to ("MN" to "Mongolie"), "977" to ("NP" to "Népal"), "992" to ("TJ" to "Tadjikistan"),
    "993" to ("TM" to "Turkménistan"), "994" to ("AZ" to "Azerbaïdjan"), "995" to ("GE" to "Géorgie"), "996" to ("KG" to "Kirghizistan"), "998" to ("UZ" to "Ouzbékistan")
).associate { (code, v) -> code to Country(v.first, v.second) }

private val FR = CODES.getValue("33")
// Outre-mer composé en national (0262…, 0692…)
private val DOM_PREFIX = listOf(
    "0262" to "RE", "0692" to "RE", "0693" to "RE", "0269" to "YT", "0639" to "YT",
    "0590" to "GP", "0690" to "GP", "0691" to "GP", "0596" to "MQ", "0696" to "MQ", "0697" to "MQ", "0594" to "GF", "0694" to "GF"
)
private val DOM_NAMES = mapOf("RE" to "La Réunion", "YT" to "Mayotte", "GP" to "Guadeloupe", "MQ" to "Martinique", "GF" to "Guyane")

/** Indicatif international du numéro (sans le +), ou null s'il est national. */
private fun intlDigits(n: String): String? {
    val t = n.trim().replace(" ", "").replace("-", "").replace(".", "")
    return when { t.startsWith("+") -> t.drop(1).filter { it.isDigit() }; t.startsWith("00") -> t.drop(2).filter { it.isDigit() }; else -> null }
}

/** Pays du numéro ; null pour masqué / numéro court / inconnu. */
fun countryOf(n: String): Country? {
    val d = n.filter { it.isDigit() }
    if (d.isEmpty()) return null
    val intl = intlDigits(n)
    if (intl != null) {
        for (len in 3 downTo 1) { val c = CODES[intl.take(len)]; if (c != null && intl.length > len) return c }
        return null
    }
    if (d.length <= 6) return null                       // numéros courts (3631, 112…)
    DOM_PREFIX.firstOrNull { d.startsWith(it.first) }?.let { return Country(it.second, DOM_NAMES.getValue(it.second)) }
    return if (d.startsWith("0")) FR else null
}

fun flagOf(n: String): String = countryOf(n)?.flag ?: if (n.any { it.isDigit() }) "📞" else "🕵️"

/** Texte affiché pour un numéro : drapeau + format lisible. */
fun phoneText(n: String): String = flagOf(n) + " " + displayNumber(n) + (lineLabel(n)?.let { " · " + it.substringBefore(" ·") } ?: "")

/** Type de ligne (pour les numéros français). Identifiant français : traduire à l'affichage avec tr(). */
fun lineType(n: String): String {
    val d = n.filter { it.isDigit() }
    if (d.isEmpty()) return "Masqué"
    val c = countryOf(n) ?: return if (d.length <= 6) "Court / service" else "Inconnu"
    if (c.iso != "FR") return "Étranger"
    val nat = nationalDigits(n)
    return when {
        nat.startsWith("06") || nat.startsWith("07") -> "Mobile"
        nat.startsWith("09") -> "Box / VoIP"
        nat.startsWith("08") -> "Numéro spécial"
        nat.length >= 2 && nat[1] in '1'..'5' -> "Fixe"
        else -> "Autre"
    }
}

/** Région d'un fixe français (01 à 05) ; null sinon. Identifiant français : traduire à l'affichage avec tr(). */
fun frRegion(n: String): String? {
    val c = countryOf(n) ?: return null
    if (c.iso != "FR") return if (c.iso in DOM_NAMES.keys) "Outre-mer" else null
    val nat = nationalDigits(n)
    return when (nat.take(2)) { "01" -> "Île-de-France"; "02" -> "Nord-Ouest"; "03" -> "Nord-Est"; "04" -> "Sud-Est"; "05" -> "Sud-Ouest"; else -> null }
}

/** Type de ligne traduit, pour l'affichage. */
fun lineTypeLabel(n: String): String = tr(lineType(n))

/** Région traduite, pour l'affichage ; null si inconnue. */
fun frRegionLabel(n: String): String? = frRegion(n)?.let { tr(it) }

/** Outre-mer composé en national → indicatif international. */
private val DOM_CC = mapOf("RE" to "262", "YT" to "262", "GP" to "590", "MQ" to "596", "GF" to "594")

/**
 * Format international : +33 6 12 34 56 78, +262 692 12 34 56, +44 79 11 12 34 56…
 * Les numéros courts (3631, 112) et masqués restent tels quels.
 */
fun intlNumber(n: String): String {
    val t = n.trim()
    if (t.any { it.isLetter() }) return t
    val d = t.filter { it.isDigit() }
    if (d.length <= 6) return d.ifEmpty { t }
    val intl = intlDigits(t)
    val (cc, rest) = when {
        intl != null -> {
            val len = (3 downTo 1).firstOrNull { CODES.containsKey(intl.take(it)) && intl.length > it } ?: return "+" + intl.chunked(3).joinToString(" ")
            intl.take(len) to intl.drop(len)
        }
        else -> {
            val dom = DOM_PREFIX.firstOrNull { d.startsWith(it.first) }
            when {
                dom != null -> DOM_CC.getValue(dom.second) to d.drop(1)
                d.startsWith("0") && d.length == 10 -> "33" to d.drop(1)
                else -> return d.chunked(2).joinToString(" ")
            }
        }
    }
    val body = when {
        cc == "33" && rest.length == 9 -> rest.take(1) + " " + rest.drop(1).chunked(2).joinToString(" ")
        cc in DOM_CC.values && rest.length == 9 -> rest.take(3) + " " + rest.drop(3).chunked(2).joinToString(" ")
        cc == "1" && rest.length == 10 -> rest.take(3) + " " + rest.substring(3, 6) + " " + rest.drop(6)
        else -> rest.chunked(2).joinToString(" ")
    }
    return "+$cc $body"
}

/** Mobile ou fixe (et région pour la France) : « 📱 Mobile », « ☎️ Fixe · Île-de-France »… null si on ne sait pas. */
fun lineLabel(n: String): String? = when (lineType(n)) {
    "Mobile" -> "📱 " + tr("Mobile")
    "Fixe" -> "☎️ " + tr("Fixe") + (frRegionLabel(n)?.let { " · $it" } ?: "")
    "Box / VoIP" -> "🌐 " + tr("Box / VoIP")
    "Numéro spécial" -> "💶 " + tr("N° spécial")
    "Court / service" -> "🔢 " + tr("Service")
    else -> null
}
