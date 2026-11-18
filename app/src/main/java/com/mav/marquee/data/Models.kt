package com.mav.marquee.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Immutable
@Serializable
data class MediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("Profile") val profile: String? = null,
)

@Immutable
@Serializable
data class MediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
)

@Immutable
@Serializable
data class Person(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("ImageBlurHashes") val imageBlurHashes: Map<String, Map<String, String>>? = null,
)

@Immutable
@Serializable
data class Chapter(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
)

@Immutable
@Serializable
data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Taglines") val taglines: List<String>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("CriticRating") val criticRating: Int? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String>? = null,
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("SeriesThumbImageTag") val seriesThumbImageTag: String? = null,
    @SerialName("ImageBlurHashes") val imageBlurHashes: Map<String, Map<String, String>>? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("People") val people: List<Person>? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null,
    @SerialName("LocalTrailerCount") val localTrailerCount: Int? = null,
    @SerialName("RemoteTrailers") val remoteTrailers: List<RemoteTrailer>? = null,
    @SerialName("Chapters") val chapters: List<Chapter>? = null,
    @SerialName("SpecialFeatureCount") val specialFeatureCount: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("ProductionLocations") val productionLocations: List<String>? = null,
    @SerialName("Studios") val studios: List<Studio>? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String>? = null,
) {
    val isPlayable: Boolean get() = !isFolder && (mediaType == "Video" || mediaType == "Audio" ||
        type == "Movie" || type == "Episode" || type == "Video" || type == "MusicVideo" || type == "Audio" || type == "Trailer")
    val resumeMs: Long get() = (userData?.playbackPositionTicks ?: 0L) / 10_000L
    val runtimeMs: Long get() = (runTimeTicks ?: 0L) / 10_000L
    val progress: Float get() {
        val rt = runTimeTicks ?: return 0f
        val pos = userData?.playbackPositionTicks ?: 0
        return if (rt > 0 && pos > 0) (pos.toFloat() / rt).coerceIn(0f, 1f) else 0f
    }
    val episodeLabel: String? get() = when {
        type == "Episode" && parentIndexNumber != null && indexNumber != null -> "S$parentIndexNumber · E$indexNumber"
        type == "Episode" && indexNumber != null -> "E$indexNumber"
        else -> null
    }
}

@Immutable
@Serializable
data class Studio(@SerialName("Name") val name: String = "", @SerialName("Id") val id: String? = null)

@Immutable
@Serializable
data class RemoteTrailer(@SerialName("Url") val url: String? = null, @SerialName("Name") val name: String? = null)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<Item> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Immutable
@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("HasPassword") val hasPassword: Boolean = true,
)

@Serializable
data class AuthResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class QuickConnectState(
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class GenreDto(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

/** A home-screen row. `wide` rows use 16:9 landscape cards. */
@Immutable
@Serializable
data class HomeRow(
    val key: String,
    val title: String,
    val items: List<Item>,
    val wide: Boolean = false,
    val libraryId: String? = null,
)

@Serializable
data class HomeSnapshot(
    val userId: String,
    val views: List<Item>,
    val rows: List<HomeRow>,
    val savedAt: Long,
    val serverUrl: String = "",
)
