package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-uicopy-capsule] Косметика капсулы для конвейера ui-copy.
 *
 * Контракт, который проверяется:
 *  A. вызов `minis-uicopy` через shell_execute распознаётся и получает своё
 *     имя (иконка/цвет/подпись), НЕ ломая обычные shell-вызовы;
 *  B. подпись описывает ФАЗУ (что делается), а не имя скрипта;
 *  C. посторонние инструменты и команды не задеты — никакой подмены.
 */
class UiCopyCapsuleTest {

    @Test
    fun `shell-вызов minis-uicopy получает имя ui_copy`() {
        val args = """{"tool_title":"Измеряю палитру","command":"minis-uicopy measure hist"}"""
        assertEquals(UI_COPY_TOOL, effectiveToolName("shell_execute", args))
    }

    @Test
    fun `обычный shell не подменяется`() {
        val args = """{"command":"ls -la /var/minis"}"""
        assertEquals("shell_execute", effectiveToolName("shell_execute", args))
    }

    @Test
    fun `другие инструменты не задеты даже с похожими аргументами`() {
        // file_read по гайду ui-copy остаётся file_read: подмена привязана к shell.
        val args = """{"path":"/usr/local/share/minis-uicopy/GUIDE.md"}"""
        assertEquals("file_read", effectiveToolName("file_read", args))
    }

    @Test
    fun `у ui_copy своя иконка цвет и подпись`() {
        // Значения не сверяем поштучно (это UI-константы), важно что ОТЛИЧАЮТСЯ
        // от shell — иначе капсула визуально не изменится.
        assert(toolAccentColor(UI_COPY_TOOL) != toolAccentColor("shell_execute"))
        assert(toolIconFor(UI_COPY_TOOL) != toolIconFor("shell_execute"))
        assertEquals("Minis копирует интерфейс", toolTitleLabel(UI_COPY_TOOL))
        assertEquals("UI copy", toolDisplayName(UI_COPY_TOOL))
    }

    @Test
    fun `фаза распознаётся по подкоманде`() {
        val cases = mapOf(
            "minis-uicopy init shot.png" to "Готовлю скриншот к разбору",
            "minis-uicopy measure hist" to "Анализирую изображение: палитра и границы",
            "minis-uicopy boxes" to "Ищу элементы интерфейса",
            "minis-uicopy probe" to "Измеряю рамки, цвета и текст",
            "minis-uicopy stripes" to "Извлекаю профили границ",
            "minis-uicopy bevel" to "Снимаю объёмные кромки элементов",
            "minis-uicopy edges 6" to "Измеряю кромки кадра",
            "minis-uicopy spacefit" to "Подбираю межбуквенные интервалы",
            "minis-uicopy residual r.png" to "Разделяю остаточную ошибку",
        )
        for ((cmd, expected) in cases) {
            val args = """{"command":"$cmd"}"""
            assertEquals("фаза для: $cmd", expected, uiCopyPhaseLabel(args))
        }
    }

    @Test
    fun `неизвестная подкоманда даёт общую фазу, а не null`() {
        val args = """{"command":"minis-uicopy --help"}"""
        assertEquals("Реконструирую интерфейс", uiCopyPhaseLabel(args))
    }

    @Test
    fun `для постороннего вызова фазы нет`() {
        assertNull(uiCopyPhaseLabel("""{"command":"git status"}"""))
        assertNull(uiCopyPhaseLabel(""))
    }

    @Test
    fun `render и diff различаются — иначе цикл неотличим в капсуле`() {
        val render = uiCopyPhaseLabel("""{"command":"minis-uicopy render index.html out.png"}""")
        val diff = uiCopyPhaseLabel("""{"command":"minis-uicopy diff ORIGINAL.png out.png"}""")
        assertEquals("Рендерю реконструкцию", render)
        assertEquals("Сравниваю с оригиналом", diff)
    }
}
