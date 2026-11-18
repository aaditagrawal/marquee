package com.mav.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Sort orders accepted by /Items. */
object SortBy {
    const val Name = "SortName"
    const val DateAdded = "DateCreated"
    const val Premiere = "PremiereDate"
    const val Year = "ProductionYear"
    const val Rating = "CommunityRating"
    const val Critic = "CriticRating"
    const val Runtime = "Runtime"
    const val Random = "Random"
    const val DatePlayed = "DatePlayed"
}

/**
 * Query for /Items. All fields optional; defaults suit a poster grid.
 * [filters]: IsPlayed, IsUnplayed, IsFavorite, IsResumable. [fields]: extra ItemFields (Overview, Genres, ...).
 */
data class ItemQuery(
    val parentId: String? = null,
    val includeItemTypes: List<String>? = null,
    val recursive: Boolean = true,
    val sortBy: List<String> = listOf(SortBy.Name),
    val descending: Boolean = false,
    val startIndex: Int = 0,
    val limit: Int? = 100,
    val filters: List<String>? = null,
    val genres: List<String>? = null,
    val genreIds: List<String>? = null,
    val years: List<Int>? = null,
    val personIds: List<String>? = null,
    val studioIds: List<String>? = null,
    val searchTerm: String? = null,
    val nameStartsWith: String? = null,
    val isFavorite: Boolean? = null,
    val fields: List<String> = Fields.Card,
    val enableTotalRecordCount: Boolean = true,
    val nameLessThan: String? = null,
    val ids: List<String>? = null,
)

object Fields {
    /** Lean set for cards/rows (hero needs Overview + Genres). */
    val Card = listOf("PrimaryImageAspectRatio", "Overview", "Genres", "ChildCount")
    val Catalog = Card + listOf("ProductionLocations", "OriginalTitle", "Studios", "DateCreated",
        "PremiereDate", "CommunityRating", "OfficialRating", "ImageTags", "ImageBlurHashes", "UserData", "ProviderIds", "MediaStreams")
    /** Full detail page. */
    val Detail = listOf(
        "Overview", "Genres", "Taglines", "People", "MediaSources", "MediaStreams", "Chapters", "Studios",
        "RemoteTrailers", "ProviderIds", "PrimaryImageAspectRatio", "DateCreated", "Width", "Height",
        "ChildCount",
    )
}

/**
 * Lean Jellyfin REST client. Every call is a main-safe `suspend` function (network + JSON on IO).
 * Uses [Session.httpClient] (auth header added automatically) and [Session.userId].
 * Errors surface as [java.io.IOException] / [HttpException]; callers catch.
 */
object JellyfinRepository {
    private val http get() = Session.httpClient
    private val base get() = Session.serverUrl
    private val uid get() = Session.userId

    private const val IMG = "Primary,Backdrop,Logo,Thumb"

    // ----------------------------------------------------------------------------------------- core

