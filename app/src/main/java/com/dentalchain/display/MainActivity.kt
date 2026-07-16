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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import kotlin.random.Random

data class DisplayState(
    val mode:String="home", val connected:Boolean=false, val clinicName:String="عيادة د. طاهر",
    val patientName:String="", val doctorName:String="", val mediaUrl:String?=null, val mediaName:String="",
    val zoom:Float=1f, val dx:Float=0f, val dy:Float=0f, val theme:String="dark",
    val connectionHint:String="جارِ البحث عن وحدة التحكم",
    val treatmentId:String="",
    val treatmentName:String=""
)

class MainActivity:ComponentActivity(){
    private val state=mutableStateOf(DisplayState())
    private val handler=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences("chair_display",Context.MODE_PRIVATE)}
    private val client=OkHttpClient.Builder().connectTimeout(900,TimeUnit.MILLISECONDS).readTimeout(1200,TimeUnit.MILLISECONDS).pingInterval(15,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private var socket:WebSocket?=null
    private var discoveryThread:Thread?=null
    private var multicastLock:WifiManager.MulticastLock?=null
    private val connecting=AtomicBoolean(false)
    private val scanning=AtomicBoolean(false)

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);immersive()
        state.value=state.value.copy(theme=prefs.getString("theme","dark")?:"dark")
        prefs.getString("last_ws_url",null)?.let{connect(it)}
        startDiscovery()
        handler.postDelayed({scanSubnet()},3500)
        setContent{DentalChairApp(state.value)}
    }
    private fun immersive(){window.decorView.systemUiVisibility=5894 or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY}

    private fun startDiscovery(){
        val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock=wifi.createMulticastLock("DentalChairDiscovery").apply{setReferenceCounted(true);acquire()}
        discoveryThread=Thread{
            try{DatagramSocket(8766).use{ds->
                ds.broadcast=true;val buffer=ByteArray(2048)
                while(!Thread.currentThread().isInterrupted){
                    val packet=DatagramPacket(buffer,buffer.size);ds.receive(packet)
                    val obj=JSONObject(String(packet.data,0,packet.length))
                    if(obj.optString("product")=="DentalChairController"){
                        val url="ws://${obj.optString("ip")}:${obj.optInt("wsPort",8765)}"
                        runOnUiThread{state.value=state.value.copy(clinicName=obj.optString("clinicName",state.value.clinicName));connect(url)}
                    }
                }
            }}catch(_:Exception){handler.postDelayed({startDiscovery()},3000)}
        }.apply{isDaemon=true;start()}
    }

    private fun localPrefix():String?{
        val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip=wifi.connectionInfo.ipAddress
        if(ip==0)return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
    }

    private fun scanSubnet(){
        if(state.value.connected||!scanning.compareAndSet(false,true))return
        val prefix=localPrefix()?:run{scanning.set(false);return}
        runOnUiThread{state.value=state.value.copy(connectionHint="فحص الشبكة المحلية تلقائيًا")}
        for(i in 1..254){
            val host="$prefix.$i";val req=Request.Builder().url("http://$host:8765/health").build()
            client.newCall(req).enqueue(object:Callback{
                override fun onFailure(call:Call,e:java.io.IOException){}
                override fun onResponse(call:Call,response:Response){response.use{
                    if(it.isSuccessful&&!state.value.connected){
                        try{val obj=JSONObject(it.body?.string()?:"{}")
                            if(obj.optString("product")=="DentalChairController")runOnUiThread{connect("ws://$host:8765")}
                        }catch(_:Exception){}
                    }
                }}
            })
        }
        handler.postDelayed({scanning.set(false);if(!state.value.connected)handler.postDelayed({scanSubnet()},12000)},2500)
    }

    private fun connect(url:String){
        if(state.value.connected||!connecting.compareAndSet(false,true))return
        runOnUiThread{state.value=state.value.copy(connectionHint="تم العثور على الوحدة، جارِ الاتصال")}
        socket?.cancel()
        socket=client.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){connecting.set(false);prefs.edit().putString("last_ws_url",url).apply();runOnUiThread{state.value=state.value.copy(connected=true,connectionHint="متصل محليًا")}}
            override fun onMessage(webSocket:WebSocket,text:String){runOnUiThread{handle(JSONObject(text))}}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String)=lost()
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?)=lost()
        })
    }
    private fun lost(){connecting.set(false);runOnUiThread{state.value=state.value.copy(connected=false,connectionHint="انقطع الاتصال — إعادة المحاولة تلقائيًا")};handler.postDelayed({prefs.getString("last_ws_url",null)?.let{connect(it)}?:scanSubnet()},2500)}

    private fun handle(o:JSONObject){when(o.optString("type")){
        "hello"->state.value=state.value.copy(connected=true)
        "theme"->{val t=o.optString("theme","dark");prefs.edit().putString("theme",t).apply();state.value=state.value.copy(theme=t)}
        "home"->state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f)
        "services"->state.value=state.value.copy(mode="services",mediaUrl=null)
        "patient"->state.value=state.value.copy(mode="patient",patientName=o.optString("displayName"),doctorName=o.optString("doctorName"),mediaUrl=null)
        "image","gif","video","pdf"->state.value=state.value.copy(mode=o.optString("type"),mediaUrl=o.optString("url"),mediaName=o.optString("name"),zoom=1f,dx=0f,dy=0f)
        "treatment_gif"->{
            val id=o.optString("id")
            val url=o.optString("url")
            val name=o.optString("name")
            state.value=state.value.copy(mode="treatment_gif",treatmentId=id,treatmentName=name,mediaUrl=url,mediaName=name,zoom=1f,dx=0f,dy=0f)
        }
        "game"->state.value=state.value.copy(mode="game",mediaUrl=null)
        "black"->state.value=state.value.copy(mode="black",mediaUrl=null)
        "hide"->state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f)
        "reset_view"->state.value=state.value.copy(zoom=1f,dx=0f,dy=0f)
        "transform"->state.value=state.value.copy(zoom=(state.value.zoom+o.optDouble("zoom",0.0).toFloat()).coerceIn(.5f,5f),dx=state.value.dx+o.optDouble("dx",0.0).toFloat(),dy=state.value.dy+o.optDouble("dy",0.0).toFloat())
    }}

    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{if(keyCode==KeyEvent.KEYCODE_BACK||keyCode==KeyEvent.KEYCODE_ESCAPE){state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f);return true};return super.onKeyDown(keyCode,event)}
    override fun onDestroy(){try{multicastLock?.release()}catch(_:Exception){};discoveryThread?.interrupt();socket?.cancel();super.onDestroy()}
}

