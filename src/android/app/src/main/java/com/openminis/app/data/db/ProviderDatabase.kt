package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Standalone Room database for provider config. Lives in `provider.db`,
 * separate from `minis.db` (sessions/messages/etc), so that downgrading
 * to a version that doesn't know about these tables does NOT crash on
 * `minis.db`. Old builds simply ignore provider.db and continue to read
 * provider config from the legacy SharedPreferences JSON mirror — which
 * we keep writing on every save so it's never stale.
 *
 * On re-upgrade, ProviderRepository compares a stored hash of the JSON
 * mirror against the live mirror to detect "old build wrote JSON behind
 * our back during the downgrade window" and re-imports if needed; that
 * way provider.db can never become authoritative-but-stale relative to
 * what the user did while downgraded.
 *
 * Schema starts at version 1; future column adds use Migration like
 * AppDatabase does. We deliberately do NOT enable
 * fallbackToDestructiveMigration: provider.db is the only copy of
 * structured provider state, and the JSON mirror is the safety net, not
 * a substitute for proper migrations.
 */
@Database(
    entities = [
        ProviderInstanceEntity::class,
        ProviderModelEntryEntity::class,
        ProviderModelGroupEntity::class,
        ProviderAgentLoopIdEntity::class,
        ProviderConfigMetaEntity::class,
        AgentGraphEntity::class,
        AgentTaskEntity::class,
        TaskStatusHistoryEntity::class,
        AgentMailboxEntity::class,
        MissionLogEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class ProviderDatabase : RoomDatabase() {
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun agentGraphDao(): AgentGraphDao
    abstract fun agentBoardDao(): AgentBoardDao

    companion object {
        @Volatile
        private var INSTANCE: ProviderDatabase? = null

        /**
         * [T-android-azure-openai] Add the Azure OpenAI mode column. Pure
         * additive ALTER with NOT NULL DEFAULT 0 so every existing provider row
         * backfills to "off" — no data rewrite, no provider drop. This is the
         * first migration on provider.db (introduced at v1 with all columns
         * inline); older builds that don't know the column keep reading the JSON
         * mirror, and re-upgrade re-imports if they wrote behind our back.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN azure_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [GH#68 T-android-image-endpoint-persist] Add the image-endpoint
         * picker columns that the Room migration of the provider store
         * (4dd24ecf) missed — the JSON model carried them but every Room
         * round-trip dropped the value, snapping the picker back to Auto.
         * Pure additive nullable TEXT ALTERs; existing rows read as null →
         * auto / no cached probe, no data rewrite, no provider drop.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_mode TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_resolved TEXT")
            }
        }

        /**
         * [T-agent-graph] Add agent_graphs table for multi-agent graph persistence.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_graphs (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        json_config TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_graphs_name ON agent_graphs(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_graphs_updated ON agent_graphs(updated_at DESC)")
            }
        }

        /**
         * [T-provider-folders] Add the folder column used to organize the
         * provider list into user-defined folders. Pure additive nullable
         * TEXT ALTER; existing rows read as null → ungrouped (rendered under
         * their providerType section, exactly as before). No data rewrite, no
         * provider drop. Older builds that don't know the column keep reading
         * the JSON mirror, and re-upgrade re-imports if they wrote behind our
         * back.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN folder TEXT")
            }
        }

        /**
         * [T-task-board] The durable agent task board (review spec 27.09):
         * tasks + append-only status history + typed mailbox + append-only
         * mission log. Pure CREATE TABLE — additive, no existing table is
         * touched, so a downgrade path simply leaves four empty tables the
         * old code never reads.
         *
         * FK actions: history cascades with its task; mailbox's task_id
         * SET NULLs (the message survives its task's pruning — a completion
         * notice is still a fact after the card is gone); mission_log has no
         * FK by design (see AgentTaskEntity docs).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        team_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        role_required TEXT NOT NULL,
                        status TEXT NOT NULL,
                        depends_on_task_ids TEXT NOT NULL,
                        assigned_agent_id TEXT,
                        workspace_dir TEXT,
                        result_artifact TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_team_id ON agent_tasks(team_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_status ON agent_tasks(status)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_status_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        task_id TEXT NOT NULL,
                        from_status TEXT NOT NULL,
                        to_status TEXT NOT NULL,
                        changed_by_agent_id TEXT,
                        reason TEXT NOT NULL,
                        changed_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_status_history_task_id ON task_status_history(task_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_status_history_task_id_changed_at ON task_status_history(task_id, changed_at)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_mailbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        from_agent_id TEXT NOT NULL,
                        to_agent_id TEXT NOT NULL,
                        task_id TEXT,
                        message_type TEXT NOT NULL,
                        body TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_mailbox_to_agent_id ON agent_mailbox(to_agent_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_mailbox_task_id ON agent_mailbox(task_id)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mission_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        event_type TEXT NOT NULL,
                        agent_id TEXT,
                        task_id TEXT,
                        payload TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mission_log_task_id ON mission_log(task_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mission_log_agent_id ON mission_log(agent_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mission_log_timestamp ON mission_log(timestamp)")
            }
        }

        fun getInstance(context: Context): ProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderDatabase::class.java,
                    "provider.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
