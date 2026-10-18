package com.tomasmcguinness.mcc.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One client for the whole app, so connections are pooled. It has no cache configured, which
 * matters here: controller addresses get reassigned on the local network (DHCP, a swapped
 * device, a controller reflashed at the same IP), so a cached response could go stale in a way
 * the HTTP layer has no way to know about.
 */
internal val sharedHttpClient = OkHttpClient()

internal class HttpResult(val status: Int, val body: String)

/** Sends [request] with an overall [timeoutSeconds], reading the whole body. Throws [IOException] if it can't. */
internal suspend fun OkHttpClient.send(request: Request, timeoutSeconds: Long): HttpResult {
    val call = newBuilder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
        .newCall(request)

    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { it.body?.string().orEmpty() }
                    continuation.resume(HttpResult(response.code, body))
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
