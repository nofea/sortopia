package com.sortopia

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StringsTest {
    @Test
    fun `apostrophes are written once, as LibGDX's I18NBundle expects`() {
        // I18NBundle keeps a single ' literal; a doubled one shows up doubled on screen.
        val doubled =
            File("../assets/i18n/strings.properties")
                .readLines()
                .filter { !it.startsWith("#") && "''" in it }
        assertTrue(doubled.isEmpty(), "doubled apostrophes: $doubled")
    }
}
