package com.jlptexamhub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ModeOpt(val key: String, val label: String, val count: Int, val seconds: Int)

class ExamResult(
    val total: Int, val correct: Int, val wrong: Int, val pct: Int, val elapsedSec: Int,
    val sections: List<Triple<String, Int, Int>>, val jlpt: JlptResult
)

class AppVM : ViewModel() {
    enum class Screen { LOADING, LOGIN, HOME, MODE, QUIZ, RESULT }

    var screen by mutableStateOf(Screen.LOADING)
    var bootError by mutableStateOf<String?>(null)
    var loginError by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var userName by mutableStateOf("")
    var isAdmin by mutableStateOf(false)

    var exams by mutableStateOf<List<ExamInfo>>(emptyList())
    var examsLoading by mutableStateOf(false)
    var examsError by mutableStateOf<String?>(null)

    var maintenance by mutableStateOf(false)
    var globalLocked by mutableStateOf(false)
    var lockReason by mutableStateOf("")
    var examLocks by mutableStateOf<Map<String, String>>(emptyMap())

    var current by mutableStateOf<ExamData?>(null)
    var examLoading by mutableStateOf(false)
    var examError by mutableStateOf<String?>(null)
    var questions by mutableStateOf<List<Question>>(emptyList())
    val answers = mutableStateMapOf<Int, Answer>()
    var idx by mutableStateOf(0)
    var remaining by mutableStateOf(0)
    var totalSec = 0
    var review by mutableStateOf(false)
    var result by mutableStateOf<ExamResult?>(null)
    var mode = "all"

    private var startMs = 0L
    private var startedIso = ""
    private var ticker: Job? = null
    private var pollJob: Job? = null
    private var booted = false

    @Volatile
    var foreground = true

    // audio
    private var mp: MediaPlayer? = null
    var audioPlaying by mutableStateOf<String?>(null)
    val audioPlayed = mutableStateListOf<String>()

    // ---------------- boot & auth ----------------

    fun boot(ctx: Context) {
        if (booted) return
        booted = true
        Api.init(ctx)
        startPolling()
        runBoot()
    }

    fun retryBoot() {
        bootError = null
        runBoot()
    }

