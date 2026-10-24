package com.sortopia.ui.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException
import com.sortopia.theme.ArtIndex

/**
 * The generated art (`tools/gen_art.py`): sprite sheets, the index naming their cells, and the
 * per-theme background bands, which load on first use. A missing sprite draws nothing rather
 * than crashing.
 */
class ArtAtlas : Disposable {
    private val index: ArtIndex = loadIndex()
    private val sheets = HashMap<Int, Texture>()
    private val regions = HashMap<String, TextureRegion?>()
    private val backgrounds = HashMap<String, Texture?>()

    fun region(name: String): TextureRegion? = regions.getOrPut(name) { index.cells[name]?.let(::cut) }

    fun face(art: String): ArtIndex.FaceAnchor? = index.faces[art]

    /** Background band [part] ("top" or "bottom") of theme [themeId], or null if it's missing. */
    fun background(
        themeId: String,
        part: String,
    ): Texture? =
        backgrounds.getOrPut("$themeId-$part") {
            val file = Gdx.files.internal("art/bg/$themeId-$part.png")
            if (!file.exists()) {
                null
            } else {
                Texture(file).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
            }
        }

    private fun cut(cell: ArtIndex.Cell): TextureRegion? {
        val sheet = sheet(cell.sheet) ?: return null
        val px = index.cellPx
        return TextureRegion(sheet, cell.col * px, cell.row * px, px, px)
    }

    private fun sheet(i: Int): Texture? =
        sheets[i] ?: run {
            val file = Gdx.files.internal("art/sheet$i.png")
            if (!file.exists()) return null
            Texture(file, true).also {
                it.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
                sheets[i] = it
            }
        }

    override fun dispose() {
        sheets.values.forEach(Texture::dispose)
        backgrounds.values.forEach { it?.dispose() }
    }

    private companion object {
        fun loadIndex(): ArtIndex =
            try {
                ArtIndex.parse(Gdx.files.internal("art/atlas.properties").readString("UTF-8"))
            } catch (e: GdxRuntimeException) {
                Gdx.app.error("Sortopia", "Art index unavailable: ${e.javaClass.simpleName}")
                ArtIndex.parse("")
            }
    }
}
