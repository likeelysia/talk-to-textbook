import json, os, subprocess, sys, time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]
PY = os.environ.get('PYTHON', sys.executable)
RUN = ROOT / 'target/browser-runs' / ('manual-' + time.strftime('%Y%m%d-%H%M%S'))
RUN.mkdir(parents=True, exist_ok=False)
INFO = RUN / 'fixture.json'; OUT = RUN / 'evidence'; OUT.mkdir()
ENV = {**os.environ, 'PROJECT_ROOT': str(ROOT), 'BROWSER_RUN_DIR': str(RUN / 'runtime'),
       'BROWSER_INFO': str(INFO), 'BROWSER_OUT': str(OUT), 'BROWSER_RESULTS': str(RUN),
       'PYTHONIOENCODING': 'utf-8'}
fixture = subprocess.Popen([PY, str(ROOT / 'src/test/browser/browser_fixture.py')], cwd=str(ROOT),
                           env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
try:
    for _ in range(120):
        if INFO.exists(): break
        if fixture.poll() is not None: raise RuntimeError('fixture exited')
        time.sleep(.5)
    else: raise RuntimeError('fixture timeout')
    info = json.loads(INFO.read_text(encoding='utf-8'))
    BASE, PW = info['base'], info['password']
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=False)
        ctx = browser.new_context()
        a = ctx.new_page()
        def login(page, user):
            page.goto(BASE + '/login.html', wait_until='domcontentloaded')
            page.fill('input[name=username]', user); page.fill('input[name=password]', PW)
            page.click('button[type=submit]'); page.wait_for_url('**/study.html')
            page.wait_for_function('typeof identityReady!=="undefined" && identityReady')
        login(a, 'review_A')
        token = a.request.get(BASE + '/api/auth/csrf').json()['token']
        a.request.put(BASE + '/api/memory/core', data={'text': 'A_MANUAL_PRIVATE_MARKER'},
                      headers={'X-XSRF-TOKEN': token})
        a.evaluate("""window.__events=[];
          document.addEventListener('visibilitychange',e=>__events.push({t:'visibilitychange',trusted:e.isTrusted,vis:document.visibilityState,ts:Date.now()}));
          ['focus','blur','pageshow','pagehide'].forEach(n=>addEventListener(n,e=>__events.push({t:n,trusted:e.isTrusted,vis:document.visibilityState,persisted:e.persisted||false,ts:Date.now()})));
          fetch('/api/memory/core').then(r=>r.json()).then(x=>{window.__aCore=x.text});""")
        b = ctx.new_page(); login(b, 'review_A')
        b.click('form[action="/logout"] button'); b.wait_for_url('**/')
        login(b, 'review_B')
        b.evaluate("document.title='B_READY'")
        STOP = RUN / 'STOP'
        print(json.dumps({'base': BASE, 'A': 'review_A', 'B': 'review_B', 'password': PW,
                          'run_dir': str(RUN), 'stop_file': str(STOP)}), flush=True)
        print('READY: switch to the A tab (the one showing A_MANUAL_PRIVATE_MARKER), then create STOP file.', flush=True)
        while not STOP.exists():
            time.sleep(1)
        events = a.evaluate('window.__events')
        me = a.request.get(BASE + '/api/auth/me').json()
        body = a.inner_text('body')
        a.screenshot(path=str(OUT / 'A-after-native-return.png'), full_page=True)
        result = {'me': me, 'events': events, 'a_marker_visible': 'A_MANUAL_PRIVATE_MARKER' in body,
                  'a_core': a.evaluate('window.__aCore'), 'url': a.url}
        (RUN / 'manual-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
        browser.close()
finally:
    pid_file = RUN / 'runtime' / 'java.pid'
    pid = int(pid_file.read_text()) if pid_file.exists() else None
    if pid:
        subprocess.call(['taskkill', '/PID', str(pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if fixture.poll() is None:
        subprocess.call(['taskkill', '/PID', str(fixture.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    INFO.unlink(missing_ok=True)
    print('RUN_DIR=' + str(RUN), flush=True)
