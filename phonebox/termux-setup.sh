#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════
#  phonebox — گوشی تو → سرور شخصی با تونل رایگان
#  کل این فایل را در Termux پیست کن و Enter بزن
# ═══════════════════════════════════════════════
set -e
echo "📦 نصب پیش‌نیازها (چند دقیقه صبر کن)…"
yes 2>/dev/null | pkg update >/dev/null 2>&1 || true
yes 2>/dev/null | pkg install python cloudflared >/dev/null 2>&1 || true
command -v cloudflared >/dev/null 2>&1 || { echo "❌ cloudflared نصب نشد؛ این را اجرا کن: pkg install cloudflared"; exit 1; }
command -v python  >/dev/null 2>&1 || { echo "❌ python نصب نشد؛ این را اجرا کن: pkg install python"; exit 1; }
mkdir -p ~/sandb0x && cd ~/sandb0x
cat > server.py <<'PYEOF'
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# phonebox — گوشی تو به یک سرور شخصی تبدیل می‌شود
# فقط روی 127.0.0.1 گوش می‌دهد و از طریق تونل cloudflared بیرون می‌آید
import json
import os
import secrets
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE = Path(__file__).resolve().parent
BOX = BASE / "box"
BOX.mkdir(exist_ok=True)
TOKEN_FILE = BASE / "token"
TOKEN = TOKEN_FILE.read_text().strip() if TOKEN_FILE.exists() else ""
PORT = 8022
MAX_BODY = 2 * 1024 ** 3   # سقف آپلود فایل: ۲ گیگابایت
EXEC_TIMEOUT = 280         # سقف زمان اجرای دستور: ۲۸۰ ثانیه
SH_BIN = "/system/bin/sh" if os.path.exists("/system/bin/sh") else "/bin/sh"


