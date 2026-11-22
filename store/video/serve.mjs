// Petit serveur local pour la vidéo de présentation :
//   node store/video/serve.mjs   puis   http://localhost:5178/store/video/promo.html
// Sert le dépôt et enregistre les MP4 exportés dans store/video/out/.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const outDir = path.join(here, "out");
const PORT = 5178;
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".png": "image/png", ".jpg": "image/jpeg", ".mp4": "video/mp4", ".css": "text/css", ".md": "text/plain; charset=utf-8" };

http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (req.method === "POST" && url.pathname === "/save") {
    const name = path.basename(url.searchParams.get("name") || "");
    if (!/^[\w.-]+\.mp4$/.test(name)) { res.writeHead(400).end("bad name"); return; }
    fs.mkdirSync(outDir, { recursive: true });
    const file = fs.createWriteStream(path.join(outDir, name));
    req.pipe(file);
    file.on("finish", () => { console.log("saved", name); res.writeHead(200).end("ok"); });
    file.on("error", e => res.writeHead(500).end(String(e)));
    return;
  }
  const p = path.join(root, decodeURIComponent(url.pathname));
  if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404).end("not found"); return; }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(p)] || "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(p).pipe(res);
}).listen(PORT, () => console.log(`http://localhost:${PORT}/store/video/promo.html`));
