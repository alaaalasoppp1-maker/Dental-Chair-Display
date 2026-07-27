"use strict";
const fs = require("fs");
const path = require("path");

const DEFAULT_SHORTCUTS = Object.freeze({
  latest: "CommandOrControl+`",
  home: "CommandOrControl+H",
  black: "CommandOrControl+B",
  tempImage: "CommandOrControl+I",
  treatments: "CommandOrControl+G",
  video: "CommandOrControl+V",
  pdf: "CommandOrControl+P",
  game: "CommandOrControl+L",
  hide: "CommandOrControl+Escape",
  moveLeft: "CommandOrControl+Left",
  moveRight: "CommandOrControl+Right",
  moveUp: "CommandOrControl+Up",
  moveDown: "CommandOrControl+Down",
  zoomIn: "CommandOrControl+=",
  zoomOut: "CommandOrControl+-",
  resetView: "CommandOrControl+0",
  rotate: "CommandOrControl+Shift+8",
  previous: "CommandOrControl+PageUp",
  next: "CommandOrControl+PageDown"
});

class SettingsStore {
  constructor(app) {
    this.file = path.join(app.getPath("userData"), "settings.json");
    this.defaults = {
      sensorFolder: "C:\\Users\\Public\\Documents\\Images SOPRO-Imaging",
      patientArchiveRoot: "",
      clinicName: "عيادة د. طاهر",
      chainName: "DR TAHER DENTAL CHAIN",
      displayTitle: "Clinic Display",
      clinicDisplayName: "DR TAHER CLINIC",
      homeEyebrow: "DENTAL CHAIN",
      specialty: "DDS, PhD • Endodontics",
      welcomeText: "WELCOME",
      comfortText: "نتمنى لك جلسة مريحة",
      qrEventTitle: "موعدك في {clinic}",
      qrEventDescription: "شكراً لثقتكم.",
      qrReminderMessage: "موعدك غداً في {clinic}",
      qrReminderHours: 24,
      doctorName: "د. طاهر",
      launchAtLogin: false,
      startMinimized: false,
      wsPort: 8765,
      discoveryPort: 8766,
      mediaMaxWidth: 1280,
      mediaMaxHeight: 1024,
      displayTheme: "dark",
      controllerTheme: "dark",
      shortcuts: {...DEFAULT_SHORTCUTS},
      lastDisplayUrl: "",
      treatments: [],
      treatmentColumns: 3
    };
    this.data = this.load();
  }

  load() {
    try {
      if (!fs.existsSync(this.file)) return {...this.defaults};
      const loaded = JSON.parse(fs.readFileSync(this.file, "utf8"));
      return {
        ...this.defaults,
        ...loaded,
        shortcuts: {...DEFAULT_SHORTCUTS, ...(loaded.shortcuts || {})}
      };
    } catch {
      return {...this.defaults};
    }
  }
  all() { return {...this.data}; }
  get(key) { return this.data[key]; }
  patch(values) {
    const next = {...(values || {})};
    if (next.shortcuts) next.shortcuts = {...DEFAULT_SHORTCUTS, ...this.data.shortcuts, ...next.shortcuts};
    this.data = {...this.data, ...next};
    fs.mkdirSync(path.dirname(this.file), {recursive:true});
    fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2), "utf8");
  }
}
module.exports = {SettingsStore, DEFAULT_SHORTCUTS};
