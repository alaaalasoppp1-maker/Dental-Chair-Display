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
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import org.json.JSONArray
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
    val zoom:Float=1f, val dx:Float=0f, val dy:Float=0f, val rotation:Float=0f, val theme:String="dark",
    val connectionHint:String="جارِ البحث عن وحدة التحكم",
    val treatmentId:String="",
    val treatmentName:String="",
    val qrDataUrl:String="", val qrPatient:String="", val qrDate:String="", val qrTime:String="",
    val startupError:String="", val greeting:String="", val sessionId:String="",
    val treatmentPlan:TreatmentPlan?=null, val planPanoramaUrl:String="",
    val planStep:Int=0, val planAuto:Boolean=false
)

data class PlanPoint(val x:Float,val y:Float)
data class PlanAnnotation(val annotationId:String,val color:String,val opacity:Float,val strokeWidth:Float,val points:List<PlanPoint>)
data class PlanStage(val title:String,val description:String,val teeth:String,val priority:String,val prognosis:String,val sessions:Int,val duration:String,val cost:Double,val color:String,val points:List<String>,val imageUrl:String,val backgroundUrl:String,val annotations:List<PlanAnnotation>)
data class TreatmentPlan(val title:String,val currency:String,val totalCost:Double,val totalSessions:Int,val closingNote:String,val patientName:String,val stages:List<PlanStage>)

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

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        runCatching{immersive()}
        state.value=state.value.copy(theme=prefs.getString("theme","dark")?:"dark")

        // Render the interface first. Network discovery must never be able to close the app.
        setContent{DentalChairApp(state.value)}

        handler.postDelayed({
            runCatching{
                prefs.getString("last_ws_url",null)?.let{connect(it)}
                startDiscovery()
                handler.postDelayed({safeScanSubnet()},3500)
            }.onFailure{error->
                state.value=state.value.copy(
                    connected=false,
                    connectionHint="وضع العرض جاهز — تعذر بدء الاكتشاف التلقائي",
                    startupError=error.message?:"Network initialization error"
                )
            }
        },350)
    }
    private fun immersive(){window.decorView.systemUiVisibility=5894 or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY}

    private fun startDiscovery(){
        if(discoveryThread?.isAlive==true)return

        val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if(wifi==null){
            state.value=state.value.copy(connectionHint="لا توجد خدمة Wi‑Fi — سيتم فحص الشبكة البديلة")
            return
        }

        runCatching{
            multicastLock?.let{if(it.isHeld)it.release()}
            multicastLock=wifi.createMulticastLock("DentalChairDiscovery").apply{
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure{
            // Some TV boxes block multicast locks. Subnet discovery can still work.
            multicastLock=null
        }

        discoveryThread=Thread{
            try{
                DatagramSocket(8766).use{ds->
                    ds.broadcast=true
                    ds.reuseAddress=true
                    ds.soTimeout=15000
                    val buffer=ByteArray(2048)
                    while(!Thread.currentThread().isInterrupted){
                        try{
                            val packet=DatagramPacket(buffer,buffer.size)
                            ds.receive(packet)
                            val obj=JSONObject(String(packet.data,0,packet.length))
                            if(obj.optString("product")=="DentalChairController"){
                                val ip=obj.optString("ip").ifBlank{packet.address.hostAddress?:""}
                                val port=obj.optInt("wsPort",8765)
                                if(ip.isNotBlank()){
                                    val url="ws://$ip:$port"
                                    runOnUiThread{
                                        state.value=state.value.copy(
                                            clinicName=obj.optString("clinicName",state.value.clinicName),
                                            connectionHint="تم العثور على وحدة التحكم"
                                        )
                                        connect(url)
                                    }
                                }
                            }
                        }catch(_:java.net.SocketTimeoutException){
                            // Keep listening without terminating the application.
                        }catch(_:Exception){
                            if(Thread.currentThread().isInterrupted)break
                        }
                    }
                }
            }catch(_:Exception){
                runOnUiThread{
                    state.value=state.value.copy(connectionHint="فحص الشبكة المحلية تلقائيًا")
                }
            }
        }.apply{
            name="DentalChairDiscovery"
            isDaemon=true
            start()
        }
    }

    private fun localPrefix():String?{
        return runCatching{
            val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching null
            @Suppress("DEPRECATION")
            val ip=wifi.connectionInfo?.ipAddress?:0
            if(ip==0)return@runCatching null
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
        }.getOrNull()
    }

    private fun safeScanSubnet(){
        runCatching{scanSubnet()}.onFailure{error->
            scanning.set(false)
            state.value=state.value.copy(
                connected=false,
                connectionHint="بانتظار وحدة التحكم — يمكنك إدخال العنوان من الكونترولر",
                startupError=error.message?:"Network scan error"
            )
        }
    }

    private fun scanSubnet(){
        if(state.value.connected||!scanning.compareAndSet(false,true))return
        val prefix=localPrefix()
        if(prefix==null){
            scanning.set(false)
            state.value=state.value.copy(connectionHint="بانتظار إعلان وحدة التحكم على الشبكة")
            handler.postDelayed({safeScanSubnet()},15000)
            return
        }

        state.value=state.value.copy(connectionHint="فحص الشبكة المحلية تلقائيًا")
        Thread{
            try{
                for(batchStart in 1..254 step 24){
                    if(state.value.connected)break
                    val batchEnd=minOf(batchStart+23,254)
                    for(i in batchStart..batchEnd){
                        if(state.value.connected)break
                        val host="$prefix.$i"
                        val req=Request.Builder().url("http://$host:8765/health").build()
                        runCatching{
                            client.newCall(req).execute().use{response->
                                if(response.isSuccessful&&!state.value.connected){
                                    val obj=JSONObject(response.body?.string()?:"{}")
                                    if(obj.optString("product")=="DentalChairController"){
                                        runOnUiThread{connect("ws://$host:8765")}
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(120)
                }
            }catch(_:InterruptedException){
                Thread.currentThread().interrupt()
            }catch(_:Exception){
            }finally{
                scanning.set(false)
                if(!state.value.connected)handler.postDelayed({safeScanSubnet()},15000)
            }
        }.apply{
            name="DentalChairSubnetScan"
            isDaemon=true
            start()
        }
    }

    private fun connect(url:String){
        if(url.isBlank()||!url.startsWith("ws://"))return
        if(state.value.connected||!connecting.compareAndSet(false,true))return
        runOnUiThread{state.value=state.value.copy(connectionHint="تم العثور على الوحدة، جارِ الاتصال")}
        socket?.cancel()
        socket=client.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){connecting.set(false);prefs.edit().putString("last_ws_url",url).apply();webSocket.send(JSONObject().put("type","display_ready").toString());runOnUiThread{state.value=state.value.copy(connected=true,connectionHint="متصل محليًا")}}
            override fun onMessage(webSocket:WebSocket,text:String){val obj=JSONObject(text);obj.optString("messageId").takeIf{it.isNotBlank()}?.let{webSocket.send(JSONObject().put("type","ack").put("messageId",it).toString())};runOnUiThread{handle(obj)}}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String)=lost()
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?)=lost()
        })
    }
    private fun lost(){connecting.set(false);runOnUiThread{state.value=state.value.copy(connected=false,connectionHint="انقطع الاتصال — إعادة المحاولة تلقائيًا")};handler.postDelayed({prefs.getString("last_ws_url",null)?.let{connect(it)}?:safeScanSubnet()},2500)}

    private fun closeExternalGames(){runCatching{sendBroadcast(Intent("com.dentalchain.games.CLOSE").setPackage("com.dentalchain.games"))}}
    private fun launchGames(gameId:String=""):Boolean{
        return runCatching{
            val intent=Intent("com.dentalchain.games.OPEN_GAME").setPackage("com.dentalchain.games").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(gameId.isNotBlank())intent.putExtra("gameId",gameId)
            startActivity(intent);true
        }.getOrElse{
            runCatching{packageManager.getLaunchIntentForPackage("com.dentalchain.games")?.let{startActivity(it);true}?:false}.getOrDefault(false)
        }
    }
    private fun parsePlan(o:JSONObject):TreatmentPlan{
        val p=o.optJSONObject("plan")?:JSONObject()
        val patient=p.optJSONObject("patient")
        val arr=p.optJSONArray("stages")?:JSONArray()
        val stages=mutableListOf<PlanStage>()
        for(i in 0 until arr.length()){val s=arr.optJSONObject(i)?:continue;val teeth=s.optJSONArray("toothIds")?:s.optJSONArray("teeth")
            val toothText=if(teeth==null)"" else (0 until teeth.length()).joinToString("، "){teeth.optString(it)}
            val pointsArr=s.optJSONArray("points")?:JSONArray();val points=(0 until pointsArr.length()).map{pointsArr.optString(it)}
            val annsArr=s.optJSONArray("annotations")?:JSONArray();val annotations=(0 until annsArr.length()).mapNotNull{j->annsArr.optJSONObject(j)?.let{a->val pts=a.optJSONArray("points")?:JSONArray();PlanAnnotation(a.optString("annotationId"),a.optString("color","#32d6ff"),a.optDouble("opacity",.25).toFloat(),a.optDouble("strokeWidth",4.0).toFloat(),(0 until pts.length()).mapNotNull{k->pts.optJSONObject(k)?.let{q->PlanPoint(q.optDouble("x").toFloat(),q.optDouble("y").toFloat())}})}}
            stages+=PlanStage(s.optString("title","مرحلة علاج"),s.optString("description"),toothText,s.optString("priority"),s.optString("prognosis"),s.optInt("sessions",1),s.optString("duration"),s.optDouble("cost",0.0),s.optString("color","#32d6ff"),points,s.optString("imageUrl"),s.optString("backgroundUrl"),annotations)}
        val patientName=patient?.optString("fullName")?:o.optJSONObject("patient")?.optString("fullName").orEmpty()
        return TreatmentPlan(p.optString("title","خطة العلاج المقترحة"),p.optString("currency","USD"),p.optDouble("totalCost",0.0),p.optInt("totalSessions",0),p.optString("closingNote"),patientName,stages)
    }
    private fun handle(o:JSONObject){
      val type=o.optString("type")
      if(type in setOf("home","services","patient","image","gif","video","pdf","treatment_gif","appointment_qr","black","hide","treatment_plan"))closeExternalGames()
      when(type){
        "hello"->state.value=state.value.copy(connected=true)
        "theme"->{val t=o.optString("theme","dark");prefs.edit().putString("theme",t).apply();state.value=state.value.copy(theme=t)}
        "home"->state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f,rotation=0f)
        "services"->state.value=state.value.copy(mode="services",mediaUrl=null)
        "patient"->{val incoming=o.optString("sessionId");if(state.value.sessionId.isBlank()||incoming.isBlank()||incoming==state.value.sessionId)state.value=state.value.copy(mode="patient",patientName=o.optString("displayName"),greeting=o.optString("greeting"),doctorName=o.optString("doctorName"),clinicName=o.optString("clinicName",state.value.clinicName),sessionId=incoming,mediaUrl=null)}
        "image","gif","video","pdf"->state.value=state.value.copy(mode=o.optString("type"),mediaUrl=o.optString("url"),mediaName=o.optString("name"),zoom=1f,dx=0f,dy=0f,rotation=0f)
        "treatment_gif"->{
            val id=o.optString("id")
            val url=o.optString("url")
            val name=o.optString("name")
            state.value=state.value.copy(mode="treatment_gif",treatmentId=id,treatmentName=name,mediaUrl=url,mediaName=name,zoom=1f,dx=0f,dy=0f,rotation=0f)
        }
                "appointment_qr"->state.value=state.value.copy(
            mode="appointment_qr",
            qrDataUrl=o.optString("qrDataUrl"),
            qrPatient=o.optString("patientName"),
            qrDate=o.optString("date"),
            qrTime=o.optString("time"),
            mediaUrl=null
        )
        "treatment_plan"->state.value=state.value.copy(mode="treatment_plan",treatmentPlan=parsePlan(o),planPanoramaUrl=o.optString("panoramaUrl"),mediaUrl=null,planStep=0,planAuto=false)
        "plan_navigate"->{ val max=((state.value.treatmentPlan?.stages?.size?:0)*2)+1; val action=o.optString("action"); val next=when(action){"next"->(state.value.planStep+1).coerceAtMost(max);"previous"->(state.value.planStep-1).coerceAtLeast(0);"home"->0;"end"->max;else->state.value.planStep}; state.value=state.value.copy(planStep=next,planAuto=if(action=="toggle_auto")!state.value.planAuto else state.value.planAuto) }
        "game"->{if(!launchGames(o.optString("gameId")))state.value=state.value.copy(mode="game",mediaUrl=null)}
        "black"->state.value=state.value.copy(mode="black",mediaUrl=null)
        "hide"->state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f,rotation=0f)
        "reset_view"->state.value=state.value.copy(zoom=1f,dx=0f,dy=0f,rotation=0f)
        "transform"->state.value=state.value.copy(zoom=(state.value.zoom+o.optDouble("zoom",0.0).toFloat()).coerceIn(.5f,5f),dx=state.value.dx+o.optDouble("dx",0.0).toFloat(),dy=state.value.dy+o.optDouble("dy",0.0).toFloat(),rotation=(state.value.rotation+o.optDouble("rotate",0.0).toFloat())%360f)
    }}

    private val browseModes=listOf("home","patient","services","appointment_qr","game")
    private fun browse(delta:Int){
        val current=browseModes.indexOf(state.value.mode).let{if(it<0)0 else it}
        val next=(current+delta+browseModes.size)%browseModes.size
        state.value=state.value.copy(mode=browseModes[next],mediaUrl=null)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{
        when(keyCode){
            KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE->{
                state.value=state.value.copy(mode="home",mediaUrl=null,zoom=1f,dx=0f,dy=0f,rotation=0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT->{
                if(state.value.mode=="treatment_plan"){val max=((state.value.treatmentPlan?.stages?.size?:0)*2)+1;state.value=state.value.copy(planStep=(state.value.planStep+1).coerceAtMost(max));return true}
                if(state.value.mode!="game"){browse(1);return true}
            }
            KeyEvent.KEYCODE_DPAD_LEFT->{
                if(state.value.mode=="treatment_plan"){state.value=state.value.copy(planStep=(state.value.planStep-1).coerceAtLeast(0));return true}
                if(state.value.mode!="game"){browse(-1);return true}
            }
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onDestroy(){
        handler.removeCallbacksAndMessages(null)
        runCatching{if(multicastLock?.isHeld==true)multicastLock?.release()}
        discoveryThread?.interrupt()
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

data class Palette(val bg:Brush,val card:Color,val text:Color,val muted:Color,val accent:Color,val blue:Color)
@Composable private fun palette(theme:String):Palette{
    val dark=theme!="light"
    return if(dark)Palette(Brush.linearGradient(listOf(Color(0xFF071727),Color(0xFF0D2941),Color(0xFF0B2035))),Color(0xCC14354C),Color.White,Color(0xFFC6DCEB),Color(0xFF54E5D6),Color(0xFF19B8F2))
    else Palette(Brush.linearGradient(listOf(Color(0xFFF6FBFF),Color(0xFFEEF8FF),Color.White)),Color.White,Color(0xFF12324A),Color(0xFF607D92),Color(0xFF0FAAA7),Color(0xFF0F52FF))
}

@Composable fun DentalChairApp(s:DisplayState){
    val p=palette(s.theme)
    Box(Modifier.fillMaxSize().background(p.bg)){
        AmbientGlow(p)
        Crossfade(s.mode,label="screen"){mode->when(mode){
            "black"->Box(Modifier.fillMaxSize().background(Color.Black))
            "image","gif"->MediaImage(s)
            "treatment_gif"->CachedTreatmentGif(s)
            "game"->RoadRunnerGame()
            "treatment_plan"->TreatmentPlanScreen(s,p)
            "appointment_qr"->AppointmentQrScreen(s,p)
            "video"->VideoPlayer(s.mediaUrl)
            "pdf"->PdfFirstPage(s.mediaUrl)
            "patient"->PatientScreen(s,p)
            "services"->ServicesScreen(p)
            else->HomeScreen(s,p)
        }}
        if(s.mode!="treatment_plan") Row(
            Modifier.align(Alignment.TopEnd).padding(22.dp)
                .background(p.card,RoundedCornerShape(999.dp))
                .padding(horizontal=14.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Box(Modifier.size(9.dp).background(if(s.connected)Color(0xFF55E7BD) else Color(0xFFFFCE69),RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(8.dp))
            Text(if(s.connected)"متصل محليًا" else s.connectionHint,color=p.muted,fontSize=14.sp)
        }
    }
}

private fun safeColor(value:String,fallback:Color=Color(0xFF19B8F2)):Color=runCatching{Color(android.graphics.Color.parseColor(value))}.getOrDefault(fallback)

@Composable fun TreatmentPlanScreen(s:DisplayState,p:Palette){
    val plan=s.treatmentPlan?:return
    val stageCount=plan.stages.size
    val maxStep=stageCount*2+1
    var localStep by remember(plan){mutableStateOf(s.planStep)}
    LaunchedEffect(s.planStep){localStep=s.planStep.coerceIn(0,maxStep)}
    LaunchedEffect(s.planAuto,localStep){if(s.planAuto){delay(3800);localStep=if(localStep>=maxStep)0 else localStep+1}}
    Box(Modifier.fillMaxSize().background(Color(0xFF061624))){
        when{
            localStep==0->PlanPanoramaIntro(s,plan,0)
            localStep in 1..stageCount->PlanPanoramaIntro(s,plan,localStep)
            localStep==maxStep->PlanSummary(s,plan,p)
            else->{val stageIndex=localStep-stageCount;PlanStageScene(s,plan,plan.stages[stageIndex-1],stageIndex,p)}
        }
    }
}

@Composable private fun PlanHeader(){
    Row(Modifier.fillMaxWidth().padding(horizontal=54.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent,Color(0x8832D6FF)))))
        Column(Modifier.padding(horizontal=22.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("DR TAHER DENTAL CHAIN",color=Color.White,fontSize=15.sp);Text("عرض خطة العلاج",color=Color(0xFFBFD4DF),fontSize=12.sp)}
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color(0x8832D6FF),Color.Transparent))))
    }
}

@Composable private fun PlanPanoramaIntro(s:DisplayState,plan:TreatmentPlan,revealCount:Int){
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
        if(s.planPanoramaUrl.isNotBlank())AsyncImage(model=s.planPanoramaUrl,contentDescription="Panorama",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
        PlanAnnotationOverlay(plan.stages.take(revealCount).flatMap{it.annotations})
    }
}

@Composable private fun PlanAnnotationOverlay(items:List<PlanAnnotation>){
    Canvas(Modifier.fillMaxSize()){
        items.forEach{a->
            if(a.points.size<3)return@forEach
            val path=androidx.compose.ui.graphics.Path()
            a.points.forEachIndexed{i,p->if(i==0)path.moveTo(p.x*size.width,p.y*size.height)else path.lineTo(p.x*size.width,p.y*size.height)}
            path.close();val c=safeColor(a.color)
            drawPath(path,c.copy(alpha=a.opacity.coerceIn(0f,1f)))
            drawPath(path,c,style=Stroke(width=a.strokeWidth.coerceAtLeast(2f)))
        }
    }
}

@Composable private fun PlanStageScene(s:DisplayState,plan:TreatmentPlan,stage:PlanStage,index:Int,p:Palette){
    Box(Modifier.fillMaxSize()){
        if(stage.backgroundUrl.isNotBlank())AsyncImage(model=stage.backgroundUrl,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0A3150),Color(0xFF0B5A78),Color(0xFF061B2C)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x66031627),Color.Transparent,Color(0x55031627)))))
        Column(Modifier.fillMaxSize()){PlanHeader();CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){Row(Modifier.fillMaxSize().padding(horizontal=56.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(42.dp)){
            Column(Modifier.width(430.dp).fillMaxHeight(),verticalArrangement=Arrangement.Center){
                Column(Modifier.fillMaxWidth().background(Color(0xB00A3452),RoundedCornerShape(28.dp)).padding(28.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(66.dp).background(Color(0x2232D6FF),RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center){Text(index.toString().padStart(2,'0'),color=Color(0xFF32D6FF),fontSize=28.sp)};Text("المرحلة ${index}",color=Color(0xFFBDF65D),fontSize=14.sp)}
                    Text(stage.title,color=Color.White,fontSize=42.sp,lineHeight=50.sp,modifier=Modifier.padding(top=12.dp));Spacer(Modifier.height(12.dp));Box(Modifier.width(150.dp).height(2.dp).background(Color(0xFF32D6FF)));Spacer(Modifier.height(16.dp));Text(stage.description,color=Color(0xFFE2EDF2),fontSize=16.sp,lineHeight=27.sp)
                    Spacer(Modifier.height(18.dp));Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=.055f),RoundedCornerShape(16.dp)).padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween){PlanMeta("الأسنان",stage.teeth);PlanMeta("المدة",stage.duration);PlanMeta("التكلفة","${stage.cost.toInt()} ${plan.currency}")}
                    Spacer(Modifier.height(15.dp));stage.points.take(4).forEach{Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=4.dp)){Box(Modifier.size(22.dp).background(Color(0x2232D6FF),RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center){Text("✓",color=Color(0xFF32D6FF),fontSize=13.sp)};Spacer(Modifier.width(9.dp));Text(it,color=Color.White,fontSize=14.sp)}}
                }
                Spacer(Modifier.height(16.dp));Box(Modifier.fillMaxWidth().height(190.dp).background(Color.Black,RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center){if(s.planPanoramaUrl.isNotBlank())AsyncImage(model=s.planPanoramaUrl,contentDescription="Panorama",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize());PlanAnnotationOverlay(stage.annotations)}
            }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center){if(stage.imageUrl.isNotBlank())AsyncImage(model=stage.imageUrl,contentDescription=stage.title,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize(.96f)) else if(s.planPanoramaUrl.isNotBlank())AsyncImage(model=s.planPanoramaUrl,contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize(.86f))}
        }}}
    }
}
@Composable private fun PlanMeta(title:String,value:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(title,color=Color(0xFFABC1CE),fontSize=11.sp);Text(value.ifBlank{"—"},color=Color.White,fontSize=16.sp)}}
@Composable private fun PlanSummary(s:DisplayState,plan:TreatmentPlan,p:Palette){
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF061624),Color(0xFF0A3150)))).padding(horizontal=58.dp,vertical=20.dp)){PlanHeader();Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1.45f).background(Color(0xA80A3452),RoundedCornerShape(28.dp)).padding(22.dp)){Text("الخطة المكتملة",color=Color(0xFFBDF65D),fontSize=14.sp);Text("كل الحالات أصبحت ضمن خطة علاج واضحة",color=Color.White,fontSize=36.sp);Spacer(Modifier.height(16.dp));Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center){if(s.planPanoramaUrl.isNotBlank())AsyncImage(model=s.planPanoramaUrl,contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())}}
        Column(Modifier.weight(.55f),verticalArrangement=Arrangement.spacedBy(10.dp)){plan.stages.forEachIndexed{i,st->Row(Modifier.fillMaxWidth().background(Color(0xA80A3452),RoundedCornerShape(17.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(30.dp).background(Color(0xFF168FFF),RoundedCornerShape(30.dp)),contentAlignment=Alignment.Center){Text("✓",color=Color.White)};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(st.title,color=Color.White,fontSize=15.sp);Text("${st.teeth} · ${st.sessions} جلسة",color=Color(0xFFB5C9D4),fontSize=11.sp)};Text("${st.cost.toInt()} ${plan.currency}",color=Color(0xFFBDF65D),fontSize=14.sp)}};Row(Modifier.fillMaxWidth().background(Color(0xA80A3452),RoundedCornerShape(17.dp)).padding(17.dp),horizontalArrangement=Arrangement.SpaceBetween){PlanMeta("المدة الإجمالية","${plan.totalSessions} جلسات");PlanMeta("التكلفة الإجمالية","${plan.totalCost.toInt()} ${plan.currency}")}}
    }}
}

