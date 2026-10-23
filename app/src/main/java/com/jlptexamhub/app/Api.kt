package com.jlptexamhub.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

/** Klien Supabase tipis: auth (GoTrue) + REST (PostgREST). Tanpa library tambahan. */
object Api {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anon = BuildConfig.SUPABASE_ANON
    private var prefs: SharedPreferences? = null

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var expiresAt: Long = 0L

    @Volatile
    var userId: String? = null
        private set

    @Volatile
    var email: String? = null
        private set

    val hasSession: Boolean get() = refreshToken != null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        prefs = p
        accessToken = p.getString("at", null)
        refreshToken = p.getString("rt", null)
        expiresAt = p.getLong("exp", 0L)
        userId = p.getString("uid", null)
        email = p.getString("em", null)
    }

    private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 15000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "apikey" to anon,
        "Content-Type" to "application/json",
        "Accept" to "application/json"
    )

    private fun parseErr(text: String): String {
        return try {
            val j = JSONObject(text)
            val m = j.optString("error_description", "").ifEmpty {
                j.optString("msg", "").ifEmpty {
                    j.optString("message", "").ifEmpty { j.optString("error", "") }
                }
            }
            m.ifEmpty { "Terjadi kesalahan." }
        } catch (e: Exception) {
            if (text.isBlank()) "Terjadi kesalahan." else text.take(200)
        }
    }

    private fun store(j: JSONObject) {
        accessToken = j.optString("access_token", "").ifEmpty { null }
        refreshToken = j.optString("refresh_token", "").ifEmpty { refreshToken }
        val exp = j.optLong("expires_at", 0L)
        expiresAt = if (exp > 0) exp else System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600L)
        val u = j.optJSONObject("user")
        if (u != null) {
            userId = u.optString("id", "").ifEmpty { userId }
            email = u.optString("email", "").ifEmpty { email }
        }
        prefs?.edit()
            ?.putString("at", accessToken)
            ?.putString("rt", refreshToken)
            ?.putLong("exp", expiresAt)
            ?.putString("uid", userId)
            ?.putString("em", email)
            ?.apply()
    }

    fun login(email: String, password: String, captchaToken: String) {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("gotrue_meta_security", JSONObject().put("captcha_token", captchaToken))
            .toString()
        val (code, text) = call("POST", "$base/auth/v1/token?grant_type=password", authHeaders(), body)
        if (code >= 400) throw ApiException(code, parseErr(text))
        store(JSONObject(text))
    }

    @Synchronized
    fun refresh() {
        val rt = refreshToken ?: return
        val body = JSONObject().put("refresh_token", rt).toString()
        val (code, text) = call("POST", "$base/auth/v1/token?grant_type=refresh_token", authHeaders(), body)
        if (code >= 400) {
            if (code == 400 || code == 401 || code == 403) logout()
            throw ApiException(code, parseErr(text))
        }
        store(JSONObject(text))
    }

    @Synchronized
    private fun ensureToken() {
        if (refreshToken != null && System.currentTimeMillis() / 1000 > expiresAt - 60) {
            try {
                refresh()
            } catch (e: ApiException) {
                // sesi mati: logout sudah dilakukan di refresh()
            } catch (e: java.io.IOException) {
                // offline: pakai token lama dulu
            }
        }
    }

    fun logout() {
        accessToken = null
        refreshToken = null
        expiresAt = 0L
        userId = null
        email = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun rest(method: String, path: String, body: String?, extra: Map<String, String>?): Pair<Int, String> {
        var attempt = 0
        while (true) {
            ensureToken()
            val h = HashMap<String, String>()
            h.putAll(authHeaders())
            h["Authorization"] = "Bearer " + (accessToken ?: anon)
            if (extra != null) h.putAll(extra)
            val r = call(method, "$base/rest/v1/$path", h, body)
            if (r.first == 401 && attempt == 0 && refreshToken != null) {
                attempt++
                try {
                    refresh()
                } catch (e: Exception) {
                }
                continue
            }
            if (r.first >= 400) throw ApiException(r.first, parseErr(r.second))
            return r
        }
    }

    fun get(path: String): JSONArray {
        val r = rest("GET", path, null, null)
        return JSONArray(r.second)
    }

    fun upsert(table: String, conflict: String, json: JSONObject) {
        rest(
            "POST", "$table?on_conflict=$conflict", json.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }
}
