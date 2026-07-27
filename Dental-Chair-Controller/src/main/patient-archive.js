"use strict";

const fs=require("fs");
const path=require("path");
const mime=require("mime-types");
const crypto=require("crypto");

const IMAGE_EXTENSIONS=new Set([".png",".jpg",".jpeg",".bmp",".webp",".tif",".tiff"]);
const FOLDERS=["Panorama","Sensor","Other","TreatmentPlans"];
const FOLDER_ALIASES={
  Panorama:["01 - صور بانوراما","Panorama"],
  Sensor:["02 - صور أشعة وسينسور","Sensor"],
  Other:["07 - صور أخرى","Other"],
  TreatmentPlans:["TreatmentPlans","08 - خطط العلاج"]
};

function safePart(value,fallback="patient"){
  const cleaned=String(value||"").normalize("NFKC").replace(/[<>:"/\\|?*\u0000-\u001f]/g," ").replace(/\s+/g," ").trim().replace(/[. ]+$/g,"");
  return(cleaned||fallback).slice(0,90);
}
function matchPart(value){return String(value||"").normalize("NFKC").toLocaleLowerCase().replace(/[\s_\-–—]+/g," ").trim();}
function sameValue(a,b){return Boolean(a&&b&&matchPart(a)===matchPart(b));}
function insideRoot(root,candidate){const relative=path.relative(path.resolve(root),path.resolve(candidate));return relative===""||(!relative.startsWith("..")&&!path.isAbsolute(relative));}
function readPatientManifest(dir){
  for(const name of ["_patient.json",".dtdc-patient.json","patient.json"]){
    const file=path.join(dir,name);if(!fs.existsSync(file))continue;
    try{return JSON.parse(fs.readFileSync(file,"utf8"));}catch{}
  }
  return null;
}
function patientDirectories(root){
  try{return fs.readdirSync(root,{withFileTypes:true}).filter(entry=>entry.isDirectory()).map(entry=>path.join(root,entry.name));}catch{return[];}
}
function explicitPatientDirectory(root,payload){
  const values=[payload.patientDir,payload.patientPath,payload.archivePatientPath,payload.patientFolderPath,payload.folderPath,payload.patientFolder].filter(Boolean);
  for(const value of values){const candidate=path.isAbsolute(String(value))?String(value):path.join(root,String(value));if(insideRoot(root,candidate)&&fs.existsSync(candidate)&&fs.statSync(candidate).isDirectory())return candidate;}
  return"";
}
function resolvePatientDirectory(root,payload,identity){
  const explicit=explicitPatientDirectory(root,payload);if(explicit)return{patientDir:explicit,manifest:readPatientManifest(explicit)};
  const dirs=patientDirectories(root),scored=[];
  for(const dir of dirs){
    const manifest=readPatientManifest(dir);if(!manifest)continue;
    const manifestId=String(manifest.patientId||manifest.id||""),manifestFileNo=String(manifest.fileNo||manifest.fileNumber||""),manifestName=String(manifest.fullName||manifest.name||"");let score=0;
    if(identity.fileNo&&sameValue(identity.fileNo,manifestFileNo))score+=120;
    if(identity.patientId&&sameValue(identity.patientId,manifestId))score+=100;
    if(identity.fullName&&sameValue(identity.fullName,manifestName))score+=30;
    if(score>=100)scored.push({patientDir:dir,manifest,score});
  }
  if(scored.length){scored.sort((a,b)=>b.score-a.score);return scored[0];}

  const exactNames=[];
  if(identity.fileNo&&identity.fullName)exactNames.push(`${identity.fileNo} - ${identity.fullName}`,`${identity.fullName} - ${identity.fileNo}`,`${identity.fileNo}_${identity.fullName}`,`${identity.fullName}_${identity.fileNo}`);
  for(const name of exactNames){const found=dirs.find(dir=>sameValue(path.basename(dir),name));if(found)return{patientDir:found,manifest:readPatientManifest(found)};}

  if(identity.fullName){const nameMatches=dirs.filter(dir=>matchPart(path.basename(dir)).includes(matchPart(identity.fullName)));if(nameMatches.length===1)return{patientDir:nameMatches[0],manifest:readPatientManifest(nameMatches[0])};}
  return null;
}
function patientFolder(dir,logicalName){
  const aliases=FOLDER_ALIASES[logicalName]||[logicalName];
  const existing=aliases.map(name=>path.join(dir,name)).find(candidate=>fs.existsSync(candidate)&&fs.statSync(candidate).isDirectory());
  const folder=existing||path.join(dir,aliases[0]);fs.mkdirSync(folder,{recursive:true});return folder;
}
function uniqueFile(dir,name){const ext=path.extname(name),base=path.basename(name,ext);let candidate=path.join(dir,name),index=2;while(fs.existsSync(candidate)){candidate=path.join(dir,`${base}-${index}${ext}`);index++;}return candidate;}
function writeJson(file,value){fs.mkdirSync(path.dirname(file),{recursive:true});const temp=`${file}.${process.pid}.tmp`;fs.writeFileSync(temp,JSON.stringify(value,null,2),"utf8");try{fs.renameSync(temp,file)}catch{try{fs.unlinkSync(file)}catch{}fs.renameSync(temp,file)}}
function html(value){return String(value??"").replace(/[&<>"']/g,char=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[char]));}
function dataUrlParts(value){const match=String(value||"").match(/^data:image\/(png|jpe?g|webp);base64,([\s\S]+)$/i);if(!match)return null;const type=match[1].toLowerCase(),ext=type==="jpeg"||type==="jpg"?".jpg":`.${type}`;return{ext,buffer:Buffer.from(match[2],"base64")};}
function saveDataUrl(value,basePath){const parsed=dataUrlParts(value);if(!parsed)return"";const file=`${basePath}${parsed.ext}`;fs.writeFileSync(file,parsed.buffer);return file;}
function fileDataUrl(file){if(!file||!fs.existsSync(file))return"";return`data:${mime.lookup(file)||"image/png"};base64,${fs.readFileSync(file).toString("base64")}`;}

class PatientArchive{
  constructor({app,settings,onState,onNotice}){this.app=app;this.settings=settings;this.onState=onState||(()=>{});this.onNotice=onNotice||(()=>{});this.current=null;}
  root(){const configured=String(this.settings.get("patientArchiveRoot")||"").trim();return configured||path.join(this.app.getPath("documents"),"Dental Chain Patients");}
  setRoot(root){if(!root)return this.snapshot();fs.mkdirSync(root,{recursive:true});this.settings.patch({patientArchiveRoot:root});if(this.current)this.select(this.current);return this.snapshot();}
  snapshot(){return this.current?{...this.current,archiveRoot:this.root(),selected:true}:{archiveRoot:this.root(),selected:false};}
  select(payload={}){
    const root=this.root();fs.mkdirSync(root,{recursive:true});
    const suppliedFullName=safePart(payload.fullName||payload.name||"",""),suppliedFileNo=safePart(payload.fileNo||payload.fileNumber||"",""),suppliedPatientId=String(payload.patientId||payload.id||suppliedFileNo||"").trim();
    const resolved=resolvePatientDirectory(root,payload,{fullName:suppliedFullName,fileNo:suppliedFileNo,patientId:suppliedPatientId}),manifest=resolved?.manifest||{};
    const fullName=safePart(manifest.fullName||manifest.name||suppliedFullName,"مريض"),fileNo=safePart(manifest.fileNo||manifest.fileNumber||suppliedFileNo,""),patientId=String(manifest.patientId||manifest.id||suppliedPatientId||fileNo||fullName).trim();
    const stableCode=fileNo||(patientId&&!sameValue(patientId,fullName)?safePart(patientId,""):"");
    const folderName=safePart(stableCode?`${stableCode} - ${fullName}`:fullName),patientDir=resolved?.patientDir||path.join(root,folderName);fs.mkdirSync(patientDir,{recursive:true});
    const folders={};for(const name of FOLDERS)folders[name]=patientFolder(patientDir,name);
    const previousSame=this.current&&sameValue(this.current.patientId,patientId);
    const selectedAt=new Date().toISOString();
    this.current={
      patientId,
      fileNo,
      fullName,
      firstName:String(payload.firstName||payload.displayName||manifest.firstName||fullName.split(/\s+/)[0]||fullName),
      displayName:String(payload.firstName||payload.displayName||manifest.firstName||fullName.split(/\s+/)[0]||fullName),
      gender:["male","female"].includes(String(payload.gender||manifest.gender))?String(payload.gender||manifest.gender):"",
      doctorName:String(payload.doctorName||manifest.doctorName||""),
      clinicName:String(payload.clinicName||manifest.clinicName||""),
      sessionId:String(payload.sessionId||(previousSame?this.current.sessionId:"")||crypto.randomUUID()),
      patientDir,
      folders,
      selectedAt
    };
    writeJson(path.join(patientDir,"patient.json"),{
      schema:"dtdc-patient-archive-v3",
      patientId,
      fileNo,
      fullName,
      firstName:this.current.firstName,
      gender:this.current.gender,
      doctorName:this.current.doctorName,
      clinicName:this.current.clinicName,
      sessionId:this.current.sessionId,
      lastSelectedAt:selectedAt
    });
    this.onState(this.snapshot());return this.snapshot();
  }
  clear(){this.current=null;this.onState(this.snapshot());return this.snapshot();}
  requirePatient(){if(!this.current)throw new Error("افتح ملف المريض في البرنامج الرئيسي أولاً");return this.current;}
  panoramaFolder(){return this.requirePatient().folders.Panorama;}
  listPanoramas(){const dir=this.panoramaFolder();return fs.readdirSync(dir,{withFileTypes:true}).filter(entry=>entry.isFile()&&IMAGE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())).map(entry=>{const filePath=path.join(dir,entry.name),stat=fs.statSync(filePath);return{name:entry.name,path:filePath,size:stat.size,modifiedAt:stat.mtimeMs};}).sort((a,b)=>b.modifiedAt-a.modifiedAt);}
  importPanorama(source){this.requirePatient();if(!source||!fs.existsSync(source))throw new Error("ملف الصورة غير موجود");if(!IMAGE_EXTENSIONS.has(path.extname(source).toLowerCase()))throw new Error("صيغة الصورة غير مدعومة");const destination=uniqueFile(this.panoramaFolder(),safePart(path.basename(source),"panorama.jpg"));fs.copyFileSync(source,destination);return{path:destination,name:path.basename(destination)};}
  plansDir(){return this.requirePatient().folders.TreatmentPlans;}
  savePlan(plan={}){
    const patient=this.requirePatient(),id=safePart(plan.id||`PLAN-${Date.now()}`),createdAt=plan.createdAt||new Date().toISOString(),dir=path.join(this.plansDir(),id);fs.mkdirSync(dir,{recursive:true});
    const normalized={...JSON.parse(JSON.stringify(plan)),id,schema:"dtdc-treatment-plan-v3-manual",createdAt,updatedAt:new Date().toISOString(),patient:{patientId:patient.patientId,fileNo:patient.fileNo,fullName:patient.fullName,doctorName:plan.doctorName||patient.doctorName}};
    if(plan.sourcePath&&fs.existsSync(plan.sourcePath)){const ext=IMAGE_EXTENSIONS.has(path.extname(plan.sourcePath).toLowerCase())?path.extname(plan.sourcePath).toLowerCase():".png";normalized.sourceArchivePath=path.join(dir,`source${ext}`);if(path.resolve(plan.sourcePath)!==path.resolve(normalized.sourceArchivePath))fs.copyFileSync(plan.sourcePath,normalized.sourceArchivePath);}
    const annotatedPath=saveDataUrl(plan.annotatedImageDataUrl,path.join(dir,"annotated-panorama"));if(annotatedPath)normalized.annotatedImagePath=annotatedPath;delete normalized.annotatedImageDataUrl;delete normalized.displayImageDataUrl;
    normalized.stages=(normalized.stages||[]).map((stage,index)=>{
      const item={...stage},prefix=`stage-${String(index+1).padStart(2,"0")}`;
      const illustrationPath=saveDataUrl(stage.illustrationDataUrl,path.join(dir,`${prefix}-illustration`));
      if(illustrationPath)item.illustrationPath=illustrationPath;
      if(stage.backgroundPath&&fs.existsSync(stage.backgroundPath)){
        const ext=IMAGE_EXTENSIONS.has(path.extname(stage.backgroundPath).toLowerCase())?path.extname(stage.backgroundPath).toLowerCase():".jpg";
        const archivedBackground=path.join(dir,`${prefix}-background${ext}`);
        if(path.resolve(stage.backgroundPath)!==path.resolve(archivedBackground))fs.copyFileSync(stage.backgroundPath,archivedBackground);
        item.backgroundPath=archivedBackground;
      }
      delete item.illustrationDataUrl;
      delete item.backgroundDataUrl;
      return item;
    });
    writeJson(path.join(dir,"plan.json"),normalized);fs.writeFileSync(path.join(dir,"presentation.html"),this.planHtml(normalized),"utf8");return{...normalized,folder:dir};
  }
  listPlans(){const dir=this.plansDir();return fs.readdirSync(dir,{withFileTypes:true}).filter(entry=>entry.isDirectory()&&!entry.name.startsWith(".")).map(entry=>{try{const plan=JSON.parse(fs.readFileSync(path.join(dir,entry.name,"plan.json"),"utf8"));return{id:plan.id,title:plan.title||"خطة علاج",createdAt:plan.createdAt,updatedAt:plan.updatedAt,totalCost:plan.totalCost,currency:plan.currency,stagesCount:plan.stages?.length||0};}catch{return null;}}).filter(Boolean).sort((a,b)=>String(b.updatedAt).localeCompare(String(a.updatedAt)));}
  loadPlan(id,includeAssets=false){const file=path.join(this.plansDir(),safePart(id),"plan.json");if(!fs.existsSync(file))throw new Error("الخطة غير موجودة");const plan=JSON.parse(fs.readFileSync(file,"utf8"));if(!includeAssets)return plan;const source=plan.sourceArchivePath||plan.sourcePath||plan.panoramaPath;return{...plan,sourceDataUrl:fileDataUrl(source),annotatedImageDataUrl:fileDataUrl(plan.annotatedImagePath),stages:(plan.stages||[]).map(stage=>({...stage,illustrationDataUrl:fileDataUrl(stage.illustrationPath)}))};}
  saveLivePresentation(dataUrl){this.requirePatient();const parsed=dataUrlParts(dataUrl);if(!parsed)throw new Error("صورة العرض غير صالحة");const file=path.join(this.plansDir(),`.live-presentation${parsed.ext}`);fs.writeFileSync(file,parsed.buffer);return file;}
  planHtml(plan){
    const stages=Array.isArray(plan.stages)?plan.stages:[],annotated=plan.annotatedImagePath?path.basename(plan.annotatedImagePath):"";
    const rows=stages.map((stage,index)=>{
      const bg=stage.backgroundPath?path.basename(stage.backgroundPath):(stage.illustrationPath?path.basename(stage.illustrationPath):"");
      const priority=stage.priority||stage.title||"مرحلة علاج";
      return`<section class="story-stage" style="--stage:${html(stage.color||"#2f8fe9")}">${bg?`<img class="stage-bg" src="${html(bg)}" alt="">`:""}<div class="veil"></div><img class="mini-panorama" src="${html(annotated)}" alt=""><article><small>المرحلة ${index+1}</small><h2>${html(priority)}</h2><h3>${html(stage.title||"")}</h3><p>${html(stage.description||"")}</p><div class="meta"><span>الأسنان ${html((stage.teeth||[]).join("، ")||"—")}</span><span>${html(stage.sessions||1)} جلسة</span><span>${html(stage.duration||"مدة مرنة")}</span></div></article></section>`;
    }).join("");
    return`<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${html(plan.title||"خطة علاج")}</title><style>*{box-sizing:border-box}body{margin:0;background:#eaf4f8;color:#102f43;font-family:Tahoma,Arial;overflow:hidden}.story-stage{display:none;position:relative;width:100vw;height:100vh;overflow:hidden;background:linear-gradient(135deg,#eef8fb,#fff)}.story-stage.active{display:block}.stage-bg{position:absolute;inset:-5%;width:110%;height:110%;object-fit:cover;animation:livingBackground 16s ease-in-out infinite alternate;filter:saturate(.9) brightness(1.04)}.veil{position:absolute;inset:0;background:linear-gradient(90deg,rgba(244,250,252,.08),rgba(244,250,252,.72) 58%,rgba(244,250,252,.98) 100%)}.mini-panorama{position:absolute;left:4.5vw;bottom:5vh;width:30vw;height:18vw;max-height:28vh;object-fit:contain;background:#02070a;border:8px solid rgba(255,255,255,.9);border-radius:24px;box-shadow:0 20px 65px rgba(8,55,80,.24)}article{position:absolute;right:6vw;top:50%;transform:translateY(-50%);width:min(43vw,680px);padding:38px 40px;border-right:10px solid var(--stage);border-radius:30px;background:rgba(255,255,255,.88);box-shadow:0 24px 80px rgba(16,60,83,.16);backdrop-filter:blur(18px)}article small{color:var(--stage);font-weight:800}h2{font-size:clamp(38px,5vw,78px);line-height:1;margin:10px 0;color:var(--stage)}h3{font-size:clamp(20px,2.2vw,34px);font-weight:500;margin:0 0 20px}p{font-size:clamp(17px,1.4vw,24px);line-height:1.8;color:#496778}.meta{display:flex;flex-wrap:wrap;gap:9px;margin-top:24px}.meta span{padding:8px 13px;border-radius:99px;background:#edf5f8;font-weight:700;color:#35596c}.counter{position:fixed;left:30px;top:25px;z-index:8;padding:8px 13px;border-radius:99px;background:rgba(255,255,255,.8);font-weight:700}.final{display:none;width:100vw;height:100vh;padding:8vh 8vw;background:linear-gradient(135deg,#102f43,#0b5b76);color:white}.final.active{display:grid;place-items:center;text-align:center}.final h1{font-size:clamp(36px,5vw,74px)}.total{font-size:clamp(28px,4vw,60px);font-weight:900}@keyframes livingBackground{0%{transform:scale(1.02) translate3d(-1%,0,0)}100%{transform:scale(1.10) translate3d(2%,-1%,0)}}@media(max-width:900px){article{right:4vw;width:58vw}.mini-panorama{width:34vw}.veil{background:linear-gradient(90deg,rgba(244,250,252,.2),rgba(244,250,252,.92) 65%)}}</style><body><div class="counter" id="counter"></div>${rows}<section class="final"><div><small>DENTAL CHAIN</small><h1>${html(plan.title||"خطة العلاج المقترحة")}</h1><p>${html(plan.closingNote||"")}</p><div class="total">${html(plan.totalCost||0)} ${html(plan.currency||"")} · ${html(plan.totalSessions||0)} جلسة</div></div></section><script>const slides=[...document.querySelectorAll('.story-stage'),document.querySelector('.final')];let i=0;function show(n){i=Math.max(0,Math.min(slides.length-1,n));slides.forEach((s,x)=>s.classList.toggle('active',x===i));document.getElementById('counter').textContent=(i+1)+' / '+slides.length}addEventListener('keydown',e=>{if(['ArrowRight','PageDown','Enter',' '].includes(e.key))show(i+1);if(['ArrowLeft','PageUp','Backspace'].includes(e.key))show(i-1);if(e.key==='Home')show(0);if(e.key==='End')show(slides.length-1)});show(0)</script></body></html>`;
  }
}

module.exports={PatientArchive,IMAGE_EXTENSIONS,FOLDER_ALIASES};
