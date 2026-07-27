const $=id=>document.getElementById(id);
let timer,currentState={},shortcutDraft={},shortcutDirty=new Set();

const labels={home:"الواجهة الرئيسية",patient:"ترحيب مريض",image:"صورة",gif:"GIF",treatment_gif:"معالجة",treatment_plan:"خطة علاج",appointment_qr:"QR موعد",video:"فيديو",pdf:"PDF",black:"أسود",game:"لعبة"};
const shortcutMeta=[
  ["latest","أحدث صورة SOPRO","CommandOrControl+`"],
  ["home","شاشة الترحيب","CommandOrControl+H"],
  ["black","شاشة سوداء","CommandOrControl+B"],
  ["tempImage","بانوراما / صورة مؤقتة","CommandOrControl+I"],
  ["treatments","فتح شبكة المعالجات","CommandOrControl+G"],
  ["video","اختيار فيديو","CommandOrControl+V"],
  ["pdf","اختيار PDF","CommandOrControl+P"],
  ["game","لعبة الأطفال","CommandOrControl+L"],
  ["hide","إغلاق العرض","CommandOrControl+Escape"],
  ["previous","الصورة السابقة","CommandOrControl+PageUp"],
  ["next","الصورة التالية","CommandOrControl+PageDown"],
  ["zoomIn","تكبير الصورة","CommandOrControl+="],
  ["zoomOut","تصغير الصورة","CommandOrControl+-"],
  ["moveLeft","تحريك الصورة لليسار","CommandOrControl+Left"],
  ["moveRight","تحريك الصورة لليمين","CommandOrControl+Right"],
  ["moveUp","تحريك الصورة للأعلى","CommandOrControl+Up"],
  ["moveDown","تحريك الصورة للأسفل","CommandOrControl+Down"],
  ["resetView","إعادة ضبط الصورة","CommandOrControl+0"],
  ["rotate","تدوير الصورة 20°","CommandOrControl+Shift+8"]
];
const defaultShortcuts=Object.fromEntries(shortcutMeta.map(([key,,value])=>[key,value]));

function syncValue(id,value){const el=$(id);if(el&&document.activeElement!==el)el.value=value??""}
function prettyShortcut(value){
  if(!value)return "معطّل";
  const map={CommandOrControl:"Ctrl",Left:"←",Right:"→",Up:"↑",Down:"↓",PageUp:"Page Up",PageDown:"Page Down",Escape:"Esc"};
  return String(value).split("+").map(part=>map[part]||part).join(" + ");
}
function applyControllerTheme(theme){
  const value=theme==="light"?"light":"dark";
  document.body.dataset.controllerTheme=value;
  $("controllerTheme").value=value;
  document.querySelectorAll("[data-controller-theme]").forEach(button=>button.classList.toggle("active",button.dataset.controllerTheme===value));
  const toggle=$("controllerThemeToggle");
  toggle.querySelector(".theme-icon").textContent=value==="dark"?"☀":"🌙";
  toggle.querySelector(".theme-label").textContent=value==="dark"?"فاتح":"داكن";
  toggle.title=value==="dark"?"الانتقال إلى الوضع الفاتح":"الانتقال إلى الوضع الداكن";
}
function applyDisplayThemeChoice(theme){
  const value=["dark","light","auto"].includes(theme)?theme:"dark";
  $("displayTheme").value=value;
  document.querySelectorAll("[data-display-theme]").forEach(button=>button.classList.toggle("active",button.dataset.displayTheme===value));
}

