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
                           env=ENV, stdout=open(RUN / 'fixture.log', 'w'), stderr=subprocess.STDOUT,
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
events = []
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
        ctx.expose_binding('recordEvent', lambda source, arg: events.append(arg))

        def login(page, user):
            page.goto(BASE + '/login.html', wait_until='domcontentloaded')
            page.fill('input[name=username]', user); page.fill('input[name=password]', PW)
            page.click('button[type=submit]'); page.wait_for_url('**/study.html')
            page.wait_for_function('typeof identityReady!=="undefined" && identityReady')

        a = ctx.new_page()
        doc_id = str(int(time.time() * 1000))
        ctx.add_init_script("""
          (() => {
            const doc = {id: '__DOC__', origin: performance.timeOrigin};
            const rec = (t, e) => { try { recordEvent({t, doc, trusted: e ? e.isTrusted : null,
              vis: document.visibilityState, persisted: e ? !!e.persisted : null, ts: Date.now()}); } catch (_) {} };
            document.addEventListener('visibilitychange', e => rec('visibilitychange', e));
            ['focus','blur','pageshow','pagehide'].forEach(n => addEventListener(n, e => rec(n, e)));
          })();
        """.replace('__DOC__', doc_id))
        login(a, 'review_A')
        token = a.request.get(BASE + '/api/auth/csrf').json()['token']
        seeded = a.request.put(BASE + '/api/memory/core', data={'text': 'A_MANUAL_PRIVATE_MARKER'},
                               headers={'X-XSRF-TOKEN': token, 'X-Expected-User-Id': a.evaluate('authUserId')})
        assert seeded.ok, seeded.status
        a.click('#openMemory')
        a.wait_for_function("document.querySelector('#coreText') && document.querySelector('#coreText').value === 'A_MANUAL_PRIVATE_MARKER'", timeout=10000)
        a.evaluate("document.title='A_TAB_MARKER'")
        a.screenshot(path=str(OUT / 'A-before-switch-visible-marker.png'), full_page=True)
        before = {'me': a.request.get(BASE + '/api/auth/me').json(),
                  'coreValue': a.input_value('#coreText'), 'visible': a.is_visible('#coreText')}

        b = ctx.new_page(); login(b, 'review_A')
        b.click('form[action="/logout"] button'); b.wait_for_url('**/')
        login(b, 'review_B'); b.evaluate("document.title='B_READY'")
        STOP = RUN / 'STOP'
        print(json.dumps({'base': BASE, 'A': 'review_A', 'B': 'review_B', 'password': PW,
                          'run_dir': str(RUN), 'stop_file': str(STOP), 'before': before}), flush=True)
        print('READY: click the tab titled A_TAB_MARKER, wait, then tell the operator to create STOP.', flush=True)
        while not STOP.exists():
            try: a.wait_for_timeout(500)
            except Exception: b.wait_for_timeout(500)

        after = {'url': a.url, 'events': events}
        try:
            after['me'] = a.request.get(BASE + '/api/auth/me').json()
            after['authUserId'] = a.evaluate('typeof authUserId != "undefined" ? authUserId : null')
            after['identityReady'] = a.evaluate('typeof identityReady != "undefined" ? identityReady : null')
            after['authEpoch'] = a.evaluate('typeof authEpoch != "undefined" ? authEpoch : null')
            after['coreExists'] = a.locator('#coreText').count()
            if after['coreExists']:
                after['coreValue'] = a.input_value('#coreText')
                after['coreVisible'] = a.is_visible('#coreText')
            after['bodyHasMarker'] = 'A_MANUAL_PRIVATE_MARKER' in a.inner_text('body')
        except Exception as e:
            after['readError'] = type(e).__name__ + ': ' + str(e)
        a.screenshot(path=str(OUT / 'A-after-native-return.png'), full_page=True)
        (RUN / 'manual-result.json').write_text(json.dumps({'before': before, 'after': after}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'before': before, 'after': after}, ensure_ascii=False, indent=2), flush=True)
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
