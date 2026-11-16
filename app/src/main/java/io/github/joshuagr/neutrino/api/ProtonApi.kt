package io.github.joshuagr.neutrino.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A decoded API answer: HTTP status, Proton's own `Code`, and the JSON body. */
data class ApiResponse(val status: Int, val body: JSONObject) {
    val code: Int get() = body.optInt("Code")
    val ok: Boolean get() = status in HTTP_OK_RANGE
    val error: String get() = body.optString("Error")

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}

/** Thin JSON transport for the few account endpoints the Drive SDK leaves to the host. */
class ProtonApi(val http: OkHttpClient, val appVersion: String) {

    fun baseHeaders(builder: Request.Builder): Request.Builder = builder
        .header("x-pm-appversion", appVersion)
        .header("Accept", "application/vnd.protonmail.v1+json")

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = withContext(Dispatchers.IO) {
        val builder = baseHeaders(Request.Builder().url(API_BASE + path))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val requestBody: RequestBody? = body?.toString()?.toRequestBody(JSON)
            ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
        builder.method(method, requestBody)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            ApiResponse(response.code, runCatching { JSONObject(text) }.getOrElse { JSONObject() })
        }
    }

    companion object {
        const val API_BASE = "https://drive-api.proton.me"
        const val ACCOUNT_BASE = "https://account.proton.me"
        private val JSON = "application/json".toMediaType()
    }
}
