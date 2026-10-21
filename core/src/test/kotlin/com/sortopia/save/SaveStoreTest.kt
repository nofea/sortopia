package com.sortopia.save

import com.sortopia.gen.Tuning
import com.sortopia.model.LevelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SaveStoreTest {
    @TempDir
    lateinit var dir: Path

    private val mac =
        MacProvider { data ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec("test-key".toByteArray(), "HmacSHA256"))
                doFinal(data)
            }
        }

    private fun store() = SaveStore(dir, mac)

    private val sample =
        SaveData(
            wallet = 420,
            best = mapOf("c-1-1" to 90, "c-1-2" to 100),
            flags = listOf("tutorial-done"),
            settings = Settings(music = false),
            inProgress = InProgress("c-1-3", listOf("p0>2", "u"), 4.5),
        )

    @Test
    fun `round trip`() {
        store().save(sample)
        val r = store().load()
        assertEquals(LoadOutcome.OK, r.outcome)
        assertEquals(sample, r.data)
    }

    @Test
    fun `no save means a new game`() {
        val r = store().load()
        assertEquals(LoadOutcome.NEW, r.outcome)
        assertEquals(SaveData(), r.data)
    }

    @Test
    fun `edited save is detected and the last valid backup is restored`() {
        val s = store()
        s.save(sample)
        s.save(sample.copy(wallet = 500))
        val main = dir.resolve(SaveStore.MAIN)
        Files.writeString(main, Files.readString(main).replace("500", "99999"))
        val r = s.load()
        assertEquals(LoadOutcome.RESTORED_BACKUP, r.outcome)
        assertEquals(420, r.data.wallet)
    }

    @Test
    fun `garbage everywhere resets without crashing`() {
        listOf(SaveStore.MAIN, SaveStore.BACKUP_1, SaveStore.BACKUP_2).forEach {
            Files.write(dir.resolve(it), byteArrayOf(0, 1, 2, 3, -1))
        }
        val r = store().load()
        assertEquals(LoadOutcome.RESET, r.outcome)
        assertEquals(SaveData(), r.data)
    }

    @Test
    fun `save signed with another key is rejected`() {
        val other = SaveStore(dir, MacProvider { ByteArray(32) })
        other.save(sample)
        assertEquals(LoadOutcome.RESET, store().load().outcome)
    }

    @Test
    fun `validly signed but inconsistent data is rejected`() {
        val s = store()
        assertNull(s.decode(s.encode(sample.copy(wallet = -1))))
        assertNull(s.decode(s.encode(SaveData(best = mapOf("c-1-1" to Tuning.basePoints(LevelId.Campaign(1, 1)) + 1)))))
        assertNull(s.decode(s.encode(SaveData(best = mapOf("c-1-5" to 10)))), "level 5 solved without 1-4")
        assertNull(s.decode(s.encode(SaveData(best = mapOf("c-2-1" to 10)))), "world 2 never unlocked")
        assertNull(s.decode(s.encode(SaveData(best = mapOf("e-1" to 10)))), "endless before world 8")
        assertNull(s.decode(s.encode(SaveData(best = mapOf("x-1" to 10)))))
        assertNull(s.decode(s.encode(SaveData(dailyClaims = listOf("2026-10-21")))), "daily before world 1 done")
        assertNull(s.decode(s.encode(SaveData(flags = listOf("BAD FLAG")))))
        assertNull(s.decode(s.encode(SaveData(version = 99))))
    }

    @Test
    fun `unknown fields are rejected by the strict schema`() {
        val s = store()
        val tampered = String(s.encode(sample)).replace("\\\"wallet\\\":420", "\\\"wallet\\\":420,\\\"cheat\\\":1")
        assertTrue(tampered.contains("cheat"))
        assertNull(s.decode(tampered.toByteArray()))
    }

    @Test
    fun `invalid in-progress level is dropped but the rest is kept`() {
        val s = store()
        val bad = sample.copy(inProgress = InProgress("c-5-1", listOf("p0>1"), 1.0))
        val decoded = s.decode(s.encode(bad))!!
        assertNull(decoded.inProgress)
        assertEquals(420, decoded.wallet)
        val badEvents = sample.copy(inProgress = InProgress("c-1-3", listOf("rm -rf"), 1.0))
        assertNull(s.decode(s.encode(badEvents))!!.inProgress)
    }

    @Test
    fun `writes are atomic and leave no temp files`() {
        val s = store()
        s.save(sample)
        s.save(sample)
        s.save(sample)
        val names = Files.list(dir).use { st -> st.map { it.fileName.toString() }.toList() }.toSet()
        assertEquals(setOf(SaveStore.MAIN, SaveStore.BACKUP_1, SaveStore.BACKUP_2), names)
        assertFalse(names.any { it.endsWith(".tmp") })
    }
}
