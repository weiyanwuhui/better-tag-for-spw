package local.spw.lyrics

import java.util.HexFormat
import java.util.zip.InflaterInputStream

/** QQ's QRC wire cipher changes DES byte order, key compression and two S-box entries. */
object QrcCrypto {
    private val sboxes = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7, 0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8, 4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0, 15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10, 3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5, 0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15, 13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8, 13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1, 13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7, 1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15, 13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9, 10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4, 3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9, 14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6, 4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14, 11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11, 10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8, 9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6, 4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1, 13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6, 1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2, 6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7, 1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2, 7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8, 2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11)
    )
    private val ip = IntArray(64) { i -> (if (i / 8 < 4) 57 + i / 8 * 2 else 56 + (i / 8 - 4) * 2) - i % 8 * 8 }
    private val fp = IntArray(64).also { table -> ip.forEachIndexed { i, bit -> table[bit] = i } }
    private val expansion = IntArray(48) { (it / 6 * 4 + it % 6 + 31) % 32 }
    private val p = intArrayOf(15,6,19,20,28,11,27,16,0,14,22,25,4,17,30,9,1,7,23,13,31,26,2,8,18,12,29,5,21,10,3,24)
    private val pc1 = intArrayOf(56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3)
    private val pc2 = intArrayOf(13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32)
    private val shifts = intArrayOf(1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1)
    private val key = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
    private val schedules = arrayOf(schedule(16).reversedArray(), schedule(8), schedule(0).reversedArray())

    private fun permute(value: Long, bits: Int, table: IntArray): Long {
        var result = 0L
        table.forEach { result = (result shl 1) or if (it >= bits) 0 else ((value ushr (bits - 1 - it)) and 1) }
        return result
    }
    private fun wordBlock(data: ByteArray, offset: Int): Long {
        var result = 0L
        repeat(8) { i -> result = (result shl 8) or (data[offset + i / 4 * 4 + 3 - i % 4].toLong() and 255) }
        return result
    }
    private fun schedule(offset: Int): LongArray {
        val selected = permute(wordBlock(key, offset), 64, pc1)
        var left = selected ushr 28
        var right = selected and 0xfffffff
        return LongArray(16) { round ->
            val shift = shifts[round]
            left = ((left shl shift) or (left ushr (28 - shift))) and 0xfffffff
            right = ((right shl shift) or (right ushr (28 - shift))) and 0xfffffff
            permute((left shl 28) or right, 56, pc2)
        }
    }
    private fun des(block: Long, keys: LongArray): Long {
        val input = permute(block, 64, ip)
        var left = input ushr 32
        var right = input and 0xffffffffL
        keys.forEach { subkey ->
            val expanded = permute(right, 32, expansion) xor subkey
            var substituted = 0L
            repeat(8) { box ->
                val six = ((expanded ushr (42 - box * 6)) and 63).toInt()
                val row = ((six and 32) shr 4) or (six and 1)
                substituted = (substituted shl 4) or sboxes[box][row * 16 + ((six shr 1) and 15)].toLong()
            }
            val next = left xor permute(substituted, 32, p)
            left = right; right = next
        }
        return permute((right shl 32) or left, 64, fp)
    }
    fun decrypt(hex: String): String {
        require(hex.isNotBlank() && hex.length <= 8_388_608 && hex.length % 16 == 0) { "QQ 逐字歌词密文无效" }
        val data = HexFormat.of().parseHex(hex)
        for (offset in data.indices step 8) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("QQ 歌词解密已取消")
            var block = wordBlock(data, offset)
            schedules.forEach { block = des(block, it) }
            repeat(8) { i -> data[offset + i / 4 * 4 + 3 - i % 4] = (block ushr (56 - i * 8)).toByte() }
        }
        val plain = InflaterInputStream(data.inputStream()).use { it.readNBytes(LyricsStore.MAX_LYRICS_BYTES + 1) }
        require(plain.size <= LyricsStore.MAX_LYRICS_BYTES) { "QQ 逐字歌词解压后过大" }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
    }
}
