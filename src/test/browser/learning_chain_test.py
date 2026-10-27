import json, os
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(r'F:\JAVA__PROJECT\talk-to-textbook')
info = json.loads(Path(os.environ.get('BROWSER_INFO', str(ROOT / 'target/browser-fixture.json'))).read_text(encoding='utf-8'))
BASE, PW = info['base'], info['password']
OUT = Path(os.environ.get('BROWSER_OUT', str(ROOT / 'target/browser-evidence'))); OUT.mkdir(parents=True, exist_ok=True)
results = []
def rec(n, p, d=None): results.append({'name': n, 'passed': bool(p), 'detail': d or {}})
def login(page, u):
    page.goto(BASE + '/login.html', wait_until='domcontentloaded')
    page.fill('input[name=username]', u); page.fill('input[name=password]', PW)
    page.click('button[type=submit]'); page.wait_for_url('**/study.html')
    page.wait_for_function('typeof identityReady!=="undefined" && identityReady')

with sync_playwright() as pw:
    b = pw.chromium.launch(channel='msedge', headless=True)
    c = b.new_context(); a = c.new_page(); login(a, 'review_A')
    a.fill('#question', 'CHAIN_NORMAL_QUESTION'); a.click('#send')
    a.wait_for_selector('text=SYNTHETIC_STREAM_ANSWER', timeout=20000)
    rec('A_normal_sse_answer', True, {})

    a.fill('#question', 'SLOW_SSE'); a.click('#send')
    a.wait_for_selector('text=SYNTHETIC_SLOW_FIRST', timeout=15000)
    a.click('#stop')
    a.wait_for_selector('#send:not([disabled])', timeout=10000)
    rec('stop_restores_composer', a.is_enabled('#send'), {})
    a.fill('#question', 'CHAIN_AFTER_CANCEL'); a.click('#send')
    a.wait_for_selector('text=SYNTHETIC_STREAM_ANSWER', timeout=20000)
    rec('A_can_ask_after_cancel', True, {})

    bctx = b.new_context(); btab = bctx.new_page(); login(btab, 'review_B')
    token = btab.request.get(BASE + '/api/auth/csrf').json()['token']
    bconv = btab.request.post(BASE + '/api/conversations', data={'title': 'B_ONLY_CHAIN'},
                              headers={'X-XSRF-TOKEN': token, 'X-Expected-User-Id': btab.evaluate('authUserId')}).json()['id']
    r = a.request.get(BASE + '/api/conversations/' + bconv)
    rec('A_cannot_read_B_conversation', 400 <= r.status < 500, {'status': r.status})
    a.screenshot(path=str(OUT / 'A-learning-chain.png'), full_page=True)
    b.close()
(Path(os.environ.get('BROWSER_RESULTS', str(ROOT / 'target'))) / 'learning-chain-results.json').write_text(
    json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
if not all(x['passed'] for x in results): raise SystemExit(1)