@Composable
private fun BoxScope.AmbientGlow(p:Palette){
    val transition=rememberInfiniteTransition(label="ambient")
    val driftA by transition.animateFloat(
        initialValue=-28f,
        targetValue=34f,
        animationSpec=infiniteRepeatable(tween(7000),RepeatMode.Reverse),
        label="driftA"
    )
    val driftB by transition.animateFloat(
        initialValue=24f,
        targetValue=-38f,
        animationSpec=infiniteRepeatable(tween(9000),RepeatMode.Reverse),
        label="driftB"
    )
    Box(
        Modifier.size(430.dp).offset(x=(-110+driftA).dp,y=(-130).dp)
            .background(p.blue.copy(alpha=.12f),RoundedCornerShape(430.dp))
    )
    Box(
        Modifier.align(Alignment.BottomEnd).size(520.dp)
            .offset(x=(120+driftB).dp,y=170.dp)
            .background(p.accent.copy(alpha=.09f),RoundedCornerShape(520.dp))
    )
}

@Composable
fun HomeScreen(s:DisplayState,p:Palette){
    var now by remember{mutableStateOf(Date())}
    LaunchedEffect(Unit){while(true){delay(30000);now=Date()}}
    Row(
        Modifier.fillMaxSize().padding(horizontal=64.dp,vertical=46.dp),
        horizontalArrangement=Arrangement.spacedBy(24.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Column(
            Modifier.weight(1.42f).fillMaxHeight(.82f)
                .background(p.card,RoundedCornerShape(36.dp)).padding(horizontal=58.dp,vertical=48.dp),
            verticalArrangement=Arrangement.Center
        ){
            Text("WELCOME TO",color=p.accent,fontSize=18.sp)
            Spacer(Modifier.height(16.dp))
            Text("DR TAHER",color=p.text,fontSize=67.sp)
            Text("CLINIC",color=p.blue,fontSize=73.sp)
            Spacer(Modifier.height(18.dp))
            Text("DDS, PhD · Endodontics",color=p.muted,fontSize=21.sp)
            Spacer(Modifier.height(34.dp))
            Box(Modifier.width(184.dp).height(5.dp).background(Brush.horizontalGradient(listOf(p.accent,p.blue)),RoundedCornerShape(9.dp)))
            Spacer(Modifier.height(34.dp))
            Text("Precision care. Calm experience.",color=p.muted,fontSize=19.sp)
        }
        Column(
            Modifier.weight(.58f).fillMaxHeight(.70f)
                .background(p.card,RoundedCornerShape(32.dp)).padding(30.dp),
            verticalArrangement=Arrangement.SpaceBetween
        ){
            Column{
                Text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(now),color=p.text,fontSize=54.sp)
                Text(SimpleDateFormat("EEEE, MMMM d",Locale.US).format(now),color=p.muted,fontSize=18.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(226.dp)
                    .background(Brush.linearGradient(listOf(p.blue.copy(alpha=.22f),p.accent.copy(alpha=.12f))),RoundedCornerShape(30.dp)),
                contentAlignment=Alignment.Center
            ){
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text("DT",color=p.text,fontSize=88.sp)
                    Text("CLINIC",color=p.muted,fontSize=15.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha=.055f),RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Box(Modifier.size(9.dp).background(Color(0xFF63F0C9),RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(10.dp))
                Text(if(s.connected)"Display ready" else "Waiting for controller",color=p.text,fontSize=16.sp)
            }
        }
    }
}
@Composable
fun PatientScreen(s:DisplayState,p:Palette){
    Box(Modifier.fillMaxSize().padding(horizontal=72.dp,vertical=56.dp),contentAlignment=Alignment.Center){
        Column(
            Modifier.fillMaxWidth(.88f).background(p.card,RoundedCornerShape(38.dp)).padding(58.dp)
        ){
            Text("WELCOME",color=p.accent,fontSize=17.sp)
            Spacer(Modifier.height(15.dp))
            Text(s.patientName.ifBlank{"ضيفنا الكريم"},color=p.text,fontSize=66.sp)
            Spacer(Modifier.height(10.dp))
            Text("أهلًا بك في عيادة د. طاهر",color=p.muted,fontSize=25.sp)
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp)){
                InfoCard("الطبيب",s.doctorName.ifBlank{"د. طاهر"},p,Modifier.weight(1f))
                InfoCard("حالة الجلسة","جاهزة",p,Modifier.weight(1f),Color(0xFF63F0C9))
                InfoCard("الخصوصية","مفعّلة",p,Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoCard(title:String,value:String,p:Palette,modifier:Modifier=Modifier,valueColor:Color=p.text){
    Column(modifier.background(Color.White.copy(alpha=.055f),RoundedCornerShape(22.dp)).padding(20.dp)){
        Text(title,color=p.muted,fontSize=14.sp)
        Spacer(Modifier.height(8.dp))
        Text(value,color=valueColor,fontSize=21.sp)
    }
}

@Composable fun ServicesScreen(p:Palette){
    val services=listOf(
        "علاج العصب" to "دقة وراحة",
        "الزرعات" to "حلول ثابتة",
        "التيجان والجسور" to "استعادة طبيعية",
        "تجميل الأسنان" to "ابتسامة متوازنة",
        "أسنان الأطفال" to "رعاية لطيفة",
        "تبييض الأسنان" to "إشراقة آمنة"
    )
    Column(Modifier.fillMaxSize().padding(horizontal=58.dp,vertical=48.dp)){
        Text("خدمات العيادة",color=p.text,fontSize=45.sp)
        Spacer(Modifier.height(8.dp))
        Text("رعاية متكاملة ضمن تجربة هادئة وحديثة",color=p.muted,fontSize=19.sp)
        Spacer(Modifier.height(28.dp))
        services.chunked(3).forEach{row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(17.dp)){
                row.forEachIndexed{index,(title,subtitle)->
                    Column(
                        Modifier.weight(1f).height(158.dp).background(p.card,RoundedCornerShape(26.dp)).padding(22.dp),
                        verticalArrangement=Arrangement.Center
                    ){
                        Text("0${services.indexOf(title to subtitle)+1}",color=p.accent,fontSize=14.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(title,color=p.text,fontSize=24.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(subtitle,color=p.muted,fontSize=15.sp)
                    }
                }
            }
            Spacer(Modifier.height(17.dp))
        }
    }
}
@Composable fun MediaImage(s:DisplayState){Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){AsyncImage(model=s.mediaUrl,contentDescription=s.mediaName,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=s.zoom,scaleY=s.zoom,translationX=s.dx,translationY=s.dy,rotationZ=s.rotation))}}
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

@Composable
fun AppointmentQrScreen(s:DisplayState,p:Palette){
    Row(
        Modifier.fillMaxSize().padding(horizontal=68.dp,vertical=50.dp),
        horizontalArrangement=Arrangement.spacedBy(40.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Column(Modifier.weight(1f).background(p.card,RoundedCornerShape(36.dp)).padding(48.dp)){
            Text("موعدك القادم",color=p.accent,fontSize=18.sp)
            Spacer(Modifier.height(16.dp))
            Text("احفظ الموعد\nعلى هاتفك",color=p.text,fontSize=51.sp,lineHeight=60.sp)
            Spacer(Modifier.height(20.dp))
            Text("امسح الرمز بالكاميرا، ثم وافق على إضافة الموعد إلى التقويم.",color=p.muted,fontSize=22.sp,lineHeight=35.sp)
            if(s.qrPatient.isNotBlank()){
                Spacer(Modifier.height(26.dp))
                Text(s.qrPatient,color=p.text,fontSize=25.sp)
            }
            if(s.qrDate.isNotBlank()||s.qrTime.isNotBlank()){
                Spacer(Modifier.height(7.dp))
                Text(listOf(s.qrDate,s.qrTime).filter{it.isNotBlank()}.joinToString("  •  "),color=p.muted,fontSize=20.sp)
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                listOf("iPhone","Android","بدون إنترنت").forEach{label->
                    Text(label,color=p.muted,fontSize=14.sp,modifier=Modifier.background(Color.White.copy(alpha=.055f),RoundedCornerShape(999.dp)).padding(horizontal=13.dp,vertical=9.dp))
                }
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Box(
                Modifier.width(374.dp).aspectRatio(1f).background(Color.White,RoundedCornerShape(32.dp)).padding(24.dp),
                contentAlignment=Alignment.Center
            ){
                AsyncImage(model=s.qrDataUrl,contentDescription="Appointment QR",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(15.dp))
            Text("وجّه كاميرا الهاتف نحو الرمز",color=p.muted,fontSize=16.sp)
        }
    }
}

@Composable
fun RoadRunnerGame(){
    var carX by remember{mutableStateOf(.5f)}
    var score by remember{mutableStateOf(0)}
    var obstacleX by remember{mutableStateOf(Random.nextFloat().coerceIn(.12f,.88f))}
    var obstacleY by remember{mutableStateOf(-.1f)}
    val requester=remember{FocusRequester()}
    LaunchedEffect(Unit){requester.requestFocus()}
    val controls=Modifier.focusRequester(requester).focusable().onPreviewKeyEvent{event->
        if(event.type==KeyEventType.KeyDown){
            when(event.key){
                Key.DirectionLeft->{carX=(carX-.08f).coerceAtLeast(.08f);true}
                Key.DirectionRight->{carX=(carX+.08f).coerceAtMost(.92f);true}
                else->false
            }
        }else false
    }
    LaunchedEffect(Unit){
        while(true){
            delay(38);obstacleY+=.015f
            if(obstacleY>.92f){
                if(kotlin.math.abs(obstacleX-carX)>.13f)score++ else score=maxOf(0,score-1)
                obstacleY=-.1f;obstacleX=Random.nextFloat().coerceIn(.12f,.88f)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF073251),Color(0xFF06131F)))).then(controls)){
        Text("DENTAL DASH",color=Color.White,fontSize=36.sp,modifier=Modifier.align(Alignment.TopCenter).padding(25.dp))
        Text("Score  $score",color=Color(0xFF63F0C9),fontSize=22.sp,modifier=Modifier.align(Alignment.TopStart).padding(28.dp))
        Box(Modifier.align(Alignment.Center).width(540.dp).fillMaxHeight().background(Color(0xFF1C2730))){
            repeat(5){i->Box(Modifier.align(Alignment.TopCenter).offset(y=(i*180).dp).width(12.dp).height(90.dp).background(Color.White.copy(alpha=.5f)))}
            Box(Modifier.offset(x=((obstacleX-.5f)*480f).dp,y=(obstacleY*700f).dp).size(width=70.dp,height=100.dp).background(Color(0xFFE25B5B),RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center){Text("🚙",fontSize=35.sp)}
            Box(Modifier.align(Alignment.BottomCenter).offset(x=((carX-.5f)*480f).dp).padding(bottom=35.dp).size(width=76.dp,height=110.dp).background(Color(0xFF19A7FF),RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center){Text("🏎️",fontSize=39.sp)}
        }
        Text("استخدم يمين ويسار وتجنّب السيارات",color=Color(0xFFB8CFDD),fontSize=18.sp,modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}


@Composable fun PdfFirstPage(url:String?){val context=LocalContext.current;var bitmap by remember(url){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(url){if(url==null)return@LaunchedEffect;withContext(Dispatchers.IO){try{val bytes=OkHttpClient().newCall(Request.Builder().url(url).build()).execute().body?.bytes()?:return@withContext
        val file=File(context.cacheDir,"chair-temp.pdf");file.writeBytes(bytes);val pfd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);val renderer=PdfRenderer(pfd);val page=renderer.openPage(0)
        val bmp=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);page.close();renderer.close();pfd.close();bitmap=bmp}catch(_:Exception){}}}
    Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){bitmap?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}}}
