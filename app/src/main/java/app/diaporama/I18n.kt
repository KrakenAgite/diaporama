package app.diaporama

import java.util.Locale

/** Textes en paire dans le code : anglais si la langue d'Android (ou celle choisie pour l'appli) est l'anglais, sinon français. */
fun tr(fr: String, en: String): String = if (Locale.getDefault().language == "en") en else fr

/** « 1 photo », « 3 photos » : le pluriel en -s marche dans les deux langues pour nos mots. */
fun plural(n: Int, word: String) = "$n $word" + if (n > 1) "s" else ""
