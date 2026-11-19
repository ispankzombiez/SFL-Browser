package com.sfl.browser.notify

import android.content.Context
import android.os.SystemClock
import com.sfl.browser.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Thin client for the Sunflower Land Community API.
 *
 * Everything the poller needs is a `GET`, so this is plain `HttpURLConnection`
 * rather than a networking library: one fewer dependency, and the only
 * interesting behaviour here is the throttle.
 *
 * The API allows roughly one request per five seconds per endpoint per IP.
 * Use a single conservative pace across native callers, including query
 * variants of `/community/data`, and share any server cooldown across them.
 */
object SflApi {

    const val BASE_URL = "https://api.sunflower-land.com"

    /** Minimum spacing between any two requests, enforced globally. */
    const val MIN_GAP_MS = 5_500L

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MIN_RETRY_MS = 30_000L
    private const val COOLDOWN_KEY = "cooldownUntil"
    private val limiter = CommunityRateLimiter(SystemClock::elapsedRealtime, TimeUnit.MILLISECONDS::sleep)

    /** Result of a request: a parsed body, or a reason we could not get one. */
    sealed class ApiResult {
        data class Success(val body: JSONObject) : ApiResult()

        /** Any non-2xx. [code] is the HTTP status; body may be empty. */
        data class HttpError(val code: Int, val body: String) : ApiResult()

        /** 429 — we were throttled. */
        object Throttled : ApiResult()

        /** No API key configured (or it failed to decrypt). */
        object NoKey : ApiResult()

        /** DNS, TLS, timeout, malformed JSON… */
        data class Failure(val message: String) : ApiResult()
    }

    /**
     * GET `[path]` (a path such as `/community/farms/123`, already query-encoded
     * by the caller) and parse the response as one JSON object.
     */
    internal fun get(context: Context, path: String, retry: CommunityRateLimiter.Retry? = null): ApiResult {
        val prefs = context.getSharedPreferences("community_api_rate_limit", Context.MODE_PRIVATE)
        return try {
            limiter.execute(
                retry = retry,
                restoreCooldown = {
                    (prefs.getLong(COOLDOWN_KEY, 0) - System.currentTimeMillis()).coerceAtLeast(0)
                },
                saveCooldown = { delay ->
                    prefs.edit().putLong(COOLDOWN_KEY, System.currentTimeMillis() + delay).apply()
                },
            ) {
                val key = Prefs.apiKey(context)
                if (key == null) CommunityRateLimiter.Response(ApiResult.NoKey)
                else request(path, key)
            }
        } catch (e: CancellationException) {
            if (retry != null) throw e
            ApiResult.Failure("Request interrupted")
        }
    }

    private fun request(path: String, key: String): CommunityRateLimiter.Response {
        return try {
            requestHttp(path, key)
        } catch (e: IOException) {
            CommunityRateLimiter.Response(ApiResult.Failure(e.message ?: e.javaClass.simpleName))
        } catch (e: org.json.JSONException) {
            CommunityRateLimiter.Response(ApiResult.Failure("Invalid JSON: ${e.message}"))
        }
    }

    private fun requestHttp(path: String, key: String): CommunityRateLimiter.Response {
        val url = URL(BASE_URL + path)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("x-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            connection.instanceFollowRedirects = false

            val code = connection.responseCode
            if (code == 429) {
                return CommunityRateLimiter.Response(
                    ApiResult.Throttled,
                    retryAfterMillis(connection.getHeaderField("Retry-After"), System.currentTimeMillis()),
                )
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            return CommunityRateLimiter.Response(
                if (code !in 200..299 || body.isBlank()) ApiResult.HttpError(code, body)
                else ApiResult.Success(JSONObject(body)),
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun retryAfterMillis(header: String?, now: Long): Long {
        val value = header?.trim() ?: return MIN_RETRY_MS
        val seconds = value.toLongOrNull()
        val delay = if (seconds != null && seconds >= 0) {
            seconds.coerceAtMost((Long.MAX_VALUE / 2) / 1000) * 1000
        } else {
            try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - now
            } catch (_: DateTimeParseException) {
                MIN_RETRY_MS
            }
        }
        return maxOf(MIN_RETRY_MS, delay)
    }

    /**
     * [get] plus the `data` unwrap every Community endpoint does. Endpoints
     * without a `data` wrapper return the whole body; a payload that is not an
     * object (i.e. the caller wanted [getDataArray]) becomes [ApiResult.Failure].
     */
    internal fun getData(context: Context, path: String, retry: CommunityRateLimiter.Retry? = null): ApiResult =
        when (val r = get(context, path, retry)) {
        is ApiResult.Success -> when (val data = r.body.opt("data")) {
            null -> r
            is JSONObject -> ApiResult.Success(data)
            else -> ApiResult.Failure("data is ${data.javaClass.simpleName}, not an object")
        }

        else -> r
    }

    /** `data` as a [JSONArray] — raffles, and anything else that is a list. */
    internal fun getDataArray(context: Context, path: String, retry: CommunityRateLimiter.Retry? = null): ArrayResult =
        when (val r = get(context, path, retry)) {
        is ApiResult.Success -> when (val data = r.body.opt("data")) {
            is JSONArray -> ArrayResult.Success(data)

            // Some payloads are keyed by id rather than an array; normalise.
            is JSONObject -> ArrayResult.Success(objectsOf(data))

            null -> ArrayResult.Failure("Response has no data")
            else -> ArrayResult.Failure("Unexpected data type ${data.javaClass.simpleName}")
        }

        is ApiResult.Throttled -> ArrayResult.Throttled
        is ApiResult.NoKey -> ArrayResult.NoKey
        is ApiResult.HttpError -> ArrayResult.HttpError(r.code, r.body)
        is ApiResult.Failure -> ArrayResult.Failure(r.message)
    }

    /** Variant of [ApiResult] for endpoints whose `data` is an array. */
    sealed class ArrayResult {
        data class Success(val array: JSONArray) : ArrayResult()
        data class HttpError(val code: Int, val body: String) : ArrayResult()
        object Throttled : ArrayResult()
        object NoKey : ArrayResult()
        data class Failure(val message: String) : ArrayResult()
    }

    /** Re-serialise an object-of-objects (`{id: {...}}`) as a JSON array. */
    private fun objectsOf(obj: JSONObject): JSONArray {
        val out = JSONArray()
        val keys = obj.keys()
        while (keys.hasNext()) out.put(obj.getJSONObject(keys.next()))
        return out
    }

}
