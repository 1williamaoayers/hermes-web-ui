// Socket.IO mock Hermes server for CI self-verification.
// Emulates the chat-run-socket flow from hermes-web-ui:
//   - client connects to /chat-run namespace
//   - client emits "user_message" with text
//   - server streams back response.output_text.delta events
//   - frontend must display the assembled message

const http = require("http");
const { Server } = require("socket.io");

const PORT = process.env.PORT || 8648;

const HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hermes</title>
<script src="/socket.io/socket.io.js"></script>
<style>
  body{margin:0;font-family:system-ui,sans-serif;background:#0a0a0a;color:#fff}
  .app{display:flex;min-height:100vh}
  .sidebar{width:240px;background:#111;padding:16px;border-right:1px solid #333}
  .sidebar h2{font-size:14px;color:#888;margin:0 0 8px}
  .main{flex:1;display:flex;flex-direction:column}
  header{padding:12px 16px;border-bottom:1px solid #333;display:flex;align-items:center;justify-content:space-between}
  header h1{font-size:18px;margin:0}
  #status{font-size:12px;color:#888}
  #status.ok{color:#4ade80}
  .chat{flex:1;padding:16px;overflow-y:auto}
  .msg{margin:8px 0;padding:8px 12px;border-radius:12px;background:#222;max-width:70%}
  .msg.me{background:#ffd700;color:#000;margin-left:auto}
  .msg.bot{background:#222;color:#fff}
  .input{padding:12px 16px;border-top:1px solid #333;display:flex;gap:8px}
  input{flex:1;padding:10px;border-radius:8px;border:1px solid #444;background:#111;color:#fff}
  button{padding:10px 20px;border-radius:8px;border:0;background:#ffd700;color:#000;font-weight:bold}
  .hidden{display:none}
</style>
</head>
<body>
<div class="app">
  <aside class="sidebar">
    <h2>CONVERSATION</h2>
    <div>Chat</div>
    <div>History</div>
    <div>Search</div>
    <div style="margin-top:24px">Sign Out</div>
  </aside>
  <div class="main">
    <header>
      <h1>Hermes</h1>
      <span id="status">Disconnected</span>
    </header>
    <div class="chat" id="chat"></div>
    <div class="input">
      <input id="input" placeholder="Type a message..." />
      <button id="send">Send</button>
    </div>
  </div>
</div>

<div id="chat_ready_marker" class="hidden">CHAT_READY</div>
<div id="reply_ready_marker" class="hidden"></div>

<script>
(function(){
  const chat = document.getElementById('chat');
  const input = document.getElementById('input');
  const send = document.getElementById('send');
  const status = document.getElementById('status');

  function appendMsg(text, me) {
    const d = document.createElement('div');
    d.className = 'msg ' + (me ? 'me' : 'bot');
    d.textContent = text;
    chat.appendChild(d);
    chat.scrollTop = chat.scrollHeight;
    return d;
  }

  // Seed a greeting so the verifier can confirm initial render
  appendMsg('你好！我是 Hermes Agent，很高兴为你服务。', false);

  // localStorage + cookie round-trip
  try {
    localStorage.setItem('hermes_test', 'v2');
    document.cookie = 'hermes_session=verified; path=/';
    console.log('[Hermes] storage ok');
  } catch (e) {
    console.error('[Hermes] storage failed:', e.message);
  }

  // Connect Socket.IO (this is what was broken in WebView before)
  const socket = io('/chat-run', { transports: ['polling', 'websocket'] });

  socket.on('connect', () => {
    status.textContent = 'Connected';
    status.className = 'ok';
    console.log('[Hermes] socket connected id=', socket.id);
    document.getElementById('chat_ready_marker').textContent = 'CHAT_READY';
  });
  socket.on('disconnect', () => {
    status.textContent = 'Disconnected';
    status.className = '';
    console.log('[Hermes] socket disconnected');
  });
  socket.on('connect_error', (e) => {
    console.error('[Hermes] connect_error:', e && e.message);
  });

  let streamingMsg = null;
  let streamingBuffer = '';

  socket.on('response.output_text.delta', (payload) => {
    if (!streamingMsg) {
      streamingMsg = appendMsg('', false);
      streamingBuffer = '';
    }
    streamingBuffer += payload.delta || '';
    streamingMsg.textContent = streamingBuffer;
  });

  socket.on('response.completed', () => {
    console.log('[Hermes] response completed:', streamingBuffer);
    document.getElementById('reply_ready_marker').textContent = streamingBuffer || 'REPLY_EMPTY';
    streamingMsg = null;
    streamingBuffer = '';
  });

  function sendMessage() {
    const v = input.value.trim();
    if (!v) return;
    appendMsg(v, true);
    input.value = '';
    socket.emit('user_message', { text: v });
  }

  send.addEventListener('click', sendMessage);
  input.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') sendMessage();
  });

  // Auto-send a test message on page load (for CI self-test)
  if (new URLSearchParams(location.search).get('autotest') === '1') {
    setTimeout(() => {
      input.value = '你好';
      sendMessage();
    }, 500);
  }
})();
</script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  console.log(`[mock-hermes] ${req.method} ${req.url}`);
  if (req.url === "/" || req.url.startsWith("/#") || req.url.startsWith("/?")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(HTML);
  } else if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end('{"status":"ok"}');
  } else {
    res.writeHead(404);
    res.end();
  }
});

const io = new Server(server, {
  path: "/socket.io",
  cors: { origin: "*" },
});

const chatNs = io.of("/chat-run");

chatNs.on("connection", (socket) => {
  console.log(`[socket.io] client connected id=${socket.id}`);

  socket.on("user_message", async (payload) => {
    const text = (payload && payload.text) || "(empty)";
    console.log(`[socket.io] got user_message: ${text}`);

    // Stream a reply char by char, just like real Hermes
    const reply = `收到你的消息「${text}」，这是来自模拟 Hermes 的回复。`;
    socket.emit("response.created", {});
    for (const ch of reply) {
      socket.emit("response.output_text.delta", { delta: ch });
      await new Promise((r) => setTimeout(r, 15));
    }
    socket.emit("response.completed", { text: reply });
    console.log(`[socket.io] streamed reply complete`);
  });

  socket.on("disconnect", () => {
    console.log(`[socket.io] client disconnected id=${socket.id}`);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`mock-hermes (socket.io) listening on 0.0.0.0:${PORT}`);
});
