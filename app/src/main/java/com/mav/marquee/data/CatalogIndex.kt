package com.mav.marquee.data

/** Pure classifier and row builder. No Android, IO or per-row server queries. */
object CatalogIndex {
    enum class Origin(val title: String) {
        Korean("Korean Shows & Movies"), Indian("Hindi & Indian Cinema"), English("Hollywood / English"),
        British("British"), Japanese("Japanese & Anime"), French("French"), German("German"),
        Scandinavian("Scandinavian"), Chinese("Chinese"), Spanish("Spanish"), Unknown("Other")
    }
    data class Classification(val origin: Origin, val evidence: String)

    fun classify(item: Item, episodeAudio: List<MediaStream> = emptyList()): Classification {
        val original = item.originalTitle.orEmpty()
        val locations = item.productionLocations.orEmpty().map { it.lowercase() }
        val studios = item.studios.orEmpty().joinToString(" ") { it.name.lowercase() }
        val script = when {
            original.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' } -> Origin.Korean
            original.any { it in '\u0900'..'\u0d7f' } -> Origin.Indian
            original.any { it in '\u3040'..'\u30ff' } -> Origin.Japanese
            original.any { it in '\u4e00'..'\u9fff' } -> if (studios.contains("iqiyi") || studios.contains("tencent")) Origin.Chinese else Origin.Japanese
            else -> null
        }
        fun country(): Origin? = when {
            locations.any { it in setOf("south korea", "korea", "republic of korea") } -> Origin.Korean
            "india" in locations -> Origin.Indian
            "japan" in locations -> Origin.Japanese
            locations.any { it in setOf("china", "hong kong", "taiwan") } -> Origin.Chinese
            "united kingdom" in locations && locations.none { it == "united states of america" || it == "united states" } -> Origin.British
            locations.any { it in setOf("united states of america", "united states", "canada", "australia", "new zealand") } -> Origin.English
            "france" in locations -> Origin.French
            "germany" in locations -> Origin.German
            locations.any { it in setOf("sweden", "denmark", "norway", "finland", "iceland") } -> Origin.Scandinavian
            "spain" in locations -> Origin.Spanish
            else -> null
        }
        fun language(value: String?): Origin? = when (value?.lowercase()) {
            "kor", "ko", "korean" -> Origin.Korean
            "hin", "hi", "hindi", "tam", "ta", "tel", "te", "mal", "ml", "kan", "kn", "mar", "mr", "ben", "bn", "pan", "pa", "guj", "gu", "urd", "ur" -> Origin.Indian
            "jpn", "ja", "japanese" -> Origin.Japanese
            "eng", "en", "english" -> Origin.English
            "fra", "fre", "fr" -> Origin.French
            "deu", "ger", "de" -> Origin.German
            "swe", "sv", "dan", "da", "nor", "no", "fin", "fi" -> Origin.Scandinavian
            "zho", "chi", "zh", "yue" -> Origin.Chinese
            "spa", "es" -> Origin.Spanish
            else -> null
        }
        fun audio(streams: List<MediaStream>): Classification? {
            val tracks = streams.filter { it.type == "Audio" && !it.title.orEmpty().contains("commentary", true) }
            // Multiple default tracks and mislabeled encoder tags occur in this library. Prefer an
            // audio language corroborated by original script/country over a dub or generic English tag.
            val originHint = script ?: country()
            val matching = tracks.firstOrNull { language(it.language) == originHint }
            val chosen = matching ?: tracks.firstOrNull { it.isDefault && language(it.language) != null }
                ?: tracks.firstOrNull { language(it.language) != null } ?: return null
            var origin = language(chosen.language) ?: return null
            if (origin == Origin.English) origin = script ?: country() ?: Origin.English
            return Classification(origin, "audio:${chosen.language}" + if (origin != language(chosen.language)) "+origin" else "")
        }
        if (item.type == "Movie") audio(item.mediaStreams.orEmpty())?.let { return it }
        script?.let { return Classification(it, "original-title script") }
        country()?.let { return Classification(it, "production country") }
        val studioOrigin = when {
            listOf("tvn", "tving", "sbs", "mbc", "mbn", "jtbc", "wavve", "kbs").any { studios.split(' ').contains(it) } -> Origin.Korean
            listOf("sonyliv", "voot", "jiocinema", "hotstar", "amazon mx").any(studios::contains) -> Origin.Indian
            listOf("fuji tv", "tv tokyo", "nhk", "tokyo mx").any(studios::contains) -> Origin.Japanese
            listOf("bbc", "itv", "channel 4", "sky atlantic").any(studios::contains) -> Origin.British
            listOf("iqiyi", "tencent", "youku").any(studios::contains) -> Origin.Chinese
            listOf("hbo", "nbc", "fox", "pbs", "disney", "apple tv", "usa network", "the cw", "amc", "nebula").any(studios::contains) -> Origin.English
            else -> null
        }
        studioOrigin?.let { return Classification(it, "studio") }
        audio(episodeAudio)?.let { return it.copy(evidence = "first-episode ${it.evidence}") }
        return Classification(Origin.Unknown, "no reliable origin metadata")
    }

    fun rows(items: List<Item>, episodeAudio: Map<String, List<MediaStream>> = emptyMap()): List<HomeRow> {
        val ordered = items.distinctBy { it.id }.sortedWith(compareByDescending<Item> { it.dateCreated ?: it.premiereDate.orEmpty() }.thenBy { it.id })
        val origins = ordered.associate { it.id to classify(it, episodeAudio[it.id].orEmpty()).origin }
        val rows = mutableListOf<HomeRow>()
        fun add(key: String, title: String, selection: List<Item>) {
            if (selection.size >= 5) rows += HomeRow(key, title, selection)
        }
        add("recent", "Recently Added", ordered.take(30))
        for (o in Origin.entries.filter { it != Origin.Unknown }) {
            add("origin_${o.name}", o.title, ordered.filter {
                origins[it.id] == o || o == Origin.English && origins[it.id] == Origin.British
            })
        }
        add("rated", "Top Rated", ordered.filter { (it.communityRating ?: 0f) >= 7.5f }
            .sortedWith(compareByDescending<Item> { it.communityRating }.thenByDescending { it.dateCreated.orEmpty() }))
        val genres = linkedMapOf(
            "Comedy" to setOf("comedy"), "Thriller & Mystery" to setOf("thriller", "mystery"),
            "Sci-Fi & Fantasy" to setOf("sci-fi", "science fiction", "science-fiction", "fantasy", "sci-fi & fantasy"),
            "Action & Adventure" to setOf("action", "adventure", "action & adventure"),
            "Animation & Family" to setOf("animation", "family", "kids"), "Crime" to setOf("crime"),
            "Romance" to setOf("romance"), "Documentary" to setOf("documentary"))
        val genreStart = rows.size
        for ((title, values) in genres) add("genre_$title", title, ordered.filter { i -> i.genres.orEmpty().any { it.lowercase() in values } })
        // Small, deterministic recency changes inside adjacent genre pairs; the opening shelves
        // and origin groups stay in the requested order, and focus uses persistent row keys.
        val genreRows = rows.drop(genreStart).chunked(2).flatMap { pair ->
            pair.sortedByDescending { it.items.firstOrNull()?.dateCreated.orEmpty() }
        }
        while (rows.size > genreStart) rows.removeAt(rows.lastIndex)
        rows += genreRows
        return rows
    }
}
