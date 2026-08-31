/**
 * Records a walkthrough of the Ossian console as a frame sequence.
 *
 * Frames are captured on a loop that runs alongside the storyboard rather than between its steps,
 * so the streaming answer and the page transitions are caught as they happen. Capturing only at
 * step boundaries would produce a slideshow of the same stills already in the README, which is
 * the thing a video is meant to improve on.
 */
import { writeFileSync, mkdirSync, rmSync } from "node:fs";
import { spawn } from "node:child_process";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 9444;
const OUT = process.argv[2];
const W = 1280, H = 800;

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

const chrome = spawn(CHROME, [
  "--headless=new", `--remote-debugging-port=${PORT}`, `--window-size=${W},${H}`,
  "--hide-scrollbars", "--disable-gpu", "--no-first-run",
  `--user-data-dir=/tmp/ossian-rec-${Date.now()}`, "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function target() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find((t) => t.type === "page");
      if (page) return page;
    } catch { /* not up */ }
    await sleep(400);
  }
  throw new Error("no debugging target");
}

const page = await target();
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res) => (ws.onopen = res));
let id = 0;
const pending = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
const send = (method, params = {}) =>
  new Promise((res) => { const n = ++id; pending.set(n, res); ws.send(JSON.stringify({ id: n, method, params })); });
const evaluate = async (expression) =>
  (await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true }))
    .result?.result?.value;

await send("Page.enable");
await send("Runtime.enable");
await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 1, mobile: false });

const goto = async (url, settle = 2000) => { await send("Page.navigate", { url }); await sleep(settle); };

// --- the capture loop ---
let frame = 0;
let recording = false;
async function pump() {
  while (recording) {
    const started = Date.now();
    try {
      const r = await send("Page.captureScreenshot", { format: "png" });
      if (r.result?.data) {
        writeFileSync(`${OUT}/f${String(frame++).padStart(5, "0")}.png`, Buffer.from(r.result.data, "base64"));
      }
    } catch { /* a frame lost to a navigation is not worth failing over */ }
    // ~5 fps, minus however long the capture itself took.
    await sleep(Math.max(0, 200 - (Date.now() - started)));
  }
}

// --- sign in before recording, so no credential is ever on screen ---
await goto("http://localhost:5173/", 1500);
await evaluate(`(async () => {
  localStorage.setItem('ossian.theme','light');
  localStorage.setItem('ossian.accent','indigo');
  const body = new URLSearchParams({ client_id:"ossian-frontend", grant_type:"password",
    scope:"openid profile email", username:"admin", password:"admin" });
  const r = await fetch("http://localhost:8180/realms/ossian/protocol/openid-connect/token",
    { method:"POST", headers:{"Content-Type":"application/x-www-form-urlencoded"}, body });
  const t = await r.json();
  const claims = j => JSON.parse(atob(j.split(".")[1].replace(/-/g,"+").replace(/_/g,"/")));
  sessionStorage.setItem("oidc.user:http://localhost:8180/realms/ossian:ossian-frontend", JSON.stringify({
    id_token:t.id_token, session_state:t.session_state, access_token:t.access_token,
    refresh_token:t.refresh_token, token_type:t.token_type, scope:t.scope,
    profile:claims(t.id_token), expires_at:Math.floor(Date.now()/1000)+t.expires_in }));
  return true;
})()`);

const type = (selector, value, isTextarea) => evaluate(`(() => {
  const el = document.querySelector('${selector}');
  const proto = ${isTextarea ? "window.HTMLTextAreaElement" : "window.HTMLInputElement"}.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`);

await goto("http://localhost:5173/notebook", 3000);
recording = true;
const loop = pump();

await sleep(1600);

// 1. Ask, and let the answer stream in.
await type(".composer textarea", "Can we deploy to production on a Friday?", true);
await sleep(900);
await evaluate(`document.querySelector('.composer button.primary').click(); true`);
await evaluate(`(async () => {
  for (let i = 0; i < 90 && !document.querySelector('.cite-chip'); i++) await new Promise(r => setTimeout(r, 500));
  return true;
})()`);
await sleep(1800);

// 2. Open the passage the answer came from.
await evaluate(`(() => { const c = document.querySelector('.cite-chip'); if (c) c.click(); return true; })()`);
await sleep(3200);
await evaluate(`(() => { const b = [...document.querySelectorAll('.citation-pane .link')].find(x => x.textContent.trim() === 'close'); if (b) b.click(); return true; })()`);
await sleep(800);

// 3. Ask something the corpus cannot answer.
await type(".composer textarea", "Who won the 1998 World Cup?", true);
await sleep(900);
await evaluate(`document.querySelector('.composer button.primary').click(); true`);
await sleep(6000);

// 4. What the retriever sees.
await goto("http://localhost:5173/vectors", 4500);
await sleep(2200);

// 5. Memory, with a recall.
await goto("http://localhost:5173/memory", 3000);
await evaluate(`(() => { const t = [...document.querySelectorAll('.agent-tabs button')].find(b => b.textContent.includes('support')); if (t) t.click(); return true; })()`);
await sleep(1400);
await type(".ask input", "how should I write to Ankit?", false);
await sleep(800);
await evaluate(`document.querySelector('.ask button.primary').click(); true`);
await sleep(4500);

// 6. Operations.
await goto("http://localhost:5173/admin", 4500);
await sleep(2600);

recording = false;
await loop;
console.log(`  captured ${frame} frames`);
ws.close();
chrome.kill();
process.exit(0);
