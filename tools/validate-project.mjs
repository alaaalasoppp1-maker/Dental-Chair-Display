import assert from "node:assert/strict";
import fs from "node:fs";

const read = file => fs.readFileSync(file, "utf8");
const required = [
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/com/dentalchain/display/MainActivity.kt",
  "app/src/main/java/com/dentalchain/display/BootReceiver.kt",
  ".github/workflows/build.yml"
];
required.forEach(file => assert.ok(fs.existsSync(file), `Missing ${file}`));

const gradle = read("app/build.gradle.kts");
const source = read("app/src/main/java/com/dentalchain/display/MainActivity.kt");
const manifest = read("app/src/main/AndroidManifest.xml");
const bootReceiver = read("app/src/main/java/com/dentalchain/display/BootReceiver.kt");
const workflow = read(".github/workflows/build.yml");

assert.match(gradle, /versionName = "5\.7\.0"/);
assert.match(gradle, /versionCode = 570/);
for (const token of [
  "/display/presence", "/display/ack", "displayDeviceId", "lastDisplaySequence",
  "handledCommandIds", "http://127.0.0.1:8765", "prefetchPlanMedia",
  "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_DOWN"
]) assert.ok(source.includes(token), `Missing Display contract token ${token}`);
assert.ok(source.includes("* 2 + 2"), "Treatment plan final green-result and inventory screens are missing");
assert.match(manifest, /RECEIVE_BOOT_COMPLETED/);
assert.match(manifest, /\.BootReceiver/);
for (const token of [
  "Intent.ACTION_BOOT_COMPLETED",
  "Intent.ACTION_LOCKED_BOOT_COMPLETED",
  "android.intent.action.QUICKBOOT_POWERON",
  "Intent.FLAG_ACTIVITY_NEW_TASK",
  "MainActivity::class.java"
]) assert.ok(bootReceiver.includes(token), `Missing boot receiver token ${token}`);
assert.match(workflow, /DTDC_DISPLAY_v5\.7\.0_DEBUG\.apk/);

console.log("DTDC Display 5.7.0 contract validation passed");
