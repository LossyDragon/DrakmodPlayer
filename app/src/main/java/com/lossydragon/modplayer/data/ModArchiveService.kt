package com.lossydragon.modplayer.data

import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.ArtistResult
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.SearchListResult
import com.lossydragon.modplayer.model.TypeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import nl.adaptivity.xmlutil.serialization.XML
import timber.log.Timber

class ModArchiveService(private val client: HttpClient, private val prefs: AppPreferences) {

    private suspend inline fun <reified T : TypeResult> executeRequest(
        request: String,
        vararg params: Pair<String, Any?>
    ): Result<T> = runCatching {
        if (BuildConfig.DEBUG) Timber.d("request=$request ${params.toList()}")
        val apiKey = prefs.getApiKey()
        val response = client.get("/xml-tools.php") {
            accept(ContentType.Application.Xml)
            parameter("key", apiKey)
            parameter("request", request)
            params.forEach { (key, value) -> if (value != null) parameter(key, value) }
        }.bodyAsText()

        val data: T = XML.decodeFromString(response)
        if (!data.error.isNullOrEmpty()) throw Exception(data.error)
        data
    }

    suspend fun getArtistById(id: Int, page: Int? = null): Result<SearchListResult> =
        executeRequest("view_modules_by_artistid", "query" to id, "page" to page)

    suspend fun getArtistSearch(query: String, page: Int? = null): Result<ArtistResult> =
        executeRequest("search_artist", "query" to query, "page" to page)

    suspend fun getModuleById(id: Int): Result<ModuleResult> =
        executeRequest("view_by_moduleid", "query" to id)

    suspend fun getRandomModule(): Result<ModuleResult> = executeRequest("random")

    suspend fun searchByFileNameOrTitle(
        query: String,
        page: Int? = null
    ): Result<SearchListResult> =
        executeRequest(
            "search",
            "type" to "filename_or_songtitle",
            "query" to query,
            "page" to page,
        )
}
