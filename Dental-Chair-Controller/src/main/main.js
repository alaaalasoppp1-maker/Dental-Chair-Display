"use strict";
const fs=require("fs");
const path=require("path");
const mime=require("mime-types");
const QRCode=require("qrcode");
const {
  app,BrowserWindow,dialog,globalShortcut,ipcMain,Tray,Menu,nativeImage,shell
}=require("electron");
const {SettingsStore,DEFAULT_SHORTCUTS}=require("./settings-store");
const {ImageLibrary}=require("./image-library");
const {ChairServer}=require("./server");
const {DiscoveryBroadcaster}=require("./discovery");
const {PatientArchive}=require("./patient-archive");

let win,tray,settings,images,server,discovery,archive,quitting=false,currentMediaPath="";
let pendingProtocolUrl=null;
const state={settings:{},images:{},network:{},patient:{selected:false},display:{mode:"home",imageVisible:false}};

function emit(){if(win&&!win.isDestroyed())win.webContents.send("state:changed",state);}
function notice(message,type="info"){if(win&&!win.isDestroyed())win.webContents.send("notice",{message,type,at:Date.now()});}
function setDisplay(patch){state.display={...state.display,...patch};emit();}
function treatmentList(){return Array.isArray(settings?.get("treatments"))?settings.get("treatments"):[];}
function treatmentById(id){return treatmentList().find(item=>String(item.id)===String(id))||null;}
function normalizeTreatment(item){return {id:String(item?.id||Date.now()),name:String(item?.name||"").trim(),filePath:String(item?.filePath||"").trim()};}
function runCatchingTreatmentVersion(file){
  try{const st=fs.statSync(file);return `${Math.round(st.mtimeMs)}-${st.size}`;}catch{return "";}
}
function displayConfig(){
  return {
    chainName:settings?.get("chainName")||"DR TAHER DENTAL CHAIN",
    displayTitle:settings?.get("displayTitle")||"Clinic Display",
    clinicDisplayName:settings?.get("clinicDisplayName")||"DR TAHER CLINIC",
    clinicName:settings?.get("clinicName")||"عيادة د. طاهر",
    homeEyebrow:settings?.get("homeEyebrow")||"DENTAL CHAIN",
    specialty:settings?.get("specialty")||"DDS, PhD • Endodontics",
    welcomeText:settings?.get("welcomeText")||"WELCOME",
    comfortText:settings?.get("comfortText")||"نتمنى لك جلسة مريحة",
    displayTheme:settings?.get("displayTheme")||"dark"
  };
}

