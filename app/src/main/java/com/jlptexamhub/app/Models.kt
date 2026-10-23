package com.jlptexamhub.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

data class ExamInfo(
    val key: String,
    val level: String,
    val year: Int,
    val month: Int,
    val title: String,
    val durationMin: Int?
)

data class Question(
    val id: Int,
    val sec: String,
    val type: String,
    val q: String,
    val opts: List<String>,
    val ans: Int,
    val exp: String,
    val passage: String?,
    val audio: String?,
    val img: String?
)

class ExamData(val exam: ExamInfo, val questions: List<Question>, val passages: Map<String, String>)

data class Answer(val selected: Int, val correct: Boolean)

const val SEC_MOJI = "文字・語彙"
const val SEC_BUNPOU = "文法"
const val SEC_DOKKAI = "読解"

class Denied(message: String) : Exception(message)

fun isOn(v: Any?): Boolean {
    if (v == null || v == JSONObject.NULL) return false
    if (v is Boolean) return v
    if (v is Number) return v.toInt() == 1
    val s = v.toString().trim().lowercase()
    return s == "true" || s == "1" || s == "yes" || s == "on"
}

object Repo {
    // detik: [semua, moji, bunpou, dokkai]
    private val TLV = mapOf(
        "N5" to intArrayOf(4500, 1500, 1200, 1800),
        "N4" to intArrayOf(5400, 1800, 1800, 1800),
        "N3" to intArrayOf(6300, 2100, 1500, 3000),
        "N2" to intArrayOf(6300, 2100, 1500, 3000),
        "N1" to intArrayOf(6600, 2100, 1500, 3000)
    )

    fun limits(level: String, durationMin: Int?): IntArray {
        val t = (TLV[level] ?: TLV["N3"]!!).copyOf()
        if (durationMin != null && durationMin > 0) t[0] = durationMin * 60
        return t
    }

    private fun parseExam(r: JSONObject): ExamInfo = ExamInfo(
        key = r.optString("key"),
        level = r.optString("level"),
        year = r.optInt("exam_year"),
        month = r.optInt("exam_month"),
        title = r.optString("title"),
        durationMin = if (r.isNull("duration_min")) null else r.optInt("duration_min")
    )

    fun listExams(): List<ExamInfo> {
        val arr = Api.get("content_exams?select=*&status=eq.published")
        val out = ArrayList<ExamInfo>()
        for (i in 0 until arr.length()) out.add(parseExam(arr.getJSONObject(i)))
        return out
    }

    suspend fun loadExam(key: String): ExamData = coroutineScope {
        val k = key.lowercase()
        val exD = async { Api.get("content_exams?key=eq.$k&select=*") }
        val qsD = async { Api.get("content_questions?exam_key=eq.$k&select=*&order=num") }
        val psD = async { Api.get("content_passages?exam_key=eq.$k&select=*") }
        val ex = exD.await()
        if (ex.length() == 0) throw Exception("Ujian tidak ditemukan atau belum dipublikasikan.")
        val qs = qsD.await()
        val ps = psD.await()
        val exam = parseExam(ex.getJSONObject(0))
        val passages = HashMap<String, String>()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            passages[p.optString("passage_key")] = p.optString("body")
        }
        val list = ArrayList<Question>()
        for (i in 0 until qs.length()) {
            val r = qs.getJSONObject(i)
            val oa = r.optJSONArray("options")
            if (r.isNull("answer") || oa == null || oa.length() < 2) continue
            val opts = ArrayList<String>()
            for (j in 0 until oa.length()) opts.add(oa.optString(j))
            var sec = r.optString("section")
            if (sec == "言語知識") sec = SEC_MOJI
            list.add(
                Question(
                    id = r.optInt("num"),
                    sec = sec,
                    type = r.optString("qtype", ""),
                    q = r.optString("question", ""),
                    opts = opts,
                    ans = r.optInt("answer"),
                    exp = if (r.isNull("explanation")) "" else r.optString("explanation"),
                    passage = if (r.isNull("passage_key")) null else r.optString("passage_key").ifEmpty { null },
                    audio = if (r.isNull("audio_url")) null else r.optString("audio_url").ifEmpty { null },
                    img = if (r.isNull("image_url")) null else r.optString("image_url").ifEmpty { null }
                )
            )
        }
        if (list.isEmpty()) throw Exception(if (qs.length() > 0) "Ujian ini belum punya soal yang siap (kunci jawaban kosong)." else "Ujian ini belum punya soal.")
        ExamData(exam, list, passages)
    }
}

// ---------- Skor resmi JLPT (port dari exam.html) ----------

data class JlptGroup(val label: String, val secs: List<String>, val max: Int, val pass: Int, val notTested: Boolean = false)

data class SectionScore(
    val label: String, val max: Int, val pass: Int,
    val score: Int?, val correct: Int, val total: Int, val passed: Boolean?, val notTested: Boolean
)

