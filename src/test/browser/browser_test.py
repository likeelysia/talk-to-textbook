import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(r'F:\JAVA__PROJECT\talk-to-textbook')
info = json.loads((ROOT / 'target/browser-fixture.json').read_text(encoding='utf-8'))
BASE = info['base']; PW = info['password']
OUT = ROOT / 'target/browser-evidence'; OUT.mkdir(exist_ok=True)
results = []
def rec(name, passed, detail):
    results.append({'name': name, 'passed': bool(passed), 'detail': detail})

def login(ctx, user):
    p = ctx.new_page()
    p.goto(BASE + '/login.html', wait_until='domcontentloaded')
    p.fill('input[name=username]', user)
    p.fill('input[name=password]', PW)
    p.click('button[type=submit]')
    p.wait_for_url('**/study.html', timeout=15000)
    p.wait_for_load_state('networkidle')
    return p

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    a_ctx = browser.new_context()
    b_ctx = browser.new_context()
    a = login(a_ctx, 'review_A')
    a.screenshot(path=str(OUT / 'A-login.png'), full_page=True)
    a.fill('#question', 'SYNTHETIC_BROWSER_A_QUESTION')
    a.click('#send')
    a.wait_for_selector('text=SYNTHETIC_STREAM_ANSWER', timeout=20000)
    rec('A_normal_stream_login', True, {'url': a.url})
    a.screenshot(path=str(OUT / 'A-answer.png'), full_page=True)

    b = login(b_ctx, 'review_B')
    body_b = b.inner_text('body')
    rec('B_does_not_see_A_answer', 'SYNTHETIC_BROWSER_A_QUESTION' not in body_b and 'SYNTHETIC_STREAM_ANSWER' not in body_b, {'url': b.url})
    b.screenshot(path=str(OUT / 'B-login.png'), full_page=True)

    # 同身份恢复：pageshow 触发重验，恢复后仍可输入
    a.evaluate("window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true}))")
    time.sleep(1.5)
    a.wait_for_selector('#question:not([disabled])', timeout=10000)
    rec('same_identity_pageshow_restores_input', a.is_enabled('#question'), {'url': a.url})
    a.fill('#question', 'AFTER_PAGESHOW_QUESTION')
    rec('same_identity_can_type_after_restore', a.input_value('#question') == 'AFTER_PAGESHOW_QUESTION', {})
    before_answers = a.locator('text=SYNTHETIC_STREAM_ANSWER').count()
    a.click('#send')
    a.wait_for_function(
        "n => document.body.innerText.split('SYNTHETIC_STREAM_ANSWER').length - 1 > n",
        arg=before_answers, timeout=20000)
    rec('same_identity_can_submit_after_restore', True, {'answers_before': before_answers})

    # 打开 core 弹窗后退出；退出后页面不应残留 A 的 core
    a.click('#openMemory')
    a.wait_for_timeout(500)
    a.evaluate("document.querySelector('form[action=\"/logout\"]').submit()")
    a.wait_for_url('**/', timeout=15000)
    body_after = a.inner_text('body')
    rec('logout_clears_private_view', 'SYNTHETIC_OLD_PROFILE' not in body_after and 'A_ONLY_PROFILE' not in body_after, {'url': a.url})
    a.screenshot(path=str(OUT / 'A-after-logout.png'), full_page=True)

    # 后退到 study 页，不应显示 A 的私人历史
    a.go_back()
    a.wait_for_timeout(1500)
    body_back = a.inner_text('body')
    rec('back_after_logout_does_not_show_A_history', 'SYNTHETIC_BROWSER_A_QUESTION' not in body_back, {'url': a.url})
    a.screenshot(path=str(OUT / 'A-back-after-logout.png'), full_page=True)

    # 多标签：同一 Cookie 下 page2 退出，page1 恢复时必须重新校验
    c_ctx = browser.new_context()
    p1 = login(c_ctx, 'review_A')
    p2 = c_ctx.new_page()
    p2.goto(BASE + '/study.html', wait_until='domcontentloaded')
    p2.wait_for_load_state('networkidle')
    p2.evaluate("document.querySelector('form[action=\"/logout\"]').submit()")
    p2.wait_for_url('**/', timeout=15000)
    p1.evaluate("window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true}))")
    p1.wait_for_url('**/login.html', timeout=15000)
    rec('multi_tab_logout_forces_old_page_recheck', p1.url.endswith('/login.html'), {'url': p1.url})
    p1.screenshot(path=str(OUT / 'multi-tab-old-page.png'), full_page=True)
    c_ctx.close()

    # SSE：A 先收到唯一 delta，流被挂起；真实退出并登录 B 后再释放旧流
    s_ctx = browser.new_context()
    sa = login(s_ctx, 'review_A')
    sa.fill('#question', 'SLOW_SSE')
    sa.click('#send')
    sa.wait_for_selector('text=SYNTHETIC_SLOW_FIRST', timeout=15000)
    sb = s_ctx.new_page()
    sb.goto(BASE + '/study.html', wait_until='domcontentloaded')
    sb.wait_for_load_state('networkidle')
    sb.click('form[action="/logout"] button')
    sb.wait_for_url('**/', timeout=15000)
    sb.goto(BASE + '/login.html', wait_until='domcontentloaded')
    sb.fill('input[name=username]', 'review_B')
    sb.fill('input[name=password]', PW)
    sb.click('button[type=submit]')
    sb.wait_for_url('**/study.html', timeout=15000)
    import urllib.request
    urllib.request.urlopen(BASE + '/release', timeout=5).read()
    sb.wait_for_timeout(2000)
    rec('sse_identity_switch_does_not_leak_into_B', 'SYNTHETIC_SLOW_FIRST' not in sb.inner_text('body'), {'url': sb.url})
    sb.screenshot(path=str(OUT / 'B-after-slow-sse.png'), full_page=True)
    s_ctx.close()

    browser.close()

(ROOT / 'target/browser-results.json').write_text(json.dumps({'base': BASE, 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