const STAGE_ILLUSTRATION_EXTENSIONS=new Set([".png",".jpg",".jpeg",".webp"]);
const STAGE_ILLUSTRATION_NAMES={
  "periapical-lesion":"آفة حول ذروية وعلاج لبّي",
  "fractured-root":"جذر أو سن غير قابل للترميم",
  "caries-restoration":"إزالة النخر والترميم",
  "crown-build-up":"بناء السن والتتويج",
  "dental-implant":"زرعة وتعويض سن مفقود",
  "periodontal-care":"معالجة لثوية وتنظيف"
};
function customStageIllustrationsDir(){return path.join(app.getPath("userData"),"StageIllustrations");}
function uniqueStageIllustrationPath(dir,name){
  const ext=path.extname(name).toLowerCase(),base=path.basename(name,ext).replace(/[<>:"/\\|?*\u0000-\u001f]/g," ").trim()||"stage-image";let file=path.join(dir,`${base}${ext}`),index=2;
  while(fs.existsSync(file)){file=path.join(dir,`${base}-${index}${ext}`);index++;}return file;
}
function stageIllustrationItem(file,builtin){
  const ext=path.extname(file).toLowerCase(),base=path.basename(file,ext),type=mime.lookup(file)||"image/png";
  return{id:`${builtin?"builtin":"custom"}:${base}`,name:STAGE_ILLUSTRATION_NAMES[base]||base.replace(/[-_]+/g," "),path:file,builtin,dataUrl:`data:${type};base64,${fs.readFileSync(file).toString("base64")}`};
}
function listStageIllustrations(){
  const builtInDir=path.join(app.getAppPath(),"assets","stage-illustrations"),customDir=customStageIllustrationsDir();fs.mkdirSync(customDir,{recursive:true});
  const read=(dir,builtin)=>fs.existsSync(dir)?fs.readdirSync(dir,{withFileTypes:true}).filter(entry=>entry.isFile()&&STAGE_ILLUSTRATION_EXTENSIONS.has(path.extname(entry.name).toLowerCase())).map(entry=>stageIllustrationItem(path.join(dir,entry.name),builtin)):[];
  return[...read(builtInDir,true),...read(customDir,false)];
}

async function showImageItem(item,kind="image"){
  if(!item){notice("لا توجد صورة متاحة","error");return;}
  currentMediaPath=item.path;
  const url=server.registerMedia(item.path,true);
  server.send({type:"image",url,name:item.name,position:images.index+1,total:images.items.length,kind});
  setDisplay({mode:"image",imageVisible:true});
  enableViewerKeys();
}

function showFile(file,type,optimizeImage=false){
  if(!file)return;
  if(type==="image")currentMediaPath=file;
  const url=server.registerMedia(file,optimizeImage);
  server.send({type,url,name:path.basename(file)});
  setDisplay({mode:type,imageVisible:type==="image"});
  if(type==="image")enableViewerKeys(); else disableViewerKeys();
}

function chooseFile(filters,type,optimizeImage=false){
  const r=dialog.showOpenDialogSync(win,{properties:["openFile"],filters});
  if(r?.[0])showFile(r[0],type,optimizeImage);
}

function showHome(clearPatient=false){
  if(clearPatient){
    state.patient=archive?.clear()||{selected:false};
    server?.setSession("");
  }
  server.send({type:"home",clearPatient:clearPatient===true},false);
  setDisplay({mode:"home",imageVisible:false});
  disableViewerKeys();
}
function showBlack(){server.send({type:"black"},false);setDisplay({mode:"black",imageVisible:false});disableViewerKeys();}
function hide(){server.send({type:"hide"},false);setDisplay({imageVisible:false});disableViewerKeys();}


function decodeBase64UrlUtf8(value){
  try{
    let s=String(value||"").replace(/-/g,"+").replace(/_/g,"/");
    while(s.length%4)s+="=";
    return Buffer.from(s,"base64").toString("utf8");
  }catch{return""}
}
function findProtocolUrl(argv){
  return (argv||[]).find(v=>String(v).startsWith("dentalchair://"))||null;
}
function pad2(v){return String(v).padStart(2,"0")}
function localIcsDate(d){return `${d.getFullYear()}${pad2(d.getMonth()+1)}${pad2(d.getDate())}T${pad2(d.getHours())}${pad2(d.getMinutes())}00`}
function icsEscape(v){return String(v??"").replace(/\\/g,"\\\\").replace(/\n/g,"\\n").replace(/,/g,"\\,").replace(/;/g,"\\;")}
function qrSetting(key,fallback){
  const value=String(settings?.get(key)??"").trim();
  return value||fallback;
}
function qrTemplate(value,clinic){return String(value||"").replace(/\{clinic\}/gi,clinic)}
function qrReminderHours(){return Math.max(1,Math.min(168,Number(settings?.get("qrReminderHours"))||24))}
function buildAppointmentVevent(data){
  const [y,mo,d]=String(data?.date||"").split("-").map(Number);
  const [h,mi]=String(data?.time||"").split(":").map(Number);
  if(!y||!mo||!d)throw new Error("تاريخ الموعد غير صالح");
  const start=new Date(y,mo-1,d,h||0,mi||0,0);
  const clinic=String(data?.clinicName||settings?.get("clinicName")||"عيادة د. طاهر").trim();
  const reminderHours=qrReminderHours();
  return [
    "BEGIN:VEVENT",
    `SUMMARY:${icsEscape(qrTemplate(qrSetting("qrEventTitle","موعدك في {clinic}"),clinic))}`,
    `DTSTART:${localIcsDate(start)}`,
    `DESCRIPTION:${icsEscape(qrSetting("qrEventDescription","شكراً لثقتكم."))}`,
    "BEGIN:VALARM",
    `TRIGGER:-PT${reminderHours}H`,
    "ACTION:DISPLAY",
    `DESCRIPTION:${icsEscape(qrTemplate(qrSetting("qrReminderMessage","موعدك غداً في {clinic}"),clinic))}`,
    "END:VALARM",
    "END:VEVENT"
  ].join("\r\n");
}
async function showAppointmentQr(data){
  const payload=buildAppointmentVevent(data||{});
  const qrDataUrl=await QRCode.toDataURL(payload,{errorCorrectionLevel:"M",width:760,margin:4,color:{dark:"#082f49",light:"#ffffff"}});
  const reminderHours=qrReminderHours();
  server.send({type:"appointment_qr",qrDataUrl,patientName:String(data?.patientName||""),date:String(data?.date||""),time:String(data?.time||""),clinicName:String(data?.clinicName||settings?.get("clinicName")||"عيادة د. طاهر"),reminderHours});
  setDisplay({mode:"appointment_qr",imageVisible:false});disableViewerKeys();notice(`تم عرض QR الموعد مع تذكير قبل ${reminderHours} ساعة`,"success");return true;
}
function sendPatientToDisplay(payload={}){
  state.patient=archive?.select(payload)||state.patient;
  const fullName=String(state.patient?.fullName||payload.fullName||payload.displayName||"ضيفنا الكريم").trim();
  const displayName=String(state.patient?.firstName||payload.firstName||payload.displayName||fullName.split(/\s+/)[0]||"ضيفنا الكريم").trim();
  const rawGender=String(state.patient?.gender||payload.gender||"").toLowerCase();
  const gender=rawGender==="female"||rawGender==="male"?rawGender:"";
  const honorific=gender==="female"?"سيدة":gender==="male"?"سيد":"";
  const greeting=honorific?`أهلاً بك ${honorific} ${displayName}`:`أهلاً بك ${displayName}`;
  const doctorName=String(state.patient?.doctorName||payload.doctorName||"").trim();
  const clinicName=String(state.patient?.clinicName||payload.clinicName||settings?.get("clinicName")||"عيادة د. طاهر").trim();
  server?.setSession(state.patient?.sessionId||payload.sessionId||"");
  server?.send({type:"patient",displayName,fullName,gender,honorific,greeting,doctorName,clinicName,sessionId:state.patient?.sessionId||"",...displayConfig()});
  setDisplay({mode:"patient",imageVisible:false});disableViewerKeys();notice(`تم إرسال الترحيب: ${honorific?`${honorific} `:""}${displayName}`,"success");
  return true;
}
async function handleCommand(payload){
  if(payload?.action==="clear_patient"){
    state.patient=archive?.clear()||{selected:false};
    server?.setSession("");
    server?.setCurrentState({type:"home",clearPatient:true,sessionId:""});
    emit();
    return true;
  }
  if(payload?.action==="select_patient"){
    state.patient=archive?.select(payload)||{selected:false};
    server?.setSession(state.patient?.sessionId||payload.sessionId||"");
    emit();return true;
  }
  if(payload?.action==="show_patient")return sendPatientToDisplay(payload);
  if(payload?.action==="show_appointment_qr")return await showAppointmentQr(payload);
  return false;
}
function handleProtocolUrl(raw){
  try{
    const url=new URL(String(raw||""));if(url.protocol!=="dentalchair:")return false;
    const payload=JSON.parse(decodeBase64UrlUtf8(url.searchParams.get("data")||"")||"{}");
    handleCommand(payload).catch(e=>notice(`تعذر تنفيذ الأمر: ${e.message}`,"error"));
    return ["clear_patient","select_patient","show_patient","show_appointment_qr"].includes(payload?.action);
  }catch(e){notice(`تعذر قراءة أمر شاشة الكرسي: ${e.message}`,"error")}
  return false;
}

function shortcuts(){return {...DEFAULT_SHORTCUTS,...(settings?.get("shortcuts")||{})};}
const VIEWER_SHORTCUT_KEYS=["moveLeft","moveRight","moveUp","moveDown","zoomIn","zoomOut","resetView","rotate","previous","next"];

function safeRegister(accelerator,handler,label,used){
  const key=String(accelerator||"").trim();
  if(!key)return true;
  const normalized=key.toLowerCase();
  if(used?.has(normalized))return false;
  try{
    globalShortcut.unregister(key);
    const ok=globalShortcut.register(key,()=>{
      try{handler();}catch(error){notice(`تعذر تنفيذ الاختصار: ${error.message}`,"error");}
    });
    if(ok)used?.add(normalized);
    return ok;
  }catch(error){
    notice(`تعذر تسجيل اختصار ${label||key}`,"warning");
    return false;
  }
}
function enableViewerKeys(){
  disableViewerKeys();
  const s=shortcuts();
  const used=new Set(Object.entries(s)
    .filter(([name,value])=>!VIEWER_SHORTCUT_KEYS.includes(name)&&String(value||"").trim())
    .map(([,value])=>String(value).trim().toLowerCase()));
  const moves=[
    ["moveLeft",()=>server.send({type:"transform",dx:-70,dy:0},false),"تحريك الصورة لليسار"],
    ["moveRight",()=>server.send({type:"transform",dx:70,dy:0},false),"تحريك الصورة لليمين"],
    ["moveUp",()=>server.send({type:"transform",dx:0,dy:-70},false),"تحريك الصورة للأعلى"],
    ["moveDown",()=>server.send({type:"transform",dx:0,dy:70},false),"تحريك الصورة للأسفل"],
    ["zoomIn",()=>server.send({type:"transform",zoom:0.15},false),"تكبير الصورة"],
    ["zoomOut",()=>server.send({type:"transform",zoom:-0.15},false),"تصغير الصورة"],
    ["resetView",()=>server.send({type:"reset_view"},false),"إعادة ضبط الصورة"],
    ["rotate",()=>server.send({type:"transform",rotate:20},false),"تدوير الصورة"],
    ["previous",()=>showImageItem(images.previous()),"الصورة السابقة"],
    ["next",()=>showImageItem(images.next()),"الصورة التالية"]
  ];
  moves.forEach(([name,handler,label])=>safeRegister(s[name],handler,label,used));
}
function disableViewerKeys(){
  const s=shortcuts();
  VIEWER_SHORTCUT_KEYS.map(name=>s[name]).filter(Boolean).forEach(key=>{try{globalShortcut.unregister(key);}catch{}});
}
function registerGlobalKeys(){
  globalShortcut.unregisterAll();
  const s=shortcuts(),used=new Set();
  const base=[
    ["latest",()=>showImageItem(images.latest()),"أحدث صورة"],
    ["home",showHome,"واجهة الترحيب"],
    ["black",showBlack,"الشاشة السوداء"],
    ["tempImage",()=>chooseFile([{name:"Images",extensions:["png","jpg","jpeg","bmp","webp","tif","tiff"]}],"image",true),"الصورة المؤقتة"],
    ["treatments",()=>{if(win){win.show();win.focus();win.webContents.send("ui:open-treatments");}},"المعالجات"],
    ["video",()=>chooseFile([{name:"Video",extensions:["mp4","webm","mkv"]}],"video",false),"الفيديو"],
    ["pdf",()=>chooseFile([{name:"PDF",extensions:["pdf"]}],"pdf",false),"PDF"],
    ["game",()=>server.send({type:"game"},false),"اللعبة"],
    ["hide",hide,"إغلاق العرض"]
  ];
  const failed=[];
  base.forEach(([name,handler,label])=>{if(!safeRegister(s[name],handler,label,used))failed.push(label)});
  if(!safeRegister("Alt+I",()=>{if(win){win.show();win.focus();win.webContents.send("ui:open-clinical-studio","draw");}},"الرسم السريري اليدوي",used))failed.push("الرسم السريري اليدوي");
  if(state.display.imageVisible)enableViewerKeys();
  if(failed.length)notice(`راجع الاختصارات المتعارضة أو غير الصالحة: ${failed.join("، ")}`,"warning");
}

function createWindow(){
  let silentStart=false;
  if(pendingProtocolUrl){
    try{
      const url=new URL(String(pendingProtocolUrl));
      const payload=JSON.parse(decodeBase64UrlUtf8(url.searchParams.get("data")||"")||"{}");
      silentStart=payload.action==="select_patient";
    }catch{}
  }
  win=new BrowserWindow({
    width:980,height:860,minWidth:820,minHeight:720,
    show:!settings.get("startMinimized")&&!silentStart,
    backgroundColor:"#071727",
    webPreferences:{preload:path.join(__dirname,"preload.js"),contextIsolation:true,nodeIntegration:false}
  });
  win.loadFile(path.join(__dirname,"..","renderer","index.html"));
  win.on("close",e=>{if(!quitting){e.preventDefault();win.hide();}});
  win.webContents.on("did-finish-load",emit);
}

function createTray(){
  const iconPath=path.join(__dirname,"..","..","assets","app-icon.png");
  let trayImage=nativeImage.createFromPath(iconPath);
  if(!trayImage.isEmpty())trayImage=trayImage.resize({width:20,height:20});
  tray=new Tray(trayImage.isEmpty()?nativeImage.createEmpty():trayImage);
  tray.setToolTip("Dental Chain Chair Controller");
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:"فتح",click:()=>{win.show();win.focus();}},
    {label:"أحدث صورة",click:()=>showImageItem(images.latest())},
    {label:"ترحيب",click:showHome},
    {label:"شاشة سوداء",click:showBlack},
    {type:"separator"},
    {label:"خروج",click:()=>{quitting=true;app.quit();}}
  ]));
}

