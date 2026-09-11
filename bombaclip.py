#!/usr/bin/env python3
"""bombaclip - a tiny LAN clipboard bridge for your PC and Android phone.

One plain-HTTP server holds a live copy of the PC clipboard (text or image).
The native Android app (in this repo's android/ dir) polls it and mirrors
changes into the phone clipboard; share from any app -> bombaclip to send the
other way. The web page is just a from-any-browser read-only view.

Requires: wl-copy/wl-paste (or xclip) on the PC. No Python deps. No CA, no
TLS, no install step -- run it on LAN, point the app at http://<pc-ip>:<port>.
"""

import argparse
import hashlib
import os
import socket
import subprocess
import sys
import threading
from email.parser import BytesParser
from email.policy import default as email_default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

POLL_MS = 1500

# ------------------------------------------------------------ clipboard

class Clipboard:
    def __init__(self, copy_cmd, paste_cmd):
        self.copy_cmd = copy_cmd       # e.g. ["wl-copy"]
        self.paste_cmd = paste_cmd     # e.g. ["wl-paste", "-n"]
        self.lock = threading.Lock()
        self.text = ""
        self.image = b""
        self.ct = "image/png"
        self.kind = "text"
        self.hash = hashlib.sha1(b"").hexdigest()

    def _image_types(self):
        try:
            r = subprocess.run(["timeout", "1", "wl-paste", "--list-types"],
                               capture_output=True)
        except (OSError, subprocess.TimeoutExpired):
            return []
        if r.returncode != 0:
            return []
        return [t.strip() for t in r.stdout.decode("utf-8", "replace").splitlines()
                if t.strip().startswith("image/")]

    def _read_text(self):
        try:
            buf = subprocess.run(["timeout", "1", *self.paste_cmd],
                                 capture_output=True).stdout or b""
        except (OSError, subprocess.TimeoutExpired):
            buf = b""
        try:
            return buf.decode("utf-8")
        except UnicodeDecodeError:
            return ""

    def read_once(self):
        """Return (state_json, hash, changed). changed is False when the PC
        clipboard hasn't moved since the last read, so the phone can skip the
        copy. state has {"kind","h"} plus "text" or "len"/"ct"."""
        img_types = self._image_types()
        changed = False
        if img_types:
            ct = "image/png" if "image/png" in img_types else img_types[0]
            buf = subprocess.run(
                ["timeout", "1", *self.paste_cmd, "-t", ct],
                capture_output=True).stdout or b""
            h = hashlib.sha1(buf).hexdigest()
            if h != self.hash or self.kind != "image" or self.image != buf:
                self.image, self.ct, self.kind, self.hash = buf, ct, "image", h
                changed = True
            state = {"kind": "image", "ct": ct, "h": h, "len": len(buf)}
        else:
            t = self._read_text()
            h = hashlib.sha1(t.encode()).hexdigest()
            if h != self.hash or self.kind != "text" or self.text != t:
                self.text, self.kind, self.hash = t, "text", h
                changed = True
            state = {"kind": "text", "ct": "text/plain", "h": h, "text": t}
        return state, self.hash, changed

    def write_text(self, text):
        try:
            subprocess.run(self.copy_cmd, input=text.encode("utf-8"),
                           stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=3)
        except (OSError, subprocess.TimeoutExpired):
            return False
        self.text, self.kind, self.hash = text, "text", \
            hashlib.sha1(text.encode()).hexdigest()
        return True

    def write_image(self, data, ct):
        try:
            subprocess.run([*self.copy_cmd, "-t", ct], input=data,
                           stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=3)
        except (OSError, subprocess.TimeoutExpired):
            return False
        self.image, self.ct, self.kind = data, ct, "image"
        self.hash = hashlib.sha1(data).hexdigest()
        return True

# ------------------------------------------------------------ http

