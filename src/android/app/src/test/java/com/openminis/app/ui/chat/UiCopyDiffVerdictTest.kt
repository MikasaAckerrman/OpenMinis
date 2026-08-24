package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-uicopy-diff-verdict] Парсер вердикта сравнения для капсулы ui-copy.
 *
 * Фикстуры — фактический формат вывода `tools/diff.py` (строки 52-58), а не
 * придуманный: парсер по регуляркам ломается именно от расхождения с реальным
 * текстом, поэтому проверять надо на нём.
 */
class UiCopyDiffVerdictTest {

    private val realDiffOutput = """
        РАЗМЕР        1097x654
        MAE глобально 1.96
        MAE по контенту (не-фон, 121983 px = 17.0%) 8.42
        worst-1% даёт 43.2% всей ошибки
        пикселей точно совпало: 681534 (95.0%)
        пикселей |d|<=12:       702111 (97.9%)

        === ПОЭЛЕМЕНТНО (MAE, отсортировано по убыванию) ===
            12.40 max= 210  label_title                    (12, 8, 340, 28)
    """.trimIndent()

    @Test
    fun `extracts content MAE and exact-pixel share`() {
        assertEquals("MAE 8.42 · совпало 95.0%", uiCopyDiffVerdict(realDiffOutput))
    }

    @Test
    fun `ignores the global MAE which is flattered by the background`() {
        // Фон ~83% кадра, поэтому глобальный MAE всегда красивее правды.
        // Он не должен подхватываться вместо контентного.
        assertNull(uiCopyDiffVerdict("MAE глобально 1.96"))
    }

    @Test
    fun `returns null for output of other subcommands`() {
        assertNull(uiCopyDiffVerdict("ГИСТОГРАММА\n  #1a1a1a  43.2%"))
        assertNull(uiCopyDiffVerdict("рендер сохранён: render.png"))
        assertNull(uiCopyDiffVerdict("РАЗМЕР        1097x654"))
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(uiCopyDiffVerdict(""))
    }

    @Test
    fun `degrades to whichever half is present`() {
        // Вывод дописывается построчно: пока команда бежит, может быть только
        // одна из двух величин.
        assertEquals(
            "MAE 8.42",
            uiCopyDiffVerdict("MAE по контенту (не-фон, 121983 px = 17.0%) 8.42"),
        )
        assertEquals(
            "совпало 95.0%",
            uiCopyDiffVerdict("пикселей точно совпало: 681534 (95.0%)"),
        )
    }

    @Test
    fun `handles the identical-images case`() {
        assertEquals(
            "MAE 0.00 · совпало 100.0%",
            uiCopyDiffVerdict(
                "MAE по контенту (не-фон, 100 px = 1.0%) 0.00\n" +
                    "идентично\n" +
                    "пикселей точно совпало: 717438 (100.0%)",
            ),
        )
    }
}
