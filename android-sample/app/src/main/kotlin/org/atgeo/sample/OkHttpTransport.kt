package org.atgeo.sample

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.atgeo.client.HttpResponse
import org.atgeo.client.HttpTransport
import org.atgeo.client.HttpUrlConnectionTransport
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An atgeo transport on OkHttp, for apps that already ship it: HTTP/2, a current TLS stack on old
 * devices, and cancellation that tears the request down on the wire. Copy it into your app; the
 * library does not depend on OkHttp.
 *
 * Pass an OkHttpClient without a Cache (the default has none), or the client's promise to keep
 * nothing on disk no longer holds. A body larger than `maxBodyBytes` once decoded (OkHttp gunzips on
 * its own) fails with IOException, as with the default transport.
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val maxBodyBytes: Int = HttpUrlConnectionTransport.DEFAULT_MAX_BODY_BYTES,
) : HttpTransport {
    override suspend fun get(url: String): HttpResponse = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            // request() buffers only as far as it must to answer, so a huge body stops here.
                            val source = it.body.source()
                            if (source.request(maxBodyBytes + 1L)) throw IOException("response body is larger than $maxBodyBytes bytes")
                            cont.resume(HttpResponse(it.code, source.readByteArray()))
                        }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                    }
                }
            },
        )
    }
}
