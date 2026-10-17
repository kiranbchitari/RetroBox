package com.retrobox.tv.library

import com.retrobox.tv.emulator.Systems

enum class SortMode(val label: String) { TITLE("Title A–Z"), RECENT("Recently played"), SYSTEM("System, then title") }

data class LibraryQuery(
    val text: String = "",
    val systemId: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: SortMode = SortMode.TITLE,
)

object LibraryFilter {
    /** @param lastPlayed epoch ms a game was last played (0 = never); only used by [SortMode.RECENT]. */
    fun apply(games: List<Game>, q: LibraryQuery, lastPlayed: (Game) -> Long = { 0L }): List<Game> {
        val words = q.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = games.filter { g ->
            (q.systemId == null || g.systemId == q.systemId) &&
                (!q.favoritesOnly || g.favorite) &&
                words.all { w -> g.title.lowercase().contains(w) || g.fileName.lowercase().contains(w) }
        }
        val byTitle = Comparator<Game> { a, b -> naturalCompare(a.title, b.title).let { if (it != 0) it else a.path.compareTo(b.path) } }
        return when (q.sort) {
            SortMode.TITLE -> filtered.sortedWith(byTitle)
            SortMode.RECENT -> filtered.sortedWith(compareByDescending<Game> { lastPlayed(it) }.then(byTitle))
            SortMode.SYSTEM -> filtered.sortedWith(compareBy<Game> { systemOrder(it.systemId) }.then(byTitle))
        }
    }

    private fun systemOrder(id: String) = Systems.all.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }

    /** Case-insensitive, digit runs compared as numbers ("Level 2" before "Level 10"), a leading "The " ignored. */
    fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase().removePrefix("the ")
        val y = b.lowercase().removePrefix("the ")
        var i = 0; var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ei = i; while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j; while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0'); val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }
}
