package com.lossydragon.modplayer.model

import androidx.compose.runtime.*
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.util.fromHtml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.*

/**
 * XML models for the ModArchive API.
 * Deserialized via `kotlinx.serialization` with xmlutil.
 * API reference: https://modarchive.org/index.php?xml-api
 */

@Immutable
@Serializable
@SerialName("modarchive")
data class ModuleResult(
    @XmlElement val error: String? = null,
    @XmlElement val module: Module = Module(),
    @XmlElement val results: Int = 0,
    @XmlElement val sponsor: Sponsor = Sponsor(),
    @XmlElement val totalpages: Int = 0
) {
    @Transient
    val hasSponsor: Boolean = sponsor.details.text.isNotBlank()
}

@Immutable
@Serializable
@SerialName("modarchive")
data class SearchListResult(
    @XmlElement val error: String? = null,
    @XmlElement val results: Int = 0,
    @XmlElement val sponsor: Sponsor = Sponsor(),
    @XmlElement val totalpages: Int = 0,
    @XmlSerialName("module", "", "") val module: List<Module> = emptyList()
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("modarchive")
data class ArtistResult(
    @XmlElement val error: String? = null,
    @XmlElement val items: Items = Items(),
    @XmlElement val results: Int = 0,
    @XmlElement val sponsor: Sponsor = Sponsor(),
    @XmlElement val total_results: Int = 0,
    @XmlElement val totalpages: Int = 0
) {
    val listItems: List<Item>
        get() = items.item
}

@Immutable
@Serializable
@SerialName("sponsor")
data class Sponsor(
    @XmlElement val details: SponsorDetails = SponsorDetails()
)

@Immutable
@Serializable
@SerialName("details")
data class SponsorDetails(
    @XmlElement val image: String = "",
    @XmlElement val imagehtml: String = "",
    @XmlElement val link: String = "",
    @XmlElement val text: String = ""
)

@Immutable
@Serializable
@SerialName("module")
data class Module(
    @XmlElement val bytes: Int = 0,
    @XmlElement val channels: Int = 0,
    @XmlElement val comment: String = "",
    @XmlElement val date: String = "",
    @XmlElement val favourites: Favourites = Favourites(),
    @XmlElement val featured: Featured = Featured(),
    @XmlElement val filename: String = "",
    @XmlElement val format: String = "",
    @XmlElement val genreid: Int = 0,
    @XmlElement val genretext: String = "",
    @XmlElement val hash: String = "",
    @XmlElement val hidetext: Int = 0,
    @XmlElement val hits: Int = 0,
    @XmlElement val id: Int = 0,
    @XmlElement val infopage: String = "",
    @XmlElement val instruments: String = "",
    @XmlElement val license: License = License(),
    @XmlElement val size: String = "",
    @XmlElement val songtitle: String = "",
    @XmlElement val timestamp: Long = 0L,
    @XmlElement val url: String = "",
    @XmlSerialName("artist_info", "", "")
    @XmlElement val artistInfo: ArtistInfo = ArtistInfo(),
    @XmlSerialName("overall_ratings", "", "")
    @XmlElement val overallRatings: OverallRatings = OverallRatings()
) {
    val isSupported: Boolean
        get() = format.lowercase() !in Constants.UNSUPPORTED_EXTENSIONS
    val downloadUrl: String
        get() = url.trim()
    val sizeKb: Int
        get() = bytes / 1024
    val artist: String
        get() = artistInfo.artist.firstOrNull()
            ?.alias
            ?.trim()
            ?: artistInfo.guessedArtistList.firstOrNull()?.trim()
            ?: "unknown"
    val formattedComment: String
        get() = comment.lineSequence().joinToString("\n") { it.fromHtml() }
    val formattedInstruments: String
        get() = instruments.lineSequence().joinToString("\n") { it.fromHtml() }
}

@Immutable
@Serializable
@SerialName("featured")
data class Featured(
    @XmlElement val date: String = "",
    @XmlElement val state: String = "",
    @XmlElement val timestamp: String = ""
)

@Immutable
@Serializable
@SerialName("favourites")
data class Favourites(
    @XmlElement val favoured: Int = 0,
    @XmlElement val myfav: Int = 0
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("overall_ratings")
data class OverallRatings(
    @XmlElement val comment_rating: Double = 0.0,
    @XmlElement val comment_total: Int = 0,
    @XmlElement val review_rating: Int = 0,
    @XmlElement val review_total: Int = 0
)

@Immutable
@Serializable
@SerialName("license")
data class License(
    @XmlElement val deedurl: String = "",
    @XmlElement val description: String = "",
    @XmlElement val imageurl: String = "",
    @XmlElement val legalurl: String = "",
    @XmlElement val licenseid: String = "",
    @XmlElement val title: String = ""
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("artist_info")
data class ArtistInfo(
    @XmlElement val artists: Int = 0,
    @XmlElement val guessed_artist: GuessedArtists = GuessedArtists(),
    @XmlElement val guessed_artists: Int = 0,
    @XmlSerialName("artist", "", "") val artist: List<Artist> = emptyList()
) {
    val guessedArtistList: List<String>
        get() = guessed_artist.alias
}

@Immutable
@Serializable
@SerialName("guessed_artist")
data class GuessedArtists(
    @XmlSerialName("alias", "", "") val alias: List<String> = emptyList()
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("artist")
data class Artist(
    @XmlElement val alias: String = "",
    @XmlElement val id: Int = 0,
    @XmlElement val imageurl: String = "",
    @XmlElement val imageurl_icon: String = "",
    @XmlElement val imageurl_thumb: String = "",
    @XmlElement val module_data: ModuleData = ModuleData(),
    @XmlElement val profile: String = ""
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("module_data")
data class ModuleData(
    @XmlElement val module_description: String = ""
)

@Immutable
@Serializable
@SerialName("items")
data class Items(
    @XmlSerialName("item", "", "") val item: List<Item> = emptyList()
)

@Suppress("PropertyName")
@Immutable
@Serializable
@SerialName("item")
data class Item(
    @XmlElement val alias: String = "",
    @XmlElement val date: String = "",
    @XmlElement val id: Int = 0,
    @XmlElement val imageurl: String = "",
    @XmlElement val imageurl_icon: String = "",
    @XmlElement val imageurl_thumb: String = "",
    @XmlElement val isartist: String = "",
    @XmlElement val lastseen: String = "",
    @XmlElement val profile: String = "",
    @XmlElement val timestamp: Int = 0
)