    private suspend fun <T> call(req: Request, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} ${req.url.encodedPath}")
            MarqueeJson.decodeFromString(ser, r.body!!.string())
        }
    }

    private suspend fun exec(req: Request): Int = withContext(Dispatchers.IO) {
        http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} ${req.url.encodedPath}")
            r.code
        }
    }

    private fun HttpUrl.Builder.images(limit: Int = 1) = q("enableImageTypes", IMG).q("imageTypeLimit", limit)

    // ----------------------------------------------------------------------------------------- auth

    /** Unauthenticated probe; use before login to validate a server URL. */
    suspend fun publicInfo(serverUrl: String = base): PublicSystemInfo =
        call(get(url(Session.normalizeUrl(serverUrl), "/System/Info/Public")), PublicSystemInfo.serializer())

    /** Users visible on the login screen (may be empty if the server hides them). */
    suspend fun publicUsers(): List<UserDto> = call(get(url(base, "/Users/Public")), ListSerializer(UserDto.serializer()))

    /** Password login. On success the session is persisted ([Session.login]). */
    suspend fun authenticateByName(username: String, password: String): AuthResult {
        val body = buildJsonObject { put("Username", username); put("Pw", password) }.toString().toRequestBody(JSON_MEDIA)
        val res = call(
            Request.Builder().url(url(base, "/Users/AuthenticateByName")).post(body)
                .header("Authorization", Session.authorizationHeader(token = "")).build(),
            AuthResult.serializer(),
        )
        Session.login(base, res.accessToken, res.user.id, res.user.name)
        return res
    }

    suspend fun quickConnectEnabled(): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            http.newCall(get(url(base, "/QuickConnect/Enabled"))).await().use { it.body!!.string().trim() == "true" }
        }
    }.getOrDefault(false)

    /** Starts Quick Connect; show [QuickConnectState.code] to the user, keep [QuickConnectState.secret] private. */
    suspend fun quickConnectInitiate(): QuickConnectState = call(
        Request.Builder().url(url(base, "/QuickConnect/Initiate")).post(EMPTY_BODY)
            .header("Authorization", Session.authorizationHeader(token = "")).build(),
        QuickConnectState.serializer(),
    )

    suspend fun quickConnectState(secret: String): QuickConnectState =
        call(get(url(base, "/QuickConnect/Connect") { q("secret", secret) }), QuickConnectState.serializer())

    /** Exchanges an authorized Quick Connect secret for a token; persists the session. */
    suspend fun quickConnectAuthenticate(secret: String): AuthResult {
        val body = buildJsonObject { put("Secret", secret) }.toString().toRequestBody(JSON_MEDIA)
        val res = call(
            Request.Builder().url(url(base, "/Users/AuthenticateWithQuickConnect")).post(body)
                .header("Authorization", Session.authorizationHeader(token = "")).build(),
            AuthResult.serializer(),
        )
        Session.login(base, res.accessToken, res.user.id, res.user.name)
        return res
    }

    suspend fun currentUser(): UserDto = call(get(url(base, "/Users/Me")), UserDto.serializer())

    /** Admin-only: all users. Non-admins get 403; use [publicUsers] on the login screen. */
    suspend fun users(): List<UserDto> = call(get(url(base, "/Users")), ListSerializer(UserDto.serializer()))

    // ---------------------------------------------------------------------------------------- browse

    /** Top-level libraries (Movies, Shows, Music, ...). */
    suspend fun views(): List<Item> =
        call(get(url(base, "/UserViews") { q("userId", uid) }), ItemsResult.serializer()).items

    suspend fun items(query: ItemQuery): ItemsResult = queryItems(query, lean = false)

    /** Stable ID-only scan for locally shuffled library pagination. Never fetch artwork/user state. */
    suspend fun itemIds(query: ItemQuery): ItemsResult = queryItems(query, lean = true)

    private suspend fun queryItems(query: ItemQuery, lean: Boolean): ItemsResult = call(get(url(base, "/Items") {
        q("userId", uid)
        q("parentId", query.parentId)
        q("includeItemTypes", query.includeItemTypes)
        q("recursive", query.recursive)
        q("sortBy", query.sortBy)
        q("sortOrder", if (query.descending) "Descending" else "Ascending")
        q("startIndex", query.startIndex.takeIf { it > 0 })
        q("limit", query.limit)
        q("filters", query.filters)
        q("genres", query.genres?.joinToString("|"))
        q("genreIds", query.genreIds?.joinToString("|"))
        q("years", query.years?.map { it.toString() })
        q("personIds", query.personIds)
        q("studioIds", query.studioIds)
        q("searchTerm", query.searchTerm)
        q("nameStartsWith", query.nameStartsWith)
        q("nameLessThan", query.nameLessThan)
        q("ids", query.ids)
        q("isFavorite", query.isFavorite)
        if (!lean) q("fields", query.fields)
        q("enableTotalRecordCount", query.enableTotalRecordCount)
        if (lean) { q("enableImages", false); q("enableUserData", false) }
        else images()
    }), ItemsResult.serializer())

    /** Continue Watching (video). */
    suspend fun resume(limit: Int = 16, parentId: String? = null): List<Item> = call(get(url(base, "/UserItems/Resume") {
        q("userId", uid); q("limit", limit); q("parentId", parentId); q("mediaTypes", "Video")
        q("fields", Fields.Card); q("enableTotalRecordCount", false); images()
    }), ItemsResult.serializer()).items

    suspend fun nextUp(limit: Int = 16, seriesId: String? = null, includeResumable: Boolean = false): List<Item> = call(get(url(base, "/Shows/NextUp") {
        q("userId", uid); q("limit", limit); q("seriesId", seriesId); q("fields", Fields.Card)
        q("enableTotalRecordCount", false); q("enableResumable", includeResumable); q("disableFirstEpisode", false); images()
    }), ItemsResult.serializer()).items

    /** Recently added in a library ([parentId] = view id). Episodes are grouped into their series by the server. */
    suspend fun latest(parentId: String?, limit: Int = 16, includeItemTypes: List<String>? = null): List<Item> =
        call(get(url(base, "/Items/Latest") {
            q("userId", uid); q("parentId", parentId); q("limit", limit); q("includeItemTypes", includeItemTypes)
            q("fields", Fields.Card); q("groupItems", true); images()
        }), ListSerializer(Item.serializer()))

    /** Full item for a detail page (people, media sources/streams, chapters...). */
    suspend fun item(id: String, fields: List<String> = Fields.Detail): Item =
        call(get(url(base, "/Items/$id") { q("userId", uid); q("fields", fields) }), Item.serializer())

    suspend fun seasons(seriesId: String): List<Item> = call(get(url(base, "/Shows/$seriesId/Seasons") {
        q("userId", uid); q("fields", Fields.Card); images()
    }), ItemsResult.serializer()).items

    suspend fun episodes(seriesId: String, seasonId: String? = null, startItemId: String? = null): List<Item> =
        call(get(url(base, "/Shows/$seriesId/Episodes") {
            q("userId", uid); q("seasonId", seasonId); q("startItemId", startItemId)
            q("fields", Fields.Card); images()
        }), ItemsResult.serializer()).items

    suspend fun firstEpisodeAudio(seriesId: String): List<MediaStream> = call(get(url(base, "/Shows/$seriesId/Episodes") {
        q("userId", uid); q("limit", 1); q("fields", listOf("MediaStreams")); q("enableImages", false)
    }), ItemsResult.serializer()).items.firstOrNull()?.mediaStreams.orEmpty().filter { it.type == "Audio" }

    suspend fun similar(id: String, limit: Int = 16): List<Item> = call(get(url(base, "/Items/$id/Similar") {
        q("userId", uid); q("limit", limit); q("fields", Fields.Card); images()
    }), ItemsResult.serializer()).items

    /** Items featuring a person (movies + series), newest first. */
    suspend fun itemsWithPerson(personId: String, limit: Int = 100): List<Item> = items(
        ItemQuery(personIds = listOf(personId), includeItemTypes = listOf("Movie", "Series"),
            sortBy = listOf(SortBy.Premiere), descending = true, limit = limit)
    ).items

    /** A Person item (Overview = biography, PremiereDate = birth date). */
    suspend fun person(id: String): Item = item(id, listOf("Overview", "PrimaryImageAspectRatio"))

    /** Search movies, series, episodes and people. People come back as Type == "Person". */
    suspend fun search(term: String, limit: Int = 40, types: List<String> = listOf("Movie", "Series", "Episode")): List<Item> {
        if (term.isBlank()) return emptyList()
        return items(ItemQuery(searchTerm = term, includeItemTypes = types, limit = limit,
            sortBy = listOf(SortBy.Name), enableTotalRecordCount = false)).items
    }

    suspend fun searchPeople(term: String, limit: Int = 20): List<Item> {
        if (term.isBlank()) return emptyList()
        return call(get(url(base, "/Persons") {
            q("userId", uid); q("searchTerm", term); q("limit", limit); q("enableImageTypes", "Primary"); q("imageTypeLimit", 1)
        }), ItemsResult.serializer()).items
    }

    suspend fun genres(parentId: String?, includeItemTypes: List<String>? = null): List<Item> =
        call(get(url(base, "/Genres") {
            q("userId", uid); q("parentId", parentId); q("includeItemTypes", includeItemTypes); q("sortBy", SortBy.Name)
        }), ItemsResult.serializer()).items

    suspend fun specialFeatures(id: String): List<Item> = call(get(url(base, "/Items/$id/SpecialFeatures") {
        q("userId", uid)
    }), ListSerializer(Item.serializer()))

    suspend fun localTrailers(id: String): List<Item> = call(get(url(base, "/Items/$id/LocalTrailers") {
        q("userId", uid)
    }), ListSerializer(Item.serializer()))

    suspend fun children(parentId: String, sortBy: String = SortBy.Name, limit: Int = 200): List<Item> =
        items(ItemQuery(parentId = parentId, recursive = false, sortBy = listOf(sortBy), limit = limit)).items

    suspend fun yearsInLibrary(parentId: String?, types: List<String>? = null): List<Int> =
        call(get(url(base, "/Years") {
            q("userId", uid); q("parentId", parentId); q("includeItemTypes", types); q("recursive", true); q("sortBy", SortBy.Name)
        }), ItemsResult.serializer()).items.mapNotNull { it.name.toIntOrNull() }

    suspend fun countBefore(parentId: String?, types: List<String>?, prefix: String, filters: List<String>? = null,
        genres: List<String>? = null, years: List<Int>? = null): Int =
        items(ItemQuery(parentId = parentId, includeItemTypes = types, nameLessThan = prefix, limit = 0,
            filters = filters, genres = genres, years = years)).totalRecordCount

    // --------------------------------------------------------------------------------------- toggles

    suspend fun setFavorite(id: String, favorite: Boolean): UserData {
        val u = url(base, "/UserFavoriteItems/$id") { q("userId", uid) }
        return call(if (favorite) post(u) else delete(u), UserData.serializer())
    }

    suspend fun setPlayed(id: String, played: Boolean): UserData {
        val u = url(base, "/UserPlayedItems/$id") { q("userId", uid) }
        return call(if (played) post(u) else delete(u), UserData.serializer())
    }

    /** Fire-and-forget logout on the server; always clears the local session. */
    suspend fun logout() {
        runCatching { exec(post(url(base, "/Sessions/Logout"))) }
        Session.logout()
    }
}