function ipc(){
  ipcMain.handle("state:get",()=>state);
  ipcMain.handle("sensor:choose",async()=>{
    const r=await dialog.showOpenDialog(win,{properties:["openDirectory"],defaultPath:settings.get("sensorFolder")});
    if(!r.canceled&&r.filePaths[0]){
      settings.patch({sensorFolder:r.filePaths[0]});state.settings=settings.all();
      await images.watch(r.filePaths[0]);emit();
    }
    return state;
  });
  ipcMain.handle("sensor:reindex",()=>images.scan(settings.get("sensorFolder")));
  ipcMain.handle("image:latest",()=>showImageItem(images.latest()));
  ipcMain.handle("image:previous",()=>showImageItem(images.previous()));
  ipcMain.handle("image:next",()=>showImageItem(images.next()));
  ipcMain.handle("media:temp-image",()=>chooseFile([{name:"Images",extensions:["png","jpg","jpeg","bmp","webp","tif","tiff"]}],"image",true));
  ipcMain.handle("media:gif",()=>chooseFile([{name:"GIF",extensions:["gif","webp"]}],"gif",false));
  ipcMain.handle("media:video",()=>chooseFile([{name:"Video",extensions:["mp4","webm","mkv"]}],"video",false));
  ipcMain.handle("media:pdf",()=>chooseFile([{name:"PDF",extensions:["pdf"]}],"pdf",false));
  ipcMain.handle("archive:choose-root",async()=>{
    const result=await dialog.showOpenDialog(win,{title:"اختر مجلد أرشيف المرضى",properties:["openDirectory","createDirectory"],defaultPath:archive.root()});
    if(!result.canceled&&result.filePaths[0]){state.patient=archive.setRoot(result.filePaths[0]);state.settings=settings.all();emit();}
    return state;
  });
  ipcMain.handle("archive:open-patient-folder",()=>shell.openPath(archive.requirePatient().patientDir));
  ipcMain.handle("panorama:list",()=>archive.listPanoramas());
  ipcMain.handle("panorama:import",async()=>{
    archive.requirePatient();
    const result=await dialog.showOpenDialog(win,{title:"إضافة صورة بانوراما إلى ملف المريض",properties:["openFile"],filters:[{name:"Dental Images",extensions:["png","jpg","jpeg","bmp","webp","tif","tiff"]}]});
    return result.canceled?null:archive.importPanorama(result.filePaths[0]);
  });
  ipcMain.handle("panorama:show",(_event,file)=>{
    const allowed=archive.listPanoramas().find(item=>item.path===file);
    if(!allowed)throw new Error("الصورة ليست ضمن مجلد بانوراما المريض");
    showFile(allowed.path,"image",true);return true;
  });
  ipcMain.handle("plan:choose-background",async()=>{
    const result=await dialog.showOpenDialog(win,{
      title:"اختر خلفية المرحلة",
      properties:["openFile"],
      filters:[{name:"Images",extensions:["png","jpg","jpeg","webp","bmp"]}]
    });
    return result.canceled?"":(result.filePaths[0]||"");
  });
  ipcMain.handle("plan:save",(_event,plan)=>archive.savePlan(plan));
  ipcMain.handle("plan:list",()=>archive.listPlans());
  ipcMain.handle("plan:load",(_event,id)=>archive.loadPlan(id,true));
  ipcMain.handle("plan:show",(_event,plan)=>{
    const requested=plan||{};
    let displayed=requested?.id?archive.loadPlan(requested.id):requested;
    if(requested.focusStageId){
      displayed={...displayed,stages:(displayed.stages||[]).filter(stage=>String(stage.id||stage.stageId)===String(requested.focusStageId))};
      displayed.totalCost=(displayed.stages||[]).reduce((sum,stage)=>sum+Number(stage.cost||0),0);
      displayed.totalSessions=(displayed.stages||[]).reduce((sum,stage)=>sum+Number(stage.sessions||0),0);
    }
    const imagePath=[displayed.panoramaPath,displayed.sourceArchivePath,displayed.sourcePath]
      .find(file=>file&&fs.existsSync(file))||"";
    const panoramaUrl=imagePath?server.registerMedia(imagePath,true):"";
    const labels=new Map((displayed.labels||[]).map(label=>[String(label.id),label]));
    const allAnnotations=Array.isArray(displayed.annotations)?displayed.annotations:[];
    const stages=(displayed.stages||[]).map((stage,index)=>{
      const labelIds=new Set((stage.labelIds||[]).map(String));
      const annotations=allAnnotations.filter(annotation=>
        labelIds.has(String(annotation.labelId||""))||
        String(annotation.stageId||"")===String(stage.id||stage.stageId||"")
      ).map(annotation=>{
        const label=labels.get(String(annotation.labelId||""))||{};
        return {
          annotationId:String(annotation.annotationId||annotation.id||`annotation-${index}`),
          color:String(annotation.color||label.color||stage.color||"#32d6ff"),
          opacity:Number(annotation.opacity??.28),
          strokeWidth:Number(annotation.strokeWidth||4),
          points:(annotation.points||[]).map(point=>({x:Number(point.x),y:Number(point.y)}))
        };
      });
      const illustrationPath=[stage.illustrationPath,stage.imagePath].find(file=>file&&fs.existsSync(file))||"";
      const backgroundPath=stage.backgroundPath&&fs.existsSync(stage.backgroundPath)?stage.backgroundPath:"";
      return {
        ...stage,
        toothIds:stage.teeth||stage.toothIds||[],
        annotations,
        imageUrl:illustrationPath?server.registerMedia(illustrationPath,true):"",
        backgroundUrl:backgroundPath?server.registerMedia(backgroundPath,true):""
      };
    });
    const clean={...displayed,stages};
    delete clean.displayImageDataUrl;
    delete clean.annotatedImageDataUrl;
    clean.stages=clean.stages.map(stage=>{
      const item={...stage};
      delete item.illustrationDataUrl;
      delete item.backgroundDataUrl;
      return item;
    });
    server.send({
      type:"treatment_plan",
      plan:clean,
      panoramaUrl,
      panoramaAspectRatio:Number(displayed.panoramaAspectRatio||0),
      patient:state.patient,
      sessionId:state.patient?.sessionId||""
    });
    setDisplay({mode:"treatment_plan",imageVisible:false});disableViewerKeys();return true;
  });
  ipcMain.handle("plan:navigate",(_event,action)=>{
    server.send({type:"plan_navigate",action:String(action||"")},false);
    return true;
  });
  ipcMain.handle("studio:current-source",()=>{
    archive.requirePatient();
    if(!currentMediaPath||!fs.existsSync(currentMediaPath))throw new Error("اعرض صورة أولاً ثم افتح الاستوديو");
    const type=mime.lookup(currentMediaPath)||"image/jpeg";
    return{path:currentMediaPath,name:path.basename(currentMediaPath),dataUrl:`data:${type};base64,${fs.readFileSync(currentMediaPath).toString("base64")}`};
  });
  ipcMain.handle("stage-illustrations:list",()=>listStageIllustrations());
  ipcMain.handle("stage-illustrations:import",async()=>{
    const result=await dialog.showOpenDialog(win,{title:"إضافة صورة توضيحية لمكتبة المراحل",properties:["openFile"],filters:[{name:"Images",extensions:["png","jpg","jpeg","webp"]}]});
    if(result.canceled)return null;const sourceFile=result.filePaths[0],dir=customStageIllustrationsDir();fs.mkdirSync(dir,{recursive:true});const destination=uniqueStageIllustrationPath(dir,path.basename(sourceFile));fs.copyFileSync(sourceFile,destination);return stageIllustrationItem(destination,false);
  });
  ipcMain.handle("display:patient",(_e,p)=>{
    return sendPatientToDisplay(p||{});
  });
  ipcMain.handle("display:home",()=>showHome(false));
  ipcMain.handle("display:end",()=>showHome(true));
  ipcMain.handle("display:black",showBlack);
  ipcMain.handle("display:hide",hide);
  ipcMain.handle("display:transform",(_e,p)=>server.send({type:"transform",...p},false));
  ipcMain.handle("display:reset",()=>server.send({type:"reset_view"},false));
  ipcMain.handle("display:rotate",()=>server.send({type:"transform",rotate:20},false));
  
  ipcMain.handle("treatments:choose-gif",async()=>{
    const result=await dialog.showOpenDialog(win,{
      title:"اختر GIF المعالجة",
      properties:["openFile"],
      filters:[{name:"Animated Media",extensions:["gif","webp"]}]
    });
    return result.canceled?"":(result.filePaths[0]||"");
  });
  ipcMain.handle("treatments:save",(_e,item)=>{
    const normalized=normalizeTreatment(item);
    if(!normalized.name||!normalized.filePath){
      throw new Error("اسم المعالجة وملف GIF مطلوبان");
    }
    const list=treatmentList();
    const index=list.findIndex(x=>String(x.id)===normalized.id);
    if(index>=0)list[index]=normalized;else list.push(normalized);
    settings.patch({treatments:list});
    state.settings=settings.all();
    emit();
    return normalized;
  });
  ipcMain.handle("treatments:delete",(_e,id)=>{
    const list=treatmentList().filter(x=>String(x.id)!==String(id));
    settings.patch({treatments:list});
    state.settings=settings.all();
    emit();
    return true;
  });
  ipcMain.handle("treatments:play",(_e,id)=>{
    const item=treatmentById(id);
    if(!item)return false;
    const url=server.registerMedia(item.filePath,false);
    const version=runCatchingTreatmentVersion(item.filePath);
    server.send({
      type:"treatment_gif",
      id:item.id,
      name:item.name,
      url,
      version
    });
    setDisplay({mode:"treatment_gif",imageVisible:false});
    disableViewerKeys();
    return true;
  });
    ipcMain.handle("appointment:show-qr",async(_e,data)=>{
    return await showAppointmentQr(data||{});
  });

  ipcMain.handle("display:game",()=>{
    server.send({type:"game"},false);
    setDisplay({mode:"game",imageVisible:false});
    disableViewerKeys();
    return true;
  });

  ipcMain.handle("display:theme",(_e,theme)=>{
    const normalized=["light","dark","auto"].includes(String(theme))?String(theme):"dark";
    settings.patch({displayTheme:normalized});
    state.settings=settings.all();
    server.send({type:"theme",theme:normalized},false);
    emit();
    notice(`تم تطبيق مظهر الشاشة: ${normalized}`,"success");
    return state;
  });
  ipcMain.handle("settings:save",(_e,p)=>{
    const patch={...(p||{})};
    if(patch.shortcuts)patch.shortcuts={...DEFAULT_SHORTCUTS,...patch.shortcuts};
    settings.patch(patch);state.settings=settings.all();
    if(discovery)discovery.clinicName=settings.get("clinicName");
    server.send({type:"display_config",...displayConfig()},false);
    server.send({type:"theme",theme:settings.get("displayTheme")||"dark"},false);
    registerGlobalKeys();
    app.setLoginItemSettings({openAtLogin:Boolean(settings.get("launchAtLogin"))});emit();return state;
  });
}


