package org.atgeo.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A response of any status. */
public class HttpResponse(public val status: Int, public val body: ByteArray)

/**
 * How the client talks to the network. Tests and apps with their own HTTP stack supply one;
 * [HttpUrlConnectionTransport] is the default.
 */
public fun interface HttpTransport {
    /**
     * GET the URL exactly as given and return the response, whatever its status. Must respond to
     * coroutine cancellation. Must not cache by default: the client keeps nothing on disk (contract §10).
     */
    public suspend fun get(url: String): HttpResponse
}

/**
 * A non-2xx answer from the server. `code` is the server's error name, or "HttpError" (contract §1).
 * `status` is the HTTP status the transport reported, so a caller can tell a refused request (4xx)
 * from a server failure (5xx) without matching on `code`.
 */
public class AtgeoException(public val code: String, message: String, public val status: Int) : RuntimeException(message)

/**
 * The default transport: `java.net.HttpURLConnection`, on every JVM and Android version with no
 * dependency. It asks for gzip and decodes it, which the JDK does not do on its own. Cancelling the
 * caller returns at once; on the JDK the socket may keep reading a body
 * already in progress until it ends or `readTimeoutMillis` passes, because `disconnect()` does not
 * interrupt that read. Apps that cancel often should supply a transport that tears requests down on
 * the wire, such as OkHttp's (android-sample/ has one).
 */
public class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
) : HttpTransport {
    override suspend fun get(url: String): HttpResponse = suspendCancellableCoroutine { cont ->
        // The caller is suspended, not blocked: the request runs on an IO thread, so cancellation
        // releases the caller whatever the socket is doing, and a late resume is ignored.
        val connectionRef = AtomicReference<HttpURLConnection?>()
        val io = Dispatchers.IO.asExecutor()
        // A cancellation handler must not block, and on the JDK disconnect() can wait for the
        // connection's lock until a read in progress finishes, so it runs on another thread.
        cont.invokeOnCancellation { connectionRef.get()?.let { connection -> io.execute { connection.disconnect() } } }
        io.execute {
            try {
                @Suppress("DEPRECATION") // URL(String) is deprecated on JDK 20+, and the only form on Android 24.
                val connection = URL(url).openConnection() as HttpURLConnection
                connectionRef.set(connection)
                // Closes the window between registering the handler and publishing the connection.
                if (cont.isCancelled) {
                    connection.disconnect()
                    return@execute
                }
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                // The JDK never asks for gzip, and tiles are about 10x smaller with it. Asking
                // explicitly turns off Android's transparent decoding, so decode here on both.
                connection.setRequestProperty("Accept-Encoding", "gzip")
                // responseCode selects the stream: getInputStream() throws on a 404, and errorStream
                // is null for an empty error body.
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.use { it.readBytes() } ?: ByteArray(0)
                // An empty body has no gzip header to read, whatever Content-Encoding says.
                val gzipped = connection.contentEncoding.equals("gzip", ignoreCase = true) && raw.isNotEmpty()
                val body = if (gzipped) GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
                cont.resume(HttpResponse(status, body))
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }
}

// Form encoding with spaces as %20: a literal + goes as %2B (contract §1). The Charset overload is
// Android API 33, above the floor.
internal fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** The XRPC and tile layer above the transport seam: URLs, status checks, error bodies. */
internal class Xrpc(server: String, private val transport: HttpTransport) {
    private val base = server.trimEnd('/')

    fun url(nsid: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (k, v) -> "${encodeQueryValue(k)}=${encodeQueryValue(v)}" }
        return "$base/xrpc/$nsid" + if (query.isEmpty()) "" else "?$query"
    }

    /** A parsed 2xx body. Throws AtgeoException on any other status, SerializationException if not JSON. */
    suspend fun query(nsid: String, params: List<Pair<String, String>> = emptyList()): JsonElement =
        parseBody(get(url(nsid, params)))

    /** The body of a 2xx answer to a GET of `url` exactly as given; AtgeoException otherwise. */
    suspend fun get(url: String): ByteArray {
        val response = try {
            transport.get(url)
        } catch (e: CancellationException) {
            // The caller's own cancellation propagates. Anything else is the transport giving up, such
            // as its withTimeout, and is a network failure (contract §14): left as a
            // CancellationException it would end the caller's coroutine silently.
            currentCoroutineContext().ensureActive()
            throw if (e is TimeoutCancellationException) SocketTimeoutException(e.message).apply { initCause(e) } else IOException(e.message, e)
        }
        if (response.status !in 200..299) throw atgeoException(response.status, response.body)
        return response.body
    }
}

/** Each of `error` and `message` is judged on its own; a body that is not a JSON object has neither. */
internal fun atgeoException(status: Int, body: ByteArray): AtgeoException {
    val obj = runCatching { parseBody(body) }.getOrNull() as? JsonObject
    return AtgeoException(obj?.get("error").stringOrNull() ?: "HttpError", obj?.get("message").stringOrNull() ?: "HTTP $status", status)
}