function render(s){
  currentState=s||{};$("clients").textContent=s.network?.clients||0;$("count").textContent=s.images?.count||0;
  $("position").textContent=s.images?.count?`${s.images.position}/${s.images.count}`:"—";
  $("mode").textContent=labels[s.display?.mode]||s.display?.mode||"ترحيب";
  $("url").textContent=s.network?.wsUrl||"—";$("folder").textContent=s.settings?.sensorFolder||"—";
  if($("archiveRoot"))$("archiveRoot").textContent=s.patient?.archiveRoot||s.settings?.patientArchiveRoot||"مجلد المستندات الافتراضي";
  if(s.patient?.selected){syncValue("patient",s.patient.displayName||s.patient.fullName||"");if(s.patient.doctorName)syncValue("doctor",s.patient.doctorName);}
  $("current").textContent=s.images?.currentName||"—";syncValue("doctor",s.settings?.doctorName||"");
  ["chainName","displayTitle","clinicName","clinicDisplayName","homeEyebrow","specialty","welcomeText","comfortText","qrEventTitle","qrEventDescription","qrReminderMessage","qrReminderHours"].forEach(id=>syncValue(id,s.settings?.[id]??""));
  syncValue("qrClinic",s.settings?.clinicName||"عيادة د. طاهر");
  $("launch").checked=!!s.settings?.launchAtLogin;$("minimized").checked=!!s.settings?.startMinimized;
  applyControllerTheme(s.settings?.controllerTheme||"dark");
  applyDisplayThemeChoice(s.settings?.displayTheme||"dark");
  syncShortcuts(s.settings?.shortcuts||defaultShortcuts);
  const connected=(s.network?.clients||0)>0;$("badge").classList.toggle("connected",connected);
  const connectionText=connected?`الشاشة متصلة (${s.network.clients})`:"بانتظار الشاشة";
  $("badge").querySelector("span").textContent=connectionText;$("drawerConnection").textContent=connectionText;
  renderTreatments(s.settings?.treatments||[]);
}
function note(n){const b=$("notice");b.textContent=n.message;b.className=`notice show ${n.type||""}`;clearTimeout(timer);timer=setTimeout(()=>b.className="notice",3600)}

function renderTreatments(items){
  const grid=$("treatmentsGrid"),empty=$("emptyTreatments");grid.innerHTML="";
  empty.style.display=items.length?"none":"block";
  items.forEach(item=>{
    const card=document.createElement("article");card.className="treatment-card";
    card.innerHTML=`<h3>${escapeHtml(item.name)}</h3><div class="actions"><button class="primary play-treatment">عرض</button><button class="edit-treatment">✎</button><button class="delete-treatment">🗑</button></div>`;
    card.querySelector(".play-treatment").onclick=()=>chairAPI.playTreatment(item.id);
    card.querySelector(".edit-treatment").onclick=()=>openTreatmentEditor(item);
    card.querySelector(".delete-treatment").onclick=async()=>{if(confirm(`حذف معالجة "${item.name}"؟`))await chairAPI.deleteTreatment(item.id)};
    grid.appendChild(card);
  });
}
function escapeHtml(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]))}

function renderShortcutEditor(){
  const editor=$("shortcutEditor");
  if(editor.children.length)return;
  shortcutMeta.forEach(([key,label])=>{
    const row=document.createElement("label");row.className="shortcut-row";
    row.innerHTML=`<span>${escapeHtml(label)}</span><input class="shortcut-input ltr" data-shortcut-key="${key}" readonly aria-label="${escapeHtml(label)}">`;
    const input=row.querySelector("input");
    input.addEventListener("focus",()=>{input.classList.add("recording");input.value="اضغط الاختصار الآن…"});
    input.addEventListener("blur",()=>{input.classList.remove("recording");input.value=prettyShortcut(shortcutDraft[key])});
    input.addEventListener("keydown",event=>recordShortcut(event,key,input));
    editor.appendChild(row);
  });
}
function syncShortcuts(values){
  renderShortcutEditor();
  shortcutMeta.forEach(([key])=>{
    if(!shortcutDirty.has(key))shortcutDraft[key]=values[key]??defaultShortcuts[key]??"";
    const input=document.querySelector(`[data-shortcut-key="${key}"]`);
    if(input&&document.activeElement!==input)input.value=prettyShortcut(shortcutDraft[key]);
  });
  renderShortcutHelp(shortcutDraft);
}
function acceleratorFromEvent(event){
  const modifiers=[];
  if(event.ctrlKey||event.metaKey)modifiers.push("CommandOrControl");
  if(event.altKey)modifiers.push("Alt");
  if(event.shiftKey)modifiers.push("Shift");
  const byCode={Backquote:"`",Minus:"-",Equal:"=",BracketLeft:"[",BracketRight:"]",Semicolon:";",Quote:"'",Comma:",",Period:".",Slash:"/",Backslash:"\\",Space:"Space",ArrowLeft:"Left",ArrowRight:"Right",ArrowUp:"Up",ArrowDown:"Down",PageUp:"PageUp",PageDown:"PageDown",Home:"Home",End:"End",Insert:"Insert",Escape:"Escape",Enter:"Enter",Tab:"Tab"};
  let key=byCode[event.code];
  if(!key&&/^Key[A-Z]$/.test(event.code))key=event.code.slice(3);
  if(!key&&/^Digit[0-9]$/.test(event.code))key=event.code.slice(5);
  if(!key&&/^F([1-9]|1[0-9]|2[0-4])$/.test(event.code))key=event.code;
  if(!key||["ControlLeft","ControlRight","MetaLeft","MetaRight","AltLeft","AltRight","ShiftLeft","ShiftRight"].includes(event.code))return "";
  return [...modifiers,key].join("+");
}
function recordShortcut(event,key,input){
  event.preventDefault();event.stopPropagation();
  if(event.code==="Delete"||event.code==="Backspace"){
    shortcutDraft[key]="";shortcutDirty.add(key);input.blur();return;
  }
  const value=acceleratorFromEvent(event);
  if(!value)return;
  shortcutDraft[key]=value;shortcutDirty.add(key);input.value=prettyShortcut(value);input.blur();
}
function renderShortcutHelp(values){
  const help=$("shortcutHelp");help.innerHTML="";
  shortcutMeta.forEach(([key,label])=>{
    const kbd=document.createElement("kbd");kbd.textContent=prettyShortcut(values[key]);
    const span=document.createElement("span");span.textContent=label;
    help.append(kbd,span);
  });
}
function duplicateShortcut(values){
  const seen=new Map();
  for(const [key,label] of shortcutMeta){
    const value=String(values[key]||"").trim().toLowerCase();
    if(!value)continue;
    if(seen.has(value))return `${label} و${seen.get(value)}`;
    seen.set(value,label);
  }
  return "";
}

