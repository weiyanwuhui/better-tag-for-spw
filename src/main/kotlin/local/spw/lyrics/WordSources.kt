package local.spw.lyrics

import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Native lyric service wire formats; cryptographic keys here are public protocol constants. */
object WordSources {
    fun qq(candidate: Candidate): DownloadedLyrics {
        val id = (candidate.songId.ifBlank { candidate.id }).toLongOrNull()
        require(id != null && id > 0) { "QQ 歌曲编号无效，请重新搜索" }
        fun base64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val payload = buildJsonObject {
            putJsonObject("comm") { put("ct", 19); put("cv", 2111); put("uin", 0) }
            putJsonObject("request") {
                put("module", "music.musichallSong.PlayLyricInfo"); put("method", "GetPlayLyricInfo")
                putJsonObject("param") {
                    put("songID", id); put("songName", base64(candidate.title)); put("singerName", base64(candidate.artist))
                    put("albumName", base64(candidate.album)); put("interval", candidate.duration / 1000)
                    put("crypt", 1); put("qrc", 1); put("trans", 1); put("roma", 1)
                    listOf("qrc_t", "lrc_t", "trans_t", "roma_t", "type").forEach { put(it, 0) }
                    put("ct", 19); put("cv", 2111)
                }
            }
        }
        val root = Network.json("https://u.y.qq.com/cgi-bin/musicu.fcg", "data" to payload.toString()).jsonObject
        val response = root["request"]?.jsonObject
        require(root["code"]?.jsonPrimitive?.intOrNull == 0 && response?.get("code")?.jsonPrimitive?.intOrNull == 0) { "QQ 音乐歌词暂时不可用" }
        val data = response["data"]?.jsonObject ?: error("QQ 音乐没有返回歌词")
        require(data["songID"]?.jsonPrimitive?.longOrNull == id) { "QQ 返回的歌曲编号不匹配" }
        fun part(key: String) = data[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(QrcCrypto::decrypt)
        return DownloadedLyrics(part("lyric") ?: error("这首歌没有 QQ 歌词"), candidate.source.label, part("trans"), part("roma"))
    }

    fun netease(id: String): JsonObject {
        require(id.matches(Regex("[0-9]+"))) { "歌曲编号无效" }
        val path = "/api/song/lyric/v1"
        val payload = buildJsonObject {
            put("id", id); listOf("lv", "tv", "rv", "yv").forEach { put(it, "-1") }
            put("e_r", false)
        }.toString()
        val signature = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest("nobody${path}use${payload}md5forencrypt".toByteArray()))
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        val body = "params=" + HexFormat.of().withUpperCase().formatHex(cipher.doFinal("$path-36cd479b6b5-$payload-36cd479b6b5-$signature".toByteArray()))
        val data = Network.bytes(URI.create("https://interface.music.163.com/eapi/song/lyric/v1"), 4_194_304, "application/json", body)
        return Json.parseToJsonElement(String(data, Charsets.UTF_8)).jsonObject.also {
            require(it["code"]?.jsonPrimitive?.intOrNull == 200) { "网易云歌词暂时不可用" }
        }
    }

    fun krc(data: ByteArray): String {
        require(data.size > 4 && String(data, 0, 4, Charsets.US_ASCII) == "krc1") { "来源未返回逐字歌词" }
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val compressed = ByteArray(data.size - 4) { (data[it + 4].toInt() xor key[it % key.size]).toByte() }
        val plain = InflaterInputStream(compressed.inputStream()).use { it.readNBytes(LyricsStore.MAX_LYRICS_BYTES + 1) }
        require(plain.size <= LyricsStore.MAX_LYRICS_BYTES) { "逐字歌词解压后过大" }
        return String(plain, Charsets.UTF_8)
    }
}