private data class Palette(val bg:Brush,val card:Color,val text:Color,val muted:Color,val accent:Color,val blue:Color)
@Composable private fun palette(theme:String):Palette{
    val dark=theme!="light"
    return if(dark)Palette(Brush.linearGradient(listOf(Color(0xFF071727),Color(0xFF0D2941),Color(0xFF0B2035))),Color(0xCC14354C),Color.White,Color(0xFFC6DCEB),Color(0xFF54E5D6),Color(0xFF19B8F2))
    else Palette(Brush.linearGradient(listOf(Color(0xFFF6FBFF),Color(0xFFEEF8FF),Color.White)),Color.White,Color(0xFF12324A),Color(0xFF607D92),Color(0xFF0FAAA7),Color(0xFF0F52FF))
}

@Composable fun DentalChairApp(s:DisplayState){
    val p=palette(s.theme)
    Box(Modifier.fillMaxSize().background(p.bg)){
        Crossfade(s.mode,label="screen"){mode->when(mode){
            "black"->Box(Modifier.fillMaxSize().background(Color.Black))
            "image","gif"->MediaImage(s)
            "treatment_gif"->CachedTreatmentGif(s)
            "game"->ToothGame()
            "video"->VideoPlayer(s.mediaUrl)
            "pdf"->PdfFirstPage(s.mediaUrl)
            "patient"->PatientScreen(s,p)
            "services"->ServicesScreen(p)
            else->HomeScreen(s,p)
        }}
        Row(Modifier.align(Alignment.TopEnd).padding(22.dp).background(p.card,RoundedCornerShape(999.dp)).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(9.dp).background(if(s.connected)Color(0xFF55E7BD) else Color(0xFFFFCE69),RoundedCornerShape(9.dp)));Spacer(Modifier.width(8.dp))
            Text(if(s.connected)"متصل محليًا" else s.connectionHint,color=p.muted,fontSize=14.sp)
        }
    }
}