const gotSingleInstanceLock=app.requestSingleInstanceLock();
if(!gotSingleInstanceLock){
  app.quit();
}else{
  app.on("second-instance",(_event,argv)=>{
    const url=findProtocolUrl(argv);
    let silent=false;
    if(url){try{const parsed=new URL(String(url));silent=JSON.parse(decodeBase64UrlUtf8(parsed.searchParams.get("data")||"")||"{}").action==="select_patient"}catch{}}
    if(url){
      if(server)handleProtocolUrl(url);
      else pendingProtocolUrl=url;
    }
    if(win&&!silent){win.show();win.focus();}
  });
}

if(process.defaultApp){
  if(process.argv.length>=2){
    app.setAsDefaultProtocolClient("dentalchair",process.execPath,[path.resolve(process.argv[1])]);
  }
}else{
  app.setAsDefaultProtocolClient("dentalchair");
}

app.on("open-url",(event,url)=>{
  event.preventDefault();
  if(server)handleProtocolUrl(url);
  else pendingProtocolUrl=url;
});

pendingProtocolUrl=findProtocolUrl(process.argv);

app.whenReady().then(async()=>{
  settings=new SettingsStore(app);state.settings=settings.all();
  archive=new PatientArchive({app,settings,onState:selected=>{state.patient=selected;emit();},onNotice:notice});
  state.patient=archive.snapshot();
  images=new ImageLibrary({onState:s=>{state.images=s;if(s.currentPath)server?.prewarmMedia(s.currentPath,true);emit();},onNotice:notice});
  server=new ChairServer({
    port:settings.get("wsPort"),maxWidth:settings.get("mediaMaxWidth"),maxHeight:settings.get("mediaMaxHeight"),
    onState:s=>{state.network=s;emit();},onNotice:notice,
    getHelloPayload:displayConfig,onCommand:handleCommand
  });
  discovery=new DiscoveryBroadcaster({
    port:settings.get("discoveryPort"),wsPort:settings.get("wsPort"),clinicName:settings.get("clinicName"),onNotice:notice
  });
  ipc();createWindow();createTray();
  try{
    await server.start();
  }catch(error){
    if(error?.code==="EADDRINUSE"){
      dialog.showMessageBoxSync({
        type:"info",
        title:"Dental Chain Chair Controller",
        message:"وحدة التحكم تعمل بالفعل",
        detail:"تم العثور على نسخة أخرى تعمل في الخلفية. افتحها من الأيقونة بجانب الساعة."
      });
      quitting=true;
      app.quit();
      return;
    }
    throw error;
  }
  discovery.start();
  images.watch(settings.get("sensorFolder")).catch(error=>notice(`تعذر فهرسة الصور: ${error.message}`,"warning"));
  registerGlobalKeys();
  server.send({type:"theme",theme:settings.get("displayTheme")||"dark"},false);
  if(pendingProtocolUrl){handleProtocolUrl(pendingProtocolUrl);pendingProtocolUrl=null;}
  app.setLoginItemSettings({openAtLogin:Boolean(settings.get("launchAtLogin"))});
  emit();
});
app.on("before-quit",()=>{quitting=true;discovery?.stop();globalShortcut.unregisterAll();});
app.on("window-all-closed",()=>{});