function openDrawer(){ $("settingsDrawer").classList.add("open");$("drawerBackdrop").classList.add("open") }
function closeDrawer(){ $("settingsDrawer").classList.remove("open");$("drawerBackdrop").classList.remove("open") }
function openHelp(){renderShortcutHelp(shortcutDraft);$("helpModal").classList.add("open") }
function closeHelp(){ $("helpModal").classList.remove("open") }

function openTreatmentEditor(item=null){
  $("treatmentId").value=item?.id||"";$("treatmentName").value=item?.name||"";$("treatmentFile").value=item?.filePath||"";
  $("treatmentEditorTitle").textContent=item?"تعديل معالجة":"إضافة معالجة";$("treatmentModal").classList.add("open");
}
function closeTreatmentEditor(){ $("treatmentModal").classList.remove("open") }

$("openSettings").onclick=openDrawer;$("drawerBackdrop").onclick=closeDrawer;document.querySelector(".close-drawer").onclick=closeDrawer;
$("openHelp").onclick=openHelp;document.querySelector(".close-help").onclick=closeHelp;
$("addTreatment").onclick=()=>openTreatmentEditor();document.querySelector(".close-treatment").onclick=closeTreatmentEditor;

$("chooseTreatmentFile").onclick=async()=>{const path=await chairAPI.chooseTreatmentGif();if(path)$("treatmentFile").value=path};
$("saveTreatment").onclick=async()=>{
  try{
    await chairAPI.saveTreatment({id:$("treatmentId").value||Date.now(),name:$("treatmentName").value,filePath:$("treatmentFile").value});
    closeTreatmentEditor();note({message:"تم حفظ المعالجة",type:"success"});
  }catch(e){note({message:e.message||"تعذر حفظ المعالجة",type:"error"})}
};

$("folderBtn").onclick=()=>chairAPI.chooseSensorFolder();$("reindex").onclick=()=>chairAPI.reindex();
$("archiveRootBtn").onclick=()=>chairAPI.chooseArchiveRoot();
$("latest").onclick=()=>chairAPI.showLatest();$("prev").onclick=()=>chairAPI.showPrevious();$("next").onclick=()=>chairAPI.showNext();$("hide").onclick=()=>chairAPI.hide();
$("temp").onclick=()=>chairAPI.chooseTemporaryImage();$("gif").onclick=()=>chairAPI.chooseGif();$("video").onclick=()=>chairAPI.chooseVideo();$("pdf").onclick=()=>chairAPI.choosePdf();
$("zoomIn").onclick=()=>chairAPI.transform({zoom:.15});$("zoomOut").onclick=()=>chairAPI.transform({zoom:-.15});
$("left").onclick=()=>chairAPI.transform({dx:-70,dy:0});$("right").onclick=()=>chairAPI.transform({dx:70,dy:0});$("up").onclick=()=>chairAPI.transform({dx:0,dy:-70});$("down").onclick=()=>chairAPI.transform({dx:0,dy:70});$("reset").onclick=()=>chairAPI.resetView();
$("black").onclick=()=>chairAPI.showBlack();$("home").onclick=()=>chairAPI.showHome();$("end").onclick=()=>chairAPI.endSession();$("game").onclick=()=>chairAPI.startGame();
$("rotateImage").onclick=()=>chairAPI.rotateImage();
$("showPatient").onclick=()=>chairAPI.showPatient({
  displayName:$("patient").value,
  doctorName:$("doctor").value,
  gender:document.querySelector('input[name="patientGender"]:checked')?.value||"male"
});

