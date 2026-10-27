from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.cookiejar, json, os, secrets, socket, subprocess, sys, threading, time
import urllib.request, urllib.error, urllib.parse

ROOT = Path(r'F:\JAVA__PROJECT\talk-to-textbook')
RUNTIME = Path(r'F:\JAVA__PROJECT\talk-to-textbook\target\browser-fixture')
if RUNTIME.exists():
    import shutil; shutil.rmtree(RUNTIME)
(RUNTIME / 'data/memory').mkdir(parents=True)
(RUNTIME / 'data/memory/core.md').write_text('SYNTHETIC_OLD_PROFILE', encoding='utf-8')
(RUNTIME / 'knowledge').mkdir()
(RUNTIME / 'knowledge/base.json').write_text('[]', encoding='utf-8')

class Fake(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path == '/release':
            release.set()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
            self.end_headers(); self.wfile.write(b'{"released":true}')
            return
        self.send_response(404); self.end_headers()

    def do_POST(self):
        b = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if 'input' in b:
            texts = b['input']; texts = [texts] if isinstance(texts, str) else texts
            data = {'data': [{'index': i, 'embedding': [1., 0.]} for i, _ in enumerate(texts)]}
            raw = json.dumps(data).encode(); self.send_response(200)
            self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(raw); return
        if b.get('stream'):
            slow = 'SLOW_SSE' in json.dumps(b, ensure_ascii=False)
            first = 'SYNTHETIC_SLOW_FIRST' if slow else 'SYNTHETIC_STREAM_ANSWER'
            self.send_response(200); self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            self.wfile.write(('data: {"choices":[{"delta":{"content":"%s"}}]}\n\n' % first).encode())
            self.wfile.flush()
            if slow:
                release.wait(20)
            self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush(); return
        data = {'choices': [{'message': {'role': 'assistant', 'content': 'SYNTHETIC_MODEL_ANSWER', 'tool_calls': []}, 'finish_reason': 'stop'}]}
        raw = json.dumps(data).encode(); self.send_response(200)
        self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(raw)

release = threading.Event()
fake = ThreadingHTTPServer(('127.0.0.1', 0), Fake)
threading.Thread(target=fake.serve_forever, daemon=True).start()
with socket.socket() as s:
    s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
base = 'http://127.0.0.1:' + str(port)
password = secrets.token_urlsafe(18)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **kw): return None
class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(self.jar))
        self.token = ''
    def req(self, path, method='GET', data=None, content_type=None):
        headers = {}
        if self.token: headers['X-XSRF-TOKEN'] = self.token
        if isinstance(data, dict): data = json.dumps(data).encode(); content_type = 'application/json'
        if content_type: headers['Content-Type'] = content_type
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=30) as r: code, raw = r.status, r.read()
        except urllib.error.HTTPError as e: code, raw = e.code, e.read()
        try: body = json.loads(raw)
        except Exception: body = raw.decode('utf-8', 'replace')[:200]
        return code, body
    def login(self, username):
        self.token = self.req('/api/auth/csrf')[1]['token']
        raw = urllib.parse.urlencode({'username': username, 'password': password, '_csrf': self.token}).encode()
        code = self.req('/login', 'POST', raw, 'application/x-www-form-urlencoded')[0]
        assert code == 302, code
        self.token = self.req('/api/auth/csrf')[1]['token']

jar = ROOT / 'dist/talk-to-textbook-0.1.0-976ad9b-local-review.jar'
args = [r'F:/opc-tools/android/jdk-17.0.20.1+1/bin/java.exe', '-Xmx384m', '-Dfile.encoding=UTF-8', '-jar', str(jar),
        '--server.address=127.0.0.1', '--server.port=' + str(port), '--fa.cli.enabled=false',
        '--fa.auth.username=review_admin', '--fa.auth.password=' + password, '--fa.auth.password-hash=',
        '--fa.security.admin-key=' + secrets.token_urlsafe(18),
        '--fa.embedding.url=http://127.0.0.1:' + str(fake.server_port), '--fa.embedding.model=mock',
        '--fa.embedding.api-key=fake-only', '--fa.embedding.dimensions=2',
        '--fa.deepseek.api-key=fake-only', '--fa.deepseek.api-url=http://127.0.0.1:' + str(fake.server_port) + '/chat/completions',
        '--fa.vision.api-key=fake-only', '--fa.vision.api-url=http://127.0.0.1:' + str(fake.server_port) + '/chat/completions',
        '--fa.memory.long-term.enabled=false', '--fa.memory.short-term.summarize=false', '--fa.memory.core.max-chars=32']
env = {k: v for k, v in os.environ.items() if not k.startswith(('FA_', 'SPRING_'))
       and k not in ('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'MOONSHOT_API_KEY', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS')}
log = open(RUNTIME / 'app.log', 'w', encoding='utf-8')
proc = subprocess.Popen(args, cwd=RUNTIME, env=env, stdout=log, stderr=subprocess.STDOUT,
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
try:
    for _ in range(120):
        if proc.poll() is not None: raise RuntimeError('app exited ' + str(proc.returncode))
        try:
            if Client().req('/api/auth/csrf')[0] == 200: break
        except Exception: pass
        time.sleep(.5)
    else: raise RuntimeError('startup timeout')
    admin = Client(); admin.login('review_admin')
    for name in ('review_A', 'review_B'):
        code, body = admin.req('/api/admin/accounts', 'POST', dict(username=name, password=password, role='USER'))
        assert code == 200, (code, body)
    info = {'base': base, 'control': 'http://127.0.0.1:' + str(fake.server_port) + '/release', 'username': 'review_A', 'password': password, 'runtime': str(RUNTIME)}
    (ROOT / 'target/browser-fixture.json').write_text(json.dumps(info), encoding='utf-8')
    print(json.dumps({'ready': True, 'base': base, 'runtime': str(RUNTIME)}), flush=True)
    while True: time.sleep(60)
except BaseException as e:
    print('FIXTURE_ERROR ' + repr(e), flush=True); raise
finally:
    if proc.poll() is None:
        proc.terminate()
        try: proc.wait(timeout=10)
        except subprocess.TimeoutExpired: proc.kill()
    fake.shutdown(); fake.server_close(); log.close()
