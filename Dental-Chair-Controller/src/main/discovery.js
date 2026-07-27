"use strict";
const dgram = require("dgram");
const os = require("os");

function getLocalIPv4() {
  const addresses = [];
  let interfaces = {};
  try { interfaces = os.networkInterfaces() || {}; } catch { interfaces = {}; }
  for (const group of Object.values(interfaces)) {
    for (const item of group || []) {
      if (item.family === "IPv4" && !item.internal) addresses.push(item.address);
    }
  }
  return addresses;
}

class DiscoveryBroadcaster {
  constructor({port, wsPort, clinicName, onNotice}) {
    this.port = port;
    this.wsPort = wsPort;
    this.clinicName = clinicName;
    this.onNotice = onNotice || (()=>{});
    this.socket = null;
    this.timer = null;
  }

  start() {
    this.stop();
    this.socket = dgram.createSocket("udp4");
    this.socket.bind(() => {
      this.socket.setBroadcast(true);
      this.broadcast();
      this.timer = setInterval(() => this.broadcast(), 2000);
      this.onNotice("الاكتشاف التلقائي يعمل", "success");
    });
    this.socket.on("error", err => this.onNotice(`خطأ الاكتشاف: ${err.message}`, "error"));
  }

  broadcast() {
    if (!this.socket) return;
    for (const ip of getLocalIPv4()) {
      const parts = ip.split(".");
      if (parts.length !== 4) continue;
      const broadcast = `${parts[0]}.${parts[1]}.${parts[2]}.255`;
      const payload = Buffer.from(JSON.stringify({
        product: "DentalChairController",
        protocol: 3,
        ip,
        wsPort: this.wsPort,
        clinicName: this.clinicName,
        sentAt: Date.now()
      }));
      this.socket.send(payload, 0, payload.length, this.port, broadcast, ()=>{});
    }
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (this.socket) {
      try { this.socket.close(); } catch {}
    }
    this.socket = null;
  }
}

module.exports = {DiscoveryBroadcaster, getLocalIPv4};
