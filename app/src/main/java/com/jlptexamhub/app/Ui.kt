@file:OptIn(ExperimentalMaterial3Api::class)

package com.jlptexamhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

val Ink = Color(0xFF221D18)
val Muted = Color(0xFF5A5046)
val Paper = Color(0xFFF3EDE0)
val CardBg = Color(0xFFFFFBF2)
val Accent = Color(0xFF8A1C1C)
val Gold = Color(0xFF8A6A1C)
val Good = Color(0xFF2E7D32)
val GoodBg = Color(0xFFE3F1E4)
val Bad = Color(0xFFC62828)
val BadBg = Color(0xFFFBE4E2)
val Line = Color(0xFFD8CDB6)

private val Scheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    secondary = Gold, background = Paper, onBackground = Ink,
    surface = CardBg, onSurface = Ink, surfaceVariant = Color(0xFFEAE2D0),
    onSurfaceVariant = Muted, error = Bad, outline = Line
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

fun timeFmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun Html(text: String, modifier: Modifier = Modifier, size: TextUnit = 16.sp, color: Color = Ink, weight: FontWeight? = null) {
    val a = remember(text) { htmlToAnnotated(text) }
    Text(text = a, modifier = modifier, fontSize = size, color = color, fontWeight = weight, lineHeight = size * 1.55f)
}

// ---------------------------------------------------------------- Root

@Composable
fun MaintenanceScreen() {
    Column(
        Modifier.fillMaxSize().background(Paper).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MAINTENANCE", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("Sedang dalam perbaikan", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Website dan aplikasi sedang maintenance. Layar ini akan hilang otomatis saat sudah selesai.",
            color = Muted, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text("The service is under maintenance. This screen closes automatically.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun LockOverlay(reason: String, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Paper).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("UJIAN DIKUNCI", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(reason, fontSize = 20.sp, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text("Layar ini hilang otomatis kalau admin membuka kunci.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onLeave) { Text("Kembali ke menu") }
    }
}

@Composable
fun Root(vm: AppVM) {
    val key = vm.current?.exam?.key
    val inExam = vm.screen == AppVM.Screen.MODE || vm.screen == AppVM.Screen.QUIZ
    val lockMsg = if (inExam && key != null) vm.lockedReason(key) else null

    if (vm.maintenance && !vm.isAdmin) {
        MaintenanceScreen()
        return
    }
    Box(Modifier.fillMaxSize().background(Paper)) {
        when (vm.screen) {
            AppVM.Screen.LOADING -> LoadingScreen(vm)
            AppVM.Screen.LOGIN -> LoginScreen(vm)
            AppVM.Screen.HOME -> HomeScreen(vm)
            AppVM.Screen.MODE -> ModeScreen(vm)
            AppVM.Screen.QUIZ -> QuizScreen(vm)
            AppVM.Screen.RESULT -> ResultScreen(vm)
        }
        if (lockMsg != null) LockOverlay(lockMsg) { vm.leaveExam() }
    }
}

@Composable
fun LoadingScreen(vm: AppVM) {
    val err = vm.bootError
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (err == null) {
                CircularProgressIndicator(color = Accent)
            } else {
                Text(err, textAlign = TextAlign.Center, color = Ink)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.retryBoot() }) { Text("Coba lagi") }
            }
        }
    }
}

// ---------------------------------------------------------------- Login + captcha

