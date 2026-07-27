"use strict";
const express = require("express");
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const mime = require("mime-types");
let nativeImage = null;
try { ({nativeImage} = require("electron")); } catch { nativeImage = null; }
let sharp = null;
try { sharp = require("sharp"); } catch { sharp = null; }
const {WebSocketServer, WebSocket} = require("ws");
const {getLocalIPv4} = require("./discovery");

class ChairServer {
  constructor({port,maxWidth,maxHeight,onState,onNotice,getHelloPayload,onCommand}) {
    this.port=port;
    this.maxWidth=maxWidth;
    this.maxHeight=maxHeight;
    this.onState=onState||(()=>{});
    this.onNotice=onNotice||(()=>{});
    this.getHelloPayload=getHelloPayload||(()=>({}));
    this.onCommand=onCommand||(()=>false);
    this.clients=new Set();
    this.media=new Map();
    this.server=null;
    this.wss=null;
    this.pending=new Map();
    this.currentState=null;
    this.sessionId="";
    this.lastDisconnectReason="";
    this.heartbeatTimer=null;
  }

  state() {
    const ip=getLocalIPv4()[0]||"127.0.0.1";
    return {
      clients:this.clients.size,
      ip,
      wsUrl:`ws://${ip}:${this.port}`,
      httpUrl:`http://${ip}:${this.port}`,
      sessionId:this.sessionId,
      pendingAcks:this.pending.size,
      lastDisconnectReason:this.lastDisconnectReason
    };
  }

  setSession(id){
    const next=String(id||"");
    if(next!==this.sessionId)this.pending.clear();
    this.sessionId=next;
    this.emit();
  }

  setCurrentState(payload){this.currentState=payload||null;}

  async start() {
    const app=express();
    app.use((req,res,next)=>{
      res.setHeader("Access-Control-Allow-Origin","*");
      res.setHeader("Access-Control-Allow-Headers","Content-Type");
      res.setHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
      res.setHeader("Access-Control-Allow-Private-Network","true");
      if(req.method==="OPTIONS")return res.sendStatus(204);
      next();
    });
    app.use(express.json({limit:"256kb"}));
    app.get("/health",(_req,res)=>res.json({ok:true,product:"DentalChairController",protocol:3,sessionId:this.sessionId}));
    app.post("/command",async(req,res)=>{
      try{
        const remote=String(req.socket.remoteAddress||"");
        if(!["127.0.0.1","::1","::ffff:127.0.0.1"].includes(remote))return res.status(403).json({ok:false,error:"loopback_only"});
        const handled=await this.onCommand(req.body||{});
        if(!handled)return res.status(400).json({ok:false,error:"unsupported_command"});
        res.json({ok:true});
      }catch(error){
        res.status(400).json({ok:false,error:String(error?.message||error)});
      }
    });
    app.get("/media/:id",async(req,res)=>{
      const entry=this.media.get(req.params.id);
      if(!entry || !fs.existsSync(entry.path)) return res.sendStatus(404);
      try {
        const etag=`"${req.params.id}"`;
        res.setHeader("Cache-Control","public, max-age=31536000, immutable");
        res.setHeader("ETag",etag);
        if(req.headers["if-none-match"]===etag)return res.sendStatus(304);
        const optimized=entry.optimizeImage ? await this.optimizedBuffer(entry) : null;
        if(optimized){res.type("image/jpeg");return res.end(optimized);}
        res.type(mime.lookup(entry.path)||"application/octet-stream");
        fs.createReadStream(entry.path).pipe(res);
      } catch { res.sendStatus(500); }
    });

    this.server=http.createServer(app);
    this.wss=new WebSocketServer({server:this.server});
    this.wss.on("connection",socket=>{
      socket.isAlive=true;
      this.clients.add(socket);
      socket.on("pong",()=>socket.isAlive=true);
      socket.on("message",raw=>this.onMessage(raw));
      socket.on("close",(code,reason)=>{
        this.lastDisconnectReason=`${code} ${String(reason||"")}`.trim();
        this.clients.delete(socket);
        this.emit();
      });
      socket.on("error",error=>{this.lastDisconnectReason=String(error?.message||"socket error");});
      socket.send(JSON.stringify({type:"hello",protocol:3,controllerVersion:"2.7.0",sessionId:this.sessionId,...this.getHelloPayload()}));
      if(this.currentState)setTimeout(()=>this.send(this.currentState,{important:true,warn:false}),180);
      this.emit();
    });

    this.heartbeatTimer=setInterval(()=>{
      for(const s of this.clients){
        if(!s.isAlive){s.terminate();this.clients.delete(s);continue;}
        s.isAlive=false;
        try{s.ping();}catch{}
      }
      this.retryPending();
      this.emit();
    },5000);

    await new Promise((resolve,reject)=>{
      this.server.once("error",reject);
      this.server.listen(this.port,"0.0.0.0",resolve);
    });
    this.emit();
    this.onNotice(`خادم الشاشة يعمل على ${this.port}`,"success");
  }

