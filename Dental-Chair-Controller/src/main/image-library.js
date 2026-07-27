"use strict";
const fs = require("fs");
const path = require("path");
const chokidar = require("chokidar");

const SUPPORTED = new Set([".png",".jpg",".jpeg",".bmp",".webp",".tif",".tiff"]);

class ImageLibrary {
  constructor({onState,onNotice}) {
    this.onState = onState || (()=>{});
    this.onNotice = onNotice || (()=>{});
    this.folder = "";
    this.items = [];
    this.index = -1;
    this.watcher = null;
  }

  async scan(folder) {
    this.folder = folder;
    const items = [];
    if (folder && fs.existsSync(folder)) {
      const stack = [folder];
      while (stack.length) {
        const dir = stack.pop();
        let entries = [];
        try { entries = await fs.promises.readdir(dir,{withFileTypes:true}); } catch { continue; }
        for (const e of entries) {
          const full = path.join(dir,e.name);
          if (e.isDirectory()) stack.push(full);
          else if (e.isFile() && SUPPORTED.has(path.extname(e.name).toLowerCase())) {
            try {
              const st = await fs.promises.stat(full);
              if (st.size > 0) items.push({path:full,name:e.name,mtimeMs:st.mtimeMs,size:st.size});
            } catch {}
          }
        }
      }
    }
    items.sort((a,b)=>a.mtimeMs-b.mtimeMs);
    this.items = items;
    this.index = items.length ? items.length-1 : -1;
    this.emit();
    this.onNotice(`تمت فهرسة ${items.length} صورة`, "success");
  }

  async watch(folder) {
    if (this.watcher) await this.watcher.close();
    await this.scan(folder);
    if (!folder || !fs.existsSync(folder)) return;
    this.watcher = chokidar.watch(folder,{
      ignoreInitial:true,
      depth:30,
      awaitWriteFinish:{stabilityThreshold:450,pollInterval:100}
    });
    this.watcher.on("add", file => this.upsert(file));
    this.watcher.on("change", file => this.upsert(file));
    this.watcher.on("unlink", file => {
      this.items = this.items.filter(x=>x.path!==file);
      this.index = Math.min(this.index,this.items.length-1);
      this.emit();
    });
  }

  upsert(file) {
    if (!SUPPORTED.has(path.extname(file).toLowerCase())) return;
    try {
      const st = fs.statSync(file);
      if (!st.size) return;
      const item = {path:file,name:path.basename(file),mtimeMs:st.mtimeMs,size:st.size};
      const i = this.items.findIndex(x=>x.path===file);
      if (i>=0) this.items[i]=item; else this.items.push(item);
      this.items.sort((a,b)=>a.mtimeMs-b.mtimeMs);
      this.index = this.items.findIndex(x=>x.path===file);
      this.emit();
      this.onNotice(`صورة جديدة: ${item.name}`, "success");
    } catch {}
  }

  current(){ return this.index>=0 ? this.items[this.index] : null; }
  latest(){ if(!this.items.length)return null; this.index=this.items.length-1; this.emit(); return this.current(); }
  previous(){ if(!this.items.length)return null; this.index=Math.max(0,this.index-1); this.emit(); return this.current(); }
  next(){ if(!this.items.length)return null; this.index=Math.min(this.items.length-1,this.index+1); this.emit(); return this.current(); }
  emit(){ this.onState(this.status()); }
  status(){
    const c=this.current();
    return {folder:this.folder,count:this.items.length,index:this.index,position:c?this.index+1:0,currentName:c?.name||"",currentPath:c?.path||""};
  }
}
module.exports = {ImageLibrary};
