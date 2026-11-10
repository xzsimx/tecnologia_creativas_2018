package cl.tecnocreativas.budswidget.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tipografía e íconos de matriz de puntos, al estilo de la fuente Ndot de Nothing OS.
 * Se dibujan como bitmaps blancos sobre fondo transparente; el color final lo pone el
 * widget con un tint (así respetan modo claro/oscuro sin regenerar nada).
 *
 * Las tablas de glifos también las lee tools/preview.py para generar las vistas previas.
 */
object DotMatrix {

    const val GLYPH_W = 5
    const val GLYPH_H = 7

    // GLYPHS-BEGIN
    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '%' to arrayOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '.' to arrayOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        '·' to arrayOf(".....", ".....", ".....", "..#..", ".....", ".....", "....."),
        '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        ' ' to arrayOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
    )
    // GLYPHS-END

    /** Matriz de puntos encendidos para un texto (1 columna de separación entre letras). */
    fun textGrid(text: String): Array<BooleanArray> {
        val clean = Normalizer.normalize(text.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "") // Ó → O
        val chars = clean.map { GLYPHS[it] ?: GLYPHS.getValue('?') }
        val w = if (chars.isEmpty()) 1 else chars.size * (GLYPH_W + 1) - 1
        val grid = Array(GLYPH_H) { BooleanArray(w) }
        chars.forEachIndexed { i, g ->
            for (y in 0 until GLYPH_H) for (x in 0 until GLYPH_W) {
                if (g[y][x] == '#') grid[y][i * (GLYPH_W + 1) + x] = true
            }
        }
        return grid
    }

    /**
     * Íconos de 13×13 generados por distancia al centro, así quedan perfectamente
     * redondos en la grilla (como los íconos Glyph de Nothing).
     */
    fun iconGrid(mode: NoiseMode, size: Int = 13): Array<BooleanArray> {
        val c = (size - 1) / 2.0
        val outer = c
        return Array(size) { y ->
            BooleanArray(size) { x ->
                val d = hypot(x - c, y - c)
                val ring = abs(d - outer) < 0.55
                when (mode) {
                    // ANC: anillo exterior cerrado + núcleo sólido = sonido sellado
                    NoiseMode.ANC -> ring || d <= outer * 0.42
                    // Transparencia: ondas abiertas (anillos punteados) alrededor de un punto
                    NoiseMode.TRANSPARENCY -> {
                        val mid = abs(d - outer * 0.58) < 0.5
                        (ring && (x + y) % 2 == 0) || (mid && (x + y) % 2 == 1) || d <= 1.0
                    }
                    // Normal: anillo + barra diagonal
                    NoiseMode.OFF -> ring || (abs(x - (size - 1 - y)) <= 0 && d < outer)
                }
            }
        }
    }

    /**
     * Dibuja una matriz. [pitch] = distancia entre centros de puntos en px. Los puntos
     * apagados se pueden dibujar tenues ([ghostAlpha] > 0) para el look de panel LED.
     */
    fun render(grid: Array<BooleanArray>, pitch: Float, ghostAlpha: Int = 0, dotRatio: Float = 0.78f): Bitmap {
        val rows = grid.size
        val cols = grid.firstOrNull()?.size ?: 1
        val w = maxOf(1, (cols * pitch).toInt())
        val h = maxOf(1, (rows * pitch).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = ghostAlpha }
        val r = pitch * dotRatio / 2f
        for (y in 0 until rows) for (x in 0 until cols) {
            val cx = x * pitch + pitch / 2f
            val cy = y * pitch + pitch / 2f
            if (grid[y][x]) canvas.drawCircle(cx, cy, r, on)
            else if (ghostAlpha > 0) canvas.drawCircle(cx, cy, r, off)
        }
        return bmp
    }

    fun text(text: String, pitch: Float, ghostAlpha: Int = 0) = render(textGrid(text), pitch, ghostAlpha)

    fun icon(mode: NoiseMode, pitch: Float) = render(iconGrid(mode), pitch)
}