@Composable fun HomeScreen(s:DisplayState,p:Palette){
    var now by remember{mutableStateOf(Date())};LaunchedEffect(Unit){while(true){delay(30000);now=Date()}}
    Column(Modifier.fillMaxSize().padding(horizontal=70.dp,vertical=55.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.background(p.card,RoundedCornerShape(30.dp)).padding(horizontal=52.dp,vertical=38.dp),contentAlignment=Alignment.Center){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text("DENTAL CHAIN  |  DR. TAHER",color=p.accent,fontSize=27.sp)
                Spacer(Modifier.height(22.dp));Text("أهلًا بكم في ${s.clinicName}",color=p.text,fontSize=52.sp)
                Spacer(Modifier.height(13.dp));Text("Modern Technology • Precise Care • Continuous Follow-Up",color=p.muted,fontSize=22.sp)
                Spacer(Modifier.height(28.dp));Text(SimpleDateFormat("yyyy-MM-dd   HH:mm",Locale.getDefault()).format(now),color=p.text,fontSize=18.sp)
            }
        }
    }
}
@Composable fun PatientScreen(s:DisplayState,p:Palette){Column(Modifier.fillMaxSize().padding(60.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
    Box(Modifier.background(p.card,RoundedCornerShape(30.dp)).padding(horizontal=70.dp,vertical=45.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){
        Text("أهلًا بك",color=p.accent,fontSize=27.sp);Text(s.patientName.ifBlank{"ضيفنا الكريم"},color=p.text,fontSize=58.sp)
        if(s.doctorName.isNotBlank())Text("مع ${s.doctorName}",color=p.muted,fontSize=23.sp)
        Spacer(Modifier.height(15.dp));Text("نتمنى لك جلسة مريحة وهادئة",color=p.muted,fontSize=21.sp)
    }}
}}
@Composable fun ServicesScreen(p:Palette){val services=listOf("علاج العصب","الزرعات","التيجان والجسور","تجميل الأسنان","طب أسنان الأطفال","تبييض الأسنان")
    Column(Modifier.fillMaxSize().padding(48.dp)){Text("خدمات العيادة",color=p.text,fontSize=42.sp);Spacer(Modifier.height(22.dp))
        services.chunked(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(15.dp)){row.forEach{service->Box(Modifier.weight(1f).height(150.dp).background(p.card,RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center){Text(service,color=p.text,fontSize=23.sp)}}};Spacer(Modifier.height(15.dp))}
    }}
@Composable fun MediaImage(s:DisplayState){Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){AsyncImage(model=s.mediaUrl,contentDescription=s.mediaName,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=s.zoom,scaleY=s.zoom,translationX=s.dx,translationY=s.dy))}}
@Composable fun VideoPlayer(url:String?){AndroidView(factory={VideoView(it).apply{layoutParams=ViewGroup.LayoutParams(-1,-1);setVideoURI(Uri.parse(url));setOnPreparedListener{mp->mp.isLooping=true;start()}}},update={if(url!=null&&!it.isPlaying){it.setVideoURI(Uri.parse(url));it.start()}},modifier=Modifier.fillMaxSize().background(Color.Black))}

private fun cacheName(id:String,url:String):String{
    val digest=MessageDigest.getInstance("SHA-256").digest("$id|$url".toByteArray())
    return digest.joinToString(""){"%02x".format(it)}+".gif"
}

