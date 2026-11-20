package com.example.myapplication.loopbox

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One Deezer chart track with its 30-second preview, as shown in the web prototype's Music tab. */
data class PreviewTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val previewUrl: String,
)

/** Same endpoint as the prototype: https://api.deezer.com/chart/{id}/tracks?limit=15 */
class DeezerPreviewClient(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chartTracks(chartId: Int = 0, limit: Int = 15): List<PreviewTrack> {
        val body = client.get("$BASE/chart/$chartId/tracks") { parameter("limit", limit) }.bodyAsText()
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val album = o["album"] as? JsonObject
            val artist = o["artist"] as? JsonObject
            val preview = o.text("preview")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            PreviewTrack(
                id = "dz" + (o.text("id") ?: return@mapNotNull null),
                title = o.text("title") ?: return@mapNotNull null,
                artist = artist?.text("name").orEmpty(),
                album = album?.text("title").orEmpty(),
                coverUrl = album?.text("cover_big").orEmpty(),
                previewUrl = preview,
            )
        }
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BASE = "https://api.deezer.com"
    }
}
