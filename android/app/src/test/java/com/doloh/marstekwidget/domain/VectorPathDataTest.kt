package com.doloh.marstekwidget.domain

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every vector path must use explicit separators: Android's PathParser rejects SVG's compact arc flags
 * («a2 2 0 00-2-2») and the screen that inflates the icon crashes (1.4.8: «Графік відключень» closed the app).
 */
class VectorPathDataTest {
    private val number = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
    private val arity = mapOf('m' to 2, 'l' to 2, 'h' to 1, 'v' to 1, 'c' to 6, 's' to 4, 'q' to 4, 't' to 2, 'a' to 7, 'z' to 0)

    private fun drawables(): List<File> {
        val dir = listOf("src/main/res/drawable", "app/src/main/res/drawable").map(::File).first { it.isDirectory }
        return dir.listFiles { f -> f.name.startsWith("ic_") && f.name.endsWith(".xml") }!!.sortedBy { it.name }
    }

    @Test fun allIconPathsParseOnAndroid() {
        val files = drawables()
        assertTrue("no icons found", files.size >= 25)
        for (file in files) {
            Regex("""android:pathData="([^"]*)"""").findAll(file.readText()).forEach { m ->
                val data = m.groupValues[1]
                // tokens separated by whitespace/commas only; a letter starts a command
                val tokens = Regex("""[A-Za-z]|[^\sA-Za-z,]+""").findAll(data).map { it.value }.toList()
                var cmd: Char? = null
                var args = 0
                fun close() {
                    val c = cmd ?: return
                    val k = arity.getValue(c.lowercaseChar())
                    if (k == 0 && args != 0 || k > 0 && (args == 0 || args % k != 0)) fail("${file.name}: «$c» has $args args in «$data»")
                }
                for (t in tokens) {
                    if (t.length == 1 && t[0].isLetter()) { close(); cmd = t[0]; args = 0; continue }
                    if (!number.matches(t)) fail("${file.name}: token «$t» is not one number (compact SVG syntax?) in «$data»")
                    args++
                }
                close()
            }
        }
    }
}
