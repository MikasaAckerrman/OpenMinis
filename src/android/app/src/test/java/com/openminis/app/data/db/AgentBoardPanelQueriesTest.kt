package com.openminis.app.data.db

import com.openminis.app.offload.AgentBoardLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-board-panel-queries] The queries the agent panel reads, proved on a
 * REAL SQLite engine.
 *
 * The panel's whole value rests on two things that are easy to get subtly
 * wrong: which tasks count as live, and which count as finished. A live
 * task hidden by a limit is a task the user cannot stop; a cancelled task
 * left out of the finished list is a run that looks like it never happened.
 *
 * So the queries are tested for what they include, what they leave out,
 * how they order, and how they separate one chat from another.
 *
 * The app and this test run the SAME SQL constants, so they cannot drift
 * apart. What this does NOT cover: Room's own code generation and the
 * entity mapping - those are compile-time artefacts, and the only honest
 * proof for them is a device.
 */
class AgentBoardPanelQueriesTest {

    // --- the data the tests work with -------------------------------------
    //
    // Two chats, so that isolation can be tested: team-a has one task of
    // every kind, team-b has a single running task that must never show up
    // in team-a's lists.
    private val teamA = "team-a"
    private val teamB = "team-b"

    private data class Task(
        val id: String,
        val team: String,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private val tasks = listOf(
        // team-a, live
        Task("a-running", teamA, AgentBoardLogic.STATUS_RUNNING, 100L, 100L),
        Task("a-pending", teamA, AgentBoardLogic.STATUS_PENDING, 200L, 200L),
        Task("a-blocked", teamA, AgentBoardLogic.STATUS_BLOCKED, 300L, 300L),
        // team-a, finished
        Task("a-completed", teamA, AgentBoardLogic.STATUS_COMPLETED, 400L, 900L),
        Task("a-failed", teamA, AgentBoardLogic.STATUS_FAILED, 500L, 800L),
        Task("a-cancelled", teamA, AgentBoardLogic.STATUS_CANCELLED, 600L, 700L),
        // team-b, live
        Task("b-running", teamB, AgentBoardLogic.STATUS_RUNNING, 150L, 150L),
    )

    private fun withDb(block: (Connection) -> Unit) {
        val file = File.createTempFile("panel-queries", ".db")
        val url = "jdbc:sqlite:" + file.absolutePath
        DriverManager.getDriver(url)
        val conn = DriverManager.getConnection(url)
        try {
            conn.createStatement().use { s ->
                s.executeUpdate(
                    "CREATE TABLE agent_tasks(" +
                        "id TEXT PRIMARY KEY, team_id TEXT NOT NULL, title TEXT, " +
                        "description TEXT, role_required TEXT, status TEXT NOT NULL, " +
                        "depends_on_task_ids TEXT, assigned_agent_id TEXT, " +
                        "workspace_dir TEXT, result_artifact TEXT, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
                s.executeUpdate(
                    "CREATE TABLE sessions(" +
                        "id TEXT PRIMARY KEY, title TEXT, agent_run_id TEXT, " +
                        "agent_role TEXT, " +
                        "is_agent_showcase INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
            }
            conn.prepareStatement(
                "INSERT INTO agent_tasks(" +
                    "id, team_id, title, status, created_at, updated_at) " +
                    "VALUES(?,?,?,?,?,?)",
            ).use { p ->
                for (t in tasks) {
                    p.setString(1, t.id)
                    p.setString(2, t.team)
                    p.setString(3, t.id)
                    p.setString(4, t.status)
                    p.setLong(5, t.createdAt)
                    p.setLong(6, t.updatedAt)
                    p.addBatch()
                }
                p.executeBatch()
            }
            block(conn)
        } finally {
            conn.close()
            file.delete()
        }
    }

    private fun activeIds(conn: Connection, team: String): List<String> {
        conn.prepareStatement(AgentBoardDao.SQL_ACTIVE_TASKS).use { p ->
            p.setString(1, team)
            return p.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString("id"))
                out
            }
        }
    }

    private fun finishedIds(conn: Connection, team: String, limit: Int): List<String> {
        conn.prepareStatement(AgentBoardDao.SQL_RECENT_FINISHED).use { p ->
            p.setString(1, team)
            p.setInt(2, limit)
            return p.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString("id"))
                out
            }
        }
    }

    // --- the live tasks ---------------------------------------------------

    @Test
    fun activeTasks_liveOnly_andOldestFirst() {
        withDb { conn ->
            val ids = activeIds(conn, teamA)
            // PENDING, RUNNING and BLOCKED are live. COMPLETED, FAILED and
            // CANCELLED are finished and must not be here.
            assertEquals(
                listOf("a-running", "a-pending", "a-blocked"),
                ids,
            )
            // Oldest first: the task that waits longest is the one that
            // needs attention.
            assertEquals(
                listOf(100L, 200L, 300L),
                ids.map { id -> tasks.first { it.id == id }.createdAt },
            )
        }
    }

    @Test
    fun activeTasks_doNotCrossTeams() {
        withDb { conn ->
            val ids = activeIds(conn, teamA)
            assertTrue("team-b task leaked into team-a", !ids.contains("b-running"))
            // And the other way round: team-b sees only its own.
            assertEquals(listOf("b-running"), activeIds(conn, teamB))
        }
    }

    @Test
    fun activeTasks_areNotLimitedAway() {
        // The live list has no window: fifty finished tasks must never be
        // able to push a running task out of the panel.
        withDb { conn ->
            val filler = (1..60).map { n ->
                Task("filler-$n", teamA, AgentBoardLogic.STATUS_COMPLETED, 1_000L + n, 1_000L + n)
            }
            conn.prepareStatement(
                "INSERT INTO agent_tasks(" +
                    "id, team_id, title, status, created_at, updated_at) " +
                    "VALUES(?,?,?,?,?,?)",
            ).use { p ->
                for (t in filler) {
                    p.setString(1, t.id)
                    p.setString(2, t.team)
                    p.setString(3, t.id)
                    p.setString(4, t.status)
                    p.setLong(5, t.createdAt)
                    p.setLong(6, t.updatedAt)
                    p.addBatch()
                }
                p.executeBatch()
            }
            val ids = activeIds(conn, teamA)
            assertTrue("a live task was pushed out by finished ones", ids.contains("a-running"))
            assertEquals(3, ids.size)
        }
    }

    // --- the finished tasks -----------------------------------------------

    @Test
    fun finishedTasks_includeCancelled_newestFirst() {
        withDb { conn ->
            val ids = finishedIds(conn, teamA, 20)
            // A cancelled task IS finished. Leaving it out would make a
            // cancelled run look like one that never happened.
            assertEquals(listOf("a-completed", "a-failed", "a-cancelled"), ids)
            // Newest first, by when the task was last touched.
            assertEquals(
                listOf(900L, 800L, 700L),
                ids.map { id -> tasks.first { it.id == id }.updatedAt },
            )
        }
    }

    @Test
    fun finishedTasks_respectTheLimit() {
        withDb { conn ->
            assertEquals(listOf("a-completed"), finishedIds(conn, teamA, 1))
            assertEquals(listOf("a-completed", "a-failed"), finishedIds(conn, teamA, 2))
        }
    }

    @Test
    fun finishedTasks_excludeLiveOnes() {
        withDb { conn ->
            val ids = finishedIds(conn, teamA, 20)
            assertTrue(!ids.contains("a-running"))
            assertTrue(!ids.contains("a-pending"))
            assertTrue(!ids.contains("a-blocked"))
        }
    }

    // --- the prune --------------------------------------------------------

    /**
     * The prune, exactly as the DAO runs it: history first, then the
     * tasks. The order matters - the history delete matches the tasks
     * that still exist.
     */
    private fun prune(conn: Connection, cutoff: Long): Int {
        conn.prepareStatement(AgentBoardDao.SQL_PRUNE_HISTORY).use { p ->
            p.setLong(1, cutoff)
            p.executeUpdate()
        }
        return conn.prepareStatement(AgentBoardDao.SQL_PRUNE_CLOSED).use { p ->
            p.setLong(1, cutoff)
            p.executeUpdate()
        }
    }

    @Test
    fun prune_removesEveryClosedTask_andKeepsTheLiveOnes() {
        withDb { conn ->
            // Cutoff 1000: every task is older than it, so the ones that
            // are closed go and the live ones stay.
            val cut = prune(conn, 1000L)
            // a-completed, a-failed and a-cancelled go; a-running,
            // a-pending and a-blocked stay, and so does the other team's
            // task - a prune is about age, not about the team.
            assertEquals(3, cut)
            val left = conn.createStatement().use { s ->
                s.executeQuery("SELECT id FROM agent_tasks ORDER BY id").use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
            assertEquals(
                listOf("a-blocked", "a-pending", "a-running", "b-running"),
                left,
            )
        }
    }

    @Test
    fun prune_keepsTasksThatAreNotOldEnough() {
        withDb { conn ->
            // Cutoff 650: the closed tasks were touched at 700, 800 and
            // 900, so none of them is old enough to go yet.
            assertEquals(0, prune(conn, 650L))
            val count = conn.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM agent_tasks").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
            assertEquals(7, count)
        }
    }

    @Test
    fun prune_removesCancelledOnceItIsOldEnough() {
        withDb { conn ->
            // Cutoff 750: only the cancelled task (touched at 700) is old
            // enough. A cancelled task is closed, so it has to go with the
            // others - otherwise the board fills up with cancellations
            // that never leave.
            assertEquals(1, prune(conn, 750L))
            val left = conn.createStatement().use { s ->
                s.executeQuery("SELECT id FROM agent_tasks WHERE status = 'CANCELLED'").use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
            assertTrue("the cancelled task survived the prune", left.isEmpty())
        }
    }

    @Test
    fun prune_takesTheHistoryOfThePrunedTasks_withIt() {
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate(
                    "INSERT INTO task_status_history(task_id, from_status, to_status, reason) " +
                        "VALUES('a-completed','RUNNING','COMPLETED','ok')",
                )
                s.executeUpdate(
                    "INSERT INTO task_status_history(task_id, from_status, to_status, reason) " +
                        "VALUES('a-cancelled','RUNNING','CANCELLED','cancelled')",
                )
                s.executeUpdate(
                    "INSERT INTO task_status_history(task_id, from_status, to_status, reason) " +
                        "VALUES('a-running','PENDING','RUNNING','started')",
                )
            }
            prune(conn, 1000L)
            val history = conn.createStatement().use { s ->
                s.executeQuery("SELECT task_id FROM task_status_history ORDER BY task_id").use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
            // The history of the pruned tasks is gone with them; the history
            // of the task that stayed is still there. A history row for a
            // task that does not exist is a row nobody will ever read.
            assertEquals(listOf("a-running"), history)
        }
    }

    // --- the showcase session ---------------------------------------------

    @Test
    fun showcase_isFound_byRunId_andWorkersAreNot() {
        withDb { conn ->
            conn.createStatement().use { s ->
                // A worker session of the same run: same run id, hidden.
                s.executeUpdate(
                    "INSERT INTO sessions(id, title, agent_run_id, agent_role, " +
                        "is_agent_showcase, created_at, updated_at) " +
                        "VALUES('worker-1','w','run-1','worker',0,10,10)",
                )
                s.executeUpdate(
                    "INSERT INTO sessions(id, title, agent_run_id, agent_role, " +
                        "is_agent_showcase, created_at, updated_at) " +
                        "VALUES('showcase-1','s','run-1',NULL,1,20,20)",
                )
            }
            // The worker session carries the same run id - asking for the
            // run id alone would return a hidden session.
            assertEquals("showcase-1", showcaseFor(conn, "run-1"))
            assertNull(showcaseFor(conn, "run-without-showcase"))
        }
    }

    @Test
    fun showcase_picksTheNewestWhenThereAreSeveral() {
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate(
                    "INSERT INTO sessions(id, title, agent_run_id, " +
                        "is_agent_showcase, created_at, updated_at) " +
                        "VALUES('old','o','run-2',1,10,10)",
                )
                s.executeUpdate(
                    "INSERT INTO sessions(id, title, agent_run_id, " +
                        "is_agent_showcase, created_at, updated_at) " +
                        "VALUES('new','n','run-2',1,20,20)",
                )
            }
            // Deterministic, not arbitrary: the same run always resolves to
            // the same session.
            assertEquals("new", showcaseFor(conn, "run-2"))
        }
    }

    private fun showcaseFor(conn: Connection, runId: String): String? {
        conn.prepareStatement(ChatDao.SQL_SHOWCASE_FOR_RUN).use { p ->
            p.setString(1, runId)
            return p.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }
}
