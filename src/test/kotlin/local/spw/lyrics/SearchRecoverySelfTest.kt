package local.spw.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.zip.DeflaterOutputStream

fun checkSearchRecovery() = runBlocking {
    val song = Candidate(LyricSource.KUGOU, "hash", "Melody", "陶喆", duration = 269000, hash = "hash")
    val word = "[ti:Melody]\n[ar:陶喆 (David Tao)]\n[1000,1000]<0,400,0>你<400,600,0>好"
    val line = "[ti:Melody]\n[ar:陶喆]\n[1000,1000]<0,1000,0>你好"
    fun encrypted(text: String): String {
        val stream = java.io.ByteArrayOutputStream()
        DeflaterOutputStream(stream).use { it.write(text.toByteArray()) }
        val key = intArrayOf(64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105)
        val bytes = stream.toByteArray().mapIndexed { i, byte -> (byte.toInt() xor key[i % 16]).toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString("krc1".toByteArray() + bytes)
    }
    fun response(text: String) = buildJsonObject { put("status",200); put("content",encrypted(text)) }
    val lyricCandidates = buildJsonObject {
        put("status",200)
        putJsonArray("candidates") { for (id in listOf("bad", "good")) addJsonObject {
            put("id",id);put("accesskey",id);put("song","Melody");put("singer","陶喆");put("duration",269000)
        } }
    }
    for (format in listOf(LyricsFormat.WORD, LyricsFormat.ENHANCED, LyricsFormat.AUTO)) {
        for (first in listOf("download-error", "line-only", "wrong-header")) {
            val calls = mutableListOf<String>()
            val sources = LyricSources { url, pairs ->
                val params = pairs.toMap()
                if (url.endsWith("/search")) lyricCandidates else {
                    calls += params["id"] + ":" + params["fmt"]
                    if (params["id"] == "good") response(word)
                    else when (first) {
                        "download-error" -> buildJsonObject { put("status",500) }
                        "line-only" -> response(line)
                        else -> response(word.replace("[ti:Melody]", "[ti:other song]"))
                    }
                }
            }
            check(sources.download(song, format).document().hasWords)
            check(calls == listOf("bad:krc","good:krc")) { "A bad first candidate blocked a real later word candidate: $calls" }
        }
    }
    check(LyricSources.kugouMatches(song, "Melody", "陶喆 (David Tao)", 269000))
    check(LyricSources.kugouMatches(song.copy(artist="王力宏 / 单依纯"), "Melody", "单依纯 & 王力宏", 269000))
    check(!LyricSources.kugouMatches(song, "Melody", "其他歌手", 269000))
    check(!LyricSources.kugouMatches(song, "Melody (Live)", "陶喆", 269000))
    val probeIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val source = LyricSources { url, pairs ->
        val params = pairs.toMap()
        when {
            url.contains("/api/v3/search/song") -> buildJsonObject {
                put("status",1)
                putJsonObject("data") {
                    putJsonArray("info") {
                        repeat(11) { i -> addJsonObject {
                            put("hash","song$i");put("songname","Melody");put("singername","陶喆");put("duration",269)
                        } }
                    }
                }
            }
            url.endsWith("/search") -> buildJsonObject {
                put("status",200);putJsonArray("candidates") { addJsonObject {
                    put("id",params["hash"]);put("accesskey","key");put("song","Melody");put("singer","陶喆");put("duration",269000)
                } }
            }
            else -> { val id=params.getValue("id");probeIds.add(id);response(if(id=="song10")word else line) }
        }
    }
    val recovered = source.searchTyped(LyricSource.KUGOU,"Melody","陶喆",LyricsFormat.WORD)
    check(recovered.size == 1 && recovered.single().id == "song10" && probeIds.size == 11) { "Word data after the first ten songs was skipped" }
    var qqCalls=0
    val qq = LyricSources { url, _ ->
        check(url.contains("client_search_cp")) { "QQ full search fell back to suggestion results" };qqCalls++
        buildJsonObject { put("code",0);putJsonObject("data") { putJsonObject("song") { putJsonArray("list") {
            repeat(20) { i -> addJsonObject {
                put("songid",i+1);put("songmid","mid$i");put("songname","Melody");put("albumname","黑色柳丁");put("interval",269)
                putJsonArray("singer") { addJsonObject { put("name","陶喆") } }
            } }
        } } } }
    }
    val qqResults=qq.search(LyricSource.QQ,"Melody","陶喆")
    check(qqCalls==1 && qqResults.size==20 && qqResults.last().songId=="20" && qqResults.first().duration==269000L)
    println("PASS: first-candidate errors, line-only KRC and mismatched headers continue to later real words; all returned song candidates are checked; singer aliases and collaboration ordering match without accepting other recordings; QQ uses full search metadata.")
}