class CaptchaBridge(private val onToken: (String) -> Unit) {
    private val h = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun token(t: String) {
        h.post { onToken(t) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaDialog(onToken: (String) -> Unit, onDismiss: () -> Unit) {
    val cb by rememberUpdatedState(onToken)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(16.dp), color = Paper) {
            Column(Modifier.padding(12.dp)) {
                Text("Verifikasi bahwa kamu bukan robot", fontWeight = FontWeight.Bold, color = Ink)
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.parseColor("#F3EDE0"))
                            addJavascriptInterface(CaptchaBridge { t -> cb(t) }, "Android")
                            val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                                "<script src='https://js.hcaptcha.com/1/api.js?render=explicit&onload=onl' async defer></script></head>" +
                                "<body style='margin:0;display:flex;align-items:center;justify-content:center;height:100vh;background:#F3EDE0'>" +
                                "<div id='c'></div><script>function onl(){hcaptcha.render('c',{sitekey:'" + BuildConfig.HCAPTCHA_SITEKEY +
                                "',callback:function(t){Android.token(t)}});}</script></body></html>"
                            loadDataWithBaseURL(BuildConfig.SITE_URL, html, "text/html", "utf-8", null)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        }
    }
}

@Composable
fun LoginScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var captcha by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("JLPT Exam Hub", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Masuk untuk mulai ujian", color = Muted)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Sembunyi" else "Lihat") } },
            modifier = Modifier.fillMaxWidth()
        )
        val err = vm.loginError
        if (err != null) {
            Spacer(Modifier.height(12.dp))
            Text(err, color = Bad, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { captcha = true },
            enabled = !vm.busy && email.isNotBlank() && pass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Text("Masuk")
        }
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SITE_URL + "auth.html")))
        }) { Text("Belum punya akun? Daftar di website") }
        Spacer(Modifier.height(8.dp))
        Text("v" + BuildConfig.VERSION_NAME, color = Muted, fontSize = 12.sp)
    }
    if (captcha) {
        CaptchaDialog(
            onToken = { t ->
                captcha = false
                vm.login(email.trim(), pass, t)
            },
            onDismiss = { captcha = false }
        )
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: AppVM) {
    var level by remember { mutableStateOf("ALL") }
    val list = vm.exams
        .filter { level == "ALL" || it.level == level }
        .sortedWith(compareBy<ExamInfo>({ it.level }, { -(it.year * 100 + it.month) }))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JLPT Exam Hub", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (vm.userName.isNotEmpty()) Text(vm.userName, color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = { vm.loadExams() }) { Text("Muat ulang") }
            TextButton(onClick = { vm.logout() }) { Text("Keluar") }
        }
        if (vm.globalLocked && !vm.isAdmin) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), color = BadBg, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "🔒 " + vm.lockReason.ifBlank { "Semua ujian sedang dikunci admin." },
                    Modifier.padding(12.dp), color = Bad
                )
            }
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("ALL", "N1", "N2", "N3", "N4", "N5")) { l ->
                FilterChip(selected = level == l, onClick = { level = l }, label = { Text(if (l == "ALL") "Semua" else l) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val err = vm.examsError
        when {
            vm.examsLoading && vm.exams.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            err != null && vm.exams.isEmpty() ->
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.loadExams() }) { Text("Coba lagi") }
                }
            list.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada ujian.", color = Muted) }
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.key }) { e -> ExamCard(vm, e) }
            }
        }
    }
    val ee = vm.examError
    if (ee != null) {
        AlertDialog(
            onDismissRequest = { vm.examError = null },
            title = { Text("Gagal membuka ujian") }, text = { Text(ee) },
            confirmButton = { TextButton(onClick = { vm.examError = null }) { Text("OK") } }
        )
    }
    if (vm.examLoading) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
