package local.spw.lyrics

import kotlinx.serialization.json.*

/** QQ suggestions omit album/duration; always resolve their identities through song details. */
object QqMusic {
    const val URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    fun parameters(data: JsonElement) = arrayOf("format" to "json", "inCharset" to "utf-8", "outCharset" to "utf-8", "data" to data.toString())
    fun detail(mid: String, id: String) = buildJsonObject {
        put("module", "music.pf_song_detail_svr"); put("method", "get_song_detail_yqq")
        putJsonObject("param") { put("song_mid", mid); id.toLongOrNull()?.let { put("song_id", it) } }
    }
    fun search(query: String, request: (String, Array<out Pair<String, String>>) -> JsonElement): JsonElement {
        val data = buildJsonObject {
            putJsonObject("comm") { put("ct", 19); put("cv", 0); put("format", "json") }
            putJsonObject("search") {
                put("module", "music.search.SearchCgiService"); put("method", "DoSearchForQQMusicDesktop")
                putJsonObject("param") { put("query", query); put("search_type", 0); put("num_per_page", 50); put("page_num", 1) }
            }
        }
        try {
            val root = request(URL, parameters(data))
            require(records(root).isNotEmpty()) { "QQ 音乐未返回歌曲" }
            return root
        } catch (cancel: InterruptedException) { throw cancel } catch (_: Exception) { }
        try {
            val root = request("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", arrayOf("format" to "json", "w" to query, "n" to "50", "p" to "1", "outCharset" to "utf-8"))
            require(records(root).isNotEmpty()) { "QQ 音乐未返回歌曲" }
            return root
        } catch (cancel: InterruptedException) { throw cancel } catch (_: Exception) { }
        val root = request("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg", arrayOf("format" to "json", "key" to query, "outCharset" to "utf-8"))
        require(root.jsonObject["code"]?.jsonPrimitive?.intOrNull == 0) { "QQ 音乐搜索暂不可用" }
        val songs = root.jsonObject["data"]?.jsonObject?.get("song")?.jsonObject?.get("itemlist").array().take(20)
        if (songs.isEmpty()) return root
        val batch = buildJsonObject {
            putJsonObject("comm") { put("ct", 19); put("cv", 0) }
            songs.forEachIndexed { index, song -> put("detail$index", detail(song.jsonObject.string("mid"), song.jsonObject.string("id"))) }
        }
        val details = request(URL, parameters(batch))
        val found = songs.mapIndexedNotNull { index, entry ->
            val song = details.jsonObject["detail$index"]?.jsonObject?.get("data")?.jsonObject?.get("track_info") as? JsonObject
            song?.takeIf { it.string("mid") == entry.jsonObject.string("mid") && it.string("id") == entry.jsonObject.string("id") }
        }
        require(found.isNotEmpty()) { "QQ 音乐歌曲详情获取失败" }
        return buildJsonObject { put("code", 0); putJsonObject("data") { putJsonObject("song") { put("list", JsonArray(found)) } } }
    }
    fun records(root: JsonElement): List<JsonObject> {
        val search = root.jsonObject["search"] as? JsonObject
        require((search ?: root.jsonObject)["code"]?.jsonPrimitive?.intOrNull == 0) { "QQ 音乐搜索暂不可用" }
        val data = search?.get("data")?.jsonObject?.get("body")?.jsonObject ?: root.jsonObject["data"]?.jsonObject
        return data?.get("song")?.jsonObject?.get("list").array().flatMap { listOf(it) + it.jsonObject["grp"].array() }
            .map { it.jsonObject }.distinctBy { it.string("mid").ifBlank { it.string("songmid") } }
    }
}
