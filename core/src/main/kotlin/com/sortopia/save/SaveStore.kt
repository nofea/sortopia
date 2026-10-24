package com.sortopia.save

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/** Computes the save's HMAC. On Android the key lives in the Keystore and never leaves it. */
fun interface MacProvider {
    fun mac(data: ByteArray): ByteArray
}

enum class LoadOutcome {
    /** No save existed yet. */
    NEW,
    OK,

    /** The main save was bad; the last valid backup was restored. */
    RESTORED_BACKUP,

    /** No valid save or backup; started fresh. */
    RESET,
}

data class LoadResult(
    val data: SaveData,
    val outcome: LoadOutcome,
)

@Serializable
private data class Envelope(
    val format: Int,
    val payload: String,
    val mac: String,
)

/**
 * Loads and saves [SaveData] in app-private storage (SPEC §16): HMAC-checked, strictly validated,
 * written atomically, with two rotating backups. Never throws on bad data.
 */
class SaveStore(
    private val dir: Path,
    private val macProvider: MacProvider,
) {
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    private val main = dir.resolve(MAIN)
    private val backups = listOf(dir.resolve(BACKUP_1), dir.resolve(BACKUP_2))

    fun load(): LoadResult {
        val candidates = listOf(main) + backups
        val existing = candidates.filter { Files.exists(it) }
        if (existing.isEmpty()) return LoadResult(SaveData(), LoadOutcome.NEW)
        candidates.forEachIndexed { index, path ->
            val data = read(path)
            if (data != null) return LoadResult(data, if (index == 0) LoadOutcome.OK else LoadOutcome.RESTORED_BACKUP)
        }
        return LoadResult(SaveData(), LoadOutcome.RESET)
    }

    /** Writes [data]; the previous main save becomes backup 1 and backup 1 becomes backup 2. */
    fun save(data: SaveData) {
        Files.createDirectories(dir)
        val bytes = encode(data)
        if (Files.exists(backups[0])) atomicWrite(backups[1], Files.readAllBytes(backups[0]))
        if (Files.exists(main)) atomicWrite(backups[0], Files.readAllBytes(main))
        atomicWrite(main, bytes)
    }

    /**
     * Replaces the save with [data] and deletes the backups, so a cleared save can never be
     * restored from them (SPEC §16). The main file is written first: a crash in between leaves
     * either the old save or the new one, never neither.
     */
    fun reset(data: SaveData) {
        Files.createDirectories(dir)
        atomicWrite(main, encode(data))
        backups.forEach { Files.deleteIfExists(it) }
    }

    fun encode(data: SaveData): ByteArray {
        val payload = json.encodeToString(SaveData.serializer(), data)
        val mac = Base64.getEncoder().encodeToString(macProvider.mac(payload.toByteArray(Charsets.UTF_8)))
        return json.encodeToString(Envelope.serializer(), Envelope(FORMAT, payload, mac)).toByteArray(Charsets.UTF_8)
    }

    /** Decodes and verifies one save file; null if it is unreadable, tampered or inconsistent. */
    @Suppress("ReturnCount") // Each check rejects untrusted input as early as possible.
    fun decode(bytes: ByteArray): SaveData? {
        if (bytes.size > MAX_FILE_BYTES) return null
        return try {
            val envelope = json.decodeFromString(Envelope.serializer(), bytes.toString(Charsets.UTF_8))
            if (envelope.format != FORMAT) return null
            val expected = macProvider.mac(envelope.payload.toByteArray(Charsets.UTF_8))
            val actual = Base64.getDecoder().decode(envelope.mac)
            if (!MessageDigest.isEqual(expected, actual)) return null
            val data = json.decodeFromString(SaveData.serializer(), envelope.payload)
            if (SaveValidator.validate(data).isNotEmpty()) return null
            SaveValidator.repair(data)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun read(path: Path): SaveData? =
        try {
            if (Files.size(path) > MAX_FILE_BYTES) null else decode(Files.readAllBytes(path))
        } catch (e: IOException) {
            null
        }

    private fun atomicWrite(
        target: Path,
        bytes: ByteArray,
    ) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        FileOutputStream(tmp.toFile()).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAIN = "save.json"
        const val BACKUP_1 = "save.bak1.json"
        const val BACKUP_2 = "save.bak2.json"
        private const val FORMAT = 1
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
