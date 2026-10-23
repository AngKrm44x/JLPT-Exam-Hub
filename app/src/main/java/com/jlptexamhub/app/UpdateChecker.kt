package com.jlptexamhub.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cek release terbaru di GitHub. Catatan rilis (body) jadi isi popup. */
object UpdateChecker {
    data class Info(val tag: String, val name: String, val notes: String, val url: String, val mandatory: Boolean)

    fun check(): Info? {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank() || repo.startsWith("GANTI_")) return null
        var c: HttpURLConnection? = null
        try {
            c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "JLPTExamHub")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
            val tag = j.optString("tag_name", "")
            if (compare(tag, BuildConfig.VERSION_NAME) <= 0) return null

            val body = j.optString("body", "").trim()
            val mandatory = body.contains("[wajib]", ignoreCase = true)
            var url = j.optString("html_url", "")
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").lowercase().endsWith(".apk")) {
                        url = a.optString("browser_download_url", url)
                        break
                    }
                }
            }
            if (url.isEmpty()) return null
            val name = j.optString("name", "").ifEmpty { tag }
            return Info(tag, name, cleanNotes(body.replace("[wajib]", "", ignoreCase = true)), url, mandatory)
        } catch (e: Exception) {
            return null
        } finally {
            c?.disconnect()
        }
    }

    private fun cleanNotes(s: String): String =
        s.lines()
            .filter { !it.trimStart().startsWith("**Full Changelog**") }
            .joinToString("\n") { it.trimEnd().replace(Regex("^#+\\s*"), "").replace("**", "") }
            .trim()

    /** Bandingkan "v1.2.3" vs "1.2" secara numerik. */
    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return if (p < q) -1 else 1
        }
        return 0
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.take(9).toInt() }
}
