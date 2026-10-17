package com.retrobox.tv.util

/** File-system-safe names for saves and pictures derived from ROM file names. */
object SafeName {
    /**
     * Characters outside `A-Z a-z 0-9 . _ space -` become `_`. Names with non-ASCII characters (e.g. Japanese titles)
     * would all collapse to underscores and share one save file, so they also get a short hash of the original name.
     * ASCII names are unchanged from earlier versions, so existing saves and pictures keep matching.
     */
    fun of(name: String, maxLength: Int = 100): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(maxLength)
        if (name.all { it.code < 0x80 }) return safe
        val crc = java.util.zip.CRC32().apply { update(name.toByteArray(Charsets.UTF_8)) }.value
        return "${safe}_%08x".format(crc)
    }
}
