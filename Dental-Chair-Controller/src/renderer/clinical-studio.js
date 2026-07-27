(function(){
"use strict";

const $=id=>document.getElementById(id);
const TEETH=[18,17,16,15,14,13,12,11,21,22,23,24,25,26,27,28,48,47,46,45,44,43,42,41,31,32,33,34,35,36,37,38];
const DEFAULT_LABELS=[
  {id:"label-urgent",name:"عاجل",color:"#e64646",stageId:""},
  {id:"label-important",name:"مهم",color:"#f39a35",stageId:""},
  {id:"label-watch",name:"للمراقبة",color:"#2f8fe9",stageId:""},
  {id:"label-cold",name:"بارد",color:"#39a96b",stageId:""}
];

let appState={},source=null,sourceImage=null,baseCanvas=document.createElement("canvas");
let labels=[],annotations=[],stages=[],illustrations=[];
let activeLabelId="",activeTooth="",selectedAnnotationId="",drawTool="draw",drawingPoints=null;
let undoStack=[],redoStack=[],planId="",planPanoramaPath="",activeStage=0,presentationFocusStageId="";
let visualRevision=0,visualCache=new Map(),activePatientKey="";

const canvas=$("annotationCanvas"),ctx=canvas.getContext("2d",{willReadFrequently:true});

function clone(value){return JSON.parse(JSON.stringify(value));}
function uid(prefix){return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2,8)}`;}
function escapeHtml(value){return String(value??"").replace(/[&<>"']/g,char=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[char]));}
function notify(error){alert(error?.message||String(error||"حدث خطأ"));}
function patientReady(){return Boolean(appState.patient?.selected);}
function patientKey(state=appState){const p=state?.patient||{};return p.selected?String(p.patientId||p.fileNo||p.fullName||""):"";}
function requirePatient(){if(patientReady())return true;notify("افتح ملف المريض من Dental Chain OS أولاً");return false;}
function currentLabel(){return labels.find(item=>item.id===activeLabelId)||labels[0];}
function currentStage(){return stages[activeStage]||null;}
function illustrationById(id){return illustrations.find(item=>item.id===id)||null;}
function markVisualDirty(){visualRevision++;visualCache.clear();}
function orderedTeeth(values){const set=new Set((values||[]).map(Number));return TEETH.filter(tooth=>set.has(tooth));}

function showStep(step){
  const normalized=step==="analysis"?"draw":step;
  document.querySelectorAll("[data-studio-step]").forEach(button=>button.classList.toggle("active",button.dataset.studioStep===normalized));
  document.querySelectorAll("[data-studio-pane]").forEach(pane=>pane.classList.toggle("active",pane.dataset.studioPane===normalized));
  if(normalized==="draw")requestAnimationFrame(()=>renderDrawing());
  if(normalized==="plan"){organizeStagesFromDrawings(false);renderPlan();}
  if(normalized==="present")renderPresentation();
}
async function openStudio(step="source"){
  if(!requirePatient())return;
  $("clinicalStudio").classList.add("open");$("clinicalStudio").setAttribute("aria-hidden","false");
  updatePatientLabel();
  await Promise.all([refreshPanoramas(),refreshPlans(),refreshIllustrations()]);
  const normalized=step==="analysis"?"draw":step;
  if(normalized==="draw"&&!source){try{await useCurrentSource(false);}catch{showStep("source");return;}}
  showStep(normalized);
}
function closeStudio(){$("clinicalStudio").classList.remove("open");$("clinicalStudio").setAttribute("aria-hidden","true");drawingPoints=null;}
function updatePatientLabel(){
  const patient=appState.patient||{};
  $("studioPatientLabel").textContent=patient.selected?`${patient.fullName} · ملف ${patient.fileNo||"—"} · حفظ محلي`:"افتح ملف المريض من Dental Chain OS";
  $("presentationPatient").textContent=patient.fullName||"ضيفنا الكريم";
}

async function refreshPanoramas(){
  const select=$("patientPanoramaList");select.innerHTML='<option value="">— اختر صورة —</option>';
  if(!patientReady())return;
  try{(await chairAPI.listPanoramas()).forEach(item=>select.add(new Option(item.name,item.path)));if(planPanoramaPath)select.value=planPanoramaPath;}catch{}
}
async function refreshPlans(){
  const select=$("savedPlans");select.innerHTML='<option value="">— خطة سابقة —</option>';
  if(!patientReady())return [];
  try{const plans=await chairAPI.listPlans();plans.forEach(plan=>select.add(new Option(`${plan.title} · ${plan.stagesCount} مراحل · ${plan.totalCost||0} ${plan.currency||""}`,plan.id)));return plans;}catch{return [];}
}
async function refreshIllustrations(){
  try{illustrations=await chairAPI.listStageIllustrations();}catch{illustrations=[];}
  renderIllustrations();
}
function imageFromUrl(url){return new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>reject(new Error("تعذر قراءة الصورة"));image.src=url;});}
async function setSource(next,{preservePlan=false}={}){
  const dataUrl=next.dataUrl||next.sourceDataUrl;
  if(!dataUrl)throw new Error("تعذر العثور على نسخة الصورة الأصلية");
  const image=await imageFromUrl(dataUrl);
  source={path:next.path||next.sourceArchivePath||next.sourcePath||next.panoramaPath||"",name:next.name||next.sourceName||"صورة المريض",dataUrl};sourceImage=image;
  const max=1500,scale=Math.min(1,max/image.naturalWidth,max/image.naturalHeight);
  canvas.width=baseCanvas.width=Math.max(1,Math.round(image.naturalWidth*scale));canvas.height=baseCanvas.height=Math.max(1,Math.round(image.naturalHeight*scale));
  baseCanvas.getContext("2d",{willReadFrequently:true}).drawImage(image,0,0,canvas.width,canvas.height);
  canvas.style.display="block";$("annotationCanvasEmpty").style.display="none";
  $("studioSourcePreview").src=dataUrl;$("studioSourcePreview").style.display="block";$("studioSourceEmpty").style.display="none";$("studioSourceName").textContent=source.name;
  planPanoramaPath=source.path||planPanoramaPath;
  if(!preservePlan){annotations=[];selectedAnnotationId="";undoStack=[];redoStack=[];presentationFocusStageId="";}
  measureQuality();markVisualDirty();renderDrawing();renderPlan();renderPresentation();
}
async function useCurrentSource(withNotice=true){
  if(!requirePatient())return;
  try{await setSource(await chairAPI.getStudioSource());}catch(error){if(withNotice)notify(error);throw error;}
}
function measureQuality(){
  const data=baseCanvas.getContext("2d",{willReadFrequently:true}).getImageData(0,0,baseCanvas.width,baseCanvas.height).data,step=Math.max(1,Math.floor(data.length/(4*70000)));let sum=0,sum2=0,count=0;
  for(let index=0;index<data.length;index+=4*step){const value=.299*data[index]+.587*data[index+1]+.114*data[index+2];sum+=value;sum2+=value*value;count++;}
  const mean=sum/Math.max(1,count),std=Math.sqrt(Math.max(0,sum2/Math.max(1,count)-mean*mean));
  $("qualityDimensions").textContent=`${sourceImage.naturalWidth} × ${sourceImage.naturalHeight}`;$("qualityContrast").textContent=std<28?"منخفض":std>72?"مرتفع":"مناسب";$("sourceQualityBadge").textContent="تم اعتماد الصورة";
}

function labelForAnnotation(annotation){return labels.find(item=>item.id===annotation.labelId)||labels[0]||{name:"ملاحظة",color:"#a66bff"};}
function annotationStage(annotation){const label=labelForAnnotation(annotation);return stages.find(stage=>stage.id===label.stageId)||null;}
function denormalize(point,target){return{x:point.x*target.width,y:point.y*target.height};}
function drawShape(context,target,annotation,{selected=false}={}){
  const label=labelForAnnotation(annotation),points=annotation.points||[];if(points.length<3)return;
  context.save();context.beginPath();const first=denormalize(points[0],target);context.moveTo(first.x,first.y);points.slice(1).forEach(point=>{const p=denormalize(point,target);context.lineTo(p.x,p.y);});context.closePath();
  context.globalAlpha=Math.max(.08,Math.min(.9,Number(annotation.opacity)||.35));context.fillStyle=label.color;context.fill();context.globalAlpha=1;context.strokeStyle=label.color;context.lineWidth=selected?Math.max(4,target.width/280):Math.max(2,target.width/520);context.setLineDash(selected?[12,6]:[]);context.stroke();context.setLineDash([]);
  if(annotation.tooth){const center=points.reduce((total,point)=>({x:total.x+point.x/points.length,y:total.y+point.y/points.length}),{x:0,y:0});context.font=`700 ${Math.max(12,target.width/70)}px Segoe UI`;context.fillStyle="#fff";context.strokeStyle="#07131c";context.lineWidth=4;context.strokeText(String(annotation.tooth),center.x*target.width,center.y*target.height);context.fillText(String(annotation.tooth),center.x*target.width,center.y*target.height);}
  context.restore();
}
function drawScene(target,stageId="",includeSelection=false,currentPoints=null){
  const context=target.getContext("2d");context.clearRect(0,0,target.width,target.height);if(!sourceImage)return;context.drawImage(baseCanvas,0,0,target.width,target.height);
  annotations.filter(annotation=>!stageId||annotationStage(annotation)?.id===stageId).forEach(annotation=>drawShape(context,target,annotation,{selected:includeSelection&&annotation.id===selectedAnnotationId}));
  if(currentPoints?.length){const label=currentLabel();context.save();context.beginPath();const first=denormalize(currentPoints[0],target);context.moveTo(first.x,first.y);currentPoints.slice(1).forEach(point=>{const p=denormalize(point,target);context.lineTo(p.x,p.y);});context.strokeStyle=label?.color||"#ef496f";context.lineWidth=Math.max(2,target.width/420);context.setLineDash([8,5]);context.stroke();context.restore();}
}
function renderDrawing(){
  if(!sourceImage)return;
  drawScene(canvas,"",true,drawingPoints);renderDrawingStatus();renderToothCharts();renderAnnotationList();renderSelectedAnnotation();
}
function annotatedDataUrl(stageId=""){
  const key=stageId||"all",cached=visualCache.get(key);if(cached?.revision===visualRevision)return cached.url;
  const output=document.createElement("canvas");output.width=baseCanvas.width;output.height=baseCanvas.height;drawScene(output,stageId,false);const url=output.toDataURL("image/png");visualCache.set(key,{revision:visualRevision,url});return url;
}
function canvasPoint(event){const rect=canvas.getBoundingClientRect();return{x:Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)),y:Math.max(0,Math.min(1,(event.clientY-rect.top)/rect.height))};}
function pointInPolygon(point,polygon){let inside=false;for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){const a=polygon[i],b=polygon[j],cross=(a.y>point.y)!==(b.y>point.y)&&point.x<(b.x-a.x)*(point.y-a.y)/((b.y-a.y)||1e-9)+a.x;if(cross)inside=!inside;}return inside;}
function hitAnnotation(point){return [...annotations].reverse().find(annotation=>pointInPolygon(point,annotation.points||[]))||null;}
function circlePoints(center,radius=.025){return Array.from({length:18},(_,index)=>{const angle=index/18*Math.PI*2;return{x:Math.max(0,Math.min(1,center.x+Math.cos(angle)*radius)),y:Math.max(0,Math.min(1,center.y+Math.sin(angle)*radius))};});}
function pushHistory(){undoStack.push(clone({labels,annotations,stages,activeLabelId,activeTooth}));if(undoStack.length>40)undoStack.shift();redoStack=[];updateHistoryButtons();}
function restoreSnapshot(snapshot){if(!snapshot)return;labels=snapshot.labels;annotations=snapshot.annotations;stages=snapshot.stages;activeLabelId=snapshot.activeLabelId||labels[0]?.id||"";activeTooth=snapshot.activeTooth||"";activeStage=Math.min(activeStage,Math.max(0,stages.length-1));selectedAnnotationId="";markVisualDirty();renderAllStudio();}
function updateHistoryButtons(){$("undoDrawing").disabled=!undoStack.length;$("redoDrawing").disabled=!redoStack.length;}
function addAnnotation(points){
  const label=currentLabel();if(!label)return;
  pushHistory();annotations.push({id:uid("mark"),labelId:label.id,tooth:activeTooth?Number(activeTooth):"",opacity:Number($("annotationOpacity").value)/100,points,createdAt:new Date().toISOString()});selectedAnnotationId=annotations.at(-1).id;markVisualDirty();renderAllStudio();
}
function deleteAnnotation(id){const index=annotations.findIndex(item=>item.id===id);if(index<0)return;pushHistory();annotations.splice(index,1);if(selectedAnnotationId===id)selectedAnnotationId="";markVisualDirty();renderAllStudio();}
function setDrawTool(tool){drawTool=tool;document.querySelectorAll("[data-draw-tool]").forEach(button=>button.classList.toggle("active",button.dataset.drawTool===tool));$("annotationCanvasWrap").classList.toggle("selecting",tool==="select");$("annotationCanvasWrap").classList.toggle("erasing",tool==="erase");renderDrawingStatus();}
function renderDrawingStatus(){const names={draw:"تحديد",select:"اختيار",erase:"مسح"},label=currentLabel();$("activeToolLabel").textContent=names[drawTool]||drawTool;$("activeLabelText").textContent=label?.name||"—";$("activeLabelText").style.color=label?.color||"";$("activeToothText").textContent=activeTooth||"غير محدد";}

function renderPalette(){
  const host=$("drawingPalette");host.innerHTML="";labels.forEach(label=>{const count=annotations.filter(item=>item.labelId===label.id).length,button=document.createElement("button");button.type="button";button.className=`palette-item ${label.id===activeLabelId?"active":""}`;button.innerHTML=`<i style="background:${escapeHtml(label.color)}"></i><b>${escapeHtml(label.name)}</b><small>${count} شكل</small>`;button.onclick=()=>{activeLabelId=label.id;$("labelName").value=label.name;$("labelColor").value=label.color;renderAllStudio();};host.appendChild(button);});
  const label=currentLabel();if(label&&document.activeElement!==$("labelName"))$("labelName").value=label.name;if(label&&document.activeElement!==$("labelColor"))$("labelColor").value=label.color;
}
function renderToothChart(host,selected,onClick,{stageId=""}={}){
  host.innerHTML="";const selectedSet=selected instanceof Set?selected:new Set(selected?[Number(selected)]:[]);
  TEETH.forEach(tooth=>{const colors=[...new Set(annotations.filter(item=>Number(item.tooth)===tooth&&(!stageId||annotationStage(item)?.id===stageId)).map(item=>labelForAnnotation(item).color))],button=document.createElement("button");button.type="button";button.textContent=tooth;button.dataset.tooth=tooth;button.classList.add(Number(tooth)>40||Number(tooth)<40&&Number(tooth)>30?"lower-tooth":"upper-tooth");button.classList.toggle("selected",selectedSet.has(tooth));button.classList.toggle("linked",Boolean(colors.length));if(colors.length)button.style.setProperty("--tooth-color",colors[0]);button.onclick=()=>onClick(tooth);host.appendChild(button);});
}
function renderToothCharts(){
  renderToothChart($("annotationToothChart"),activeTooth,tooth=>{const selected=annotations.find(item=>item.id===selectedAnnotationId);if(selected){pushHistory();selected.tooth=tooth;markVisualDirty();}activeTooth=tooth;renderAllStudio();});
  const stage=currentStage();if(stage)renderToothChart($("planToothChart"),new Set(stageTeeth(stage)),tooth=>toggleStageTooth(stage,tooth),{stageId:stage.id});else $("planToothChart").innerHTML='<p class="studio-help">أضف مرحلة أولاً.</p>';
}
function toothOptions(value){return ['<option value="">دون تحديد سن</option>',...TEETH.map(tooth=>`<option value="${tooth}" ${String(tooth)===String(value)?"selected":""}>${tooth}</option>`)].join("");}
function renderSelectedAnnotation(){
  const card=document.querySelector(".selected-annotation-card"),annotation=annotations.find(item=>item.id===selectedAnnotationId);card.classList.toggle("disabled",!annotation);
  $("selectedAnnotationLabel").innerHTML=labels.map(label=>`<option value="${escapeHtml(label.id)}" ${annotation?.labelId===label.id?"selected":""}>${escapeHtml(label.name)}</option>`).join("");$("selectedAnnotationTooth").innerHTML=toothOptions(annotation?.tooth);
  if(!annotation){$("selectedAnnotationSummary").textContent="اختر شكلاً من الصورة أو القائمة.";return;}
  const label=labelForAnnotation(annotation),stage=annotationStage(annotation);$("selectedAnnotationSummary").textContent=`${label.name} · ${annotation.tooth?`السن ${annotation.tooth}`:"دون سن"} · ${stage?.title||"لم يرتبط بمرحلة بعد"}`;
}
function renderAnnotationList(){
  const host=$("annotationList");host.innerHTML="";$("annotationCount").textContent=annotations.length;
  annotations.forEach((annotation,index)=>{const label=labelForAnnotation(annotation),stage=annotationStage(annotation),button=document.createElement("button");button.type="button";button.className=`annotation-row ${annotation.id===selectedAnnotationId?"active":""}`;button.innerHTML=`<i style="background:${escapeHtml(label.color)}"></i><span><b>${index+1}. ${escapeHtml(label.name)}</b><small>${annotation.tooth?`السن ${annotation.tooth}`:"دون سن"} · ${escapeHtml(stage?.title||"غير مرتبطة")}</small></span><em>⌖</em>`;button.onclick=()=>{selectedAnnotationId=annotation.id;activeLabelId=annotation.labelId;activeTooth=annotation.tooth||"";renderAllStudio();};host.appendChild(button);});
  if(!annotations.length)host.innerHTML='<p class="studio-help">لا توجد رسومات بعد.</p>';
}

function newStage(title="مرحلة علاج جديدة",color="#2f8fe9",autoCreated=false){const priority=title==="عاجل"?"عاجل":title==="مهم"?"مهم":title==="بارد"?"بارد":"للمراقبة";return{id:uid("stage"),title,description:"",priority,sessions:1,duration:"",cost:0,color,manualTeeth:[],excludedTeeth:[],illustrationId:"",backgroundPath:"",autoCreated};}
function labelsForStage(stage){return labels.filter(label=>label.stageId===stage.id);}
function annotationsForStage(stage){const ids=new Set(labelsForStage(stage).map(label=>label.id));return annotations.filter(annotation=>ids.has(annotation.labelId));}
function derivedStageTeeth(stage){return orderedTeeth(annotationsForStage(stage).map(item=>item.tooth).filter(Boolean));}
function stageTeeth(stage){const derived=new Set(derivedStageTeeth(stage)),excluded=new Set((stage.excludedTeeth||[]).map(Number)),manual=new Set((stage.manualTeeth||[]).map(Number));return orderedTeeth([...derived].filter(tooth=>!excluded.has(tooth)).concat([...manual]));}
function organizeStagesFromDrawings(withNotice=true){
  const usedLabels=labels.filter(label=>annotations.some(annotation=>annotation.labelId===label.id));let created=0;
  usedLabels.forEach(label=>{if(!stages.some(stage=>stage.id===label.stageId)){const stage=newStage(label.name,label.color,true);stages.push(stage);label.stageId=stage.id;created++;}});
  if(!stages.length&&withNotice)notify("ارسم منطقة واحدة على الأقل أو أضف مرحلة يدوياً");activeStage=Math.min(activeStage,Math.max(0,stages.length-1));if(created)markVisualDirty();renderPlan();
  if(created&&withNotice)notify(`تم إنشاء ${created} مرحلة من مجموعات الألوان. يمكنك تعديل كل التفاصيل الآن.`);
}
function storeStage(){
  const stage=currentStage();if(!stage)return;
  stage.title=$("stageTitle").value.trim()||"مرحلة علاج";
  stage.description=$("stageDescription").value.trim();
  stage.priority=$("stagePriority").value;
  stage.sessions=Math.max(1,Number($("stageSessions").value)||1);
  stage.duration=$("stageDuration").value.trim();
  stage.cost=Math.max(0,Number($("stageCost").value)||0);
  stage.color=$("stageColor").value;
  stage.backgroundPath=$("stageBackgroundPath").value.trim();
}
function setStageFormEnabled(enabled){["stageTitle","stageDescription","stagePriority","stageSessions","stageDuration","stageCost","stageColor","stageBackgroundPath","chooseStageBackground","clearStageBackground"].forEach(id=>$(id).disabled=!enabled);}
function loadStageForm(){
  const stage=currentStage();setStageFormEnabled(Boolean(stage));
  if(!stage){$("stageTitle").value="";$("stageDescription").value="";$("stageBackgroundPath").value="";return;}
  $("stageTitle").value=stage.title||"";
  $("stageDescription").value=stage.description||"";
  $("stagePriority").value=stage.priority||"للمراقبة";
  $("stageSessions").value=stage.sessions||1;
  $("stageDuration").value=stage.duration||"";
  $("stageCost").value=stage.cost||0;
  $("stageColor").value=stage.color||"#19b8f2";
  $("stageBackgroundPath").value=stage.backgroundPath||"";
}
function totals(list=stages){return list.reduce((total,stage)=>({cost:total.cost+(Number(stage.cost)||0),sessions:total.sessions+(Number(stage.sessions)||0)}),{cost:0,sessions:0});}
function renderStageList(){
  const host=$("planStagesList");host.innerHTML="";stages.forEach((stage,index)=>{const teeth=stageTeeth(stage),marks=annotationsForStage(stage).length,button=document.createElement("button");button.type="button";button.className=`plan-stage-tab ${index===activeStage?"active":""}`;button.innerHTML=`<i style="background:${escapeHtml(stage.color)}"></i><span>${index+1}. ${escapeHtml(stage.title)}<small>${stage.sessions||1} جلسة · ${teeth.length} أسنان · ${marks} رسومات</small></span><b>${Number(stage.cost||0).toLocaleString("en")}</b>`;button.onclick=()=>{storeStage();activeStage=index;renderPlan();};host.appendChild(button);});if(!stages.length)host.innerHTML='<p class="studio-help">لا توجد مراحل. نظّمها من الرسومات أو أضف مرحلة.</p>';
  const unassigned=labels.filter(label=>annotations.some(annotation=>annotation.labelId===label.id)&&!stages.some(stage=>stage.id===label.stageId));$("unassignedLabels").textContent=unassigned.length?`ألوان غير مرتبطة: ${unassigned.map(item=>item.name).join("، ")}`:"";
}
function renderStageLabelGroups(){
  const host=$("stageLabelGroups"),stage=currentStage();host.innerHTML="";if(!stage){host.innerHTML='<span class="studio-help">أضف مرحلة أولاً.</span>';return;}
  labels.forEach(label=>{const wrapper=document.createElement("label");wrapper.className="stage-label-choice";wrapper.innerHTML=`<input type="checkbox" ${label.stageId===stage.id?"checked":""}><i style="background:${escapeHtml(label.color)}"></i><span>${escapeHtml(label.name)} (${annotations.filter(item=>item.labelId===label.id).length})</span>`;wrapper.querySelector("input").onchange=event=>{label.stageId=event.target.checked?stage.id:"";markVisualDirty();renderPlan();renderDrawing();renderPresentation();};host.appendChild(wrapper);});
}
function toggleStageTooth(stage,tooth){
  const selected=new Set(stageTeeth(stage)),derived=new Set(derivedStageTeeth(stage));stage.manualTeeth=stage.manualTeeth||[];stage.excludedTeeth=stage.excludedTeeth||[];
  if(selected.has(tooth)){if(derived.has(tooth)&&!stage.excludedTeeth.includes(tooth))stage.excludedTeeth.push(tooth);stage.manualTeeth=stage.manualTeeth.filter(item=>Number(item)!==tooth);}else{stage.excludedTeeth=stage.excludedTeeth.filter(item=>Number(item)!==tooth);if(!derived.has(tooth)&&!stage.manualTeeth.includes(tooth))stage.manualTeeth.push(tooth);}renderPlan();
}
function renderIllustrations(){
  const host=$("stageIllustrationGrid");if(!host)return;host.innerHTML="";const stage=currentStage();if(!stage){host.innerHTML='<span class="studio-help">أضف مرحلة أولاً.</span>';return;}
  const none=document.createElement("button");none.type="button";none.className=`illustration-choice none ${!stage.illustrationId?"active":""}`;none.innerHTML="<span>دون صورة</span>";none.onclick=()=>{stage.illustrationId="";renderIllustrations();renderPresentation();};host.appendChild(none);
  illustrations.forEach(item=>{const button=document.createElement("button");button.type="button";button.className=`illustration-choice ${stage.illustrationId===item.id?"active":""}`;button.innerHTML=`<img src="${item.dataUrl}" alt=""><span>${escapeHtml(item.name)}</span>`;button.onclick=()=>{stage.illustrationId=item.id;renderIllustrations();renderPresentation();};host.appendChild(button);});
}
function renderPlan(){
  renderStageList();loadStageForm();renderStageLabelGroups();renderToothCharts();renderIllustrations();const total=totals(),currency=$("planCurrency").value;$("planTotal").textContent=`${total.cost.toLocaleString("en")} ${currency}`;$("planSessionsTotal").textContent=`${total.sessions} جلسة`;renderPresentation();
}

function stagePayload(stage){const illustration=illustrationById(stage.illustrationId);return{...clone(stage),teeth:stageTeeth(stage),labelIds:labelsForStage(stage).map(label=>label.id),illustrationName:illustration?.name||stage.illustrationName||"",illustrationPath:illustration?.path||stage.illustrationPath||"",illustrationDataUrl:illustration?.dataUrl||stage.illustrationDataUrl||""};}
function planPayload(){
  storeStage();
  const total=totals(),durations=stages.map(stage=>String(stage.duration||"").trim()).filter(Boolean);
  return{id:planId||undefined,title:$("planTitle").value.trim()||"خطة العلاج المقترحة",currency:$("planCurrency").value,panoramaPath:planPanoramaPath,sourcePath:source?.path||"",sourceName:source?.name||"",sourceWidth:sourceImage?.naturalWidth||0,sourceHeight:sourceImage?.naturalHeight||0,panoramaAspectRatio:sourceImage?.naturalWidth&&sourceImage?.naturalHeight?sourceImage.naturalWidth/sourceImage.naturalHeight:0,closingNote:$("planClosing").value.trim(),labels:clone(labels),annotations:clone(annotations),annotatedImageDataUrl:sourceImage?annotatedDataUrl(""):"",stages:stages.map(stagePayload),totalCost:total.cost,totalSessions:total.sessions,totalDuration:durations.join(" + "),doctorName:appState.patient?.doctorName||""};
}
async function loadPlan(){
  const id=$("savedPlans").value;if(!id)return;
  try{
    const plan=await chairAPI.loadPlan(id);planId=plan.id;planPanoramaPath=plan.panoramaPath||plan.sourcePath||"";
    if(plan.sourceDataUrl)await setSource({path:plan.sourcePath||plan.panoramaPath,name:plan.sourceName||"صورة الخطة",dataUrl:plan.sourceDataUrl},{preservePlan:true});
    labels=Array.isArray(plan.labels)&&plan.labels.length?plan.labels.map(item=>({...item})):clone(DEFAULT_LABELS);activeLabelId=labels[0]?.id||"";
    annotations=Array.isArray(plan.annotations)?plan.annotations.map(item=>({...item,points:(item.points||[]).map(point=>({x:Number(point.x),y:Number(point.y)}))})):[];
    stages=(plan.stages||[]).map(stage=>({...stage,manualTeeth:(stage.manualTeeth||stage.teeth||[]).map(Number),excludedTeeth:(stage.excludedTeeth||[]).map(Number)}));activeStage=0;presentationFocusStageId="";
    $("planTitle").value=plan.title||"خطة العلاج المقترحة";$("planCurrency").value=plan.currency||"USD";$("planClosing").value=plan.closingNote||"";
    if(Array.isArray(plan.stages)){plan.stages.forEach(savedStage=>{const local=stages.find(stage=>stage.id===savedStage.id);if(local&&savedStage.illustrationDataUrl&&!illustrationById(local.illustrationId)){illustrations.push({id:`saved-${local.id}`,name:savedStage.illustrationName||"صورة المرحلة المحفوظة",path:savedStage.illustrationPath||"",dataUrl:savedStage.illustrationDataUrl});local.illustrationId=`saved-${local.id}`;}});}
    selectedAnnotationId="";activeTooth="";undoStack=[];redoStack=[];markVisualDirty();renderAllStudio();showStep("plan");
  }catch(error){notify(error);}
}
async function savePlan(showAll=false){
  try{const saved=await chairAPI.savePlan(planPayload());planId=saved.id;await refreshPlans();if(showAll)await showPlanOnDisplay("");notify(showAll?"تم حفظ الخطة وعرضها":"تم حفظ الخطة داخل ملف المريض");return saved;}catch(error){notify(error);return null;}
}
async function showPlanOnDisplay(stageId=""){
  if(!sourceImage)return notify("اختر صورة أولاً");const payload=planPayload();
  if(stageId){const stage=stages.find(item=>item.id===stageId);if(!stage)return notify("اختر مرحلة أولاً");await chairAPI.showPlan({...payload,title:`${payload.title} · ${stage.title}`,focusStageId:stage.id});}
  else await chairAPI.showPlan({...payload,focusStageId:""});
}

function renderPresentation(){
  if(!$("presentationStages"))return;storeStage();const focused=stages.find(stage=>stage.id===presentationFocusStageId)||null,list=focused?[focused]:stages,total=totals(list),currency=$("planCurrency").value;
  $("presentationPatient").textContent=appState.patient?.fullName||"ضيفنا الكريم";$("presentationTitle").textContent=$("planTitle").value||"خطة العلاج المقترحة";$("presentationTotal").textContent=`${total.cost.toLocaleString("en")} ${currency}`;$("presentationSessions").textContent=total.sessions;$("presentationFocusLabel").textContent=focused?focused.title:"العرض الكامل";
  const image=$("presentationImage");if(sourceImage){image.src=annotatedDataUrl(focused?.id||"");image.style.display="block";image.nextElementSibling.style.display="none";}else{image.style.display="none";image.nextElementSibling.style.display="block";}
  const visualHost=document.querySelector(".presentation-visuals"),illustrationHost=$("presentationStageIllustration"),stageImage=$("presentationStageImage"),illustration=focused?illustrationById(focused.illustrationId):null;visualHost.classList.toggle("focused",Boolean(focused));
  if(focused&&illustration?.dataUrl){stageImage.src=illustration.dataUrl;stageImage.style.display="block";stageImage.nextElementSibling.style.display="none";}else{stageImage.style.display="none";stageImage.nextElementSibling.style.display="grid";}
  $("presentationStages").innerHTML=list.map(stage=>`<article class="presentation-stage ${focused?.id===stage.id?"active":""}" data-present-stage="${escapeHtml(stage.id)}" style="--stage-color:${escapeHtml(stage.color)}"><b>${stages.indexOf(stage)+1}. ${escapeHtml(stage.title)}</b><p>${escapeHtml(stage.description)}</p><small>${escapeHtml(stageTeeth(stage).join("، ")||"دون تحديد أسنان")} · ${stage.sessions||1} جلسة · ${escapeHtml(stage.duration||"مدة مرنة")}</small></article>`).join("");
  document.querySelectorAll("[data-present-stage]").forEach(card=>card.onclick=async()=>{presentationFocusStageId=card.dataset.presentStage;renderPresentation();await showPlanOnDisplay(presentationFocusStageId);});
  const checks=[{ok:patientReady(),text:"ملف المريض مرتبط"},{ok:Boolean(sourceImage),text:"الصورة الأصلية موجودة"},{ok:Boolean(annotations.length),text:`${annotations.length} رسومات يدوية`},{ok:Boolean(stages.length),text:`${stages.length} مراحل علاجية`},{ok:stages.every(stage=>stage.title&&stage.sessions>0),text:"بيانات المراحل مكتملة"}];$("presentationChecklist").innerHTML=checks.map(check=>`<li class="${check.ok?"ready":"warning"}">${check.ok?"✓":"!"} ${check.text}</li>`).join("");
  $("presentFocusedStage").disabled=!focused;
}

async function openPreviousPlans(){
  if(!requirePatient())return;
  const modal=$("previousPlansModal"),list=$("previousPlansList"),patient=$("previousPlansPatient");
  patient.textContent=`${appState.patient?.fullName||""} · ملف ${appState.patient?.fileNo||"—"}`;
  modal.classList.add("open");modal.setAttribute("aria-hidden","false");list.innerHTML='<p class="studio-help">جاري تحميل الخطط…</p>';
  const plans=await refreshPlans();
  if(!plans.length){list.innerHTML='<div class="previous-plans-empty">لا توجد خطط محفوظة لهذا المريض بعد.</div>';return;}
  list.innerHTML=plans.map(plan=>`<button type="button" data-open-plan="${escapeHtml(plan.id)}"><span><b>${escapeHtml(plan.title||"خطة علاج")}</b><small>${plan.stagesCount||0} مراحل · ${Number(plan.totalCost||0).toLocaleString("en")} ${escapeHtml(plan.currency||"")}</small></span><em>فتح ←</em></button>`).join("");
  list.querySelectorAll("[data-open-plan]").forEach(button=>button.onclick=async()=>{modal.classList.remove("open");$("savedPlans").value=button.dataset.openPlan;await openStudio("plan");await loadPlan();});
}
function closePreviousPlans(){$("previousPlansModal").classList.remove("open");$("previousPlansModal").setAttribute("aria-hidden","true");}

function newPlan(){
  planId="";labels=clone(DEFAULT_LABELS);annotations=[];stages=[];illustrations=illustrations||[];activeLabelId=labels[0].id;activeTooth="";selectedAnnotationId="";activeStage=0;presentationFocusStageId="";undoStack=[];redoStack=[];$("planTitle").value="خطة العلاج المقترحة";$("planCurrency").value="USD";$("planClosing").value="الخطة تقديرية وقابلة للتعديل بحسب الفحص السريري والاستجابة للعلاج.";markVisualDirty();renderAllStudio();}
function renderAllStudio(){renderPalette();renderDrawing();renderPlan();updateHistoryButtons();}

document.querySelectorAll("[data-studio-step]").forEach(button=>button.onclick=()=>showStep(button.dataset.studioStep));
$("openStudioPlan").onclick=()=>openStudio("source");$("openPreviousPlans").onclick=openPreviousPlans;$("closePreviousPlans").onclick=closePreviousPlans;$("closeClinicalStudio").onclick=closeStudio;
$("sourceNext").onclick=()=>sourceImage?showStep("draw"):notify("اختر صورة أولاً");$("drawBack").onclick=()=>showStep("source");$("drawNext").onclick=()=>{organizeStagesFromDrawings(false);showStep("plan");};$("planBack").onclick=()=>showStep("draw");$("planNext").onclick=()=>showStep("present");$("presentBack").onclick=()=>showStep("plan");
$("useSelectedPanorama").onclick=async()=>{const path=$("patientPanoramaList").value;if(!path)return notify("اختر بانوراما");await chairAPI.showPanorama(path);await useCurrentSource(true);};
$("importPatientPanorama").onclick=async()=>{try{const item=await chairAPI.importPanorama();if(item){await refreshPanoramas();$("patientPanoramaList").value=item.path;await chairAPI.showPanorama(item.path);await useCurrentSource(true);}}catch(error){notify(error);}};
$("openPatientPanorama").onclick=async()=>{if(!requirePatient())return;try{const items=await chairAPI.listPanoramas();if(items[0])await chairAPI.showPanorama(items[0].path);else if(confirm("لا توجد بانوراما في ملف المريض. هل تريد إضافتها الآن؟")){const item=await chairAPI.importPanorama();if(item)await chairAPI.showPanorama(item.path);}}catch(error){notify(error);}};
$("openPatientFolder").onclick=()=>chairAPI.openPatientFolder().catch(notify);

document.querySelectorAll("[data-draw-tool]").forEach(button=>button.onclick=()=>setDrawTool(button.dataset.drawTool));
$("annotationOpacity").oninput=event=>$("annotationOpacityValue").textContent=`${event.target.value}%`;
$("addLabel").onclick=()=>{pushHistory();const label={id:uid("label"),name:"تصنيف جديد",color:"#a66bff",stageId:""};labels.push(label);activeLabelId=label.id;renderAllStudio();$("labelName").focus();$("labelName").select();};
$("updateLabel").onclick=()=>{const label=currentLabel();if(!label)return;const name=$("labelName").value.trim();if(!name)return notify("اكتب اسم اللون");pushHistory();const oldName=label.name;label.name=name;label.color=$("labelColor").value;const stage=stages.find(item=>item.id===label.stageId);if(stage&&stage.autoCreated&&stage.title===oldName){stage.title=name;stage.color=label.color;}markVisualDirty();renderAllStudio();};
$("deleteLabel").onclick=()=>{const label=currentLabel();if(!label)return;if(labels.length<=1)return notify("يجب إبقاء لون واحد على الأقل");if(annotations.some(item=>item.labelId===label.id))return notify("غيّر لون الرسومات المرتبطة أولاً ثم احذف التصنيف");if(!confirm(`حذف لون «${label.name}»؟`))return;pushHistory();labels=labels.filter(item=>item.id!==label.id);activeLabelId=labels[0].id;renderAllStudio();};
$("undoDrawing").onclick=()=>{if(!undoStack.length)return;redoStack.push(clone({labels,annotations,stages,activeLabelId,activeTooth}));restoreSnapshot(undoStack.pop());};
$("redoDrawing").onclick=()=>{if(!redoStack.length)return;undoStack.push(clone({labels,annotations,stages,activeLabelId,activeTooth}));restoreSnapshot(redoStack.pop());};
$("clearDrawings").onclick=()=>{if(!annotations.length)return;if(confirm("مسح جميع الرسومات اليدوية؟")){pushHistory();annotations=[];selectedAnnotationId="";markVisualDirty();renderAllStudio();}};
$("clearAnnotationTooth").onclick=()=>{const selected=annotations.find(item=>item.id===selectedAnnotationId);if(selected){pushHistory();selected.tooth="";markVisualDirty();}activeTooth="";renderAllStudio();};
$("selectedAnnotationLabel").onchange=event=>{const annotation=annotations.find(item=>item.id===selectedAnnotationId);if(!annotation)return;pushHistory();annotation.labelId=event.target.value;activeLabelId=event.target.value;markVisualDirty();renderAllStudio();};
$("selectedAnnotationTooth").onchange=event=>{const annotation=annotations.find(item=>item.id===selectedAnnotationId);if(!annotation)return;pushHistory();annotation.tooth=event.target.value?Number(event.target.value):"";activeTooth=annotation.tooth;markVisualDirty();renderAllStudio();};
$("deleteSelectedAnnotation").onclick=()=>selectedAnnotationId&&deleteAnnotation(selectedAnnotationId);

canvas.addEventListener("pointerdown",event=>{if(!sourceImage)return;event.preventDefault();canvas.setPointerCapture?.(event.pointerId);const point=canvasPoint(event);if(drawTool==="draw"){drawingPoints=[point];renderDrawing();return;}const hit=hitAnnotation(point);if(drawTool==="erase"){if(hit)deleteAnnotation(hit.id);return;}selectedAnnotationId=hit?.id||"";if(hit){activeLabelId=hit.labelId;activeTooth=hit.tooth||"";}renderAllStudio();});
canvas.addEventListener("pointermove",event=>{if(drawTool!=="draw"||!drawingPoints)return;const point=canvasPoint(event),last=drawingPoints.at(-1);if(Math.hypot(point.x-last.x,point.y-last.y)>.004)drawingPoints.push(point);drawScene(canvas,"",true,drawingPoints);});
function finishDrawing(event){if(drawTool!=="draw"||!drawingPoints)return;event?.preventDefault?.();let points=drawingPoints;drawingPoints=null;if(points.length<4)points=circlePoints(points[0]);addAnnotation(points);}
canvas.addEventListener("pointerup",finishDrawing);canvas.addEventListener("pointercancel",()=>{drawingPoints=null;renderDrawing();});

$("buildFromDrawings").onclick=()=>organizeStagesFromDrawings(true);
$("addPlanStage").onclick=()=>{storeStage();stages.push(newStage());activeStage=stages.length-1;renderPlan();};
$("removePlanStage").onclick=()=>{const stage=currentStage();if(!stage)return;if(confirm("حذف المرحلة الحالية؟ ستبقى الرسومات لكن تصبح غير مرتبطة.")){labels.filter(label=>label.stageId===stage.id).forEach(label=>label.stageId="");stages.splice(activeStage,1);activeStage=Math.max(0,activeStage-1);if(presentationFocusStageId===stage.id)presentationFocusStageId="";markVisualDirty();renderAllStudio();}};
[$("stageTitle"),$("stageDescription"),$("stagePriority"),$("stageSessions"),$("stageDuration"),$("stageCost"),$("stageColor")].forEach(element=>element.addEventListener("input",()=>{storeStage();renderStageList();const total=totals();$("planTotal").textContent=`${total.cost.toLocaleString("en")} ${$("planCurrency").value}`;$("planSessionsTotal").textContent=`${total.sessions} جلسة`;renderPresentation();}));
[$("planTitle"),$("planCurrency"),$("planClosing")].forEach(element=>element.addEventListener("input",()=>renderPresentation()));
$("importStageIllustration").onclick=async()=>{try{const item=await chairAPI.importStageIllustration();if(item){await refreshIllustrations();const stage=currentStage();if(stage)stage.illustrationId=item.id;renderIllustrations();renderPresentation();}}catch(error){notify(error);}};
$("chooseStageBackground").onclick=async()=>{try{const file=await chairAPI.chooseStageBackground();if(file){$("stageBackgroundPath").value=file;storeStage();renderPresentation();}}catch(error){notify(error);}};
$("clearStageBackground").onclick=()=>{const stage=currentStage();if(!stage)return;stage.backgroundPath="";$("stageBackgroundPath").value="";renderPresentation();};
$("savePlan").onclick=()=>savePlan(false);$("presentPlan").onclick=()=>savePlan(true);$("newPlan").onclick=()=>{if(!annotations.length||confirm("بدء خطة جديدة ومسح الرسومات الحالية؟"))newPlan();};
$("presentAllStages").onclick=async()=>{presentationFocusStageId="";renderPresentation();await showPlanOnDisplay("");};$("presentFocusedStage").onclick=()=>presentationFocusStageId?showPlanOnDisplay(presentationFocusStageId):notify("اختر مرحلة من المعاينة");
$("planStoryStart").onclick=()=>chairAPI.navigatePlan("home");
$("planStoryPrevious").onclick=()=>chairAPI.navigatePlan("previous");
$("planStoryNext").onclick=()=>chairAPI.navigatePlan("next");
$("planStoryEnd").onclick=()=>chairAPI.navigatePlan("end");
document.addEventListener("keydown",event=>{if(event.key==="Escape"&&$("clinicalStudio").classList.contains("open")){event.preventDefault();closeStudio();}});
chairAPI.onOpenClinicalStudio?.(step=>openStudio(step||"draw"));
function applyPatientState(state){
  const nextState=state||{},nextKey=patientKey(nextState),changed=nextKey!==activePatientKey;appState=nextState;
  if(changed){activePatientKey=nextKey;source=null;sourceImage=null;planPanoramaPath="";canvas.width=1;canvas.height=1;canvas.style.display="none";$("annotationCanvasEmpty").style.display="grid";$("studioSourcePreview").removeAttribute("src");$("studioSourcePreview").style.display="none";$("studioSourceEmpty").style.display="grid";$("studioSourceName").textContent="لم يتم اختيار صورة";newPlan();refreshPanoramas();refreshPlans();}
  updatePatientLabel();
}
chairAPI.onState(applyPatientState);chairAPI.getState().then(applyPatientState);

newPlan();setDrawTool("draw");renderAllStudio();
})();
