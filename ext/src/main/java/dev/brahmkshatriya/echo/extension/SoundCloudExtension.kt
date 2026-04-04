package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class SoundCloudExtension : ExtensionClient, SearchFeedClient, TrackClient,
    HomeFeedClient, LibraryFeedClient, LoginClient.WebView, ArtistClient {

    private val httpClient = OkHttpClient()
    private lateinit var setting: Settings
    private val json = Json { ignoreUnknownKeys = true }
    private val clientId = "tkIWLs4MIowq7bCXP80TOwx6DnDa7UPc"

    private var currentUser: User? = null
    private var oauthToken: String? = null
    private val hideSnip get() = setting.getBoolean("hide_snip") ?: false

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            key = "hide_snip",
            title = "Hide Preview-Only Tracks",
            summary = "Hide tracks that only play a 30-second preview (Go+ tracks)",
            defaultValue = false
        )
    )

    override fun setSettings(settings: Settings) {
        setting = settings
        oauthToken = settings.getString("auth_token")
    }

    override suspend fun onInitialize() {}

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = NetworkRequest("https://soundcloud.com/signin")
        override val stopUrlRegex = Regex("soundcloud\\.com/discover|soundcloud\\.com/feed|soundcloud\\.com/you")

        override suspend fun onStop(
            url: NetworkRequest,
            cookie: String
        ): List<User>? {
            val token = cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") || it.startsWith("oauth_token=") }
                ?.split("=", limit = 2)
                ?.getOrNull(1)
                ?.trim()
                ?.removeSurrounding("\"")
                ?: return null

            oauthToken = token
            setting.putString("auth_token", token)

            val request = Request.Builder()
                .url("https://api-v2.soundcloud.com/me?client_id=$clientId")
                .header("Authorization", "OAuth $token")
                .build()
            val response = httpClient.newCall(request).await()
            val body = response.body?.string() ?: return null
            val root = json.parseToJsonElement(body).jsonObject
            val user = User(
                id = root["id"]!!.jsonPrimitive.long.toString(),
                name = root["username"]!!.jsonPrimitive.content,
                cover = root["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder(),
                extras = mapOf("auth_token" to token)
            )
            return listOf(user)
        }
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        oauthToken = user?.extras?.get("auth_token") ?: setting.getString("auth_token")
    }

    override suspend fun getCurrentUser(): User? = currentUser

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        if (oauthToken != null) {
            runCatching {
                val recentBody = httpClient.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/me/play-history/tracks?client_id=$clientId&limit=10")
                        .header("Authorization", "OAuth $oauthToken")
                        .build()
                ).await().body?.string()
                if (!recentBody.isNullOrBlank()) {
                    val recentTracks = json.parseToJsonElement(recentBody).jsonObject["collection"]!!
                        .jsonArray.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                        .filter { !hideSnip || !it.extras.containsKey("snip") }
                    if (recentTracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "recently_played", title = "Recently Played", list = recentTracks))
                }
            }

            runCatching {
                val streamBody = httpClient.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/stream?client_id=$clientId&limit=20")
                        .header("Authorization", "OAuth $oauthToken")
                        .build()
                ).await().body?.string()
                if (!streamBody.isNullOrBlank()) {
                    val streamTracks = json.parseToJsonElement(streamBody).jsonObject["collection"]!!
                        .jsonArray.mapNotNull {
                            val obj = it.jsonObject
                            val track = obj["track"]?.jsonObject
                                ?: if (obj["kind"]?.jsonPrimitive?.content == "track") obj else null
                            track?.toTrack()
                        }
                        .filter { !hideSnip || !it.extras.containsKey("snip") }
                    if (streamTracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "your_stream", title = "Your Stream", list = streamTracks))
                }
            }
        }

        runCatching {
            val trendingBody = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud:genres:all-music&client_id=$clientId&limit=20")
                    .build()
            ).await().body?.string()
            if (!trendingBody.isNullOrBlank()) {
                val trendingTracks = json.parseToJsonElement(trendingBody).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                    .filter { !hideSnip || !it.extras.containsKey("snip") }
                if (trendingTracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "trending", title = "Trending", list = trendingTracks))
            }
        }

        return shelves.toFeed()
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val token = oauthToken ?: return listOf<Shelf>().toFeed()
        val userId = currentUser?.id ?: return listOf<Shelf>().toFeed()
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/$userId/track_likes?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $token")
                    .build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val likedTracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull {
                        val obj = it.jsonObject
                        obj["track"]?.jsonObject?.toTrack()
                            ?: if (obj["kind"]?.jsonPrimitive?.content == "track") obj.toTrack() else null
                    }
                    .filter { !hideSnip || !it.extras.containsKey("snip") }
                if (likedTracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "liked_tracks", title = "Liked Tracks", list = likedTracks))
            }
        }

        return shelves.toFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/search/tracks?q=$query&client_id=$clientId&limit=20")
                .build()
        ).await().body?.string()
        if (body.isNullOrBlank()) return listOf<Shelf>().toFeed()
        val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
            .jsonArray.map { it.jsonObject.toTrack() }
            .filter { !hideSnip || !it.extras.containsKey("snip") }
        return listOf<Shelf>(Shelf.Lists.Tracks(id = "search_results", title = "Results", list = tracks)).toFeed()
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/${artist.id}?client_id=$clientId")
                .build()
        ).await().body?.string() ?: return artist
        val root = json.parseToJsonElement(body).jsonObject
        return Artist(
            id = artist.id,
            name = root["username"]!!.jsonPrimitive.content,
            cover = root["avatar_url"]?.jsonPrimitive?.contentOrNull
                ?.replace("large", "t500x500")?.toImageHolder(),
            bio = root["description"]?.jsonPrimitive?.contentOrNull,
            subtitle = root["followers_count"]?.jsonPrimitive?.longOrNull
                ?.let { if (it > 0) "$it followers" else null }
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/${artist.id}/tracks?client_id=$clientId&limit=20")
                .build()
        ).await().body?.string()
        if (body.isNullOrBlank()) return listOf<Shelf>().toFeed()
        val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
            .jsonArray.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
            .filter { !hideSnip || !it.extras.containsKey("snip") }
        return listOf<Shelf>(Shelf.Lists.Tracks(id = "artist_tracks", title = "Tracks", list = tracks)).toFeed()
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val url = "${streamable.id}?client_id=$clientId"
        val builder = Request.Builder().url(url)
        oauthToken?.let { builder.header("Authorization", "OAuth $it") }
        val body = httpClient.newCall(builder.build()).await().body?.string()
        if (body.isNullOrBlank()) throw Exception("Empty stream response")
        val streamUrl = json.parseToJsonElement(body).jsonObject["url"]!!.jsonPrimitive.content
        val isHls = streamable.id.contains("hls")
        return if (isHls)
            streamUrl.toServerMedia(type = Streamable.SourceType.HLS)
        else
            streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    private fun kotlinx.serialization.json.JsonObject.toTrack(): Track {
        val id = this["id"]!!.jsonPrimitive.long.toString()
        val title = this["title"]!!.jsonPrimitive.content
        val duration = this["duration"]!!.jsonPrimitive.long
        val artwork = this["artwork_url"]?.jsonPrimitive?.contentOrNull
            ?.replace("large", "t500x500")
        val user = this["user"]!!.jsonObject
        val artist = Artist(
            id = user["id"]!!.jsonPrimitive.long.toString(),
            name = user["username"]!!.jsonPrimitive.content,
            cover = user["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder()
        )
        val transcodings = this["media"]?.jsonObject?.get("transcodings")?.jsonArray
        var streamUrl = ""
        val isSnip = this["policy"]?.jsonPrimitive?.contentOrNull == "SNIP"

        transcodings?.forEach { t ->
            val obj = t.jsonObject
            val protocol = obj["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.contentOrNull
            if (protocol == "progressive" && streamUrl.isEmpty())
                streamUrl = obj["url"]!!.jsonPrimitive.content
        }
        if (streamUrl.isEmpty()) {
            transcodings?.forEach { t ->
                val obj = t.jsonObject
                val protocol = obj["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.contentOrNull
                if (protocol == "hls")
                    streamUrl = obj["url"]!!.jsonPrimitive.content
            }
        }

        return Track(
            id = id,
            title = title,
            artists = listOf(artist),
            cover = artwork?.toImageHolder(),
            duration = duration,
            extras = if (isSnip) mapOf("snip" to "true") else emptyMap(),
            streamables = if (streamUrl.isNotEmpty())
                listOf(Streamable.server(id = streamUrl, quality = 0))
            else emptyList()
        )
    }
}
