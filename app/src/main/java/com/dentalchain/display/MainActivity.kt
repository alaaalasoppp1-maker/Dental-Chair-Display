package com.dentalchain.display

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class DisplayState(
    val mode: String = "home",
    val connected: Boolean = false,
    val clinicName: String = "عيادة د. طاهر",
    val patientName: String = "",
    val doctorName: String = "",
    val mediaUrl: String? = null,
    val mediaName: String = "",
    val zoom: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f
)

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf(DisplayState())
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var discoveryThread: Thread? = null
    private var reconnectRunnable: Runnable? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        startDiscovery()
        setContent { DentalChairApp(state.value) }
    }

    private fun immersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("DentalChairDiscovery").apply {
            setReferenceCounted(true); acquire()
        }
        discoveryThread = Thread {
            try {
                DatagramSocket(8766).use { ds ->
                    ds.broadcast = true
                    val buffer = ByteArray(2048)
                    while (!Thread.currentThread().isInterrupted) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        ds.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val obj = JSONObject(text)
                        if (obj.optString("product") == "DentalChairController") {
                            val ip = obj.optString("ip")
                            val port = obj.optInt("wsPort", 8765)
                            runOnUiThread {
                                state.value = state.value.copy(clinicName = obj.optString("clinicName", state.value.clinicName))
                                connect("ws://$ip:$port")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                handler.postDelayed({ startDiscovery() }, 3000)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun connect(url: String) {
        if (state.value.connected) return
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { state.value = state.value.copy(connected = true) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { handle(JSONObject(text)) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected()
        })
    }

    private fun disconnected() {
        runOnUiThread {
            state.value = state.value.copy(connected = false)
            reconnectRunnable?.let(handler::removeCallbacks)
            reconnectRunnable = Runnable {
                state.value = state.value.copy(mode = "home", mediaUrl = null, zoom = 1f, dx = 0f, dy = 0f)
            }
            handler.postDelayed(reconnectRunnable!!, 10 * 60 * 1000L)
        }
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "hello" -> state.value = state.value.copy(connected = true)
            "home" -> state.value = state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f)
            "services" -> state.value = state.value.copy(mode="services",mediaUrl=null)
            "patient" -> state.value = state.value.copy(mode="patient",patientName=o.optString("displayName"),doctorName=o.optString("doctorName"),mediaUrl=null)
            "image","gif","video","pdf" -> state.value = state.value.copy(mode=o.optString("type"),mediaUrl=o.optString("url"),mediaName=o.optString("name"),zoom=1f,dx=0f,dy=0f)
            "black" -> state.value = state.value.copy(mode="black",mediaUrl=null)
            "hide" -> state.value = state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f)
            "reset_view" -> state.value = state.value.copy(zoom=1f,dx=0f,dy=0f)
            "transform" -> state.value = state.value.copy(
                zoom=(state.value.zoom + o.optDouble("zoom",0.0).toFloat()).coerceIn(.5f,5f),
                dx=state.value.dx+o.optDouble("dx",0.0).toFloat(),
                dy=state.value.dy+o.optDouble("dy",0.0).toFloat()
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            state.value = state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f)
            return true
        }
        return super.onKeyDown(keyCode,event)
    }

    override fun onDestroy() {
        multicastLock?.release()
        discoveryThread?.interrupt()
        socket?.cancel()
        super.onDestroy()
    }
}

@Composable
fun DentalChairApp(s: DisplayState) {
    val bg = Brush.radialGradient(listOf(Color(0xFF155994),Color(0xFF0B2A43),Color(0xFF061524)))
    Box(Modifier.fillMaxSize().background(bg)) {
        Crossfade(targetState=s.mode,label="mode") { mode ->
            when(mode) {
                "black" -> Box(Modifier.fillMaxSize().background(Color.Black))
                "image","gif" -> MediaImage(s)
                "video" -> VideoPlayer(s.mediaUrl)
                "pdf" -> PdfFirstPage(s.mediaUrl)
                "patient" -> PatientScreen(s)
                "services" -> ServicesScreen()
                else -> HomeScreen(s)
            }
        }
        Text(
            if(s.connected) "متصل محليًا" else "بانتظار وحدة التحكم",
            color=if(s.connected) Color(0xFF6EF0D8) else Color(0xFFFFD27A),
            fontSize=14.sp,
            modifier=Modifier.align(Alignment.TopEnd).padding(20.dp)
        )
    }
}

@Composable
fun HomeScreen(s: DisplayState) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while(true){ kotlinx.coroutines.delay(30000); now=Date() } }
    Column(Modifier.fillMaxSize().padding(55.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("DENTAL CHAIN  |  DR. TAHER",color=Color(0xFF54E5D6),fontSize=28.sp)
        Spacer(Modifier.height(30.dp))
        Text("أهلًا بكم في ${s.clinicName}",color=Color.White,fontSize=52.sp)
        Spacer(Modifier.height(18.dp))
        Text("تقنية حديثة • رعاية دقيقة • متابعة مستمرة",color=Color(0xFFC8DCE8),fontSize=24.sp)
        Spacer(Modifier.height(35.dp))
        Text(SimpleDateFormat("yyyy-MM-dd   HH:mm",Locale.getDefault()).format(now),color=Color.White,fontSize=19.sp)
    }
}

