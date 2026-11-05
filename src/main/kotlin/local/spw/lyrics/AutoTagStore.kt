package local.spw.lyrics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/** Only protection flags and scan checkpoints; no previous tags or change history. */
class AutoTagStore(private val directory: Path) {
    private val seen by lazy {
        if (!Files.isDirectory(directory)) mutableSetOf<String>() else Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".seen") || it.matches(Regex("scan-[a-f0-9]{64}\\.properties")) }
                .map { if (it.startsWith("scan-")) it.removePrefix("scan-").removeSuffix(".properties") + ".seen" else it }
                .filter { !Files.exists(directory.resolve(it.removeSuffix(".seen") + ".pending")) }.toList().toMutableSet()
        }
    }
    private fun seenKey(path: String) = key(runCatching { TagFiles.localPath(path).toAbsolutePath().normalize().toString() }.getOrDefault(path).lowercase(Locale.ROOT)) + ".seen"
    @Synchronized fun wasSeen(path: String) = seenKey(path) in seen
    fun isDeferred(path: String) = Files.exists(directory.resolve(seenKey(path).removeSuffix(".seen") + ".pending"))
    @Synchronized fun defer(path: String) {
        Files.createDirectories(directory)
        Files.write(directory.resolve(seenKey(path).removeSuffix(".seen") + ".pending"), byteArrayOf())
        seen.remove(seenKey(path))
    }
    @Synchronized fun remember(path: String) {
        val name = seenKey(path)
        if (name !in seen) {
            Files.createDirectories(directory)
            Files.write(directory.resolve(name), byteArrayOf())
            seen.add(name)
        }
        Files.deleteIfExists(directory.resolve(name.removeSuffix(".seen") + ".pending"))
    }
    private fun key(value: String) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun pathKey(path: Path) = key((if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()).toString().lowercase(Locale.ROOT))
    private fun markers(id: String, path: Path?): List<Path> = listOfNotNull(
        id.takeIf { it.isNotBlank() }?.let { directory.resolve("id-${key(it)}.protected") },
        path?.let { directory.resolve("path-${pathKey(it)}.protected") })

    @Synchronized fun isProtected(id: String, path: Path?) = markers(id, path).any(Files::exists)
    @Synchronized fun protect(id: String, path: Path?) {
        Files.createDirectories(directory)
        markers(id, path).forEach { if (!Files.exists(it)) Files.createFile(it) }
    }
    @Synchronized fun <T> protectWrite(id: String, path: Path, write: () -> T): T {
        val added = markers(id, path).filterNot(Files::exists)
        protect(id, path)
        // Persist before replacement so a crash after rename cannot leave a modified song unprotected.
        try { return write() }
        catch (failure: Throwable) {
            added.forEach { marker -> runCatching { Files.deleteIfExists(marker) }.onFailure { failure.addSuppressed(it) } }
            throw failure
        }
    }
    fun importLegacy(backups: Path) {
        if (!Files.isDirectory(backups)) return
        Files.list(backups).use { files -> files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".txt") && Files.size(it) <= 16_384 }.forEach {
            val value = Files.readString(it).trim()
            val path = runCatching { Path.of(value) }.getOrNull()
            if (path != null && path.isAbsolute && !value.startsWith("\\\\") && !value.contains("://")) protect("", path)
        } }
    }
    fun stamp(path: Path) = "${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}"
    @Synchronized fun due(path: Path, stamp: String, policy: String, now: Long): Boolean {
        val state = directory.resolve("scan-${pathKey(path)}.properties")
        if (!Files.exists(state)) return true
        require(Files.size(state) <= 16_384) { "后台扫描状态无效" }
        val p = Properties().apply { Files.newInputStream(state).use { load(it) } }
        return p.getProperty("stamp") != stamp || p.getProperty("policy") != policy || now >= (p.getProperty("retryAt")?.toLongOrNull() ?: 0)
    }
    @Synchronized fun checked(path: Path, stamp: String, policy: String, retryAt: Long) {
        Files.createDirectories(directory)
        val state = directory.resolve("scan-${pathKey(path)}.properties")
        val temporary = Files.createTempFile(directory, "scan-", ".tmp")
        try {
            val p = Properties().apply { setProperty("stamp", stamp); setProperty("policy", policy); setProperty("retryAt", retryAt.toString()) }
            Files.newOutputStream(temporary).use { p.store(it, null) }
            try { Files.move(temporary, state, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, state, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
