package app.kotakbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class LogicTest {
    @Test fun seasons() {
        assertEquals("WINTER" to 2027, thisSeason(LocalDate.of(2026, 12, 3))) // December is next year's winter, like AniList
        assertEquals("FALL" to 2026, thisSeason(LocalDate.of(2026, 11, 8)))
        assertEquals("WINTER" to 2027, nextSeason(LocalDate.of(2026, 11, 8)))
        assertEquals("SPRING" to 2027, nextSeason(LocalDate.of(2026, 12, 3)))
        assertEquals("Popular this Fall", popularThis(LocalDate.of(2026, 11, 8)))
    }

    @Test fun descriptions() = assertEquals("One\nTwo and three", plain("One<br>Two <i>and</i> three"))

    @Test fun placeholders() = assertEquals("Episode 3 of {total}", fill("Episode {n} of {total}", mapOf("n" to 3)))

    @Test fun searchArguments() {
        val (args, vars, values) = searchArgs("frieren", mapOf("genre" to "Drama", "year" to "", "sort" to ""))
        assertEquals(listOf("sort: SEARCH_MATCH", "search: \$q", "genre: \$genre"), args)
        assertEquals(listOf("\$q: String", "\$genre: String"), vars)
        assertEquals(mapOf("q" to "frieren", "genre" to "Drama"), values)
        assertEquals("sort: POPULARITY_DESC", searchArgs("", mapOf("year" to "2024%")).first[0]) // only filters: most popular first
    }

    /** Every t("…") the app shows has a translation from the desktop app, or it would show English in every language. */
    @Test fun everyLabelIsTranslated() {
        val keys = Regex("\"((?:[^\"\\\\]|\\\\.)*)\":").findAll(File("src/main/assets/locales/ja.json").readText()).map { it.groupValues[1] }.toSet()
        // labels passed through t() indirectly (row names, filter labels, format/status/season names)
        val indirect = listOf("Continue Reading", "Continue Watching", "Your List", "All Time Popular", "Fuel the Hype", "Trending Now",
            "Genre", "Year", "Season", "Format", "Status", "Sort", "Popularity", "Score", "Trending", "Newest", "Title",
            "Popular this Winter", "Popular this Spring", "Popular this Summer", "Popular this Fall", "Episodes", "Related", "Similar") +
            FORMAT.values + MANGA_FORMAT.values + STATUS.values + SEASON.values
        val direct = File("src/main/java").walk().filter { it.extension == "kt" }
            .flatMap { f -> Regex("\\bt\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(f.readText()).map { it.groupValues[1] } }
        val missing = (direct + indirect).filter { it !in keys && it !in setOf("{n} episodes") }.toSet() // plural: English table
        assertTrue("No translation for: $missing", missing.isEmpty())
    }
}
