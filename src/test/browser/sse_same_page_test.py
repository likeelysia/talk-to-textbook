import json, time, urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(r'F:\JAVA__PROJECT\talk-to-textbook')
info = json.loads((ROOT / 'target/browser-fixture.json').read_text(encoding='utf-8'))
BASE, PW, CONTROL = info['base'], info['password'], info['control']
OUT = ROOT / 'target/browser-evidence'; OUT.mkdir(exist_ok=True)
results = []
def rec(n, p, d=None): results.append({'name': n, 'passed': bool(p), 'detail': d or {}})

with sync_playwright() as pw:
    b = pw.chromium.launch(channel='msedge', headless=True)
    c = b.new_context(); p = c.new_page()
    p.goto(BASE + '/login.html', wait_until='domcontentloaded')
    p.fill('input[name=username]', 'review_A'); p.fill('input[name=password]', PW); p.click('button[type=submit]')
    p.wait_for_url('**/study.html'); p.wait_for_load_state('networkidle')
    p.fill('#question', 'SLOW_SSE'); p.click('#send')
    p.wait_for_selector('text=SYNTHETIC_SLOW_FIRST', timeout=15000)
    rec('S1_first_delta_arrived', True, {'url': p.url})

    p.click('form[action="/logout"] button')
    p.wait_for_url('**/', timeout=15000)
    p.goto(BASE + '/login.html', wait_until='domcontentloaded')
    p.fill('input[name=username]', 'review_B'); p.fill('input[name=password]', PW); p.click('button[type=submit]')
    p.wait_for_url('**/study.html'); p.wait_for_load_state('networkidle')
    me = p.evaluate("apiJson('/api/auth/me')")
    rec('S2_same_page_login_is_B', me.get('username') == 'review_B', {'username': me.get('username')})

    with urllib.request.urlopen(CONTROL, timeout=5) as r:
        released = json.loads(r.read().decode('utf-8'))
    rec('S3_release_control_confirmed', released.get('released') is True, released)
    p.wait_for_timeout(1500)
    rec('S5_B_no_old_delta', 'SYNTHETIC_SLOW_FIRST' not in p.inner_text('body'), {'url': p.url})
    p.fill('#question', 'B_AFTER_RELEASE')
    rec('S5_B_send_enabled_before_ask', p.is_enabled('#send'), {'body': p.inner_text('body')[-200:]})
    p.click('#send')
    try:
        p.wait_for_selector('text=SYNTHETIC_STREAM_ANSWER', timeout=20000)
        rec('S5_B_can_ask_after_release', True, {})
    except Exception as e:
        rec('S5_B_can_ask_after_release', False, {'error': type(e).__name__, 'body': p.inner_text('body')[-300:]})
    p.screenshot(path=str(OUT / 'B-after-same-page-slow-sse.png'), full_page=True)
    b.close()
(ROOT / 'target/slow-sse-same-page-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
if not all(x['passed'] for x in results): raise SystemExit(1)
