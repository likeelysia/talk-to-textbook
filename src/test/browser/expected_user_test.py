import json, os, urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(r'F:\JAVA__PROJECT\talk-to-textbook')
info = json.loads(Path(os.environ.get('BROWSER_INFO', str(ROOT / 'target/browser-fixture.json'))).read_text(encoding='utf-8'))
BASE, PW = info['base'], info['password']
OUT = Path(os.environ.get('BROWSER_OUT', str(ROOT / 'target/browser-evidence'))); OUT.mkdir(parents=True, exist_ok=True)
res = []
def rec(n, p, d=None): res.append({'name': n, 'passed': bool(p), 'detail': d or {}})
def login(page, u):
    page.goto(BASE + '/login.html', wait_until='domcontentloaded')
    page.fill('input[name=username]', u); page.fill('input[name=password]', PW)
    page.click('button[type=submit]'); page.wait_for_url('**/study.html')
    page.wait_for_function('typeof identityReady!=="undefined" && identityReady')

with sync_playwright() as pw:
    b = pw.chromium.launch(channel='msedge', headless=True)
    c = b.new_context(); a = c.new_page(); login(a, 'review_A')
    a_user = a.evaluate('authUserId')
    token = a.request.get(BASE + '/api/auth/csrf').json()['token']
    a.request.put(BASE + '/api/memory/core', data={'text': 'A_EXPECTED_MARKER'},
                  headers={'X-XSRF-TOKEN': token, 'X-Expected-User-Id': a_user})
    btab = c.new_page(); login(btab, 'review_B')
    b_user = btab.evaluate('authUserId')
    rec('A_B_are_different_users', a_user != b_user, {'a': a_user, 'b': b_user})
    a.bring_to_front()
    a.wait_for_timeout(1000)
    invalidated = False
    try:
        a.wait_for_url('**/login.html', timeout=10000)
        invalidated = True
    except Exception:
        try:
            a.wait_for_function('typeof identityReady!=="undefined" && identityReady===false', timeout=2000)
            invalidated = True
        except Exception:
            pass
    rec('A_tab_invalidated_by_cross_tab_signal', invalidated, {'url': a.url})

    # 旧 A 预期身份 + 实际 B 会话，必须在副作用前拒绝
    token_b = btab.request.get(BASE + '/api/auth/csrf').json()['token']
    r = btab.request.put(BASE + '/api/memory/core', data={'text': 'OLD_A_SHOULD_NOT_WRITE'},
                         headers={'X-XSRF-TOKEN': token_b, 'X-Expected-User-Id': a_user})
    rec('mismatched_expected_user_rejected', r.status == 409, {'status': r.status})
    bcore = btab.request.get(BASE + '/api/memory/core').json().get('text', '')
    rec('B_core_unchanged', 'OLD_A_SHOULD_NOT_WRITE' not in bcore, {'core': bcore})
    b.close()
(Path(os.environ.get('BROWSER_RESULTS', str(ROOT / 'target'))) / 'expected-user-results.json').write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(res, ensure_ascii=False, indent=2))
if not all(x['passed'] for x in res): raise SystemExit(1)
