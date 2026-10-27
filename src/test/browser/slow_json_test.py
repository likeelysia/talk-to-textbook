import json,os,time,threading,http.client,urllib.parse
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from playwright.sync_api import sync_playwright

root=Path(r'F:\JAVA__PROJECT\talk-to-textbook')
info=json.loads(Path(os.environ.get('BROWSER_INFO', str(root/'target/browser-fixture.json'))).read_text())
backend=info['base']; upstream=urllib.parse.urlsplit(backend)
marker='A_SLOW_CORE_MARKER'; release=threading.Event(); lock=threading.Lock()
gate={'armed':False,'prefix_sent':False,'body_complete':False,'released':False}
events=[];results=[];stage='initial'
def trace(kind,**detail):
    with lock:events.append({'kind':kind,'time_ns':time.monotonic_ns(),**detail})
def rec(name,passed,**detail):results.append({'name':name,'passed':bool(passed),'detail':detail})
class Proxy(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def forward(self):
        conn=http.client.HTTPConnection(upstream.hostname,upstream.port,timeout=15)
        try:
            size=int(self.headers.get('Content-Length','0'));data=self.rfile.read(size) if size else None
            headers={k:v for k,v in self.headers.items() if k.lower() not in ['connection','proxy-connection','transfer-encoding','accept-encoding']}
            headers['Accept-Encoding']='identity'
            conn.request(self.command,self.path,body=data,headers=headers)
            response=conn.getresponse();body=response.read()
            held=False
            with lock:
                if gate['armed'] and self.command=='GET' and urllib.parse.urlsplit(self.path).path=='/api/memory/core':
                    gate['armed']=False;held=True;gate['upstream_contains_marker']=marker.encode() in body
            trace('proxy_received_upstream',method=self.command,path=urllib.parse.urlsplit(self.path).path,status=response.status,held=held)
            self.send_response(response.status)
            for k,v in response.getheaders():
                if k.lower() in ['content-length','transfer-encoding','connection']:continue
                if k.lower()=='location' and v.startswith(backend):v=public+v[len(backend):]
                self.send_header(k,v)
            self.send_header('Content-Length',str(len(body)));self.end_headers()
            if not held:
                self.wfile.write(body);self.wfile.flush();return
            split=max(1,len(body)//2);self.wfile.write(body[:split]);self.wfile.flush()
            with lock:gate.update(prefix_sent=True,prefix_bytes=split,total_bytes=len(body))
            trace('headers_and_partial_body_flushed',prefix_bytes=split,total_bytes=len(body))
            released=release.wait(25)
            with lock:gate['released']=released
            trace('release_wait_returned',released=released)
            if not released:return
            try:
                self.wfile.write(body[split:]);self.wfile.flush()
                with lock:gate['delivery']='remaining_bytes_sent'
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):
                with lock:gate['delivery']='client_cancelled'
            with lock:gate['body_complete']=True
            trace('held_response_finished',delivery=gate['delivery'])
        except Exception as e:trace('proxy_error',error_type=type(e).__name__,path=urllib.parse.urlsplit(self.path).path)
        finally:conn.close()
    do_GET=do_POST=do_PUT=do_DELETE=do_PATCH=do_OPTIONS=forward
server=ThreadingHTTPServer(('127.0.0.1',0),Proxy);public='http://127.0.0.1:'+str(server.server_port)
threading.Thread(target=server.serve_forever,daemon=True).start()
def login(page,user):
    global stage
    stage='login_'+user+'_form'
    page.goto(public+'/login.html');page.fill('input[name=username]',user);page.fill('input[name=password]',info['password'])
    page.click('button[type=submit]');page.wait_for_url('**/study.html')
    stage='login_'+user+'_identity_ready'
    page.wait_for_function('typeof identityReady!=="undefined" && identityReady && authUserId')
    page.wait_for_selector('#question:not([disabled])')
    assert page.url.startswith(public+'/'),'Login redirected away from proxy origin'
with sync_playwright() as pw:
    browser=pw.chromium.launch(executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',headless=True)
    try:
        context=browser.new_context()
        context.add_init_script('''(() => {window.__slowHeaders=false;const original=window.fetch.bind(window);window.fetch=(...args)=>original(...args).then(r=>{if(String(args[0]).startsWith('/api/memory/core'))window.__slowHeaders=true;return r;});})();''')
        page=context.new_page();stage='A_login';login(page,'review_A');trace('A_logged_in')
        token=page.request.get(public+'/api/auth/csrf').json()['token']
        seeded=page.request.put(public+'/api/memory/core',data={'text':marker},headers={'X-XSRF-TOKEN':token,'X-Expected-User-Id':page.evaluate('authUserId')});assert seeded.ok
        with lock:gate['armed']=True
        stage='partial_json_headers';page.click('#openMemory')
        page.wait_for_function('window.__slowHeaders===true',timeout=10000)
        with lock:partial=dict(gate)
        rec('browser_headers_arrived_while_json_body_pending',partial['prefix_sent'] and not partial['body_complete'] and not partial['released'] and partial.get('upstream_contains_marker',False),gate=partial)
        trace('browser_observed_response_headers')
        stage='real_logout_then_B_login';page.click('#closeMemory');page.locator('form[action="/logout"] button').click();page.wait_for_url('**/')
        login(page,'review_B');me=page.request.get(public+'/api/auth/me').json();assert me['username']=='review_B';trace('B_authenticated_same_page')
        rec('real_same_page_logout_and_B_login',me['authenticated'] and me['username']=='review_B')
        stage='release_old_json';trace('release_signal_set');release.set()
        until=time.monotonic()+5
        while not gate['body_complete'] and time.monotonic()<until:page.wait_for_timeout(30)
        rec('held_json_explicitly_released_or_cancelled',gate['released'] and gate['body_complete'],delivery=gate.get('delivery'))
        stage='B_private_view';page.click('#openMemory');page.wait_for_function('document.querySelector("#coreCount").textContent.includes("/")')
        rec('B_core_and_page_do_not_contain_A_marker',page.input_value('#coreText')=='' and marker not in page.inner_text('body'))
        page.click('#closeMemory');page.fill('#question','B_CAN_ASK_AFTER_SLOW_JSON');page.click('#send')
        page.wait_for_selector('text=SYNTHETIC_STREAM_ANSWER',timeout=15000);page.wait_for_selector('#send:not([disabled])')
        rec('B_can_ask_and_receive_answer_after_old_json_release',True)
        OUT=Path(os.environ.get('BROWSER_OUT', str(root/'target/browser-evidence'))); OUT.mkdir(parents=True,exist_ok=True)
        page.screenshot(path=str(OUT/'B-after-slow-json.png'))
    except Exception as e:
        rec('slow_json_execution',False,stage=stage,error_type=type(e).__name__,message=str(e).replace(info['password'],'[redacted]')[:800])
        try:page.screenshot(path=str(root/'target/browser-evidence/slow-json-failure.png'))
        except Exception:pass
    finally:
        release.set();browser.close();server.shutdown();server.server_close()
(Path(os.environ.get('BROWSER_RESULTS', str(root/'target')))/'proxy-events.json').write_text(json.dumps(events,indent=2),encoding='utf-8')
(Path(os.environ.get('BROWSER_RESULTS', str(root/'target')))/'gate-state.json').write_text(json.dumps(gate,indent=2),encoding='utf-8')
(Path(os.environ.get('BROWSER_RESULTS', str(root/'target')))/'slow-json-results.json').write_text(json.dumps({'base':public,'results':results},indent=2),encoding='utf-8')
print(json.dumps(results,indent=2))
if not results or not all(x['passed'] for x in results):raise SystemExit(1)