fun ExamCard(vm: AppVM, e: ExamInfo) {
    val lock = vm.lockedReason(e.key)
    Surface(
        onClick = { vm.openExam(e) }, enabled = lock == null,
        shape = RoundedCornerShape(14.dp), color = CardBg, border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(if (lock == null) Accent else Muted),
                contentAlignment = Alignment.Center
            ) { Text(e.level, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, fontWeight = FontWeight.Bold, color = Ink)
                Text("${e.year}-${"%02d".format(e.month)}", color = Muted, fontSize = 13.sp)
                if (lock != null) Text("🔒 $lock", color = Bad, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- Mode

@Composable
fun ModeScreen(vm: AppVM) {
    val d = vm.current ?: return
    BackHandler { vm.leaveExam() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = { vm.leaveExam() }) { Text("‹ Kembali") }
        Text(d.exam.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("JLPT ${d.exam.level} · ${d.questions.size} soal", color = Muted)
        Spacer(Modifier.height(16.dp))
        Text("Pilih mode", fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        for (m in vm.modeOptions()) {
            Surface(
                onClick = { vm.beginMode(m) }, enabled = m.count > 0,
                shape = RoundedCornerShape(14.dp), color = CardBg, border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, fontWeight = FontWeight.Bold, color = if (m.count > 0) Ink else Muted)
                        Text("${m.count} soal · ${m.seconds / 60} menit", color = Muted, fontSize = 13.sp)
                    }
                    Text("Mulai ›", color = if (m.count > 0) Accent else Muted, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Soal 聴解 (audio) hanya muncul kalau tersedia di database.", color = Muted, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- Quiz

object Img {
    private val cache = LruCache<String, Bitmap>(20)

    fun abs(u: String): String = if (u.startsWith("http")) u else BuildConfig.SITE_URL.trimEnd('/') + "/" + u.trimStart('/')

    fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val bytes = URL(abs(url)).openStream().use { it.readBytes() }
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / s > 1600) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            if (b != null) cache.put(url, b)
            b
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun RemoteImage(url: String) {
    val bmp by produceState<Bitmap?>(null, url) { value = withContext(Dispatchers.IO) { Img.load(url) } }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentScale = ContentScale.FillWidth)
    }
}

@Composable
fun Badge(text: String, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun QuizScreen(vm: AppVM) {
    val qs = vm.questions
    if (qs.isEmpty()) return
    val q = qs[vm.idx.coerceIn(0, qs.size - 1)]
    val d = vm.current
    val ans = vm.answers[q.id]
    val reveal = ans != null || vm.review
    val scroll = rememberScrollState()
    var grid by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    BackHandler { if (vm.review) vm.backToResult() else confirmExit = true }
    LaunchedEffect(q.id) { scroll.scrollTo(0) }
    LaunchedEffect(q.id) {
        val a = q.audio
        if (!vm.review && a != null) vm.playAudio(a)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("問 ${vm.idx + 1} / ${qs.size}", fontWeight = FontWeight.Bold, color = Ink)
                if (d != null) Text(d.exam.title, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            if (!vm.review) {
                Text(
                    timeFmt(vm.remaining), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = if (vm.remaining < 60) Bad else Ink
                )
            } else {
                Text("Mode tinjau", color = Gold, fontWeight = FontWeight.Bold)
            }
        }
        if (!vm.review) {
            val frac = vm.remaining.toFloat() / (if (vm.totalSec > 0) vm.totalSec else 1)
            LinearProgressIndicator(
                progress = { frac }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (frac < 0.25f) Bad else Accent, trackColor = Line
            )
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(q.sec, Gold)
                if (q.type.isNotBlank()) Badge(q.type, Muted)
            }
            Spacer(Modifier.height(12.dp))
            val pk = q.passage
            val pt = if (pk != null) d?.passages?.get(pk) else null
            if (pt != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFE6D2), modifier = Modifier.fillMaxWidth()) {
                    Html(pt, Modifier.padding(14.dp), size = 15.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            val im = q.img
            if (im != null) RemoteImage(im)
            val au = q.audio
            if (au != null) {
                val txt = when {
                    vm.audioPlaying == au -> "🎧 Audio sedang diputar…"
                    vm.audioPlayed.contains(au) || vm.review -> "🎧 Audio sudah diputar"
                    else -> "🎧 Audio akan diputar otomatis"
                }
                Text(txt, color = Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }
            Html(q.q, size = 18.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            q.opts.forEachIndexed { i, opt ->
                val isRight = reveal && i == q.ans
                val isWrongPick = reveal && ans != null && i == ans.selected && !ans.correct
                val bg = when {
                    isRight -> GoodBg
                    isWrongPick -> BadBg
                    else -> CardBg
                }
                val bd = when {
                    isRight -> Good
                    isWrongPick -> Bad
                    else -> Line
                }
                Surface(
                    onClick = { vm.answer(i) }, enabled = !reveal,
                    shape = RoundedCornerShape(12.dp), color = bg, border = BorderStroke(1.5.dp, bd),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontWeight = FontWeight.Bold, color = Muted)
                        Spacer(Modifier.width(12.dp))
                        Html(opt, Modifier.weight(1f), size = 16.sp)
                        if (isRight) Text("✓", color = Good, fontWeight = FontWeight.Bold)
                        if (isWrongPick) Text("✗", color = Bad, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (reveal && q.exp.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFE6D2), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Pembahasan", fontWeight = FontWeight.Bold, color = Gold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Html(q.exp, size = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Surface(color = CardBg, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.go(vm.idx - 1) }, enabled = vm.idx > 0) { Text("‹ Sebelum") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { grid = true }) { Text("Daftar soal") }
                Spacer(Modifier.weight(1f))
                val last = vm.idx == qs.size - 1
                if (last) {
                    Button(onClick = { if (vm.review) vm.backToResult() else confirmFinish = true }) {
                        Text(if (vm.review) "Selesai" else "Kumpulkan")
                    }
                } else {
                    Button(onClick = { vm.go(vm.idx + 1) }) { Text("Lanjut ›") }
                }
            }
        }
    }

    if (grid) {
        AlertDialog(
            onDismissRequest = { grid = false },
            title = { Text("Daftar soal") },
            text = {
                LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.height(320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(qs.size) { i ->
                        val a = vm.answers[qs[i].id]
                        val bg = when {
                            i == vm.idx -> Accent
                            a == null -> Color(0xFFEAE2D0)
                            a.correct -> GoodBg
                            else -> BadBg
                        }
                        val fg = if (i == vm.idx) Color.White else Ink
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(bg)
                                .clickable { vm.go(i); grid = false },
                            contentAlignment = Alignment.Center
                        ) { Text("${i + 1}", color = fg, fontWeight = FontWeight.Bold) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { grid = false }) { Text("Tutup") } }
        )
    }
    if (confirmFinish) {
        val left = qs.size - vm.answers.size
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Kumpulkan jawaban?") },
            text = { Text(if (left > 0) "Masih ada $left soal belum dijawab." else "Semua soal sudah dijawab.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish() }) { Text("Kumpulkan") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Lanjut mengerjakan") } }
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Keluar dari ujian?") },
            text = { Text("Jawaban yang belum dikumpulkan tidak dinilai.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.leaveExam() }) { Text("Keluar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Lanjut") } }
        )
    }
}

// ---------------------------------------------------------------- Result

@Composable
fun ResultScreen(vm: AppVM) {
    val r = vm.result ?: return
    val d = vm.current
    BackHandler { vm.leaveExam() }
    val pass = r.jlpt.passedOverall && r.jlpt.passedAllTested
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Hasil", color = Gold, fontWeight = FontWeight.Bold)
        Text(d?.exam?.title ?: "", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(14.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = if (pass) GoodBg else BadBg, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (pass) "Estimasi: LULUS" else "Estimasi: belum lulus",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (pass) Good else Bad
                )
                Text("Skor ${r.jlpt.totalScore}/${r.jlpt.totalMax} (batas total ${r.jlpt.overallPass}) · CEFR ${r.jlpt.cefr}", color = Muted, fontSize = 13.sp)
                Text("Hanya estimasi: bagian 聴解 tidak dihitung.", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("benar", "${r.correct}", Modifier.weight(1f))
            StatBox("salah", "${r.wrong}", Modifier.weight(1f))
            StatBox("akurasi", "${r.pct}%", Modifier.weight(1f))
            StatBox("waktu", timeFmt(r.elapsedSec), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text("Skor per seksi", fontWeight = FontWeight.Bold, color = Ink)
        for ((label, c, t) in r.sections) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(label, Modifier.weight(1f), color = Ink)
                Text("$c/$t (${if (t > 0) Math.round(c * 100.0 / t).toInt() else 0}%)", color = Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Skor resmi JLPT per seksi", fontWeight = FontWeight.Bold, color = Ink)
        for (s in r.jlpt.sections) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(s.label, Modifier.weight(1f), color = Ink)
                if (s.notTested) {
                    Text("tidak diujikan", color = Muted)
                } else {
                    Text(
                        "${s.score}/${s.max} (min ${s.pass})",
                        color = if (s.passed == false) Bad else Good, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.startReview() }, modifier = Modifier.fillMaxWidth()) { Text("Tinjau jawaban") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.backToMode() }, modifier = Modifier.fillMaxWidth()) { Text("Kerjakan lagi") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.leaveExam() }, modifier = Modifier.fillMaxWidth()) { Text("Kembali ke menu") }
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = CardBg, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- Update popup

@Composable
fun UpdateDialog(info: UpdateChecker.Info, onLater: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!info.mandatory) onLater() },
        title = { Text("Update tersedia: ${info.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (info.notes.isBlank()) "Versi baru sudah dirilis." else info.notes)
                if (info.mandatory) {
                    Spacer(Modifier.height(8.dp))
                    Text("Update ini wajib.", color = Bad, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url))) },
                colors = ButtonDefaults.buttonColors()
            ) { Text("Update sekarang") }
        },
        dismissButton = if (info.mandatory) null else ({ TextButton(onClick = onLater) { Text("Nanti") } })
    )
}
