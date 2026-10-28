import json, os, time, uuid, urllib.request, traceback
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[3]
info = json.loads(Path(os.environ.get('BROWSER_INFO', str(ROOT / 'target/browser-fixture.json'))).read_text(encoding='utf-8'))
BASE, PW = info['base'], info['password']
OUT = Path(os.environ.get('BROWSER_OUT', str(ROOT / 'target/browser-evidence'))); OUT.mkdir(parents=True, exist_ok=True)
RESULTS = Path(os.environ.get('BROWSER_RESULTS', str(ROOT / 'target')))
results = []
suffix = uuid.uuid4().hex.upper()

def rec(n, passed, detail=None):
    results.append({'name': n, 'passed': bool(passed), 'detail': detail or {}})
    assert passed, n

def login(page, username):
    page.goto(BASE + '/login.html', wait_until='domcontentloaded')
    page.fill('input[name=username]', username); page.fill('input[name=password]', PW)
    page.click('button[type=submit]'); page.wait_for_url('**/study.html')
    page.wait_for_function('typeof identityReady!=="undefined" && identityReady')

def api(page, path, method='GET', data=None):
    headers = {'X-XSRF-TOKEN': page.request.get(BASE + '/api/auth/csrf').json()['token'],
               'X-Expected-User-Id': page.evaluate('authUserId')}
    return page.request.fetch(BASE + path, method=method, data=data, headers=headers)

def control(url):
    with urllib.request.urlopen(url, timeout=5) as r: return json.loads(r.read())

def ask(page, marker):
    # 同时检查新增气泡和唯一答案，不能让上一轮答案替这一轮通过。
    before = page.locator('.bubble.bot').count()
    question = marker + '_' + suffix
    page.fill('#question', question)
    with page.expect_response(lambda r: r.url.endswith('/api/agent/stream')) as response:
        page.click('#send')
    assert response.value.status == 200
    expect(page.locator('.bubble.bot')).to_have_count(before + 1)
    expect(page.locator('.bubble.bot').nth(before)).to_contain_text('ANSWER_' + question, timeout=20000)
    page.wait_for_function('streamCtrl === null && !document.getElementById("send").disabled')
    assert 'error' not in (page.locator('.bubble.bot').nth(before).get_attribute('class') or ''), page.locator('.bubble.bot').nth(before).inner_text()
    rec(marker, True, {'newBubble': before, 'answer': 'ANSWER_' + question})
    return question

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    try:
        a = browser.new_context().new_page(); login(a, 'review_A')
        conv = api(a, '/api/conversations', 'POST', {'title': 'A_CHAIN_' + suffix})
        assert conv.status == 200, (conv.status, conv.text())
        aid = conv.json()['id']; a.evaluate('(id) => switchConversation(id)', aid)
        ask(a, 'CHAIN_NORMAL_A')
        a.fill('#question', 'SLOW_SSE'); a.click('#send')
        expect(a.locator('.bubble.bot').last).to_contain_text('SYNTHETIC_SLOW_FIRST', timeout=15000)
        state = control(info['streamState'])
        rec('slow_stream_pending_before_stop', state['wait_returned'] is None and a.is_visible('#stop'))
        a.click('#stop')
        a.wait_for_function('streamCtrl === null && !document.getElementById("send").disabled')
        rec('stop_aborted_request', '已停止' in a.locator('.bubble.bot').last.inner_text())
        assert control(info['control'])['released'] is True
        until = time.monotonic() + 5
        while time.monotonic() < until:
            state = control(info['streamState'])
            if state['wait_returned'] is True and (state['done_sent'] or state['client_cancelled']): break
            time.sleep(.05)
        rec('old_stream_terminal', state['wait_returned'] is True and (state['done_sent'] or state['client_cancelled']), state)
        ask(a, 'CHAIN_AFTER_CANCEL')

        b = browser.new_context().new_page(); login(b, 'review_B')
        conv = api(b, '/api/conversations', 'POST', {'title': 'B_ONLY_CHAIN_' + suffix})
        assert conv.status == 200, (conv.status, conv.text())
        bid = conv.json()['id']; b.evaluate('(id) => switchConversation(id)', bid)
        ask(b, 'CHAIN_NORMAL_B')
        notes = {}
        for label, page, cid in [('A', a, aid), ('B', b, bid)]:
            question = 'CHAIN_JSON_' + label + '_' + suffix
            r = api(page, '/api/agent', 'POST', {'question': question, 'conversationId': cid})
            rec(label + '_nonstream_answer', r.status == 200 and r.json().get('answer') == 'ANSWER_' + question)
            # 真正点击聊天勾选、保存与下载，不能只验证手工拼出的笔记。
            page.click('#noteBtn')
            page.locator('.bubble.user').filter(has_text=question).locator('input.pick-box').check()
            with page.expect_response(lambda r: r.url.endswith('/api/notes') and r.request.method == 'POST') as saved:
                page.click('#noteSave')
            payload = saved.value.json()
            rec(label + '_chat_to_note', saved.value.status == 200 and payload.get('savedCount') == 1)
            notes[label] = payload['saved'][0]['id']
            page.click('#openNotebook')
            with page.expect_download() as download: page.click('#noteExport')
            path = OUT / (label + '-notebook.json'); download.value.save_as(path)
            exported = json.loads(path.read_text(encoding='utf-8'))
            own = next(n for n in exported['notes'] if n['id'] == notes[label])
            # 笔记设计允许附带本会话的语义相关回答，不保证本题答案排第一。
            rec(label + '_note_export', own['question'] == question
                and any(answer['text'] == 'ANSWER_' + question for answer in own['answers'])
                and own == payload['saved'][0])
            page.click('#closeNotebook')

        before = api(b, '/api/conversations/' + bid)
        rec('B_owns_valid_conversation', before.status == 200 and before.json()['id'] == bid)
        rec('A_still_authenticated', api(a, '/api/auth/me').json()['username'] == 'review_A')
        for path, method, data in [
            ('/api/conversations/' + bid, 'GET', None),
            ('/api/conversations/' + bid, 'PATCH', {'title': 'A_INTRUSION'}),
            ('/api/notes/' + notes['B'], 'GET', None),
            ('/api/notes/' + notes['B'], 'PATCH', {'question': 'A_INTRUSION', 'answers': []}),
            ('/api/notes/' + notes['B'], 'DELETE', None),
            ('/api/notes', 'POST', {'conversationId': bid, 'indexes': [0]})]:
            r = api(a, path, method, data)
            rec('A_denied_' + method + '_' + path, r.status in (400, 403, 404) and 'CHAIN_NORMAL_B' not in r.text(), {'status': r.status})
        rec('B_conversation_unchanged', api(b, '/api/conversations/' + bid).json() == before.json())
        rec('B_note_unchanged', api(b, '/api/notes/' + notes['B']).json()['question'] == 'CHAIN_JSON_B_' + suffix)
        rec('A_export_has_no_B_note', notes['B'] not in api(a, '/api/notes/export').text())
        a.screenshot(path=str(OUT / 'A-learning-chain.png'), full_page=True)
    except Exception as e:
        traceback.print_exc()
        results.append({'name': 'learning_chain_exception', 'passed': False, 'detail': {'error': str(e)}})
    finally:
        browser.close()
        RESULTS.mkdir(parents=True, exist_ok=True)
        (RESULTS / 'learning-chain-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
if not results or not all(x['passed'] for x in results): raise SystemExit(1)