def sh(*args, **kw):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=10, **kw).stdout.strip()
    except Exception:
        return ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ---------- ابزار ----------
    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        t = self.headers.get("Authorization", "").replace("Bearer", "").strip()
        if not TOKEN or not secrets.compare_digest(t, TOKEN):
            self._json(403, {"ok": False, "error": "bad token"})
            return False
        return True

    def log_message(self, fmt, *args):
        pass  # لاگ نگیر

    def _doc_path(self):
        # فقط نام فایل؛ ضد path-traversal
        name = os.path.basename(self.path.split("?", 1)[0].rstrip("/")[6:])
        return name, (BOX / name if name else None)

    # ---------- GET ----------
    def do_GET(self):
        if not self._authed():
            return
        p = self.path.split("?", 1)[0].rstrip("/")

        if p == "/ping":
            return self._json(200, {"ok": True, "time": time.time()})

        if p == "/info":
            mem = {}
            try:
                for line in Path("/proc/meminfo").read_text().splitlines():
                    k, v = line.split(":", 1)
                    mem[k] = int(v.strip().split()[0]) * 1024
            except Exception:
                pass
            st = os.statvfs(BASE)
            info = {
                "ok": True,
                "ram_total": mem.get("MemTotal", 0),
                "ram_available": mem.get("MemAvailable", 0),
                "storage_total": st.f_blocks * st.f_frsize,
                "storage_free": st.f_bavail * st.f_frsize,
                "cpu_cores": os.cpu_count(),
                "machine": os.uname().machine,
                "android": sh("/system/bin/getprop", "ro.build.version.release"),
                "model": sh("/system/bin/getprop", "ro.product.model"),
                "box_used": sum(f.stat().st_size for f in BOX.iterdir() if f.is_file()),
                "box_files": len([f for f in BOX.iterdir() if f.is_file()]),
                "uptime_s": int(time.time()),
            }
            return self._json(200, info)

        if p == "/storage":
            for cand in ("/storage/emulated/0", "/sdcard"):
                if os.path.isdir(cand):
                    st = os.statvfs(cand)
                    return self._json(200, {"path": cand,
                                            "free": st.f_bavail * st.f_frsize,
                                            "total": st.f_blocks * st.f_frsize})
            return self._json(404, {"error": "sdcard not visible (termux-setup-storage)"})

        if p == "/docs":
            items = []
            for f in sorted(BOX.iterdir(), key=lambda x: -x.stat().st_mtime):
                if f.is_file():
                    items.append({"name": f.name, "size": f.stat().st_size,
                                  "mtime": int(f.stat().st_mtime)})
            return self._json(200, items)

        if p.startswith("/docs/"):
            name, f = self._doc_path()
            if not f or not f.is_file():
                return self._json(404, {"error": "not found"})
            body = f.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self._json(404, {"error": "unknown path"})

    # ---------- POST ----------
    def do_POST(self):
        if not self._authed():
            return
        p = self.path.split("?", 1)[0].rstrip("/")
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = 0

        if p == "/exec":
            raw = self.rfile.read(n).decode("utf-8", "replace") if n else "{}"
            try:
                cmd = json.loads(raw).get("cmd", "")
            except Exception:
                return self._json(400, {"error": "invalid json"})
            if not cmd:
                return self._json(400, {"error": "empty cmd"})
            try:
                r = subprocess.run([SH_BIN, "-c", cmd],
                                   capture_output=True, text=True,
                                   timeout=EXEC_TIMEOUT, cwd=str(BASE))
                return self._json(200, {"exit": r.returncode,
                                        "stdout": r.stdout[-200000:],
                                        "stderr": r.stderr[-200000:]})
            except subprocess.TimeoutExpired:
                return self._json(200, {"exit": -1, "stdout": "",
                                        "stderr": "timeout after %ss" % EXEC_TIMEOUT})
            except Exception as e:
                return self._json(200, {"exit": -2, "stdout": "",
                                        "stderr": str(e)})

        if p == "/py":
            code = self.rfile.read(n).decode("utf-8", "replace") if n else ""
            if not code.strip():
                return self._json(400, {"error": "empty code"})
            try:
                r = subprocess.run(["python3", "-"], input=code,
                                   capture_output=True, text=True,
                                   timeout=EXEC_TIMEOUT, cwd=str(BASE))
                return self._json(200, {"exit": r.returncode,
                                        "stdout": r.stdout[-200000:],
                                        "stderr": r.stderr[-200000:]})
            except subprocess.TimeoutExpired:
                return self._json(200, {"exit": -1, "stdout": "",
                                        "stderr": "timeout"})
            except Exception as e:
                return self._json(200, {"exit": -2, "stdout": "", "stderr": str(e)})

        if p.startswith("/docs/"):
            name, f = self._doc_path()
            if not name:
                return self._json(400, {"error": "bad name"})
            remaining = min(n, MAX_BODY)
            with open(BOX / name, "wb") as out:
                while remaining > 0:
                    chunk = self.rfile.read(min(512 * 1024, remaining))
                    if not chunk:
                        break
                    out.write(chunk)
                    remaining -= len(chunk)
            size = (BOX / name).stat().st_size
            return self._json(200, {"ok": True, "name": name, "size": size})

        # بدنه‌ی ناشناخته را بخور تا اتصال هنگ نکند
        if n:
            self.rfile.read(min(n, 1024 * 1024))
        self._json(404, {"error": "unknown path"})

    # ---------- DELETE ----------
    def do_DELETE(self):
        if not self._authed():
            return
        name, f = self._doc_path()
        if f and f.is_file():
            f.unlink()
            return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})


if __name__ == "__main__":
    if not TOKEN:
        TOKEN = secrets.token_urlsafe(21)
        TOKEN_FILE.write_text(TOKEN + "\n")
        print("token generated")
    print("phonebox listening on 127.0.0.1:%d" % PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
PYEOF
[ -s token ] || python3 -c "import secrets; print(secrets.token_urlsafe(21))" > token
termux-wake-lock 2>/dev/null || true
pkill -f "python.*server\.py" 2>/dev/null || true
pkill -f "cloudflared.*8022"   2>/dev/null || true
sleep 1
nohup python server.py > server.log 2>&1 &
sleep 2
echo "🌐 در حال ساختن تونل رایگان کلادفلر…"
nohup cloudflared tunnel --url http://127.0.0.1:8022 --no-autoupdate > tunnel.log 2>&1 &
URL=""
for i in $(seq 1 40); do
  URL=$(grep -Eo "https://[a-z0-9-]+\.trycloudflare\.com" tunnel.log | head -1)
  [ -n "$URL" ] && break
  sleep 1
done
if [ -z "$URL" ]; then
  echo "❌ تونل ساخته نشد؛ پنج خط آخر لاگ:"
  tail -5 tunnel.log || true
  exit 1
fi
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "✅ آدرس API : $URL"
echo "🔑 توکن     : $(cat token)"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "▶ این دو خط را عیناً برای ایجنت بفرست."
echo "▶ توقف هر وقت خواستی:  pkill cloudflared ; pkill -f server.py"
echo "▶ این پنجره را نبند؛ Termux باز بماند."
