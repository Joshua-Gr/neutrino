package io.github.joshuagr.neutrino.drive

import android.util.Log
import io.github.joshuagr.neutrino.BuildConfig
import io.github.joshuagr.neutrino.api.ProtonApi
import io.github.joshuagr.neutrino.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.drive.sdk.SdkHttpClient
import me.proton.drive.sdk.SdkHttpClientException
import me.proton.drive.sdk.SdkHttpRequest
import me.proton.drive.sdk.SdkHttpRequestType
import me.proton.drive.sdk.SdkHttpResponse
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * The Drive SDK's HTTP layer. Adds the session to API requests and refreshes it once on a 401 when
 * the request can be replayed; storage transfers carry their own tokens from the SDK.
 */
class SdkTransport(private val api: ProtonApi, private val sessions: SessionManager) : SdkHttpClient {

    override suspend fun execute(request: SdkHttpRequest): SdkHttpResponse = withContext(Dispatchers.IO) {
        val isApi = request.type == SdkHttpRequestType.API
        val replayable = request.type != SdkHttpRequestType.STORAGE_UPLOAD
        val bytes = if (replayable) request.body?.readBytes() else null
        try {
            val session = if (isApi) sessions.current() else null
            var response = api.http.newCall(build(request, bytes, session?.let(sessions::authHeaders))).execute()
            val canRetry = replayable && session != null
            if (canRetry && response.code == HTTP_UNAUTHORIZED) {
                val renewed = sessions.refresh(session!!)
                if (renewed != null) {
                    response.close()
                    response = api.http.newCall(build(request, bytes, sessions.authHeaders(renewed))).execute()
                }
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "${request.type} ${request.method} ${redact(request.url)} -> ${response.code}")
            SdkHttpResponse(response.code, response.headers.toMultimap(), response.body.byteStream())
        } catch (e: SocketTimeoutException) {
            throw SdkHttpClientException.Timeout(e)
        } catch (e: IOException) {
            throw SdkHttpClientException.Connection(e)
        }
    }

    private fun build(request: SdkHttpRequest, bytes: ByteArray?, auth: Map<String, String>?): Request {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (auth != null) {
            api.baseHeaders(builder)
            auth.forEach { (name, value) -> builder.header(name, value) }
        }
        val type = request.headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
        val body: RequestBody? = when {
            bytes != null -> bytes.toRequestBody(type)
            request.body != null -> StreamingBody(request.body!!, type, request.contentLength)
            request.method in BODY_METHODS -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return builder.method(request.method, body).build()
    }

    private class StreamingBody(val input: InputStream, val type: MediaType?, val length: Long) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeAll(input.source())
        }
    }

    /** Keeps the endpoint shape for debugging and drops the ids and query that identify the account's data. */
    private fun redact(url: String) = url.substringBefore('?').replace(Regex("/[A-Za-z0-9_-]{16,}={0,2}"), "/<id>")

    private companion object {
        const val TAG = "Neutrino"
        const val HTTP_UNAUTHORIZED = 401
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
