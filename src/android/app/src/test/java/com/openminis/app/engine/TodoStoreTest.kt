package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoStoreTest {

    private fun store(max: Int = 50) = TodoStore(maxItems = max)

    @Test
    fun `write replaces the whole list`() {
        val s = store()
        s.write("s1", listOf(TodoItem("a", TodoStatus.PENDING)))
        s.write("s1", listOf(TodoItem("b", TodoStatus.DONE)))
        assertEquals(listOf(TodoItem("b", TodoStatus.DONE)), s.read("s1"))
    }

    @Test
    fun `sessions are isolated`() {
        val s = store()
        s.write("s1", listOf(TodoItem("a", TodoStatus.PENDING)))
        assertTrue(s.read("s2").isEmpty())
    }

    @Test
    fun `empty write clears the session`() {
        val s = store()
        s.write("s1", listOf(TodoItem("a", TodoStatus.PENDING)))
        s.write("s1", emptyList())
        assertTrue(s.read("s1").isEmpty())
    }

    @Test
    fun `write truncates to maxItems`() {
        val s = store(max = 2)
        val stored = s.write("s1", listOf(
            TodoItem("a", TodoStatus.PENDING),
            TodoItem("b", TodoStatus.PENDING),
            TodoItem("c", TodoStatus.PENDING),
        ))
        assertEquals(2, stored.size)
        assertEquals(2, s.read("s1").size)
    }

    @Test
    fun `render format matches the tool-surface contract`() {
        val rendered = TodoStore.render(listOf(
            TodoItem("done thing", TodoStatus.DONE),
            TodoItem("active thing", TodoStatus.IN_PROGRESS),
            TodoItem("pending thing", TodoStatus.PENDING),
        ))
        assertEquals(
            "TODO [1/3]\n✓ done thing\n▸ active thing\n○ pending thing",
            rendered,
        )
    }

    @Test
    fun `status strings normalize leniently`() {
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.fromString("in_progress"))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.fromString("Active"))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.fromString("DOING"))
        assertEquals(TodoStatus.DONE, TodoStatus.fromString("done"))
        assertEquals(TodoStatus.DONE, TodoStatus.fromString("Completed"))
        assertEquals(TodoStatus.PENDING, TodoStatus.fromString("whatever"))
        assertEquals(TodoStatus.PENDING, TodoStatus.fromString(""))
    }
}
