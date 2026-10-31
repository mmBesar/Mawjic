package app.mawjic.tv

/**
 * Isolate source-provided text so mixed Arabic/Latin runs cannot reorder adjacent
 * punctuation, times, episode identifiers, or surrounding UI labels.
 * Stored values are never modified; isolation is applied only at presentation.
 */
internal fun bidiIsolate(value: String): String =
    if (value.isEmpty()) value else "\u2068$value\u2069"
