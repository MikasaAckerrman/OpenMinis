package com.openminis.app.engine

/**
 * [T-engine-log] Logging seam for the engine core.
 *
 * The engine must compile and run on a plain JVM (unit tests, future KMP
 * extraction), so it can never import android.util.Log. Every engine
 * component takes one of these; the app supplies an adapter to Log, tests
 * supply STDERR or NONE.
 */
fun interface EngineLogger {
    fun log(level: Level, tag: String, message: String)

    enum class Level { DEBUG, WARNING, ERROR }

    companion object {
        val NONE = EngineLogger { _, _, _ -> }

        val STDERR = EngineLogger { level, tag, message ->
            System.err.println("[$level] $tag: $message")
        }
    }
}
