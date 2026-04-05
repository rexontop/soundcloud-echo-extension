package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SoundCloudExtension : ExtensionClient, QuickSearchClient, TrackClient,
    HomeFeedClient, LibraryFeedClient, LoginClient.WebView, ArtistClient,
    PlaylistClient, RadioClient, LikeClient, FollowClient {

    private val httpClient = OkHttpClient()
    private lateinit var setting: Settings
    private val json = Json { ignoreUnknownKeys = true }
    private var clientId = "tkIWLs4MIowq7bCXP80TOwx6DnDa7UPc"

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
        clientId = settings.getString("client_id") ?: clientId
    }

    override suspend fun onInitialize() {
        refreshClientId()
    }

    private suspend fun refreshClientId() {
        runCatching {
            val html = httpClient.newCall(
                Request.Builder().url("https://soundcloud.com").build()
            ).await().body?.string() ?: return
            val scriptUrl = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
                .findAll(html).lastOrNull()?.groupValues?.get(1) ?: return
            val script = httpClient.newCall(
                Request.Builder().url(scriptUrl).build()
            ).await().body?.string() ?: return
            val id = Regex("""client_id\s*:\s*"([a-zA-Z0-9]+)"""")
                .find(script)?.groupValues?.get(1) ?: return
            clientId = id
            setting.putString("client_id", id)
        }
    }

    private fun authHeader(builder: Request.Builder): Request.Builder {
        oauthToken?.let { builder.header("Authorization", "OAuth $it") }
        return builder
    }

    // ---- LOGIN ----

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = NetworkRequest("https://soundcloud.com/signin")
        override val stopUrlRegex = Regex("soundcloud\\.com/discover|soundcloud\\.com/feed|soundcloud\\.com/you")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User>? {
            val token = cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") || it.startsWith("oauth_token=") }
                ?.split("=", limit = 2)?.getOrNull(1)?.trim()?.removeSurrounding("\"")
                ?: return null

            oauthToken = token
            setting.putString("auth_token", token)

            val body = httpClient.newCall(
                authHeader(Request.Builder().url("https://api-v2.soundcloud.com/me?client_id=$clientId")).build()
            ).await().body?.string() ?: return null
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

    // ---- LIKE ----

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val token = oauthToken ?: return false
        val trackId = when (item) {
            is Track -> item.id
            else -> return false
        }
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/me/track_likes/$trackId?client_id=$clientId")
                .header("Authorization", "OAuth $token").build()
        ).await()
        return body.code == 200
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val token = oauthToken ?: return
        val trackId = when (item) {
            is Track -> item.id
            else -> return
        }
        val url = "https://api-v2.soundcloud.com/me/track_likes/$trackId?client_id=$clientId"
        val request = if (shouldLike) {
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .put("".toRequestBody("application/json".toMediaType())).build()
        } else {
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .delete().build()
        }
        httpClient.newCall(request).await()
    }

    // ---- FOLLOW ----

    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val token = oauthToken ?: return false
        val artistId = when (item) {
            is Artist -> item.id
            else -> return false
        }
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/me/followings/$artistId?client_id=$clientId")
                .header("Authorization", "OAuth $token").build()
        ).await()
        return body.code == 200
    }

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        val token = oauthToken ?: return
        val artistId = when (item) {
            is Artist -> item.id
            else -> return
        }
        val url = "https://api-v2.soundcloud.com/me/followings/$artistId?client_id=$clientId"
        val request = if (shouldFollow) {
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .put("".toRequestBody("application/json".toMediaType())).build()
        } else {
            Request.Builder().url(url).header("Authorization", "OAuth $token")
                .delete().build()
        }
        httpClient.newCall(request).await()
    }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        val artistId = when (item) {
            is Artist -> item.id
            else -> return null
        }
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/$artistId?client_id=$clientId").build()
        ).await().body?.string() ?: return null
        return json.parseToJsonElement(body).jsonObject["followers_count"]?.jsonPrimitive?.longOrNull
    }

    // ---- HOME FEED ----

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        if (oauthToken != null) {
            runCatching {
                val body = httpClient.newCall(
                    authHeader(Request.Builder()
                        .url("https://api-v2.soundcloud.com/me/play-history/tracks?client_id=$clientId&limit=10")).build()
                ).await().body?.string()
                if (!body.isNullOrBlank()) {
                    val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                        .jsonArray.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                        .filter { !hideSnip || !it.extras.containsKey("snip") }
                    if (tracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "recently_played", title = "Recently Played", list = tracks))
                }
            }

            runCatching {
                val body = httpClient.newCall(
                    authHeader(Request.Builder()
                        .url("https://api-v2.soundcloud.com/stream?client_id=$clientId&limit=20")).build()
                ).await().body?.string()
                if (!body.isNullOrBlank()) {
                    val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                        .jsonArray.mapNotNull {
                            val obj = it.jsonObject
                            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                            val track = obj["track"]?.jsonObject
                                ?: if (kind == "track") obj else null
                            track?.toTrack()
                        }.filter { !hideSnip || !it.extras.containsKey("snip") }
                    if (tracks.isNotEmpty())
                        shelves.add(Shelf.Lists.Tracks(id = "your_stream", title = "Your Stream", list = tracks))
                }
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud:genres:all-music&client_id=$clientId&limit=20")
                    .build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { it.jsonObject["track"]?.jsonObject?.toTrack() }
                    .filter { !hideSnip || !it.extras.containsKey("snip") }
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "trending", title = "Trending", list = tracks))
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
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/$userId/track_likes?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $token").build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull {
                        val obj = it.jsonObject
                        obj["track"]?.jsonObject?.toTrack()
                            ?: if (obj["kind"]?.jsonPrimitive?.content == "track") obj.toTrack() else null
                    }.filter { !hideSnip || !it.extras.containsKey("snip") }
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "liked_tracks", title = "Liked Tracks", list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/me/playlists?client_id=$clientId&limit=20")
                    .header("Authorization", "OAuth $token").build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "playlists", title = "Playlists",
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- SEARCH ----

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/search/autocomplete?q=$query&client_id=$clientId&limit=5")
                .build()
        ).await().body?.string() ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["collection"]!!.jsonArray
                .mapNotNull { it.jsonObject["output"]?.jsonPrimitive?.contentOrNull }
                .map { QuickSearchItem.Query(it, false) }
        }.getOrDefault(emptyList())
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/tracks?q=$query&client_id=$clientId&limit=20")
                    .build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
                    .filter { !hideSnip || !it.extras.containsKey("snip") }
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "search_tracks", title = "Tracks", list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/playlists?q=$query&client_id=$clientId&limit=10")
                    .build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "search_playlists", title = "Playlists",
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/search/users?q=$query&client_id=$clientId&limit=10")
                    .build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val artists = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toArtist() }.getOrNull() }
                if (artists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "search_users", title = "Users",
                        list = artists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- ARTIST ----

    override suspend fun loadArtist(artist: Artist): Artist {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/users/${artist.id}?client_id=$clientId").build()
        ).await().body?.string() ?: return artist
        val root = json.parseToJsonElement(body).jsonObject
        return Artist(
            id = artist.id,
            name = root["username"]!!.jsonPrimitive.content,
            cover = root["avatar_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")?.toImageHolder(),
            bio = root["description"]?.jsonPrimitive?.contentOrNull,
            subtitle = root["followers_count"]?.jsonPrimitive?.longOrNull?.let { if (it > 0) "$it followers" else null },
            isFollowable = oauthToken != null
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/${artist.id}/tracks?client_id=$clientId&limit=20").build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
                    .filter { !hideSnip || !it.extras.containsKey("snip") }
                if (tracks.isNotEmpty())
                    shelves.add(Shelf.Lists.Tracks(id = "artist_tracks", title = "Tracks", list = tracks))
            }
        }

        runCatching {
            val body = httpClient.newCall(
                Request.Builder()
                    .url("https://api-v2.soundcloud.com/users/${artist.id}/playlists?client_id=$clientId&limit=10").build()
            ).await().body?.string()
            if (!body.isNullOrBlank()) {
                val playlists = json.parseToJsonElement(body).jsonObject["collection"]!!
                    .jsonArray.mapNotNull { runCatching { it.jsonObject.toPlaylist() }.getOrNull() }
                if (playlists.isNotEmpty())
                    shelves.add(Shelf.Lists.Items(
                        id = "artist_playlists", title = "Playlists",
                        list = playlists.map { it.toShelf().media }
                    ))
            }
        }

        return shelves.toFeed()
    }

    // ---- PLAYLIST ----

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/playlists/${playlist.id}/tracks?client_id=$clientId&limit=50").build()
        ).await().body?.string()
        if (body.isNullOrBlank()) return listOf<Track>().toFeed()
        val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
            .jsonArray.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
            .filter { !hideSnip || !it.extras.containsKey("snip") }
        return tracks.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    // ---- RADIO ----

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        return Radio(
            id = item.id,
            title = "Radio: ${item.title}",
            cover = item.cover,
            extras = mapOf("seed_id" to item.id)
        )
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val seedId = radio.extras["seed_id"] ?: radio.id
        val body = httpClient.newCall(
            Request.Builder()
                .url("https://api-v2.soundcloud.com/tracks/$seedId/related?client_id=$clientId&limit=20").build()
        ).await().body?.string()
        if (body.isNullOrBlank()) return listOf<Track>().toFeed()
        val tracks = json.parseToJsonElement(body).jsonObject["collection"]!!
            .jsonArray.mapNotNull { runCatching { it.jsonObject.toTrack() }.getOrNull() }
            .filter { !hideSnip || !it.extras.containsKey("snip") }
        return tracks.toFeed()
    }

    // ---- TRACK ----

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val url = "${streamable.id}?client_id=$clientId"
        val builder = Request.Builder().url(url)
        oauthToken?.let { builder.header("Authorization", "OAuth $it") }
        val body = httpClient.newCall(builder.build()).await().body?.string()
        if (body.isNullOrBlank()) throw Exception("Empty stream response")
        val streamUrl = json.parseToJsonElement(body).jsonObject["url"]!!.jsonPrimitive.content
        val isHls = streamable.id.contains("hls")
        return if (isHls) streamUrl.toServerMedia(type = Streamable.SourceType.HLS)
        else streamUrl.toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ---- HELPERS ----

    private fun kotlinx.serialization.json.JsonObject.toTrack(): Track {
        val id = this["id"]!!.jsonPrimitive.long.toString()
        val title = this["title"]!!.jsonPrimitive.content
        val duration = this["duration"]!!.jsonPrimitive.long
        val artwork = this["artwork_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")
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
                if (protocol == "hls") streamUrl = obj["url"]!!.jsonPrimitive.content
            }
        }

        return Track(
            id = id, title = title, artists = listOf(artist),
            cover = artwork?.toImageHolder(), duration = duration,
            isLikeable = oauthToken != null,
            extras = if (isSnip) mapOf("snip" to "true") else emptyMap(),
            streamables = if (streamUrl.isNotEmpty())
                listOf(Streamable.server(id = streamUrl, quality = 0))
            else emptyList()
        )
    }

    private fun kotlinx.serialization.json.JsonObject.toPlaylist(): Playlist {
        val id = this["id"]!!.jsonPrimitive.long.toString()
        val title = this["title"]!!.jsonPrimitive.content
        val artwork = this["artwork_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")
        val user = this["user"]?.jsonObject
        val artist = user?.let {
            Artist(
                id = it["id"]!!.jsonPrimitive.long.toString(),
                name = it["username"]!!.jsonPrimitive.content,
                cover = it["avatar_url"]?.jsonPrimitive?.contentOrNull?.toImageHolder()
            )
        }
        return Playlist(
            id = id, title = title, isEditable = false, isPrivate = false,
            cover = artwork?.toImageHolder(),
            authors = if (artist != null) listOf(artist) else emptyList(),
            trackCount = this["track_count"]?.jsonPrimitive?.longOrNull,
            description = this["description"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun kotlinx.serialization.json.JsonObject.toArtist(): Artist {
        return Artist(
            id = this["id"]!!.jsonPrimitive.long.toString(),
            name = this["username"]!!.jsonPrimitive.content,
            cover = this["avatar_url"]?.jsonPrimitive?.contentOrNull?.replace("large", "t500x500")?.toImageHolder(),
            subtitle = this["followers_count"]?.jsonPrimitive?.longOrNull?.let { if (it > 0) "$it followers" else null },
            isFollowable = oauthToken != null
        )
    }
}