data class JlptResult(
    val totalScore: Int, val totalMax: Int, val overallPass: Int,
    val passedOverall: Boolean, val passedAllTested: Boolean,
    val sections: List<SectionScore>, val cefr: String, val cefrRange: String
)

object Jlpt {
    private val langRead = listOf(
        JlptGroup("言語知識・読解", listOf(SEC_MOJI, SEC_BUNPOU, SEC_DOKKAI), 120, 38),
        JlptGroup("聴解", listOf("聴解"), 60, 19, true)
    )
    private val three = listOf(
        JlptGroup("言語知識（文字・語彙・文法）", listOf(SEC_MOJI, SEC_BUNPOU), 60, 19),
        JlptGroup("読解", listOf(SEC_DOKKAI), 60, 19),
        JlptGroup("聴解", listOf("聴解"), 60, 19, true)
    )
    private val overall = mapOf("N5" to 80, "N4" to 90, "N3" to 95, "N2" to 90, "N1" to 100)
    private val groups = mapOf("N5" to langRead, "N4" to langRead, "N3" to three, "N2" to three, "N1" to three)

    // (minimum, label, range)
    private val bands = mapOf(
        "N5" to listOf(Triple(80, "A1", "80–180"), Triple(0, "A0", "0–79")),
        "N4" to listOf(Triple(90, "A2", "90–180"), Triple(80, "A1", "80–89"), Triple(0, "A0", "0–79")),
        "N3" to listOf(Triple(104, "B1", "104–180"), Triple(90, "A2", "90–103"), Triple(0, "A1", "0–89")),
        "N2" to listOf(Triple(112, "B2", "112–180"), Triple(90, "B1", "90–111"), Triple(0, "A2", "0–89")),
        "N1" to listOf(
            Triple(142, "C1", "142–180"), Triple(100, "B2", "100–141"),
            Triple(90, "B1", "90–99"), Triple(0, "A2", "0–89")
        )
    )

    fun compute(questions: List<Question>, answers: Map<Int, Answer>, level: String): JlptResult {
        val lvl = level.uppercase().let { if (groups.containsKey(it)) it else "N5" }
        val sections = groups[lvl]!!.map { g ->
            if (g.notTested) {
                SectionScore(g.label, g.max, g.pass, null, 0, 0, null, true)
            } else {
                val qs = questions.filter { g.secs.contains(it.sec) }
                val correct = qs.count { answers[it.id]?.correct == true }
                val total = qs.size
                val score = if (total > 0) Math.round(correct.toDouble() / total * g.max).toInt() else 0
                SectionScore(g.label, g.max, g.pass, score, correct, total, if (total > 0) score >= g.pass else null, false)
            }
        }
        val tested = sections.filter { !it.notTested && it.total > 0 }
        val totalScore = tested.sumOf { it.score ?: 0 }
        val totalMax = tested.sumOf { it.max }
        val need = overall[lvl] ?: 80
        val band = (bands[lvl] ?: bands["N5"]!!).firstOrNull { totalScore >= it.first }
        return JlptResult(
            totalScore, totalMax, need,
            totalScore >= need, tested.all { it.passed != false },
            sections, band?.second ?: "—", band?.third ?: ""
        )
    }
}

// ---------- HTML mini (u, b, i, ruby, br) -> AnnotatedString ----------

private val htmlRe = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>|[^<]+|<")

private fun decodeEntities(s: String): String =
    s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

fun htmlToAnnotated(src: String): AnnotatedString {
    val text = src.replace("\r", "")
    return buildAnnotatedString {
        var u = 0
        var b = 0
        var ital = 0
        var rt = 0
        var rp = 0
        for (m in htmlRe.findAll(text)) {
            val tok = m.value
            val name = m.groupValues[2].lowercase()
            if (name.isNotEmpty()) {
                val close = m.groupValues[1] == "/"
                val d = if (close) -1 else 1
                when (name) {
                    "u" -> u = maxOf(0, u + d)
                    "b", "strong" -> b = maxOf(0, b + d)
                    "i", "em" -> ital = maxOf(0, ital + d)
                    "rt" -> rt = maxOf(0, rt + d)
                    "rp" -> rp = maxOf(0, rp + d)
                    "br" -> append("\n")
                    "p", "div" -> if (close) append("\n")
                }
            } else {
                if (rp > 0) continue
                val t = decodeEntities(tok)
                val shown = if (rt > 0) "($t)" else t
                withStyle(
                    SpanStyle(
                        textDecoration = if (u > 0) TextDecoration.Underline else null,
                        fontWeight = if (b > 0) FontWeight.Bold else null,
                        fontStyle = if (ital > 0) FontStyle.Italic else null,
                        fontSize = if (rt > 0) 11.sp else TextUnit.Unspecified
                    )
                ) { append(shown) }
            }
        }
    }
}