@Composable
fun PatientScreen(s: DisplayState) {
    Column(Modifier.fillMaxSize().padding(55.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("أهلًا بك",color=Color(0xFF54E5D6),fontSize=28.sp)
        Text(s.patientName.ifBlank{"ضيفنا الكريم"},color=Color.White,fontSize=58.sp)
        if(s.doctorName.isNotBlank()) Text("مع ${s.doctorName}",color=Color(0xFFC8DCE8),fontSize=23.sp)
        Spacer(Modifier.height(18.dp));Text("نتمنى لك جلسة مريحة وهادئة",color=Color(0xFFC8DCE8),fontSize=22.sp)
    }
}

@Composable
fun ServicesScreen() {
    val services=listOf("علاج العصب","الزرعات","التيجان والجسور","تجميل الأسنان","طب أسنان الأطفال","تبييض الأسنان")
    Column(Modifier.fillMaxSize().padding(45.dp)) {
        Text("خدمات العيادة",color=Color.White,fontSize=44.sp)
        Spacer(Modifier.height(25.dp))
        services.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                row.forEach { service ->
                    Box(Modifier.weight(1f).height(150.dp).background(Color(0x22FFFFFF),RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center) {
                        Text(service,color=Color.White,fontSize=24.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun MediaImage(s: DisplayState) {
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center) {
        AsyncImage(
            model=s.mediaUrl,
            contentDescription=s.mediaName,
            contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxSize().graphicsLayer(
                scaleX=s.zoom,scaleY=s.zoom,translationX=s.dx,translationY=s.dy
            )
        )
    }
}

@Composable
fun VideoPlayer(url: String?) {
    val context=LocalContext.current
    AndroidView(factory={
        VideoView(it).apply {
            layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            setVideoURI(Uri.parse(url));setOnPreparedListener{mp->mp.isLooping=true;start()}
        }
    },update={if(url!=null && !it.isPlaying){it.setVideoURI(Uri.parse(url));it.start()}},modifier=Modifier.fillMaxSize().background(Color.Black))
}

@Composable
fun PdfFirstPage(url: String?) {
    val context=LocalContext.current
    var bitmap by remember(url){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(url) {
        if(url==null)return@LaunchedEffect
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val bytes=OkHttpClient().newCall(Request.Builder().url(url).build()).execute().body?.bytes()?:return@withContext
                val file=File(context.cacheDir,"chair-temp.pdf");file.writeBytes(bytes)
                val pfd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer=PdfRenderer(pfd);val page=renderer.openPage(0)
                val bmp=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close();renderer.close();pfd.close()
                bitmap=bmp
            }catch(_:Exception){}
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center) {
        bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
    }
}