def make_handler(cb, log_requests=False, token=""):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "bombaclip/1.2"

        def _authed(self):
            if not token:
                return True
            return self.headers.get("Authorization") == "Bearer " + token

        def log_message(self, fmt, *a):
            if log_requests:
                print(f"  {self.address_string()} {fmt % a}")

        def _send(self, code, body, ctype="application/octet-stream"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self._authed():
                return self._send(401, b"unauthorized", "text/plain")
            p = self.path.split("?")[0]
            if p in ("/", "/index.html"):
                return self._send(200, PAGE.encode("utf-8"),
                                  "text/html; charset=utf-8")
            if p == "/clipboard":
                since = ""
                try:
                    since = self.path.split("since=")[1].split("&")[0]
                except IndexError:
                    pass
                state, h, changed = cb.read_once()
                if not changed and since == h:
                    return self._send(204, b"")
                state["h"] = h
                return self._send(200, json(state))
            if p == "/image":
                return self._send(200, cb.image if cb.kind == "image" else b"")
            return self._send(404, b"not found", "text/plain")

        def do_POST(self):
            if not self._authed():
                return self._send(401, b"unauthorized", "text/plain")
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n)
            ct = (self.headers.get("Content-Type", "text/plain") or "") \
                .split(";")[0].strip().lower()
            if ct.startswith("image/"):
                ok = cb.write_image(body, ct)
            else:
                try:
                    ok = cb.write_text(body.decode("utf-8", "replace"))
                except Exception:
                    ok = False
            self._send(200 if ok else 500, b"ok" if ok else b"failed",
                       "text/plain")

        def do_OPTIONS(self):
            self._send(200, b"", "text/plain")

    return Handler


def json(state):
    import json as _json
    return _json.dumps(state, ensure_ascii=False).encode("utf-8")


# ------------------------------------------------------------ page

PAGE = """<!doctype html><html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>bombaclip</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#101216;color:#e6e6e6;font:16px/1.5 system-ui,sans-serif}
main{max-width:40rem;margin:0 auto;padding:1.5rem}
h1{font-size:1.3rem;margin:0 0 .5rem}
p.hint{font-size:.85rem;opacity:.8;margin:0 0 1rem}
label{display:block;font-size:.85rem;opacity:.7;margin:1rem 0 .3rem}
textarea{width:100%;min-height:8rem;background:#171a20;color:#e6e6e6;
 border:1px solid #2a2f38;border-radius:.5rem;padding:.6rem;font:inherit;resize:vertical}
textarea[readonly]{opacity:.85}
img{max-width:100%;max-height:65vh;object-fit:contain;background:#0a0b0e;
 border:1px solid #2a2f38;border-radius:.5rem;display:none}
button{background:#3b82f6;color:#fff;border:0;border-radius:.5rem;
 padding:.6rem 1.2rem;font:inherit;cursor:pointer}
#status{font-size:.8rem;opacity:.6;margin-top:.6rem}
</style></head><body><main>
<h1>bombaclip</h1>
<p class="hint">Live view of your PC clipboard. Use the Android app
(<code>dev.bombaclip</code>) to sync both ways; this page is read-only.</p>
<label>PC clipboard (live)</label>
<textarea id="from" readonly placeholder="(empty / non-text)"></textarea>
<img id="fromimg" alt="">
<p id="status">connecting...</p>
</main>
<script>
const from=document.getElementById('from'),fromImg=document.getElementById('fromimg'),
 status=document.getElementById('status');
let last=-1,cur=null;
function setStatus(s){status.textContent=s}
async function poll(){
 try{
  const r=await fetch('/clipboard?since='+last,{cache:'no-store'});
  if(r.status===204){return setStatus('live')}
  const j=await r.json();last=j.h;cur=j;
  if(j.kind==='image'){fromImg.style.display='block';from.value='';
   fromImg.src='/image'}
  else{fromImg.style.display='none';from.value=j.text||''}
  setStatus('live')
 }catch(e){setStatus('offline')}
}
poll();setInterval(poll,2000);
</script></body></html>
"""


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-p", "--port", type=int, default=8080)
    ap.add_argument("-H", "--host", default="0.0.0.0")
    ap.add_argument("--copy", default="wl-copy",
                    help="clipboard write command (default wl-copy)")
    ap.add_argument("--paste", default="wl-paste -n",
                    help="clipboard read command (default 'wl-paste -n')")
    ap.add_argument("--log", action="store_true",
                    help="print one line per incoming request")
    ap.add_argument("--token", default=os.environ.get("BOMBACLIP_TOKEN", ""),
                    help="optional shared secret; app must send it as Bearer")
    args = ap.parse_args()

    cb = Clipboard(args.copy.split(), args.paste.split())
    server = ThreadingHTTPServer(
        (args.host, args.port), make_handler(cb, args.log, args.token))
    ip = lan_ip()
    print(f"bombaclip: http://{ip}:{args.port}")
    print("phone app: set this URL, tap Start. This page is just a view.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
