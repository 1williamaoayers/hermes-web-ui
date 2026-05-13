#!/usr/bin/env python3
"""
Tiny mock Hermes Web UI server for CI emulator self-verification.

Serves a Vue-like SPA that exercises the same WebView code paths
as the real Hermes Web UI:
- JS execution (DOMContentLoaded handler)
- fetch() polling
- localStorage read/write
- Cookie roundtrip
- Event listeners on input elements

If the WebView in the APK can't render this, it can't render Hermes either.
"""
import http.server
import socketserver
import sys


PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8648


HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hermes</title>
<style>
  body{margin:0;font-family:system-ui,sans-serif;background:#0a0a0a;color:#fff}
  .app{display:flex;min-height:100vh}
  .sidebar{width:240px;background:#111;padding:16px;border-right:1px solid #333}
  .sidebar h2{font-size:14px;color:#888;margin:0 0 8px}
  .main{flex:1;display:flex;flex-direction:column}
  header{padding:12px 16px;border-bottom:1px solid #333;display:flex;align-items:center}
  header h1{font-size:18px;margin:0}
  .chat{flex:1;padding:16px;overflow-y:auto}
  .msg{margin:8px 0;padding:8px 12px;border-radius:12px;background:#222;max-width:70%}
  .msg.me{background:#ffd700;color:#000;margin-left:auto}
  .input{padding:12px 16px;border-top:1px solid #333;display:flex;gap:8px}
  input{flex:1;padding:10px;border-radius:8px;border:1px solid #444;background:#111;color:#fff}
  button{padding:10px 20px;border-radius:8px;border:0;background:#ffd700;color:#000;font-weight:bold}
</style>
</head>
<body>
<div class="app">
  <aside class="sidebar">
    <h2>CONVERSATION</h2>
    <div>Chat</div>
    <div>History</div>
    <div>Search</div>
  </aside>
  <div class="main">
    <header><h1>Hermes</h1></header>
    <div class="chat" id="chat">
      <div class="msg">你好！我是 Hermes Agent。</div>
    </div>
    <div class="input">
      <input id="input" placeholder="Type a message..." />
      <button id="send">Send</button>
    </div>
  </div>
</div>
<script>
(function(){
  try{
    // Exercise localStorage + Cookie roundtrip
    localStorage.setItem('hermes_test','1');
    document.cookie = 'hermes_session=verified; path=/';
    console.log('[Hermes] ready - storage ok');

    // Exercise fetch polling (same as real Hermes Socket.IO polling)
    fetch('/api/ping',{method:'GET'}).then(r=>r.text()).then(t=>{
      console.log('[Hermes] fetch ok:', t);
    }).catch(e=>{
      console.error('[Hermes] fetch failed:', e.message);
    });

    // Exercise input event listeners
    var send = document.getElementById('send');
    var input = document.getElementById('input');
    var chat = document.getElementById('chat');
    send.addEventListener('click', function(){
      var v = input.value.trim();
      if(!v) return;
      var me = document.createElement('div');
      me.className = 'msg me';
      me.textContent = v;
      chat.appendChild(me);
      input.value='';
    });

    // Mark page as fully initialized for CI scraper
    var marker = document.createElement('div');
    marker.id = 'hermes_ready';
    marker.style.display = 'none';
    marker.textContent = 'ready';
    document.body.appendChild(marker);

    // Simulate the sidebar "Sign Out" button for marker check
    var signOut = document.createElement('div');
    signOut.textContent = 'Sign Out';
    signOut.style.display = 'none';
    document.body.appendChild(signOut);
  }catch(e){
    console.error('[Hermes] init error:', e.message);
  }
})();
</script>
</body>
</html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        sys.stderr.write("[mock-hermes] " + (format % args) + "\n")

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/#"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            body = HTML.encode("utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith("/api/ping"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Access-Control-Allow-Origin", "*")
            body = b"pong"
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            body = b'{"status":"ok"}'
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", PORT), Handler) as srv:
        print(f"mock-hermes listening on 0.0.0.0:{PORT}", flush=True)
        srv.serve_forever()