document.querySelectorAll("[data-controller-theme]").forEach(button=>button.onclick=async()=>{
  const theme=button.dataset.controllerTheme;applyControllerTheme(theme);
  try{await chairAPI.saveSettings({controllerTheme:theme})}catch(error){note({message:error.message||"تعذر تغيير مظهر الكونترولر",type:"error"})}
});
document.querySelectorAll("[data-display-theme]").forEach(button=>button.onclick=async()=>{
  const theme=button.dataset.displayTheme;applyDisplayThemeChoice(theme);
  try{await chairAPI.setDisplayTheme(theme)}catch(error){note({message:error.message||"تعذر تغيير مظهر الشاشة",type:"error"})}
});
$("controllerThemeToggle").onclick=async()=>{
  const next=$("controllerTheme").value==="dark"?"light":"dark";applyControllerTheme(next);
  try{await chairAPI.saveSettings({controllerTheme:next})}catch(error){note({message:error.message||"تعذر تغيير مظهر الكونترولر",type:"error"})}
};
$("resetShortcuts").onclick=()=>{
  shortcutDraft={...defaultShortcuts};shortcutDirty=new Set(Object.keys(defaultShortcuts));syncShortcuts(shortcutDraft);
  document.querySelectorAll(".shortcut-input").forEach(input=>input.value=prettyShortcut(shortcutDraft[input.dataset.shortcutKey]));
  note({message:"تمت استعادة الاختصارات الافتراضية — اضغط حفظ لتثبيتها",type:"info"});
};

$("save").onclick=async()=>{
  const duplicate=duplicateShortcut(shortcutDraft);
  if(duplicate){note({message:`يوجد اختصار مكرر بين ${duplicate}`,type:"error"});return;}
  try{
    await chairAPI.saveSettings({
      doctorName:$("doctor").value,
      displayTheme:$("displayTheme").value,
      controllerTheme:$("controllerTheme").value,
      shortcuts:{...shortcutDraft},
      launchAtLogin:$("launch").checked,
      startMinimized:$("minimized").checked,
      chainName:$("chainName").value,
      displayTitle:$("displayTitle").value,
      clinicName:$("clinicName").value,
      clinicDisplayName:$("clinicDisplayName").value,
      homeEyebrow:$("homeEyebrow").value,
      specialty:$("specialty").value,
      welcomeText:$("welcomeText").value,
      comfortText:$("comfortText").value,
      qrEventTitle:$("qrEventTitle").value,
      qrEventDescription:$("qrEventDescription").value,
      qrReminderMessage:$("qrReminderMessage").value,
      qrReminderHours:Math.max(1,Math.min(168,Number($("qrReminderHours").value)||24))
    });
    shortcutDirty.clear();note({message:"تم حفظ الإعدادات والاختصارات وتحديث الشاشة",type:"success"});
  }catch(error){note({message:error.message||"تعذر حفظ الإعدادات",type:"error"})}
};

chairAPI.onState(render);chairAPI.onNotice(note);chairAPI.onOpenTreatments?.(()=>$("treatmentsSection").scrollIntoView({behavior:"smooth",block:"start"}));chairAPI.getState().then(render);

$("showQr").onclick=async()=>{
  try{
    await chairAPI.showAppointmentQr({
      patientName:$("qrPatient").value,
      date:$("qrDate").value,
      time:$("qrTime").value,
      type:$("qrType").value,
      clinicName:$("qrClinic").value
    });
    note({message:"تم عرض QR الموعد",type:"success"});
  }catch(error){note({message:error.message||"تعذر إنشاء QR",type:"error"})}
};