  emit(){ this.onState(this.state()); }

  onMessage(raw){
    try{
      const message=JSON.parse(String(raw));
      if(message.type==="ack"&&message.messageId)this.pending.delete(String(message.messageId));
      if(message.type==="display_ready"&&this.currentState)this.send(this.currentState,{important:true,warn:false});
      this.emit();
    }catch{}
  }

  retryPending(){
    const now=Date.now();
    for(const [id,item] of this.pending){
      if(now-item.sentAt<2500)continue;
      if(item.tries>=3){
        this.pending.delete(id);
        this.onNotice(`لم تؤكد الشاشة استلام الأمر ${item.payload.type}`,"warning");
        continue;
      }
      item.tries++;
      item.sentAt=now;
      this.broadcast(item.message);
    }
  }

  mediaId(file,optimizeImage=false){
    try{
      const st=fs.statSync(file);
      return crypto.createHash("sha256")
        .update(`${path.resolve(file)}|${Math.round(st.mtimeMs)}|${st.size}|${optimizeImage?1:0}`)
        .digest("hex").slice(0,32);
    }catch{return crypto.randomUUID();}
  }

  async optimizedBuffer(entry){
    if(!entry?.optimizeImage)return null;
    if(entry.optimizedBuffer)return entry.optimizedBuffer;
    if(entry.optimizePromise)return entry.optimizePromise;
    entry.optimizePromise=(async()=>{
      try{
        if(sharp){
          entry.optimizedBuffer=await sharp(entry.path).rotate().resize({
            width:this.maxWidth,height:this.maxHeight,fit:"inside",withoutEnlargement:true
          }).jpeg({quality:88,mozjpeg:true}).toBuffer();
          return entry.optimizedBuffer;
        }
        if(nativeImage){
          let image=nativeImage.createFromPath(entry.path);
          if(image&&!image.isEmpty()){
            const size=image.getSize();
            const ratio=Math.min(1,this.maxWidth/size.width,this.maxHeight/size.height);
            if(ratio<1)image=image.resize({width:Math.max(1,Math.round(size.width*ratio)),height:Math.max(1,Math.round(size.height*ratio)),quality:"best"});
            entry.optimizedBuffer=image.toJPEG(88);
            return entry.optimizedBuffer;
          }
        }
      }catch{}
      return null;
    })().finally(()=>{entry.optimizePromise=null;});
    return entry.optimizePromise;
  }

  registerMedia(file,optimizeImage=false){
    const id=this.mediaId(file,optimizeImage);
    let entry=this.media.get(id);
    if(!entry){
      entry={path:file,optimizeImage,createdAt:Date.now(),optimizedBuffer:null,optimizePromise:null};
      this.media.set(id,entry);
    }else entry.createdAt=Date.now();
    if(optimizeImage)this.optimizedBuffer(entry).catch(()=>{});
    if(this.media.size>80){
      const oldest=[...this.media.entries()].sort((a,b)=>a[1].createdAt-b[1].createdAt)[0];
      if(oldest)this.media.delete(oldest[0]);
    }
    return `${this.state().httpUrl}/media/${id}`;
  }

  prewarmMedia(file,optimizeImage=true){
    if(!file||!fs.existsSync(file))return;
    this.registerMedia(file,optimizeImage);
  }

  broadcast(message){
    let count=0;
    for(const socket of this.clients){
      if(socket.readyState===WebSocket.OPEN){socket.send(message);count++;}
    }
    return count;
  }

  send(payload,options=true){
    const opts=typeof options==="boolean"?{warn:options}:options||{};
    const stateful=new Set(["home","patient","image","gif","video","pdf","black","hide","treatment_gif","appointment_qr","treatment_plan","game"]).has(String(payload?.type||""));
    const important=opts.important===undefined?stateful:Boolean(opts.important);
    const messageId=important?crypto.randomUUID():undefined;
    const envelope={...payload,protocol:3,sessionId:payload?.sessionId??this.sessionId,sentAt:Date.now(),messageId};
    const message=JSON.stringify(envelope);
    const count=this.broadcast(message);
    if(stateful)this.currentState={...payload,sessionId:envelope.sessionId};
    if(important&&messageId&&count){
      if(stateful)this.pending.clear();
      this.pending.set(messageId,{payload:envelope,message,sentAt:Date.now(),tries:0});
    }
    if(!count&&opts.warn!==false)this.onNotice(`شاشة الكرسي غير متصلة${this.lastDisconnectReason?`: ${this.lastDisconnectReason}`:""}`,"warning");
    this.emit();
    return count;
  }
}
module.exports={ChairServer};
