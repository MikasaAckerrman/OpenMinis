package com.openminis.app.provider

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The HTTP client the model catalogue endpoints share.
 *
 * Five catalogue APIs - Anthropic, Gemini, OpenAI, OpenRouter and
 * Antigravity - each built their own client with `OkHttpClient()`, which
 * means no timeouts were set at all. OkHttp's defaults cover connect and
 * read, but callTimeout defaults to none: a server that keeps a response
 * open and dribbles bytes forever holds the call for good. The thread
 * waits, the connection is not returned to the pool, and the next request
 * has to open another one.
 */

/**
 * One client for all of them, so they share a connection pool and a thread
 * pool instead of each holding its own.
 *
 * The timeouts are chosen for what these endpoints do: a small JSON
 * catalogue that answers in under a second. Sixty seconds for the whole
 * call is generous, and it is the bound that was missing - readTimeout
 * alone only limits the gap between two packets, not the call as a whole.
 */
object ProviderHttpClients {
    val catalog: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