    private fun runBoot() {
        if (!Api.hasSession) {
            screen = Screen.LOGIN
            return
        }
        screen = Screen.LOADING
        viewModelScope.launch(Dispatchers.IO) {
            try {
                afterAuth()
            } catch (e: Denied) {
                withContext(Dispatchers.Main) {
                    loginError = e.message
                    screen = Screen.LOGIN
                }
            } catch (e: ApiException) {
                if (e.code == 400 || e.code == 401 || e.code == 403) {
                    Api.logout()
                    withContext(Dispatchers.Main) { screen = Screen.LOGIN }
                } else {
                    withContext(Dispatchers.Main) { bootError = errText(e) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { bootError = errText(e) }
            }
        }
    }

    fun errText(e: Throwable): String = when (e) {
        is IOException -> "Tidak ada koneksi internet."
        is ApiException -> e.message ?: "Terjadi kesalahan."
        else -> e.message ?: "Terjadi kesalahan."
    }

    private fun deny(msg: String): Nothing {
        Api.logout()
        throw Denied(msg)
    }

    private suspend fun afterAuth() {
        val uid = Api.userId ?: throw ApiException(401, "Sesi tidak valid.")
        val rows = Api.get("users?id=eq.$uid&select=*")
        if (rows.length() == 0) deny("Akun belum terdaftar. Daftar dulu lewat website.")
        val u = rows.getJSONObject(0)
        when (u.optString("status", "").lowercase()) {
            "pending" -> deny("Akun menunggu persetujuan admin.")
            "rejected" -> deny("Pendaftaran akun ditolak admin.")
            "banned" -> deny("Akun dinonaktifkan. Hubungi admin.")
        }
        val role = u.optString("role", "").trim().lowercase()
        val name = u.optString("display_name", "").ifEmpty {
            u.optString("full_name", "").ifEmpty { Api.email ?: "" }
        }
        withContext(Dispatchers.Main) {
            userName = name
            isAdmin = role in listOf("admin", "superadmin", "super admin", "super_admin")
            loginError = null
            screen = Screen.HOME
        }
        loadExams()
    }

    fun login(email: String, pass: String, captcha: String) {
        busy = true
        loginError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Api.login(email, pass, captcha)
                afterAuth()
            } catch (e: Denied) {
                withContext(Dispatchers.Main) { loginError = e.message }
            } catch (e: ApiException) {
                val m = if (e.message == "Invalid login credentials") "Email atau password salah." else e.message
                withContext(Dispatchers.Main) { loginError = m }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loginError = errText(e) }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun logout() {
        Api.logout()
        stopTicker()
        stopAudio()
        current = null
        questions = emptyList()
        answers.clear()
        exams = emptyList()
        isAdmin = false
        screen = Screen.LOGIN
    }

    // ---------------- daftar ujian ----------------

    fun loadExams() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                examsLoading = true
                examsError = null
            }
            try {
                val list = Repo.listExams()
                withContext(Dispatchers.Main) { exams = list }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { examsError = errText(e) }
            } finally {
                withContext(Dispatchers.Main) { examsLoading = false }
            }
        }
    }

    fun lockedReason(key: String): String? {
        if (isAdmin) return null
        if (globalLocked) return lockReason.ifBlank { "Semua ujian sedang dikunci admin." }
        val r = examLocks[key.lowercase()] ?: return null
        return r.ifBlank { "Ujian ini sedang dikunci admin." }
    }

    // ---------------- polling realtime (lock / maintenance) ----------------

    fun startPolling() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                if (foreground) {
                    pollLocks()
                    if (Api.hasSession && tick % 6 == 5) checkStatus()
                    tick++
                }
                delay(5000)
            }
        }
    }

    fun pollNow() {
        viewModelScope.launch(Dispatchers.IO) { pollLocks() }
    }

    private suspend fun pollLocks() {
        try {
            val r = Api.get("settings?key=eq.maintenance_mode&select=value")
            val on = r.length() > 0 && isOn(r.getJSONObject(0).opt("value"))
            withContext(Dispatchers.Main) { maintenance = on }
        } catch (e: Exception) {
        }
        try {
            val r = Api.get("system_settings?select=*")
            var locked = false
            var reason = ""
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i)
                when (o.optString("key", "").lowercase()) {
                    "exam_locked" -> locked = isOn(o.opt("value"))
                    "exam_lock_reason" -> reason = if (o.isNull("value")) "" else o.optString("value")
                }
            }
            withContext(Dispatchers.Main) {
                globalLocked = locked
                lockReason = reason
            }
        } catch (e: Exception) {
        }
        try {
            val r = Api.get("exam_settings?select=exam_key,locked,lock_reason")
            val m = HashMap<String, String>()
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i)
                if (isOn(o.opt("locked"))) {
                    m[o.optString("exam_key", "").lowercase()] =
                        if (o.isNull("lock_reason")) "" else o.optString("lock_reason")
                }
            }
            withContext(Dispatchers.Main) { examLocks = m }
        } catch (e: Exception) {
        }
    }

    private suspend fun checkStatus() {
        try {
            val uid = Api.userId ?: return
            val rows = Api.get("users?id=eq.$uid&select=status,role")
            if (rows.length() == 0) return
            val u = rows.getJSONObject(0)
            val st = u.optString("status", "").lowercase()
            val role = u.optString("role", "").trim().lowercase()
            withContext(Dispatchers.Main) {
                isAdmin = role in listOf("admin", "superadmin", "super admin", "super_admin")
                if (st == "banned" || st == "rejected") {
                    loginError = "Akun dinonaktifkan. Hubungi admin."
                    logout()
                }
            }
        } catch (e: Exception) {
        }
    }

    // ---------------- alur ujian ----------------

    fun openExam(info: ExamInfo) {
        if (examLoading) return
        examLoading = true
        examError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = Repo.loadExam(info.key)
                withContext(Dispatchers.Main) {
                    current = data
                    screen = Screen.MODE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { examError = errText(e) }
            } finally {
                withContext(Dispatchers.Main) { examLoading = false }
            }
        }
    }

    fun modeOptions(): List<ModeOpt> {
        val d = current ?: return emptyList()
        val t = Repo.limits(d.exam.level, d.exam.durationMin)
        fun cnt(s: String) = d.questions.count { it.sec == s }
        return listOf(
            ModeOpt("all", "Semua soal", d.questions.size, t[0]),
            ModeOpt("moji", SEC_MOJI, cnt(SEC_MOJI), t[1]),
            ModeOpt("bunpou", SEC_BUNPOU, cnt(SEC_BUNPOU), t[2]),
            ModeOpt("dokkai", SEC_DOKKAI, cnt(SEC_DOKKAI), t[3])
        )
    }

    private fun secOf(m: String): String? = when (m) {
        "moji" -> SEC_MOJI
        "bunpou" -> SEC_BUNPOU
        "dokkai" -> SEC_DOKKAI
        else -> null
    }

    fun beginMode(opt: ModeOpt) {
        val d = current ?: return
        mode = opt.key
        val s = secOf(opt.key)
        questions = if (s == null) d.questions else d.questions.filter { it.sec == s }
        answers.clear()
        audioPlayed.clear()
        idx = 0
        totalSec = opt.seconds
        startMs = System.currentTimeMillis()
        startedIso = iso(startMs)
        remaining = totalSec
        review = false
        result = null
        screen = Screen.QUIZ
        push("started", false)
        startTicker()
    }

    private fun startTicker() {
        stopTicker()
        ticker = viewModelScope.launch {
            var lastBeat = System.currentTimeMillis()
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                val rem = totalSec - elapsed
                remaining = if (rem < 0) 0 else rem
                if (rem <= 0) {
                    finish()
                    return@launch
                }
                if (System.currentTimeMillis() - lastBeat > 20000) {
                    lastBeat = System.currentTimeMillis()
                    push("heartbeat", false)
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun answer(optIdx: Int) {
        if (review) return
        val q = questions.getOrNull(idx) ?: return
        if (answers.containsKey(q.id)) return
        answers[q.id] = Answer(optIdx, optIdx == q.ans)
        push("answer", false)
    }

    fun go(i: Int) {
        if (i in questions.indices) idx = i
    }

    fun finish() {
        stopTicker()
        stopAudio()
        val d = current ?: return
        val elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt().coerceAtMost(totalSec)
        val total = questions.size
        val correct = questions.count { answers[it.id]?.correct == true }
        val wrong = (answers.size - correct).coerceAtLeast(0)
        val pct = if (total > 0) Math.round(correct * 100.0 / total).toInt() else 0
        val secs = listOf(SEC_MOJI, SEC_BUNPOU, SEC_DOKKAI).mapNotNull { s ->
            val qs = questions.filter { it.sec == s }
            if (qs.isEmpty()) null else Triple(s, qs.count { answers[it.id]?.correct == true }, qs.size)
        }
        val jl = Jlpt.compute(questions, answers.toMap(), d.exam.level)
        result = ExamResult(total, correct, wrong, pct, elapsed, secs, jl)
        remaining = (totalSec - elapsed).coerceAtLeast(0)
        screen = Screen.RESULT
        push("finished", true)
    }

    fun startReview() {
        review = true
        idx = 0
        screen = Screen.QUIZ
    }

    fun backToResult() {
        review = false
        screen = Screen.RESULT
    }

    fun leaveExam() {
        stopTicker()
        stopAudio()
        review = false
        current = null
        questions = emptyList()
        answers.clear()
        screen = Screen.HOME
    }

    fun backToMode() {
        review = false
        screen = Screen.MODE
    }

    // ---------------- simpan progres ke Supabase (sama seperti web) ----------------

    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun sectionScores(): JSONObject {
        val out = JSONObject()
        val groups = listOf(Triple("moji", SEC_MOJI, SEC_MOJI), Triple("bunpou", SEC_BUNPOU, SEC_BUNPOU), Triple("dokkai", SEC_DOKKAI, SEC_DOKKAI))
        for (g in groups) {
            val qs = questions.filter { it.sec == g.second }
            val c = qs.count { answers[it.id]?.correct == true }
            val pct = if (qs.isNotEmpty()) Math.round(c.toDouble() / qs.size * 10000) / 100.0 else 0.0
            out.put(g.first, JSONObject().put("label", g.third).put("correct", c).put("total", qs.size).put("percentage", pct))
        }
        return out
    }

    private fun push(event: String, done: Boolean) {
        val uid = Api.userId ?: return
        val d = current ?: return
        val total = questions.size
        val answered = answers.size
        val correct = questions.count { answers[it.id]?.correct == true }
        val wrong = if (done) (total - correct).coerceAtLeast(0) else (answered - correct).coerceAtLeast(0)
        val pct = if (total > 0) Math.round(correct.toDouble() / total * 10000) / 100.0 else 0.0
        val now = iso(System.currentTimeMillis())
        val rem = (totalSec - ((System.currentTimeMillis() - startMs) / 1000).toInt()).coerceAtLeast(0)
        val status = if (done) "done" else "active"
        val ansJson = JSONObject()
        for ((k, v) in answers) ansJson.put(k.toString(), JSONObject().put("selected", v.selected).put("correct", v.correct))
        val sec = sectionScores()
        val e = d.exam
        val cur = idx + 1
        val modeNow = mode
        val started = startedIso

        val progress = JSONObject()
            .put("user_id", uid).put("exam_key", e.key).put("exam_title", e.title)
            .put("level", e.level).put("year", e.year).put("month", e.month)
            .put("mode", modeNow).put("status", status).put("current_q", cur)
            .put("total_q", total).put("answered_count", answered)
            .put("correct_count", correct).put("wrong_count", wrong).put("percentage", pct)
            .put("remaining_seconds", rem).put("last_event", event)
            .put("client_time", now).put("updated_at", now)
        val session = JSONObject()
            .put("user_id", uid).put("exam_key", e.key).put("exam_title", e.title)
            .put("level", e.level).put("year", e.year).put("month", e.month)
            .put("mode", modeNow).put("started_at", started).put("updated_at", now)
            .put("last_seen_at", now).put("answers", ansJson)
            .put("current_question", cur).put("total_questions", total)
            .put("timer_remaining", rem).put("score", correct)
            .put("correct_count", correct).put("wrong_count", wrong).put("percentage", pct)
            .put("section_scores", sec).put("completed", done)
            .put("completed_at", if (done) now else JSONObject.NULL)
            .put("status", status).put("last_event", event)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Api.upsert("exam_progress", "user_id,exam_key", progress)
            } catch (x: Exception) {
            }
            try {
                Api.upsert("exam_sessions", "user_id,exam_key", session)
            } catch (x: Exception) {
            }
        }
    }

    // ---------------- audio soal ----------------

    private fun absUrl(u: String): String =
        if (u.startsWith("http")) u else BuildConfig.SITE_URL.trimEnd('/') + "/" + u.trimStart('/')

    fun playAudio(url: String) {
        if (audioPlayed.contains(url) || audioPlaying == url) return
        stopAudio()
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            p.setDataSource(absUrl(url))
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener {
                audioPlayed.add(url)
                audioPlaying = null
                it.release()
                mp = null
            }
            p.setOnErrorListener { player, _, _ ->
                audioPlayed.add(url)
                audioPlaying = null
                player.release()
                mp = null
                true
            }
            mp = p
            audioPlaying = url
            p.prepareAsync()
        } catch (e: Exception) {
            audioPlaying = null
        }
    }

    fun stopAudio() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        audioPlaying = null
    }

    override fun onCleared() {
        stopAudio()
        super.onCleared()
    }
}
