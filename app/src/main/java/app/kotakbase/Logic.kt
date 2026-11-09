package app.kotakbase

import java.time.LocalDate

// Plain rules shared by screens, kept free of Android so the unit tests can run them.

/** [season, year] now: December counts as next year's winter, like AniList. */
fun thisSeason(d: LocalDate = LocalDate.now()): Pair<String, Int> {
    val m = d.monthValue
    val s = listOf("WINTER", "WINTER", "SPRING", "SPRING", "SPRING", "SUMMER", "SUMMER", "SUMMER", "FALL", "FALL", "FALL", "WINTER")[m - 1]
    return s to if (m == 12) d.year + 1 else d.year
}

fun nextSeason(d: LocalDate = LocalDate.now()): Pair<String, Int> {
    val (s, y) = thisSeason(d)
    return if (s == "FALL") "WINTER" to y + 1 else mapOf("WINTER" to "SPRING", "SPRING" to "SUMMER", "SUMMER" to "FALL").getValue(s) to y
}

/** The row title for this season's popular shows (a desktop translation key). */
fun popularThis(d: LocalDate = LocalDate.now()) = "Popular this " + mapOf("WINTER" to "Winter", "SPRING" to "Spring", "SUMMER" to "Summer", "FALL" to "Fall").getValue(thisSeason(d).first)

/** AniList descriptions carry a little HTML (<br>, <i>). */
fun plain(s: String?) = (s ?: "").replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]*>"), "").replace(Regex("\n{3,}"), "\n\n").trim()

/** Fills {name} placeholders; unknown ones stay as they are. (Android's regex needs the } escaped too.) */
fun fill(s: String, vars: Map<String, Any?>) = Regex("\\{(\\w+)\\}").replace(s) { vars[it.groupValues[1]]?.toString() ?: it.value }

/** A search's AniList arguments: what's typed plus each picked filter. Returns (args, variable declarations, variables). */
fun searchArgs(q: String, filters: Map<String, String>): Triple<List<String>, List<String>, Map<String, Any>> {
    val args = mutableListOf<String>()
    val vars = mutableListOf<String>()
    val values = mutableMapOf<String, Any>()
    if (q.isNotBlank()) { args += "search: \$q"; vars += "\$q: String"; values["q"] = q.trim() }
    for ((key, arg, type) in FILTER_ARGS) filters[key]?.takeIf { it.isNotEmpty() }?.let { args += "$arg: \$$key"; vars += "\$$key: $type"; values[key] = it }
    val sort = filters["sort"]?.takeIf { it.isNotEmpty() } ?: if (q.isNotBlank()) "SEARCH_MATCH" else "POPULARITY_DESC"
    return Triple(listOf("sort: $sort") + args, vars, values)
}

private val FILTER_ARGS = listOf(
    Triple("genre", "genre", "String"),
    Triple("tag", "tag", "String"),
    Triple("year", "startDate_like", "String"),
    Triple("season", "season", "MediaSeason"),
    Triple("format", "format", "MediaFormat"),
    Triple("status", "status", "MediaStatus")
)
