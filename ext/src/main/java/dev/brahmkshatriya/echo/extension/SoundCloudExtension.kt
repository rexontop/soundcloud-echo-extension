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
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun onInitialize() {}

    // ---- LOGIN ----

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = NetworkRequest("https://soundcloud.com/signin")
        override val stopUrlRegex = Regex("soundcloud\\.com/discover|soundcloud\\.com/feed|soundcloud\\.com/you")

        override suspend fun onStop(
            url: NetworkRequest,
            cookie: String
        ): List<User>? {
            println("COOKIES: $cookie")
            val token = cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") || it.startsWith("oauth_token=") }
                ?.split("=", limit = 2)
                ?.getOrNull(1)
                ?.trim()
                ?.removeSurrounding("\"")
                ?: return null

            oauthToken = token

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
                cover = root["avatar_url"]?.jsonPrimitive?.content?.toImageHolder(),
                extras = mapOf("auth_token" to token)
            )
            return listOf(user)
        }
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        oauthToken = user?.extras?.get("auth_token")
    }

    override suspend fun getCurrentUser(): User? = currentUser

    // ---- HOME FEED ----

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        if (oauthToken != null) {
            runCatching {
                val recentRequest = Request.Builder()
                    .url("https://api-v2.soundcloud.com/me/play-history/tracks?client_id=$clientId&limit=10")
                    .header("Authorization", "OAuth $oauthToken")
                    .build()
                val recentResponse = httpClient.newCall(recentRequest).await()
                val recentBody = recentResponse.body?.string()
                if (!recentBody.isNullOrBlank()) {
                    val recentRoot = json.parseToJsonElement(recentBody).jsonObject
                    val recentTracks = recentRoot["collection"]!!.jsonArray.mapNotNull {
                        it.jsonObject["track"]?.jsonObject?.toTrack()
                    }
                    if (recentTracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "recently_played",
                            title = "Recently Played",
                            list = recentTracks
                        ))
                    }
                }
            }

            runCatching {
                val streamRequest = Request.Builder()
                    .url("https://api-v2.soundcloud.com/stream?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $oauthToken")
                    .build()
                val streamResponse = httpClient.newCall(streamRequest).await()
                val streamBody = streamResponse.body?.string()
                if (!streamBody.isNullOrBlank()) {
                    val streamRoot = json.parseToJsonElement(streamBody).jsonObject
                    val streamTracks = streamRoot["collection"]!!.jsonArray.mapNotNull {
                        val obj = it.jsonObject
                        val track = obj["track"]?.jsonObject
                            ?: if (obj["kind"]?.jsonPrimitive?.content == "track") obj else null
                        track?.toTrack()
                    }
                    if (streamTracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "your_stream",
                            title = "Your Stream",
                            list = streamTracks
                        ))
                    }
                }
            }
        }

        runCatching {
            val trendingRequest = Request.Builder()
                .url("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud:genres:all-music&client_id=$clientId&limit=20")
                .build()
            val trendingResponse = httpClient.newCall(trendingRequest).await()
            val trendingBody = trendingResponse.body?.string()
            if (!trendingBody.isNullOrBlank()) {
                val trendingRoot = json.parseToJsonElement(trendingBody).jsonObject
                val trendingTracks = trendingRoot["collection"]!!.jsonArray.mapNotNull {
                    it.jsonObject["track"]?.jsonObject?.toTrack()
                }
                if (trendingTracks.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks(
                        id = "trending",
                        title = "Trending",
                        list = trendingTracks
                    ))
                }
            }
        }

        return shelves.toFeed()
    }

    // ---- LIBRARY ----

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val token = oauthToken ?: return listOf<Shelf>().toFeed()
        val userId = currentUser?.id ?: return listOf<Shelf>().toFeed()
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val likedRequest = Request.Builder()
                .url("https://api-v2.soundcloud.com/users/$userId/track_likes?client_id=$clientId&limit=20")
                .header("Authorization", "OAuth $token")
                .build()
            val likedResponse = httpClient.newCall(likedRequest).await()
            val body = likedResponse.body?.string()
            if (!body.isNullOrBlank()) {
                val likedRoot = json.parseToJsonElement(body).jsonObject
                val likedTracks = likedRoot["collection"]!!.jsonArray.mapNotNull {
                    val obj = it.jsonObject
                    obj["track"]?.jsonObject?.toTrack()
                        ?: if (obj["kind"]?.jsonPrimitive?.content == "track") obj.toTrack() else null
                }
                if (likedTracks.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks(
                        id = "liked_tracks",
                        title = "Liked Tracks",
                        list = likedTracks
                    ))
                }
            }
        }

        return shelves.toFeed()
    }

    // ---- SEARCH ----

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val url = "https://api-v2.soundcloud.com/search/tracks?q=$query&client_id=$clientId&limit=20"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string()
        if (body.isNullOrBlank()) return listOf<Shelf>().toFeed()
        val root = json.parseToJsonElement(body).jsonObject
        val collection = root["collection"]!!.jsonArray
        val tracks = collection.map { it.jsonObject.toTrack() }
        val shelf = Shelf.Lists.Tracks(
            id = "search_results",
            title = "Results",
            list = tracks
        )
        return listOf<Shelf>(shelf).toFeed()
    }

    // ---- ARTIST ----

    override suspend fun loadArtist(artist: Artist): Artist {
        val request = Request.Builder()
            .url("https://api-v2.soundcloud.com/users/${artist.id}?client_id=$clientId")
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string() ?: return artist
        val root = json.parseToJsonElement(body).jsonObject
        return Artist(
            id = artist.id,
            name = root["username"]!!.jsonPrimitive.content,
            cover = root["avatar_url"]?.jsonPrimitive?.content
                ?.replace("large", "t500x500")?.toImageHolder(),
            bio = root["description"]?.jsonPrimitive?.content,
            subtitle = root["followers_count"]?.jsonPrimitive?.long?.let { "$it followers" }
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val request = Request.Builder()
            .url("https://api-v2.soundcloud.com/users/${artist.id}/tracks?client_id=$clientId&limit=20")
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body?.string()
        if (body.isNullOrBlank()) return listOf<Shelf>().toFeed()
        val root = json.parseToJsonElement(body).jsonObject
        val tracks = root["collection"]!!.jsonArray.mapNotNull {
            runCatching { it.jsonObject.toTrack() }.getOrNull()
        }
        val shelf = Shelf.Lists.Tracks(
            id = "artist_tracks",
            title = "Tracks",
            list = tracks
        )
        return listOf<Shelf>(shelf).toFeed()
    }

    // ---- TRACK ----

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val url = "${streamable.id}?client_id=$clientId"
        val builder = Request.Builder().url(url)
        oauthToken?.let { builder.header("Authorization", "OAuth $it") }
        val response = httpClient.newCall(builder.build()).await()
        val body = response.body?.string()
        if (body.isNullOrBlank()) throw Exception("Empty stream response")
        val root = json.parseToJsonElement(body).jsonObject
        val streamUrl = root["url"]!!.jsonPrimitive.content
        return streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ---- HELPER ----

    private fun kotlinx.serialization.json.JsonObject.toTrack(): Track {
        val id = this["id"]!!.jsonPrimitive.long.toString()
        val title = this["title"]!!.jsonPrimitive.content
        val duration = this["duration"]!!.jsonPrimitive.long
        val artwork = this["artwork_url"]?.jsonPrimitive?.content
            ?.replace("large", "t500x500") ?: ""
        val user = this["user"]!!.jsonObject
        val artist = Artist(
            id = user["id"]!!.jsonPrimitive.long.toString(),
            name = user["username"]!!.jsonPrimitive.content,
            cover = (user["avatar_url"]?.jsonPrimitive?.content ?: "").toImageHolder()
        )
        val transcodings = this["media"]?.jsonObject?.get("transcodings")?.jsonArray
        var progressiveUrl = ""

        // Try progressive first
        transcodings?.forEach { t ->
            val obj = t.jsonObject
            val protocol = obj["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.content
            if (protocol == "progressive" && progressiveUrl.isEmpty()) {
                progressiveUrl = obj["url"]!!.jsonPrimitive.content
            }
        }
        // Fallback to hls
        if (progressiveUrl.isEmpty()) {
            transcodings?.forEach { t ->
                val obj = t.jsonObject
                val protocol = obj["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.content
                if (protocol == "hls") {
                    progressiveUrl = obj["url"]!!.jsonPrimitive.content
                }
            }
        }

        return Track(
            id = id,
            title = title,
            artists = listOf(artist),
            cover = artwork.toImageHolder(),
            duration = duration,
            streamables = if (progressiveUrl.isNotEmpty())
                listOf(Streamable.server(id = progressiveUrl, quality = 0))
            else emptyList()
        )
    }
}