package com.sortopia.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ArtIndexTest {
    @Test
    fun `parses cells, faces and the grid`() {
        val index =
            ArtIndex.parse(
                """
                # comment
                cell=128
                grid=16
                fruit.apple.body=1,2,3
                face.fruit.apple=0.5,0.6,0.9
                """.trimIndent(),
            )
        assertEquals(128, index.cellPx)
        assertEquals(16, index.grid)
        assertEquals(ArtIndex.Cell(1, 2, 3), index.cells["fruit.apple.body"])
        assertEquals(ArtIndex.FaceAnchor(0.5f, 0.6f, 0.9f), index.faces["fruit.apple"])
    }

    @Test
    fun `skips malformed and out-of-range lines instead of failing`() {
        val index =
            ArtIndex.parse(
                """
                cell=128
                grid=16
                ok=0,0,0
                no-equals
                short=1,2
                letters=a,b,c
                col-too-big=0,16,0
                negative=0,-1,0
                sheet-too-big=99,0,0
                face.far=2,0.5,1
                face.tiny=0.5,0.5,0
                =0,0,0
                """.trimIndent(),
            )
        assertEquals(setOf("ok"), index.cells.keys)
        assertTrue(index.faces.isEmpty())
    }

    @Test
    fun `falls back to the defaults when the header is missing or broken`() {
        val index = ArtIndex.parse("cell=-5\ngrid=x\na=0,1,1")
        assertEquals(ArtIndex.DEFAULT_CELL, index.cellPx)
        assertEquals(ArtIndex.DEFAULT_GRID, index.grid)
        assertEquals(ArtIndex.Cell(0, 1, 1), index.cells["a"])
    }

    @Test
    fun `an empty index has nothing in it`() {
        val index = ArtIndex.parse("")
        assertTrue(index.cells.isEmpty())
        assertNull(index.faces["fruit.apple"])
    }

    @Test
    fun `the committed atlas has every layer and face of every themed item`() {
        val index = ArtIndex.parse(File("../assets/art/atlas.properties").readText())
        val arts = (ThemeCatalog.worlds.flatMap { it.items } + ThemeCatalog.endlessMixes.flatten()).map { it.art }.toSet()
        for (art in arts) {
            for (layer in listOf("under", "body", "over")) assertTrue("$art.$layer" in index.cells, "missing $art.$layer")
            assertTrue(art in index.faces, "missing face anchor for $art")
        }
        val themes = ThemeCatalog.worlds + ThemeCatalog.neutral
        for (t in themes) {
            assertTrue("${t.id}.lid" in index.cells, "missing ${t.id}.lid")
            assertTrue("${t.id}.lidtag" in index.cells, "missing ${t.id}.lidtag")
            assertTrue(File("../assets/art/bg/${t.id}-top.png").isFile, "missing ${t.id} background")
            assertTrue(File("../assets/art/bg/${t.id}-bottom.png").isFile, "missing ${t.id} background")
        }
        for (p in Particle.entries) assertTrue(p.art in index.cells, "missing ${p.art}")
        for (name in listOf("face.ink", "face.light", "ov.hidden", "ov.lock", "ov.label", "ov.glue", "ov.ice", "ov.pointer")) {
            assertTrue(name in index.cells, "missing $name")
        }
    }
}
