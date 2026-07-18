package com.dentalchain.display

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random

data class DisplayState(
    val mode: String = "home",
    val connected: Boolean = false,
    val chainName: String = "DR TAHER DENTAL CHAIN",
    val displayTitle: String = "Clinic Display",
    val clinicDisplayName: String = "DR TAHER CLINIC",
    val clinicName: String = "عيادة د. طاهر",
    val homeEyebrow: String = "DENTAL CHAIN",
    val specialty: String = "DDS, PhD • Endodontics",
    val welcomeText: String = "WELCOME",
    val comfortText: String = "نتمنى لك جلسة مريحة",
    val patientName: String = "",
    val patientGender: String = "male",
    val honorific: String = "سيد",
    val doctorName: String = "",
    val mediaUrl: String? = null,
    val mediaName: String = "",
    val zoom: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotation: Float = 0f,
    val theme: String = "dark",
    val connectionHint: String = "جارِ البحث عن وحدة التحكم",
    val treatmentId: String = "",
    val treatmentName: String = "",
    val treatmentVersion: String = "",
    val qrDataUrl: String = "",
    val qrPatient: String = "",
    val qrDate: String = "",
    val qrTime: String = "",
    val qrReminderHours: Int = 24,
    val startupError: String = ""
)

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf(DisplayState())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("chair_display", Context.MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(550, TimeUnit.MILLISECONDS)
        .readTimeout(750, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val connecting = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { immersive() }
        state.value = state.value.copy(theme = prefs.getString("theme", "dark") ?: "dark")

        // The interface is rendered immediately. Network work always remains in the background.
        setContent { DentalChairApp(state.value) }

        handler.postDelayed({
            runCatching {
                prefs.getString("last_ws_url", null)?.let { connect(it) }
                startDiscovery()
                handler.postDelayed({ safeScanSubnet() }, 1200)
            }.onFailure { error ->
                state.value = state.value.copy(
                    connected = false,
                    connectionHint = "وضع العرض جاهز — تعذر بدء الاكتشاف التلقائي",
                    startupError = error.message ?: "Network initialization error"
                )
            }
        }, 220)
    }

    private fun immersive() {
        window.decorView.systemUiVisibility = 5894 or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun startDiscovery() {
        if (discoveryThread?.isAlive == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = wifi?.createMulticastLock("DentalChairDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { multicastLock = null }

        discoveryThread = Thread {
            try {
                DatagramSocket(8766).use { ds ->
                    ds.broadcast = true
                    ds.reuseAddress = true
                    ds.soTimeout = 12000
                    val buffer = ByteArray(2048)
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            ds.receive(packet)
                            val obj = JSONObject(String(packet.data, 0, packet.length))
                            if (obj.optString("product") == "DentalChairController") {
                                val ip = obj.optString("ip").ifBlank { packet.address.hostAddress ?: "" }
                                val port = obj.optInt("wsPort", 8765)
                                if (ip.isNotBlank()) {
                                    runOnUiThread {
                                        state.value = state.value.copy(
                                            clinicName = obj.optString("clinicName", state.value.clinicName),
                                            connectionHint = "تم العثور على وحدة التحكم"
                                        )
                                        connect("ws://$ip:$port")
                                    }
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Continue listening; some TV boxes pause multicast while sleeping.
                        } catch (_: Exception) {
                            if (Thread.currentThread().isInterrupted) break
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { state.value = state.value.copy(connectionHint = "فحص الشبكة المحلية تلقائيًا") }
            }
        }.apply {
            name = "DentalChairDiscovery"
            isDaemon = true
            start()
        }
    }

    private fun localPrefix(): String? {
        val wifiPrefix = runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching null
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo?.ipAddress ?: 0
            if (ip == 0) return@runCatching null
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
        }.getOrNull()
        if (!wifiPrefix.isNullOrBlank()) return wifiPrefix

        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { address ->
                    val parts = address.hostAddress?.split(".") ?: emptyList()
                    if (parts.size == 4) parts.take(3).joinToString(".") else null
                }
                .firstOrNull()
        }.getOrNull()
    }

    private fun safeScanSubnet() {
        runCatching { scanSubnet() }.onFailure { error ->
            scanning.set(false)
            state.value = state.value.copy(
                connected = false,
                connectionHint = "بانتظار وحدة التحكم — الفحص سيعاد تلقائيًا",
                startupError = error.message ?: "Network scan error"
            )
        }
    }

    private fun scanSubnet() {
        if (state.value.connected || !scanning.compareAndSet(false, true)) return
        val prefix = localPrefix()
        if (prefix == null) {
            scanning.set(false)
            state.value = state.value.copy(connectionHint = "بانتظار إعلان وحدة التحكم على الشبكة")
            handler.postDelayed({ safeScanSubnet() }, 8000)
            return
        }

        state.value = state.value.copy(connectionHint = "فحص الشبكة المحلية تلقائيًا")
        Thread {
            val pool = Executors.newFixedThreadPool(32)
            try {
                val tasks = (1..254).map { i ->
                    Callable {
                        if (!state.value.connected && !connecting.get()) {
                            val host = "$prefix.$i"
                            val request = Request.Builder().url("http://$host:8765/health").build()
                            runCatching {
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful && !state.value.connected) {
                                        val obj = JSONObject(response.body?.string() ?: "{}")
                                        if (obj.optString("product") == "DentalChairController") {
                                            runOnUiThread { connect("ws://$host:8765") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                pool.invokeAll(tasks, 6500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                pool.shutdownNow()
                scanning.set(false)
                if (!state.value.connected) handler.postDelayed({ safeScanSubnet() }, 8000)
            }
        }.apply {
            name = "DentalChairParallelScan"
            isDaemon = true
            start()
        }
    }

    private fun connect(url: String) {
        if (url.isBlank() || !url.startsWith("ws://")) return
        if (state.value.connected || !connecting.compareAndSet(false, true)) return
        runOnUiThread { state.value = state.value.copy(connectionHint = "تم العثور على الوحدة، جارِ الاتصال") }
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting.set(false)
                prefs.edit().putString("last_ws_url", url).apply()
                runOnUiThread { state.value = state.value.copy(connected = true, connectionHint = "متصل محليًا") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { handle(JSONObject(text)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost()
        })
    }

    private fun lost() {
        connecting.set(false)
        runOnUiThread {
            state.value = state.value.copy(connected = false, connectionHint = "انقطع الاتصال — إعادة المحاولة تلقائيًا")
        }
        handler.postDelayed({ prefs.getString("last_ws_url", null)?.let { connect(it) } ?: safeScanSubnet() }, 1200)
    }

    private fun configFrom(o: JSONObject, connected: Boolean = state.value.connected): DisplayState {
        val s = state.value
        val incomingTheme = o.optString("displayTheme", s.theme).let {
            if (it in listOf("dark", "light", "auto")) it else s.theme
        }
        if (incomingTheme != s.theme) prefs.edit().putString("theme", incomingTheme).apply()
        return s.copy(
            connected = connected,
            chainName = o.optString("chainName", s.chainName),
            displayTitle = o.optString("displayTitle", s.displayTitle),
            clinicDisplayName = o.optString("clinicDisplayName", s.clinicDisplayName),
            clinicName = o.optString("clinicName", s.clinicName),
            homeEyebrow = o.optString("homeEyebrow", s.homeEyebrow),
            specialty = o.optString("specialty", s.specialty),
            welcomeText = o.optString("welcomeText", s.welcomeText),
            comfortText = o.optString("comfortText", s.comfortText),
            theme = incomingTheme
        )
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "hello" -> state.value = configFrom(o, true)
            "display_config" -> state.value = configFrom(o)
            "theme" -> {
                val t = o.optString("theme", "dark").let { if (it in listOf("dark", "light", "auto")) it else "dark" }
                prefs.edit().putString("theme", t).apply()
                state.value = state.value.copy(theme = t)
            }
            "home" -> state.value = state.value.copy(
                mode = "home",
                patientName = if (o.optBoolean("clearPatient", false)) "" else state.value.patientName,
                mediaUrl = null,
                zoom = 1f,
                dx = 0f,
                dy = 0f,
                rotation = 0f
            )
            // Kept only for compatibility with older controllers; there is no separate services screen.
            "services" -> state.value = state.value.copy(mode = "home", mediaUrl = null)
            "patient" -> {
                val gender = o.optString("gender", "male").lowercase(Locale.ROOT)
                val honorific = o.optString("honorific").ifBlank { if (gender == "female") "سيدة" else "سيد" }
                state.value = configFrom(o).copy(
                    mode = "home",
                    patientName = o.optString("displayName"),
                    patientGender = gender,
                    honorific = honorific,
                    doctorName = o.optString("doctorName"),
                    mediaUrl = null
                )
            }
            "image", "gif" -> state.value = state.value.copy(
                mode = o.optString("type"),
                mediaUrl = o.optString("url"),
                mediaName = o.optString("name"),
                zoom = 1f,
                dx = 0f,
                dy = 0f,
                rotation = 0f
            )
            "video", "pdf" -> state.value = state.value.copy(
                mode = o.optString("type"),
                mediaUrl = o.optString("url"),
                mediaName = o.optString("name")
            )
            "treatment_gif" -> state.value = state.value.copy(
                mode = "treatment_gif",
                treatmentId = o.optString("id"),
                treatmentName = o.optString("name"),
                treatmentVersion = o.optString("version"),
                mediaUrl = o.optString("url"),
                mediaName = o.optString("name"),
                zoom = 1f,
                dx = 0f,
                dy = 0f,
                rotation = 0f
            )
            "appointment_qr" -> state.value = state.value.copy(
                mode = "appointment_qr",
                qrDataUrl = o.optString("qrDataUrl"),
                qrPatient = o.optString("patientName"),
                qrDate = o.optString("date"),
                qrTime = o.optString("time"),
                qrReminderHours = o.optInt("reminderHours", 24).coerceIn(1, 168),
                mediaUrl = null
            )
            "game" -> state.value = state.value.copy(mode = "game", mediaUrl = null)
            "black" -> state.value = state.value.copy(mode = "black", mediaUrl = null)
            "hide" -> state.value = state.value.copy(mode = "home", mediaUrl = null, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
            "reset_view" -> state.value = state.value.copy(zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
            "transform" -> state.value = state.value.copy(
                zoom = (state.value.zoom + o.optDouble("zoom", 0.0).toFloat()).coerceIn(.5f, 5f),
                dx = state.value.dx + o.optDouble("dx", 0.0).toFloat(),
                dy = state.value.dy + o.optDouble("dy", 0.0).toFloat(),
                rotation = (state.value.rotation + o.optDouble("rotate", 0.0).toFloat()) % 360f
            )
        }
    }

    private val browseModes = listOf("home", "appointment_qr", "game")
    private fun browse(delta: Int) {
        val current = browseModes.indexOf(state.value.mode).let { if (it < 0) 0 else it }
        val next = (current + delta + browseModes.size) % browseModes.size
        state.value = state.value.copy(mode = browseModes[next], mediaUrl = null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                state.value = state.value.copy(mode = "home", mediaUrl = null, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (state.value.mode != "game") { browse(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (state.value.mode != "game") { browse(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        discoveryThread?.interrupt()
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

data class Palette(
    val isDark: Boolean,
    val bg: Brush,
    val card: Color,
    val cardStrong: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val blue: Color,
    val mint: Color,
    val border: Color
)

@Composable
private fun palette(theme: String): Palette {
    val dark = when (theme) {
        "light" -> false
        "auto" -> isSystemInDarkTheme()
        else -> true
    }
    return if (dark) {
        Palette(
            true,
            Brush.linearGradient(listOf(Color(0xFF061827), Color(0xFF0B2940), Color(0xFF0C3346))),
            Color(0xB512334A),
            Color(0xDF0C2A40),
            Color(0xFFF5FAFF),
            Color(0xFFB8D4E4),
            Color(0xFF19B8F2),
            Color(0xFF0F52FF),
            Color(0xFF59F2C6),
            Color(0x274CCAF4)
        )
    } else {
        Palette(
            false,
            Brush.linearGradient(listOf(Color(0xFFDDECF4), Color(0xFFEDF6FA), Color(0xFFF7FBFD))),
            Color(0xFFFFFFFF),
            Color(0xFFD9ECF6),
            Color(0xFF0A2C43),
            Color(0xFF41677E),
            Color(0xFF007EAF),
            Color(0xFF0F52FF),
            Color(0xFF0B8F75),
            Color(0x66357391)
        )
    }
}

private val ClinicFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold)
)

@Composable
fun DentalChairApp(s: DisplayState) {
    val p = palette(s.theme)
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = ClinicFontFamily)) {
        Box(Modifier.fillMaxSize().background(p.bg)) {
            AmbientGlow(p)
            Crossfade(s.mode, label = "screen") { mode ->
                when (mode) {
                    "black" -> Box(Modifier.fillMaxSize().background(Color.Black))
                    "game" -> RoadRunnerGame()
                    "video" -> VideoPlayer(s.mediaUrl)
                    "pdf" -> PdfFirstPage(s.mediaUrl)
                    else -> DisplayScaffold(s, p) {
                        when (mode) {
                            "image", "gif" -> ClinicalMediaScreen(s, p)
                            "treatment_gif" -> TreatmentMediaScreen(s, p)
                            "appointment_qr" -> AppointmentQrScreen(s, p)
                            else -> HomeScreen(s, p)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.AmbientGlow(p: Palette) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val driftA by transition.animateFloat(
        initialValue = -34f,
        targetValue = 58f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Reverse),
        label = "driftA"
    )
    val driftB by transition.animateFloat(
        initialValue = 34f,
        targetValue = -55f,
        animationSpec = infiniteRepeatable(tween(12000), RepeatMode.Reverse),
        label = "driftB"
    )
    Box(
        Modifier.size(460.dp).offset(x = (-150 + driftA).dp, y = (-170).dp)
            .background(
                Brush.radialGradient(
                    0f to p.accent.copy(alpha = .21f),
                    .48f to p.accent.copy(alpha = .10f),
                    1f to Color.Transparent
                ), CircleShape
            ).blur(28.dp)
    )
    Box(
        Modifier.align(Alignment.BottomEnd).size(550.dp).offset(x = (170 + driftB).dp, y = 205.dp)
            .background(
                Brush.radialGradient(
                    0f to p.mint.copy(alpha = .15f),
                    .52f to p.mint.copy(alpha = .065f),
                    1f to Color.Transparent
                ), CircleShape
            ).blur(34.dp)
    )
}

@Composable
private fun DisplayScaffold(s: DisplayState, p: Palette, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionChip(s, p)
                Spacer(Modifier.weight(1f))
                ScreenBrand(s, p)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

@Composable
private fun ConnectionChip(s: DisplayState, p: Palette) {
    Row(
        Modifier.background(p.cardStrong, RoundedCornerShape(999.dp)).border(1.dp, p.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (s.connected) "متصل محلياً" else s.connectionHint, color = p.muted, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(9.dp).background(if (s.connected) p.mint else Color(0xFFFFCE69), RoundedCornerShape(9.dp))
                .border(3.dp, (if (s.connected) p.mint else Color(0xFFFFCE69)).copy(alpha = .18f), RoundedCornerShape(9.dp))
        )
    }
}

@Composable
private fun ScreenBrand(s: DisplayState, p: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(s.chainName, color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(2.dp))
            Text(s.displayTitle, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(46.dp).background(p.accent.copy(alpha = .13f), RoundedCornerShape(13.dp))
                .border(1.dp, p.accent.copy(alpha = .55f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) { Text("DT", color = p.text, fontSize = 14.sp) }
    }
}

@Composable
fun HomeScreen(s: DisplayState, p: Palette) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(30000); now = Date() } }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionCard(s, p, now, Modifier.weight(.62f).fillMaxHeight())
                HeroCard(s, p, Modifier.weight(1.55f).fillMaxHeight())
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "PRIVATE CLINICAL SESSION",
                color = p.muted.copy(alpha = .20f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun HeroCard(s: DisplayState, p: Palette, modifier: Modifier = Modifier) {
    Box(
        modifier.shadow(13.dp, RoundedCornerShape(28.dp), clip = false)
            .background(p.card, RoundedCornerShape(28.dp)).border(1.dp, p.border, RoundedCornerShape(28.dp))
            .padding(horizontal = 38.dp, vertical = 31.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
            Spacer(Modifier.weight(.32f))
            Text(s.homeEyebrow, color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
            Spacer(Modifier.height(5.dp))
            Text(s.clinicDisplayName, color = p.text, fontSize = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
            Text(s.specialty, color = p.muted, fontSize = 16.sp, textAlign = TextAlign.End)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.width(126.dp).height(3.dp)
                    .background(Brush.horizontalGradient(listOf(p.blue, p.mint)), RoundedCornerShape(99.dp))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.welcomeText,
                color = p.mint,
                fontSize = 29.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.weight(.42f))
            val greeting = if (s.patientName.isBlank()) {
                "أهلاً بكم، نتمنى لكم تجربة مريحة"
            } else {
                "أهلاً بك ${s.honorific} ${s.patientName}، ${s.comfortText}"
            }
            Text(
                greeting,
                color = p.text,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp)
            )
        }
    }
}

@Composable
private fun SessionCard(s: DisplayState, p: Palette, now: Date, modifier: Modifier = Modifier) {
    Column(
        modifier.shadow(12.dp, RoundedCornerShape(26.dp), clip = false)
            .background(p.card, RoundedCornerShape(26.dp)).border(1.dp, p.border, RoundedCornerShape(26.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = p.text, fontSize = 29.sp, fontWeight = FontWeight.Medium)
            Text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), color = p.muted, fontSize = 13.sp)
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 22.dp)
                .background(
                    Brush.linearGradient(listOf(p.accent.copy(alpha = .17f), p.mint.copy(alpha = .08f))),
                    RoundedCornerShape(22.dp)
                ),
            contentAlignment = Alignment.Center
        ) { Text("✦⁺", color = p.accent, fontSize = 48.sp, fontWeight = FontWeight.Medium) }
        Row(
            Modifier.fillMaxWidth().background(p.cardStrong.copy(alpha = .65f), RoundedCornerShape(999.dp))
                .border(1.dp, p.border, RoundedCornerShape(999.dp)).padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(9.dp).background(p.mint, RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(8.dp))
            Text(if (s.connected) "الجلسة جاهزة" else "بانتظار الكونترولر", color = p.mint, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ClinicalMediaScreen(s: DisplayState, p: Palette) {
    val frameShape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxSize().shadow(12.dp, frameShape, clip = false).clip(frameShape)
            .background(Color(0xFF061C2B)).border(1.dp, p.accent.copy(alpha = .38f), frameShape)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(Color(0xF2082232))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.mediaName.ifBlank { "Panoramic X-Ray" }, color = Color(0xFFF4FAFD), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(s.patientName.ifBlank { "المريض" }, color = Color(0xFFF4FAFD), fontSize = 14.sp)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            Row(Modifier.fillMaxSize()) {
                repeat(11) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (i % 2 == 0) Color.Transparent else Color.White.copy(alpha = .035f)))
                }
            }
            AsyncImage(
                model = s.mediaUrl,
                contentDescription = s.mediaName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer(
                    scaleX = s.zoom,
                    scaleY = s.zoom,
                    translationX = s.dx,
                    translationY = s.dy,
                    rotationZ = s.rotation,
                    clip = true
                )
            )
        }
    }
}

private fun cacheName(id: String, version: String, url: String): String {
    val stableVersion = version.ifBlank { url }
    val digest = MessageDigest.getInstance("SHA-256").digest("$id|$stableVersion".toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".gif"
}

private val treatmentHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

@Composable
private fun TreatmentMediaScreen(s: DisplayState, p: Palette) {
    val context = LocalContext.current
    var mediaModel by remember(s.treatmentId, s.treatmentVersion, s.mediaUrl) { mutableStateOf<Any?>(null) }
    LaunchedEffect(s.treatmentId, s.treatmentVersion, s.mediaUrl) {
        val url = s.mediaUrl ?: return@LaunchedEffect
        mediaModel = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "treatment_cache").apply { mkdirs() }
                val file = File(dir, cacheName(s.treatmentId, s.treatmentVersion, url))
                if (!file.exists() || file.length() == 0L) {
                    val part = File(dir, file.name + ".part")
                    treatmentHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext url
                        val bytes = response.body?.bytes() ?: return@withContext url
                        part.writeBytes(bytes)
                    }
                    if (!part.renameTo(file)) {
                        file.writeBytes(part.readBytes())
                        part.delete()
                    }
                }
                file.setLastModified(System.currentTimeMillis())
                dir.listFiles { candidate -> candidate.extension == "gif" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(30)
                    ?.forEach { it.delete() }
                file
            } catch (_: Exception) { url }
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxSize().shadow(12.dp, shape, clip = false).clip(shape)
            .background(Color(0xFF061B29)).border(1.dp, p.accent.copy(alpha = .34f), shape),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = mediaModel,
            contentDescription = s.treatmentName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp)
        )
        if (mediaModel == null) Text("جارِ تحضير العرض…", color = Color(0xFFB8D4E4), fontSize = 14.sp)
        if (s.treatmentName.isNotBlank()) {
            Text(
                s.treatmentName,
                color = Color(0xFFF4FAFD),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
                    .background(Color(0xB8051826), RoundedCornerShape(999.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun AppointmentQrScreen(s: DisplayState, p: Palette) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(.88f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth(.78f).aspectRatio(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(17.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = s.qrDataUrl,
                        contentDescription = "Appointment QR",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(
                Modifier.weight(1.15f).shadow(12.dp, RoundedCornerShape(28.dp), clip = false)
                    .background(p.card, RoundedCornerShape(28.dp)).border(1.dp, p.border, RoundedCornerShape(28.dp))
                    .padding(horizontal = 34.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text("SAVE YOUR APPOINTMENT", color = p.accent, fontSize = 15.sp, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(16.dp))
                Text("احفظ موعدك على هاتفك", color = p.text, fontSize = 31.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                Spacer(Modifier.height(12.dp))
                Text(
                    "امسح الرمز لإضافة الموعد إلى التقويم مع تذكير قبل ${s.qrReminderHours} ساعة.",
                    color = p.muted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.End
                )
                if (s.qrPatient.isNotBlank()) {
                    Spacer(Modifier.height(22.dp))
                    Text(s.qrPatient, color = p.text, fontSize = 18.sp, textAlign = TextAlign.End)
                }
                if (s.qrDate.isNotBlank() || s.qrTime.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        listOf(s.qrDate, s.qrTime).filter { it.isNotBlank() }.joinToString("  •  "),
                        color = p.muted,
                        fontSize = 15.sp,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(url: String?) {
    AndroidView(
        factory = {
            VideoView(it).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp -> mp.isLooping = true; start() }
            }
        },
        update = { if (url != null && !it.isPlaying) { it.setVideoURI(Uri.parse(url)); it.start() } },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

@Composable
fun RoadRunnerGame() {
    var carX by remember { mutableStateOf(.5f) }
    var score by remember { mutableStateOf(0) }
    var obstacleX by remember { mutableStateOf(Random.nextFloat().coerceIn(.12f, .88f)) }
    var obstacleY by remember { mutableStateOf(-.1f) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val controls = Modifier.focusRequester(requester).focusable().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionLeft -> { carX = (carX - .08f).coerceAtLeast(.08f); true }
                Key.DirectionRight -> { carX = (carX + .08f).coerceAtMost(.92f); true }
                else -> false
            }
        } else false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(38)
            obstacleY += .015f
            if (obstacleY > .92f) {
                score = if (abs(obstacleX - carX) > .13f) score + 1 else maxOf(0, score - 1)
                obstacleY = -.1f
                obstacleX = Random.nextFloat().coerceIn(.12f, .88f)
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF073251), Color(0xFF06131F)))).then(controls)
    ) {
        Text("DENTAL DASH", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        Text("Score  $score", color = Color(0xFF59F2C6), fontSize = 20.sp, modifier = Modifier.align(Alignment.TopStart).padding(28.dp))
        Box(
            Modifier.align(Alignment.Center).width(540.dp).fillMaxHeight()
                .background(Color(0xFF1C2730)).border(18.dp, Color(0xFF153A49))
        ) {
            repeat(5) { i ->
                Box(
                    Modifier.align(Alignment.TopCenter).offset(y = (i * 180).dp).width(10.dp).height(88.dp)
                        .background(Color.White.copy(alpha = .42f))
                )
            }
            Box(
                Modifier.offset(x = ((obstacleX - .5f) * 480f).dp, y = (obstacleY * 700f).dp)
                    .size(width = 70.dp, height = 100.dp).background(Color(0xFFE25B5B), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) { Text("🚙", fontSize = 35.sp) }
            Box(
                Modifier.align(Alignment.BottomCenter).offset(x = ((carX - .5f) * 480f).dp).padding(bottom = 35.dp)
                    .size(width = 76.dp, height = 110.dp).background(Color(0xFF19A7FF), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) { Text("🏎️", fontSize = 39.sp) }
        }
        Text(
            "استخدم يمين ويسار وتجنّب السيارات",
            color = Color(0xFFB8CFDD),
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
}

@Composable
fun PdfFirstPage(url: String?) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val bytes = OkHttpClient().newCall(Request.Builder().url(url).build()).execute().body?.bytes()
                    ?: return@withContext
                val file = File(context.cacheDir, "chair-temp.pdf")
                file.writeBytes(bytes)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(0)
                val bmp = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close(); renderer.close(); pfd.close(); bitmap = bmp
            } catch (_: Exception) { }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}