@Composable fun CachedTreatmentGif(s:DisplayState){
    val context=LocalContext.current
    var localFile by remember(s.treatmentId,s.mediaUrl){mutableStateOf<File?>(null)}
    LaunchedEffect(s.treatmentId,s.mediaUrl){
        val url=s.mediaUrl?:return@LaunchedEffect
        withContext(Dispatchers.IO){
            try{
                val dir=File(context.filesDir,"treatment_cache").apply{mkdirs()}
                val file=File(dir,cacheName(s.treatmentId,url))
                if(!file.exists()||file.length()==0L){
                    val bytes=OkHttpClient().newCall(Request.Builder().url(url).build()).execute().body?.bytes()?:return@withContext
                    file.writeBytes(bytes)
                }
                localFile=file
            }catch(_:Exception){}
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
        AsyncImage(
            model=localFile?:s.mediaUrl,
            contentDescription=s.treatmentName,
            contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxSize()
        )
        if(s.treatmentName.isNotBlank()){
            Text(s.treatmentName,color=Color.White,fontSize=24.sp,modifier=Modifier.align(Alignment.BottomCenter).padding(18.dp).background(Color(0x88000000),RoundedCornerShape(14.dp)).padding(horizontal=18.dp,vertical=8.dp))
        }
    }
}

@Composable fun ToothGame(){
    var playerX by remember{mutableStateOf(0.5f)}
    var score by remember{mutableStateOf(0)}
    var misses by remember{mutableStateOf(0)}
    var targetX by remember{mutableStateOf(Random.nextFloat().coerceIn(.08f,.92f))}
    var targetY by remember{mutableStateOf(0.05f)}
    var bad by remember{mutableStateOf(false)}
    val focus=Modifier.onPreviewKeyEvent{e->
        if(e.type==androidx.compose.ui.input.key.KeyEventType.KeyDown){
            when(e.key){
                androidx.compose.ui.input.key.Key.DirectionLeft->{playerX=(playerX-.06f).coerceAtLeast(.05f);true}
                androidx.compose.ui.input.key.Key.DirectionRight->{playerX=(playerX+.06f).coerceAtMost(.95f);true}
                else->false
            }
        }else false
    }.focusable().focusRequester(remember{androidx.compose.ui.focus.FocusRequester()}.also{requester->LaunchedEffect(Unit){requester.requestFocus()}})

    LaunchedEffect(Unit){
        while(true){
            delay(45)
            targetY+=0.012f
            if(targetY>=.86f){
                if(kotlin.math.abs(targetX-playerX)<.12f){
                    if(bad)misses++ else score++
                }else if(!bad)misses++
                targetX=Random.nextFloat().coerceIn(.08f,.92f)
                targetY=.05f
                bad=Random.nextInt(100)<35
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0C3858),Color(0xFF071727)))).then(focus)){
        Text("لعبة ابتسامة الأسنان",color=Color.White,fontSize=36.sp,modifier=Modifier.align(Alignment.TopCenter).padding(28.dp))
        Text("النقاط: $score    الأخطاء: $misses",color=Color(0xFF67E8D5),fontSize=23.sp,modifier=Modifier.align(Alignment.TopStart).padding(28.dp))
        Box(Modifier.offset(x=(targetX*1100).dp,y=(targetY*760).dp).size(60.dp).background(if(bad)Color(0xFF8A4E2D) else Color.White,RoundedCornerShape(30.dp)),contentAlignment=Alignment.Center){
            Text(if(bad)"✕" else "🦷",fontSize=30.sp)
        }
        Box(Modifier.align(Alignment.BottomStart).offset(x=(playerX*1100).dp).padding(bottom=35.dp).size(width=150.dp,height=42.dp).background(Color(0xFF19B8F2),RoundedCornerShape(22.dp)))
        Text("حرّك بالأسهم يمين ويسار والتقط الأسنان السليمة",color=Color(0xFFC8DCE8),fontSize=18.sp,modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}


@Composable fun PdfFirstPage(url:String?){val context=LocalContext.current;var bitmap by remember(url){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(url){if(url==null)return@LaunchedEffect;withContext(Dispatchers.IO){try{val bytes=OkHttpClient().newCall(Request.Builder().url(url).build()).execute().body?.bytes()?:return@withContext
        val file=File(context.cacheDir,"chair-temp.pdf");file.writeBytes(bytes);val pfd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);val renderer=PdfRenderer(pfd);val page=renderer.openPage(0)
        val bmp=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);page.close();renderer.close();pfd.close();bitmap=bmp}catch(_:Exception){}}}
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){bitmap?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}}}
