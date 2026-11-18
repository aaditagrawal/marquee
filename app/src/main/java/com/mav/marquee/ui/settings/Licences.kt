// SPDX-License-Identifier: GPL-3.0-or-later
package com.mav.marquee.ui.settings

import android.content.res.AssetManager
import com.mav.marquee.ui.library.kit.TextChunk

/** One bundled licence text under `assets/licenses/`, listed in Settings > Open-source licences. */
internal data class LicenceFile(val path: String, val title: String, val detail: String?)

/**
 * Licence texts shipped in the APK (GPL-3.0 "Appropriate Legal Notices"). The list is read from
 * `assets/licenses/`, so a newly added file appears without code changes; known files get readable names.
 * [read] does file I/O and must run off the main thread.
 */
internal object Licences {
    private const val DIR = "licenses"
    /** Long paragraphs (the dependency table) are split so the reading list stays lazy. */
    const val MAX_CHUNK_LINES = 12

    private val known = linkedMapOf(
        "GPL-3.0.txt" to ("GNU General Public License 3.0" to "Marquee's licence (GPL-3.0-or-later)"),
        "THIRD_PARTY_NOTICES.md" to ("Third-party notices" to "Libraries, native code, fonts and icons"),
        "Apache-2.0.txt" to ("Apache License 2.0" to "AndroidX, Media3, Kotlin, OkHttp, Coil and others"),
        "OFL-1.1-HankenGrotesk.txt" to ("SIL Open Font License 1.1" to "Hanken Grotesk typeface"),
        "material-symbols.txt" to ("Material Symbols" to "Player icons, Apache License 2.0"),
    )

    fun list(assets: AssetManager): List<LicenceFile> {
        val names = runCatching { assets.list(DIR) }.getOrNull().orEmpty().filter { !it.startsWith(".") }
        val order = known.keys.toList()
        return names.sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.lowercase() }))
            .map { name -> known[name].let { LicenceFile("$DIR/$name", it?.first ?: name, it?.second) } }
    }

    fun read(assets: AssetManager, file: LicenceFile): List<TextChunk> =
        chunks(assets.open(file.path).bufferedReader().use { it.readText() })

    /** Blank lines separate paragraphs; line breaks inside a paragraph are kept verbatim. */
    fun chunks(text: String): List<TextChunk> {
        val out = ArrayList<TextChunk>()
        val lines = ArrayList<String>()
        fun flush() {
            lines.chunked(MAX_CHUNK_LINES).forEachIndexed { i, part -> out += TextChunk(part.joinToString("\n"), i == 0) }
            lines.clear()
        }
        text.replace("\r\n", "\n").split('\n').forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) flush() else lines += line
        }
        flush()
        return out
    }
}
