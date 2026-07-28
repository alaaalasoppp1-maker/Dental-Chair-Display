package com.dentalchain.display

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
import org.json.JSONArray
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
    val patientGender: String = "",
    val honorific: String = "",
    val doctorName: String = "",
    val mediaUrl: String? = null,
    val mediaName: String = "",
    val mediaMessageId: String = "",
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
    val startupError: String = "",
    val greeting: String = "",
    val sessionId: String = "",
    val treatmentPlan: TreatmentPlan? = null,
    val planPanoramaUrl: String = "",
    val planPanoramaAspectRatio: Float = 0f,
    val planStep: Int = 0,
    val planPanoramaOpen: Boolean = false,
    val planCompleted: Boolean = false
)

data class PlanPoint(val x: Float, val y: Float)
data class PlanAnnotation(
    val annotationId: String,
    val color: String,
    val opacity: Float,
    val strokeWidth: Float,
    val points: List<PlanPoint>
)
data class PlanStage(
    val title: String,
    val description: String,
    val teeth: String,
    val priority: String,
    val prognosis: String,
    val sessions: Int,
    val duration: String,
    val cost: Double,
    val color: String,
    val points: List<String>,
    val imageUrl: String,
    val backgroundUrl: String,
    val annotations: List<PlanAnnotation>
)
data class TreatmentPlan(
    val title: String,
    val currency: String,
    val totalCost: Double,
    val totalSessions: Int,
    val totalDuration: String,
    val closingNote: String,
    val patientName: String,
    val stages: List<PlanStage>
)

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf(DisplayState())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("chair_display", Context.MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(650, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private var socket: WebSocket? = null
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val connecting = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private var connectionGeneration = 0L
    private var lastVisualSentAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { immersive() }
        state.value = state.value.copy(theme = prefs.getString("theme", "dark") ?: "dark")

        // The interface is rendered immediately. Network work always remains in the background.
        setContent { DentalChairApp(state.value, ::reportMediaResult) }

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
                                scanClient.newCall(request).execute().use { response ->
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
        val generation = ++connectionGeneration
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) {
                    webSocket.cancel()
                    return
                }
                connecting.set(false)
                prefs.edit().putString("last_ws_url", url).apply()
                webSocket.send(JSONObject().put("type", "display_ready").toString())
                runOnUiThread { state.value = state.value.copy(connected = true, connectionHint = "متصل محليًا") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                val message = JSONObject(text)
                message.optString("messageId").takeIf { it.isNotBlank() }?.let {
                    webSocket.send(JSONObject().put("type", "ack").put("messageId", it).toString())
                }
                runOnUiThread { handle(message) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(generation)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(generation)
        })
    }

    private fun reportMediaResult(type: String, messageId: String, url: String, error: String = "") {
        if (messageId.isBlank()) return
        val payload = JSONObject()
            .put("type", type)
            .put("messageId", messageId)
            .put("url", url)
        if (error.isNotBlank()) payload.put("error", error)
        runCatching { socket?.send(payload.toString()) }
    }

    private fun lost(generation: Long) {
        if (generation != connectionGeneration) return
        connecting.set(false)
        socket = null
        runOnUiThread {
            state.value = state.value.copy(connected = false, connectionHint = "انقطع الاتصال — إعادة المحاولة تلقائيًا")
        }
        handler.postDelayed({ prefs.getString("last_ws_url", null)?.let { connect(it) } ?: safeScanSubnet() }, 1200)
    }

    private fun closeExternalGames() {
        runCatching {
            sendBroadcast(Intent("com.dentalchain.games.CLOSE").setPackage("com.dentalchain.games"))
        }
    }

    private fun bringDisplayToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
            )
        }
    }

    private fun launchGames(gameId: String = ""): Boolean {
        return runCatching {
            val intent = Intent("com.dentalchain.games.OPEN_GAME")
                .setPackage("com.dentalchain.games")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (gameId.isNotBlank()) intent.putExtra("gameId", gameId)
            startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                packageManager.getLaunchIntentForPackage("com.dentalchain.games")?.let {
                    startActivity(it)
                    true
                } ?: false
            }.getOrDefault(false)
        }
    }

    private fun parsePlan(o: JSONObject): TreatmentPlan {
        val plan = o.optJSONObject("plan") ?: JSONObject()
        val patient = plan.optJSONObject("patient")
        val rawStages = plan.optJSONArray("stages") ?: JSONArray()
        val stages = mutableListOf<PlanStage>()

        for (index in 0 until rawStages.length()) {
            val stage = rawStages.optJSONObject(index) ?: continue
            val rawTeeth = stage.optJSONArray("toothIds") ?: stage.optJSONArray("teeth")
            val teeth = if (rawTeeth == null) "" else {
                (0 until rawTeeth.length()).joinToString("، ") { rawTeeth.optString(it) }
            }
            val rawPoints = stage.optJSONArray("points") ?: JSONArray()
            val points = (0 until rawPoints.length()).map { rawPoints.optString(it) }
            val rawAnnotations = stage.optJSONArray("annotations") ?: JSONArray()
            val annotations = (0 until rawAnnotations.length()).mapNotNull { annotationIndex ->
                rawAnnotations.optJSONObject(annotationIndex)?.let { annotation ->
                    val rawPath = annotation.optJSONArray("points") ?: JSONArray()
                    PlanAnnotation(
                        annotationId = annotation.optString("annotationId"),
                        color = annotation.optString("color", "#32d6ff"),
                        opacity = annotation.optDouble("opacity", .25).toFloat(),
                        strokeWidth = annotation.optDouble("strokeWidth", 4.0).toFloat(),
                        points = (0 until rawPath.length()).mapNotNull { pointIndex ->
                            rawPath.optJSONObject(pointIndex)?.let { point ->
                                PlanPoint(point.optDouble("x").toFloat(), point.optDouble("y").toFloat())
                            }
                        }
                    )
                }
            }
            stages += PlanStage(
                title = stage.optString("title", "مرحلة علاج"),
                description = stage.optString("description"),
                teeth = teeth,
                priority = stage.optString("priority"),
                prognosis = stage.optString("prognosis"),
                sessions = stage.optInt("sessions", 1),
                duration = stage.optString("duration"),
                cost = stage.optDouble("cost", 0.0),
                color = stage.optString("color", "#32d6ff"),
                points = points,
                imageUrl = stage.optString("imageUrl"),
                backgroundUrl = stage.optString("backgroundUrl"),
                annotations = annotations
            )
        }

        val patientName = patient?.optString("fullName")
            ?: o.optJSONObject("patient")?.optString("fullName").orEmpty()
        return TreatmentPlan(
            title = plan.optString("title", "خطة العلاج المقترحة"),
            currency = plan.optString("currency", "USD"),
            totalCost = plan.optDouble("totalCost", 0.0),
            totalSessions = plan.optInt("totalSessions", 0),
            totalDuration = plan.optString("totalDuration"),
            closingNote = plan.optString("closingNote"),
            patientName = patientName,
            stages = stages
        )
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
        val type = o.optString("type")
        val visualTypes = setOf(
            "home", "services", "patient", "image", "gif", "video", "pdf",
            "treatment_gif", "appointment_qr", "black", "hide", "treatment_plan", "game"
        )
        val sentAt = o.optLong("sentAt", 0L)
        if (type in visualTypes && sentAt > 0L) {
            if (sentAt < lastVisualSentAt) return
            lastVisualSentAt = sentAt
        }
        if (type in setOf(
                "home", "services", "patient", "image", "gif", "video", "pdf",
                "treatment_gif", "appointment_qr", "black", "hide", "treatment_plan"
            )
        ) {
            closeExternalGames()
            bringDisplayToFront()
        }

        when (type) {
            "hello" -> state.value = configFrom(o, true).copy(
                sessionId = o.optString("sessionId", state.value.sessionId)
            )
            "display_config" -> state.value = configFrom(o)
            "theme" -> {
                val t = o.optString("theme", "dark").let { if (it in listOf("dark", "light", "auto")) it else "dark" }
                prefs.edit().putString("theme", t).apply()
                state.value = state.value.copy(theme = t)
            }
            "home" -> state.value = state.value.copy(
                mode = "home",
                patientName = if (o.optBoolean("clearPatient", false)) "" else state.value.patientName,
                patientGender = if (o.optBoolean("clearPatient", false)) "" else state.value.patientGender,
                honorific = if (o.optBoolean("clearPatient", false)) "" else state.value.honorific,
                greeting = if (o.optBoolean("clearPatient", false)) "" else state.value.greeting,
                sessionId = if (o.optBoolean("clearPatient", false)) "" else state.value.sessionId,
                mediaUrl = null,
                zoom = 1f,
                dx = 0f,
                dy = 0f,
                rotation = 0f
            )
            // Kept only for compatibility with older controllers; there is no separate services screen.
            "services" -> state.value = state.value.copy(mode = "home", mediaUrl = null)
            "patient" -> {
                val gender = o.optString("gender").lowercase(Locale.ROOT)
                    .let { if (it == "male" || it == "female") it else "" }
                val honorific = o.optString("honorific").ifBlank {
                    if (gender == "female") "سيدة" else if (gender == "male") "سيد" else ""
                }
                state.value = configFrom(o).copy(
                    mode = "home",
                    patientName = o.optString("displayName"),
                    patientGender = gender,
                    honorific = honorific,
                    greeting = o.optString("greeting"),
                    doctorName = o.optString("doctorName"),
                    sessionId = o.optString("sessionId"),
                    mediaUrl = null
                )
            }
            "image", "gif" -> state.value = state.value.copy(
                mode = o.optString("type"),
                mediaUrl = o.optString("url").ifBlank { o.optString("mediaUrl").ifBlank { o.optString("imageUrl") } },
                mediaName = o.optString("name").ifBlank { o.optString("fileName") },
                mediaMessageId = o.optString("messageId"),
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
            "treatment_plan" -> state.value = state.value.copy(
                mode = "treatment_plan",
                treatmentPlan = parsePlan(o),
                planPanoramaUrl = o.optString("panoramaUrl").ifBlank { o.optString("mediaUrl").ifBlank { o.optString("imageUrl") } },
                planPanoramaAspectRatio = o.optDouble("panoramaAspectRatio", 0.0).toFloat(),
                sessionId = o.optString("sessionId", state.value.sessionId),
                mediaUrl = null,
                planStep = 0,
                planPanoramaOpen = false,
                planCompleted = false,
                zoom = 1f, dx = 0f, dy = 0f, rotation = 0f
            )
            "plan_navigate" -> {
                val maxStep = (state.value.treatmentPlan?.stages?.size ?: 0) + 1
                when (o.optString("action")) {
                    "toggle", "enter" -> togglePlanPanorama()
                    else -> {
                        val nextStep = when (o.optString("action")) {
                            "next" -> (state.value.planStep + 1).coerceAtMost(maxStep)
                            "previous" -> (state.value.planStep - 1).coerceAtLeast(0)
                            "home" -> 0
                            "end" -> maxStep
                            else -> state.value.planStep
                        }
                        state.value = state.value.copy(planStep = nextStep, planPanoramaOpen = false, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                    }
                }
            }
            "game" -> {
                if (!launchGames(o.optString("gameId"))) {
                    state.value = state.value.copy(
                        mode = "home",
                        mediaUrl = null,
                        connectionHint = "تطبيق DTDC GAMES غير مثبت"
                    )
                }
            }
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

    private val browseModes = listOf("home", "appointment_qr")
    private fun browse(delta: Int) {
        val current = browseModes.indexOf(state.value.mode).let { if (it < 0) 0 else it }
        val next = (current + delta + browseModes.size) % browseModes.size
        state.value = state.value.copy(mode = browseModes[next], mediaUrl = null)
    }

    private fun togglePlanPanorama() {
        val current = state.value
        if (current.mode != "treatment_plan" || current.planStep == 0) return
        val finalStep = (current.treatmentPlan?.stages?.size ?: 0) + 1
        if (current.planPanoramaOpen) {
            state.value = current.copy(
                planPanoramaOpen = false,
                planCompleted = current.planCompleted || current.planStep == finalStep,
                zoom = 1f, dx = 0f, dy = 0f, rotation = 0f
            )
        } else {
            state.value = current.copy(planPanoramaOpen = true, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
        }
    }

    private fun panPlan(dx: Float = 0f, dy: Float = 0f) {
        val current = state.value
        state.value = current.copy(dx = current.dx + dx, dy = current.dy + dy)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val treatmentMode = state.value.mode == "treatment_plan"
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (treatmentMode && state.value.planPanoramaOpen) {
                    state.value = state.value.copy(planPanoramaOpen = false, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                    return true
                }
                closeExternalGames()
                state.value = state.value.copy(mode = "home", mediaUrl = null, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (treatmentMode) { togglePlanPanorama(); return true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (treatmentMode) {
                    if (state.value.planPanoramaOpen) panPlan(32f, 0f)
                    else {
                        val maxStep = (state.value.treatmentPlan?.stages?.size ?: 0) + 1
                        state.value = state.value.copy(planStep = (state.value.planStep + 1).coerceAtMost(maxStep), planPanoramaOpen = false, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                    }
                    return true
                }
                browse(1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (treatmentMode) {
                    if (state.value.planPanoramaOpen) panPlan(-32f, 0f)
                    else state.value = state.value.copy(planStep = (state.value.planStep - 1).coerceAtLeast(0), planPanoramaOpen = false, zoom = 1f, dx = 0f, dy = 0f, rotation = 0f)
                    return true
                }
                browse(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (treatmentMode) { panPlan(0f, -32f); return true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (treatmentMode) { panPlan(0f, 32f); return true }
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                if (treatmentMode) { state.value = state.value.copy(zoom = (state.value.zoom + .15f).coerceAtMost(5f)); return true }
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                if (treatmentMode) { state.value = state.value.copy(zoom = (state.value.zoom - .15f).coerceAtLeast(.5f)); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        connectionGeneration++
        handler.removeCallbacksAndMessages(null)
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        discoveryThread?.interrupt()
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        scanClient.dispatcher.executorService.shutdown()
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
fun DentalChairApp(s: DisplayState, reportMedia: (String, String, String, String) -> Unit) {
    val p = palette(s.theme)
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = ClinicFontFamily)) {
        Box(Modifier.fillMaxSize().background(p.bg)) {
            AmbientGlow(p)
            Crossfade(s.mode, label = "screen") { mode ->
                when (mode) {
                    "black" -> Box(Modifier.fillMaxSize().background(Color.Black))
                    "treatment_plan" -> TreatmentPlanScreen(s, p)
                    "video" -> VideoPlayer(s.mediaUrl)
                    "pdf" -> PdfFirstPage(s.mediaUrl)
                    else -> DisplayScaffold(s, p) {
                        when (mode) {
                            "image", "gif" -> ClinicalMediaScreen(s, p, reportMedia)
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
                SessionCard(s, p, now, Modifier.weight(.78f).fillMaxHeight())
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
                val fallback = listOf("أهلاً بك", s.honorific, s.patientName)
                    .filter { it.isNotBlank() }.joinToString(" ")
                "${s.greeting.ifBlank { fallback }}، ${s.comfortText}"
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
            .padding(16.dp),
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

private fun safeColor(value: String, fallback: Color = Color(0xFF19B8F2)): Color {
    return runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)
}

@Composable
private fun TreatmentPlanScreen(s: DisplayState, p: Palette) {
    val plan = s.treatmentPlan ?: return
    val stageCount = plan.stages.size
    val finalStep = stageCount + 1
    val currentStep = s.planStep.coerceIn(0, finalStep)
    Box(Modifier.fillMaxSize().background(p.bg)) {
        when {
            currentStep == 0 -> PlanPanoramaIntro(s, plan, 0)
            currentStep in 1..stageCount && s.planPanoramaOpen -> PlanPanoramaOverlay(s, plan.stages[currentStep - 1].annotations, false)
            currentStep in 1..stageCount -> PlanStageScene(s, plan, plan.stages[currentStep - 1], currentStep, p)
            currentStep == finalStep && s.planPanoramaOpen -> PlanPanoramaOverlay(s, plan.stages.flatMap { it.annotations }, true)
            else -> PlanSummary(s, plan, p, s.planCompleted)
        }
    }
}


@Composable
private fun PlanPanoramaOverlay(s: DisplayState, items: List<PlanAnnotation>, healed: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        PlanPanoramaFrame(s, items, Modifier.fillMaxSize(), healed)
    }
}

@Composable
private fun PlanPanoramaIntro(s: DisplayState, plan: TreatmentPlan, revealCount: Int) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        PlanPanoramaFrame(
            s = s,
            items = plan.stages.take(revealCount).flatMap { it.annotations },
            modifier = Modifier.fillMaxSize()
        )
        if (revealCount > 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(34.dp).size(74.dp)
                    .background(Color(0xB0082232), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0x6632D6FF), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    revealCount.toString().padStart(2, '0'),
                    color = Color(0xFFBDF65D),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PlanPanoramaFrame(
    s: DisplayState,
    items: List<PlanAnnotation>,
    modifier: Modifier = Modifier,
    healed: Boolean = false
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = s.planPanoramaAspectRatio.takeIf { it in .5f..6f } ?: 2.5f
        val availableRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else ratio
        val frameModifier = if (availableRatio > ratio) {
            Modifier.fillMaxHeight().aspectRatio(ratio)
        } else {
            Modifier.fillMaxWidth().aspectRatio(ratio)
        }
        Box(frameModifier.background(Color.Black).graphicsLayer(scaleX = s.zoom, scaleY = s.zoom, translationX = s.dx, translationY = s.dy, rotationZ = s.rotation), contentAlignment = Alignment.Center) {
            if (s.planPanoramaUrl.isNotBlank()) {
                CachedRemoteMedia(
                    url = s.planPanoramaUrl,
                    cacheKey = "plan-panorama",
                    contentDescription = "Panorama",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            PlanAnnotationOverlay(items, if (healed) Color(0xFF56E6A8) else null)
        }
    }
}

@Composable
private fun PlanAnnotationOverlay(items: List<PlanAnnotation>, overrideColor: Color? = null) {
    Canvas(Modifier.fillMaxSize()) {
        items.forEach { annotation ->
            if (annotation.points.size < 3) return@forEach
            val path = Path()
            annotation.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x * size.width, point.y * size.height)
                else path.lineTo(point.x * size.width, point.y * size.height)
            }
            path.close()
            val color = overrideColor ?: safeColor(annotation.color)
            val alpha = if (overrideColor != null) .28f else annotation.opacity.coerceIn(0f, 1f)
            drawPath(path, color.copy(alpha = alpha))
            drawPath(path, color, style = Stroke(width = annotation.strokeWidth.coerceAtLeast(2f)))
        }
    }
}

@Composable
private fun AnimatedStageBackground(url: String) {
    if (url.isBlank()) return
    val transition = rememberInfiniteTransition(label = "stageBackground")
    val scale by transition.animateFloat(
        initialValue = 1.03f,
        targetValue = 1.11f,
        animationSpec = infiniteRepeatable(tween(12000), RepeatMode.Reverse),
        label = "stageScale"
    )
    val drift by transition.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(15000), RepeatMode.Reverse),
        label = "stageDrift"
    )
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = drift
        )
    )
}

@Composable
private fun PlanHeader(s: DisplayState, p: Palette, subtitle: String = "عرض خطة العلاج") {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxHeight < 850.dp || maxWidth < 1450.dp
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().height(if (compact) 56.dp else 76.dp)
                    .background(Color(0xA8082232))
                    .padding(horizontal = if (compact) 24.dp else 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(subtitle, color = p.muted, fontSize = if (compact) 11.sp else 13.sp)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.chainName, color = p.accent, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold)
                    if (!compact) Text(s.displayTitle, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PlanStageScene(
    s: DisplayState,
    plan: TreatmentPlan,
    stage: PlanStage,
    index: Int,
    p: Palette
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 1450.dp || maxHeight < 850.dp
        val outerX = if (compact) 24.dp else 46.dp
        val outerY = if (compact) 16.dp else 28.dp
        val gap = if (compact) 20.dp else 34.dp
        val cardWidth = if (compact) (maxWidth * .31f).coerceIn(300.dp, 360.dp) else 410.dp
        val cardPadding = if (compact) 18.dp else 26.dp
        val titleSize = if (compact) 27.sp else 35.sp
        val descriptionSize = if (compact) 13.sp else 15.sp
        val thumbHeight = if (compact) 132.dp else 180.dp
        Box(Modifier.fillMaxSize()) {
            if (stage.backgroundUrl.isNotBlank()) {
                AnimatedStageBackground(stage.backgroundUrl)
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF061827), Color(0xFF0B3D59), Color(0xFF071B2A))
                        )
                    )
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color(0xC9061725), Color(0x52061725), Color(0xA0061725))
                    )
                )
            )
            Column(Modifier.fillMaxSize()) {
                PlanHeader(s, p, "المرحلة ${index.toString().padStart(2, '0')}")
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = outerX, vertical = outerY),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        Column(
                            Modifier.width(cardWidth).fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .shadow(12.dp, RoundedCornerShape(if (compact) 22.dp else 28.dp), clip = false)
                                    .background(p.card, RoundedCornerShape(if (compact) 22.dp else 28.dp))
                                    .border(1.dp, p.border, RoundedCornerShape(if (compact) 22.dp else 28.dp))
                                    .padding(cardPadding)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(if (compact) 48.dp else 62.dp)
                                            .background(p.accent.copy(alpha = .14f), RoundedCornerShape(16.dp))
                                            .border(1.dp, p.accent.copy(alpha = .30f), RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(index.toString().padStart(2, '0'), color = p.accent, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(stage.priority.ifBlank { "مرحلة علاجية" }, color = safeColor(stage.color, Color(0xFFBDF65D)), fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(if (compact) 9.dp else 13.dp))
                                Text(stage.title, color = Color.White, fontSize = titleSize, lineHeight = if (compact) 34.sp else 43.sp, fontWeight = FontWeight.SemiBold)
                                if (stage.description.isNotBlank()) {
                                    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                                    Text(stage.description, color = Color(0xFFD5E4EC), fontSize = descriptionSize, lineHeight = if (compact) 20.sp else 24.sp, maxLines = if (compact) 3 else 5)
                                }
                                Spacer(Modifier.height(if (compact) 10.dp else 17.dp))
                                Row(
                                    Modifier.fillMaxWidth().background(Color.White.copy(alpha = .055f), RoundedCornerShape(14.dp)).padding(if (compact) 10.dp else 13.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    PlanMeta("الأسنان", stage.teeth)
                                    PlanMeta("المدة", stage.duration)
                                    PlanMeta("التكلفة", "${stage.cost.toInt()} ${plan.currency}")
                                }
                                stage.points.take(if (compact) 2 else 4).forEach { point ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (compact) 7.dp else 11.dp)) {
                                        Box(Modifier.size(if (compact) 17.dp else 21.dp).background(p.accent.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                                            Text("✓", color = p.accent, fontSize = if (compact) 10.sp else 12.sp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(point, color = Color.White, fontSize = if (compact) 11.sp else 13.sp, maxLines = 1)
                                    }
                                }
                                Spacer(Modifier.height(if (compact) 9.dp else 14.dp))
                                Box(
                                    Modifier.fillMaxWidth().height(thumbHeight)
                                        .background(Color.Black, RoundedCornerShape(17.dp))
                                        .clip(RoundedCornerShape(17.dp))
                                        .border(1.dp, p.border, RoundedCornerShape(17.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PlanPanoramaFrame(s, stage.annotations, Modifier.fillMaxSize())
                                }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (stage.imageUrl.isNotBlank()) {
                                CachedRemoteMedia(
                                    url = stage.imageUrl,
                                    cacheKey = "stage-${index}-${stage.title}",
                                    contentDescription = stage.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(if (compact) .86f else .94f)
                                )
                            } else if (s.planPanoramaUrl.isNotBlank()) {
                                CachedRemoteMedia(
                                    url = s.planPanoramaUrl,
                                    cacheKey = "stage-fallback-${index}",
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(if (compact) .78f else .84f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanMeta(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFFABC1CE), fontSize = 11.sp)
        Text(value.ifBlank { "—" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlanSummary(s: DisplayState, plan: TreatmentPlan, p: Palette, completed: Boolean) {
    Box(Modifier.fillMaxSize().background(p.bg)) {
        AmbientGlow(p)
        Column(Modifier.fillMaxSize()) {
            PlanHeader(s, p, if (completed) "الخطة المكتملة" else "ملخص الخطة")
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        Modifier.weight(1.7f).fillMaxHeight()
                            .background(p.card, RoundedCornerShape(28.dp))
                            .border(1.dp, p.border, RoundedCornerShape(28.dp))
                            .padding(22.dp)
                    ) {
                        Text("النتيجة المستهدفة", color = Color(0xFFBDF65D), fontSize = 14.sp)
                        Text(
                            "بعد اكتمال مراحل العلاج",
                            color = p.text,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(15.dp))
                        Box(
                            Modifier.fillMaxWidth().weight(1f)
                                .background(Color.Black, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            PlanPanoramaFrame(
                                s,
                                plan.stages.flatMap { it.annotations },
                                Modifier.fillMaxSize(),
                                healed = true
                            )
                        }
                    }
                    Column(
                        Modifier.weight(.78f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        plan.stages.forEachIndexed { index, stage ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(p.card, RoundedCornerShape(17.dp))
                                    .border(1.dp, p.border, RoundedCornerShape(17.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(31.dp).background(if (completed) Color(0xFF35C990) else p.cardStrong, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (completed) "✓" else (index + 1).toString(), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${index + 1}. ${stage.title}",
                                        color = p.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${stage.teeth.ifBlank { "—" }} · ${stage.sessions} جلسة",
                                        color = p.muted,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    "${stage.cost.toInt()} ${plan.currency}",
                                    color = Color(0xFFBDF65D),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        if (completed) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(p.cardStrong, RoundedCornerShape(17.dp))
                                    .border(1.dp, p.border, RoundedCornerShape(17.dp))
                                    .padding(17.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                PlanMeta("الوقت الإجمالي", plan.totalDuration.ifBlank { "${plan.totalSessions} جلسات" })
                                PlanMeta("التكلفة الإجمالية", "${plan.totalCost.toInt()} ${plan.currency}")
                            }
                        } else {
                            Text("اضغط Enter لمعاينة النتيجة النهائية، ثم Enter مرة ثانية لإظهار الوقت والتكلفة الإجمالية.", color = p.muted, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClinicalMediaScreen(s: DisplayState, p: Palette, reportMedia: (String, String, String, String) -> Unit) {
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
            CachedRemoteMedia(
                url = s.mediaUrl.orEmpty(),
                cacheKey = "media-${s.mediaName}-${s.mediaUrl}",
                contentDescription = s.mediaName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer(
                    scaleX = s.zoom,
                    scaleY = s.zoom,
                    translationX = s.dx,
                    translationY = s.dy,
                    rotationZ = s.rotation,
                    clip = true
                ),
                onLoaded = { reportMedia("media_loaded", s.mediaMessageId, s.mediaUrl.orEmpty(), "") },
                onError = { error -> reportMedia("media_error", s.mediaMessageId, s.mediaUrl.orEmpty(), error) }
            )
        }
    }
}


private val mediaHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

private fun cachedMediaName(key: String, url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$key|$url".toByteArray())
    val extension = runCatching {
        Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.substringBefore('?')
    }.getOrNull()?.takeIf { it.length in 2..5 } ?: "bin"
    return digest.joinToString("") { "%02x".format(it) } + "." + extension
}

@Composable
private fun CachedRemoteMedia(
    url: String,
    cacheKey: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    onLoaded: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var model by remember(url, cacheKey) { mutableStateOf<Any?>(null) }
    var loadError by remember(url, cacheKey) { mutableStateOf("") }
    LaunchedEffect(url, cacheKey) {
        if (url.isBlank()) {
            loadError = "empty_url"
            onError?.invoke(loadError)
            return@LaunchedEffect
        }
        model = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "remote_media").apply { mkdirs() }
                val file = File(dir, cachedMediaName(cacheKey, url))
                if (!file.exists() || file.length() == 0L) {
                    val part = File(dir, file.name + ".part")
                    mediaHttpClient.newCall(
                        Request.Builder()
                            .url(url)
                            .header("Cache-Control", "no-cache")
                            .build()
                    ).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP_${response.code}")
                        val body = response.body ?: throw IllegalStateException("empty_body")
                        part.outputStream().use { output -> body.byteStream().copyTo(output) }
                    }
                    if (part.length() == 0L) throw IllegalStateException("zero_bytes")
                    if (!part.renameTo(file)) {
                        part.copyTo(file, overwrite = true)
                        part.delete()
                    }
                }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
                file
            } catch (error: Exception) {
                loadError = error.message ?: error.javaClass.simpleName
                null
            }
        }
        if (model == null) onError?.invoke(loadError.ifBlank { "download_failed" })
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { onLoaded?.invoke() },
                onError = { result -> onError?.invoke(result.result.throwable.message ?: "decode_failed") }
            )
        } else if (loadError.isBlank()) {
            Text("جارِ تحميل الصورة…", color = Color(0xFFB8D4E4), fontSize = 14.sp)
        } else {
            Text("تعذر تحميل الصورة", color = Color(0xFFFFB4AB), fontSize = 14.sp)
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
