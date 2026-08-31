/**
 * Screenshots the Ossian console over the Chrome DevTools Protocol.
 *
 * Headless Chrome's --screenshot flag cannot seed a session or wait for an answer, and both are
 * needed here: the console is behind OIDC, and the shot that matters is the one taken after a
 * question has been answered. Driving CDP directly gives navigation, evaluation and capture in
 * one place.
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { spawn } from "node:child_process";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 9333;
const OUT = process.argv[2];
const W = 1440, H = 900;

mkdirSync(OUT, { recursive: true });

const chrome = spawn(CHROME, [
  "--headless=new", `--remote-debugging-port=${PORT}`, `--window-size=${W},${H}`,
  "--hide-scrollbars", "--disable-gpu", "--no-first-run",
  `--user-data-dir=/tmp/ossian-shots-${Date.now()}`, "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function targets() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await r.json();
      const page = list.find((t) => t.type === "page");
      if (page) return page;
    } catch { /* not up yet */ }
    await sleep(400);
  }
  throw new Error("Chrome did not expose a debugging target");
}

const page = await targets();
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

const evaluate = async (expression) => {
  const r = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  return r.result?.result?.value;
};

await send("Page.enable");
await send("Runtime.enable");
// Deterministic size regardless of the host's display.
await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 2, mobile: false });

async function goto(url, settle = 2500) {
  await send("Page.navigate", { url });
  await sleep(settle);
}

async function shot(name) {
  const r = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(r.result.data, "base64"));
  console.log(`  ${name}.png`);
}

// Same origin as the app, so storage written here is the storage the app reads.
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
  return !!t.access_token;
})()`);


// --- the tour ---

await goto("http://localhost:5173/notebook", 3500);
await evaluate(`(async () => {
  const ta = document.querySelector('.composer textarea');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  setter.call(ta, 'Can we deploy to production on a Friday?');
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  await new Promise(r => setTimeout(r, 300));
  document.querySelector('.composer button.primary').click();
  // Citation chips appear only once the answer resolves, so they are the honest signal that the
  // page is worth capturing. A fixed sleep would photograph a spinner on a slow model.
  for (let i = 0; i < 120 && !document.querySelector('.cite-chip'); i++) {
    await new Promise(r => setTimeout(r, 1000));
  }
  return !!document.querySelector('.cite-chip');
})()`);
await sleep(800);
await shot("01-notebook");

await goto("http://localhost:5173/vectors", 5000);
await shot("02-vectors");

await goto("http://localhost:5173/memory", 3500);
await evaluate(`(async () => {
  const tabs = [...document.querySelectorAll('.agent-tabs button')];
  const support = tabs.find(b => b.textContent.includes('support')) || tabs[0];
  if (support) support.click();
  await new Promise(r => setTimeout(r, 1200));
  const input = document.querySelector('.ask input');
  if (!input) return false;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(input, 'how should I write to Ankit?');
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await new Promise(r => setTimeout(r, 300));
  document.querySelector('.ask button.primary').click();
  await new Promise(r => setTimeout(r, 5000));
  return true;
})()`);
await sleep(800);
await shot("03-memory");

await goto("http://localhost:5173/admin", 4500);
await shot("04-console");

await goto("http://localhost:5173/settings", 3000);
await shot("05-settings");

await goto("http://localhost:5173/events", 3000);
await shot("06-imports");

await evaluate("sessionStorage.clear(); true");
await goto("http://localhost:5173/", 3000);
await shot("07-landing");

ws.close();
chrome.kill();
process.exit(0);
