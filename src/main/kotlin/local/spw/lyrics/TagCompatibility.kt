package local.spw.lyrics

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.ogg.OggOpusCommentTagCreator
import org.jaudiotagger.audio.ogg.util.OggCRCFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.audio.ogg.util.OggPageHeader
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.wav.WavTag
import org.jaudiotagger.audio.wav.WavOptions
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

internal fun writableTag(audio: AudioFile): Tag {
    val tag = audio.tagOrCreateAndSetDefault
    if (tag is WavTag) {
        if (tag.getID3Tag() == null) tag.setID3Tag(WavTag.createDefaultID3Tag())
        tag.syncToId3FromInfoIfEmpty()
        // Change only this file's reader mode; the host's global WAV preferences stay intact.
        WavTag::class.java.getDeclaredField("wavOptions").apply { isAccessible = true }.set(tag, WavOptions.READ_ID3_ONLY_AND_SYNC)
    }
    return tag
}

/** The bundled Opus writer miscounts lacing segments and retains the old header cursor. */
private fun writeOpus(tag: Tag, input: RandomAccessFile, output: RandomAccessFile) {
    input.seek(0)
    val head = OggPageHeader.read(input)
    val signature = ByteArray(8); input.readFully(signature)
    require(String(signature, Charsets.US_ASCII) == "OpusHead") { "Opus 头部无效" }
    val headEnd = head.startByte + head.rawHeaderData.size + head.pageLength
    input.seek(0); val originalHead = ByteArray(headEnd.toInt()); input.readFully(originalHead); output.write(originalHead)
    val comment = OggPageHeader.read(input)
    input.readFully(signature)
    require(String(signature, Charsets.US_ASCII) == "OpusTags") { "Opus 标签头无效" }
    var page = comment
    do {
        // RFC 7845 requires a page break between the comment packet and audio packets.
        require(page.segmentTable.isNotEmpty() && page.segmentTable.dropLast(1).all { (it.toInt() and 255) == 255 }) { "Opus 标签页结构不支持安全写入" }
        require(page.rawHeaderData.copyOfRange(14, 18).contentEquals(head.rawHeaderData.copyOfRange(14, 18))) { "Opus 流编号不一致" }
        input.seek(page.startByte + page.rawHeaderData.size + page.pageLength)
        if ((page.segmentTable.last().toInt() and 255) < 255) break
        page = OggPageHeader.read(input)
    } while (true)
    val packet = OggOpusCommentTagCreator().convert(tag).let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
    val lacing = ByteArray(packet.size / 255 + 1) { index -> if (index < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
    var sequence = comment.pageSequence
    var payloadOffset = 0
    fun writePage(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(18, sequence++); buffer.putInt(22, 0)
        OggCRCFactory.computeCRC(bytes).copyInto(bytes, 22)
        output.write(bytes)
    }
    for (segmentOffset in lacing.indices step 255) {
        val segments = lacing.copyOfRange(segmentOffset, minOf(lacing.size, segmentOffset + 255))
        val size = segments.sumOf { it.toInt() and 255 }
        val bytes = ByteArray(27 + segments.size + size)
        comment.rawHeaderData.copyInto(bytes, 0, 0, 27)
        bytes[5] = if (segmentOffset == 0) 0 else 1
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(6, 0)
        bytes[26] = segments.size.toByte(); segments.copyInto(bytes, 27)
        packet.copyInto(bytes, 27 + segments.size, payloadOffset, payloadOffset + size)
        payloadOffset += size; writePage(bytes)
    }
    while (input.filePointer < input.length()) {
        val audio = OggPageHeader.read(input)
        require(audio.rawHeaderData.copyOfRange(14, 18).contentEquals(head.rawHeaderData.copyOfRange(14, 18))) { "暂不支持串联的 Opus 音频标签写入" }
        val bytes = ByteArray(audio.rawHeaderData.size + audio.pageLength)
        audio.rawHeaderData.copyInto(bytes)
        input.readFully(bytes, audio.rawHeaderData.size, audio.pageLength)
        writePage(bytes)
    }
}

internal fun commitAudio(audio: AudioFile, path: Path) {
    if (path.fileName.toString().substringAfterLast('.').lowercase() != "opus") { audio.commit(); return }
    val output = Files.createTempFile(path.parent, ".opus-tags-", ".opus")
    try {
        RandomAccessFile(path.toFile(), "r").use { input -> RandomAccessFile(output.toFile(), "rw").use { target -> writeOpus(audio.tag, input, target) } }
        Files.move(output, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(output) }
}
