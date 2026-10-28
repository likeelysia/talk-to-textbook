"""Linux 现场隔离验证。仅操作显式创建的 /srv/lamplit-codex-* 目录与同名 unit。
凭据仅在服务器受限 EnvironmentFile 中；下载 evidence/ 即可，不下载环境与账号库。
"""
import hashlib, http.cookiejar, json, os, pathlib, secrets, shutil, socket, subprocess
import sys, tarfile, time, urllib.error, urllib.parse, urllib.request

ROOT = pathlib.Path(sys.argv[1]).resolve()
if ROOT.parent != pathlib.Path('/srv') or not ROOT.name.startswith('lamplit-codex-'):
    raise SystemExit('Refuse unknown verification directory')
UNIT = ROOT.name + '.service'
UNIT_FILE = pathlib.Path('/run/systemd/system') / UNIT
PORT = 18081
BASE = 'http://127.0.0.1:' + str(PORT)
EVIDENCE = ROOT / 'evidence'
STATE = ROOT / 'state.json'
HIDDEN = subprocess.DEVNULL

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def command(*args, check=True):
    return subprocess.run(list(args), capture_output=True, text=True, check=check)

def properties(unit):
    r = command('systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'MemoryCurrent',
                '-p', 'MemoryPeak', '-p', 'NRestarts', '-p', 'MainPID', '-p', 'Result')
    return dict(line.split('=', 1) for line in r.stdout.splitlines() if '=' in line)

def health(port=PORT):
    with urllib.request.urlopen('http://127.0.0.1:%s/api/health' % port, timeout=5) as r:
        return json.loads(r.read())

def production():
    return {'unit': properties('lamplit'), 'health': health(8080),
            'release': command('readlink', '-f', '/opt/lamplit/current').stdout.strip(),
            'sha256': hashlib.sha256(pathlib.Path('/opt/lamplit/current/app.jar').read_bytes()).hexdigest()}

def budget():
    available = next(int(line.split()[1]) for line in pathlib.Path('/proc/meminfo').read_text().splitlines()
                     if line.startswith('MemAvailable:'))
    if available < 256 * 1024:
        command('systemctl', 'stop', UNIT, check=False)
        raise RuntimeError('Stopped verification: MemAvailable below 256 MiB')
    assert health(8080)['status'] == 'UP'

def stop():
    command('systemctl', 'stop', UNIT, check=False)
    for _ in range(50):
        with socket.socket() as s:
            if s.connect_ex(('127.0.0.1', PORT)) != 0: return
        time.sleep(.1)
    raise RuntimeError('Verification port remains open')

def prepare():
    if ROOT.exists() or UNIT_FILE.exists(): raise RuntimeError('Use a fresh runId')
    with socket.socket() as s:
        if s.connect_ex(('127.0.0.1', PORT)) == 0: raise RuntimeError('Verification port already in use')
    before = production()
    ROOT.mkdir(mode=0o750); shutil.chown(ROOT, user='lamplit', group='lamplit')
    EVIDENCE.mkdir(mode=0o700)
    stage = pathlib.Path(__file__).resolve().parent
    shutil.copyfile(stage / 'app.jar', ROOT / 'app.jar')
    if (stage / 'expected-sha256.txt').read_text().strip().lower() != hashlib.sha256((ROOT / 'app.jar').read_bytes()).hexdigest():
        raise RuntimeError('Candidate checksum mismatch')
    probe = ROOT / 'probe-classes'; probe.mkdir()
    relative = pathlib.Path('io/github/likeelysia/formalagent/knowledge/KnowledgeSearchProbe.class')
    (probe / relative).parent.mkdir(parents=True)
    shutil.copyfile(stage / 'KnowledgeSearchProbe.class', probe / relative)
    runtime = ROOT / 'synthetic'
    for folder in ['data/memory', 'knowledge', 'sessions', 'logs']:
        (runtime / folder).mkdir(parents=True, exist_ok=True)
    items = json.loads(pathlib.Path('/srv/lamplit/knowledge/base.json').read_text(encoding='utf-8'))[:20]
    assert len(items) == 20 and all(i.get('source') and i.get('location') for i in items)
    write(runtime / 'knowledge/base.json', items)
    for path in [runtime, *runtime.rglob('*')]: shutil.chown(path, user='lamplit', group='lamplit')
    password, key = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    settings = {'FA_AUTH_USERNAME': 'verify_admin', 'FA_AUTH_PASSWORD': password,
                'FA_AUTH_PASSWORD_HASH': '', 'FA_ADMIN_KEY': key, 'FA_SECURITY_ADMIN_KEY': key,
                'FA_MEMORY_LONG_TERM': 'false', 'FA_MEMORY_SUMMARIZE': 'false',
                'FA_MEMORY_RECONCILE': 'false', 'FA_OCR_ENGINE': 'vision',
                'FA_MODEL_MAX_CONCURRENT': '1', 'FA_MODEL_BACKGROUND_CONCURRENT': '0',
                'FA_RATE_LIMIT_PER_MINUTE': '60', 'FA_COOKIE_SECURE': 'false'}
    envfile = ROOT / 'verify.env'
    fd = os.open(envfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(''.join(k + '=' + v + '\n' for k, v in settings.items()))
    write(STATE, {'runtime': str(runtime), 'candidate_sha256': hashlib.sha256((ROOT / 'app.jar').read_bytes()).hexdigest()})
    write(EVIDENCE / 'production-before.json', before)
    configure(runtime)

def configure(runtime):
    runtime = runtime.resolve()
    if ROOT not in runtime.parents: raise RuntimeError('Runtime escaped verification root')
    flags = {'server.address': '127.0.0.1', 'server.port': str(PORT), 'fa.cli.enabled': 'false',
             'fa.account.db': str(runtime/'data/accounts/accounts.db'),
             'fa.conversation.dir': str(runtime/'data/conversations'), 'fa.memory.long-term.dir': str(runtime/'data/memory'),
             'fa.memory.core.path': str(runtime/'data/memory/core.md'), 'fa.memory.conflicts-path': str(runtime/'data/memory/conflicts.json'),
             'fa.note.dir': str(runtime/'data/notes'), 'fa.upload.dir': str(runtime/'data/uploads'),
             'fa.vocab.notebook-file': str(runtime/'data/vocab/notebook.json'),
             'fa.checkpoint.dir': str(runtime/'data/checkpoints'), 'fa.knowledge.file': str(runtime/'knowledge/base.json'),
             'fa.vocab.dict-path': '/srv/lamplit/dict/stardict.db', 'fa.llm.max-attempts': '1'}
    text = '[Unit]\nDescription=Lamplit isolated verification\n[Service]\n'
    text += 'User=lamplit\nGroup=lamplit\nWorkingDirectory=' + str(runtime) + '\n'
    text += 'EnvironmentFile=/etc/lamplit/lamplit.env\nEnvironmentFile=' + str(ROOT/'verify.env') + '\n'
    text += 'ExecStart=/usr/bin/java -Xms128m -Xmx384m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -jar ' + str(ROOT/'app.jar')
    text += ''.join(' --'+k+'='+v for k,v in flags.items()) + '\n'
    text += 'MemoryHigh=512M\nMemoryMax=640M\nCPUQuota=100%\nRestart=no\nSuccessExitStatus=143\n'
    text += 'ProtectSystem=strict\nReadOnlyPaths=/srv/lamplit /etc/lamplit\nReadWritePaths='+str(ROOT)+'\n'
    text += 'PrivateTmp=yes\nNoNewPrivileges=yes\nTimeoutStopSec=25\n'
    UNIT_FILE.write_text(text, encoding='utf-8')
    command('systemctl', 'daemon-reload')
    state = json.loads(STATE.read_text()); state['runtime'] = str(runtime); write(STATE, state)
    write(EVIDENCE/'run-manifest.json', {'unit': UNIT, 'base': BASE, 'runtime': str(runtime),
          'candidate_sha256': state['candidate_sha256'], 'paths': flags, 'heapMiB':384,
          'MemoryHighMiB':512, 'MemoryMaxMiB':640, 'ProtectSystem':'strict'})

def start():
    budget()
    command('systemctl', 'start', UNIT)
    # 完整向量文件的冷启动比 20 条烟测慢，按墙钟给足 180 秒。
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if health()['status'] == 'UP': return
        except Exception: pass
        if properties(UNIT).get('ActiveState') == 'failed': raise RuntimeError('Isolated JVM failed; inspect restricted journal')
        time.sleep(.5)
    raise RuntimeError('Isolated startup timed out')

def settings():
    return dict(line.split('=',1) for line in (ROOT/'verify.env').read_text().splitlines() if '=' in line)

class Client:
    def __init__(self, username):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token=''; self.uid=''; self.username=username
        self.token=self.req('/api/auth/csrf')[1]['token']
        form=urllib.parse.urlencode({'username':username,'password':settings()['FA_AUTH_PASSWORD'],'_csrf':self.token}).encode()
        self.req('/login','POST',form,'application/x-www-form-urlencoded')
        self.token=self.req('/api/auth/csrf')[1]['token']
        status, me=self.req('/api/auth/me'); assert status==200 and me['username']==username
        self.uid=me['userId']
    def request(self,path,method='GET',data=None,ctype=None,admin=False):
        headers={}
        if self.token: headers['X-XSRF-TOKEN']=self.token
        if self.uid: headers['X-Expected-User-Id']=self.uid
        if admin: headers['X-Admin-Key']=settings()['FA_ADMIN_KEY']
        if isinstance(data,dict): data=json.dumps(data,ensure_ascii=False).encode('utf-8'); ctype='application/json'
        if ctype: headers['Content-Type']=ctype
        return urllib.request.Request(BASE+path,method=method,data=data,headers=headers)
    def req(self,path,method='GET',data=None,ctype=None,admin=False):
        try:
            with self.opener.open(self.request(path,method,data,ctype,admin),timeout=120) as r: code,raw=r.status,r.read()
        except urllib.error.HTTPError as e: code,raw=e.code,e.read()
        try: return code,json.loads(raw)
        except Exception: return code,raw.decode('utf-8','replace')[:300]
    def ok(self,path,method='GET',data=None,admin=False):
        budget()
        code,body=self.req(path,method,data,admin=admin)
        assert code==200, (path,code,str(body)[:200])
        return body
    def stream(self,cid,question,cancel=False):
        request=self.request('/api/agent/stream','POST',{'conversationId':cid,'question':question})
        events=[]; event=''; payload=[]
        with self.opener.open(request,timeout=120) as response:
            assert response.status==200
            for raw in response:
                line=raw.decode('utf-8').rstrip('\r\n')
                if line.startswith('event:'): event=line[6:].strip()
                elif line.startswith('data:'): payload.append(line[5:].strip())
                elif not line and payload:
                    value=json.loads('\n'.join(payload)); payload=[]
                    events.append({'event':event,'data':value})
                    if event=='error': raise RuntimeError('SSE error: '+str(value))
                    if cancel and event=='delta' and value.get('text'): break
                    if event=='done': return events
        if cancel:
            assert events and events[-1]['event']=='delta'
            return events
        raise RuntimeError('SSE ended without done')

def seed():
    admin=Client('verify_admin')
    for label in ['A','B']:
        admin.ok('/api/admin/accounts','POST',{'username':'verify_'+label,'password':settings()['FA_AUTH_PASSWORD'],'role':'USER'})
    clients={k:Client('verify_'+k) for k in ['A','B']}
    result=[]; owned={}
    for label,client in clients.items():
        cid=client.ok('/api/conversations','POST',{'title':label+'_SERVER_CHAIN'})['id']
        owned[label]={'cid':cid}
        if label=='A':
            aborted=client.stream(cid,'请从1到300逐项解释自然数，每项一行。',cancel=True)
            write(EVIDENCE/'cancel-events.json',aborted)
            marker='A_AFTER_CANCEL_'+ROOT.name[-8:]
            events=client.stream(cid,'不要调用工具，只回复这个标记：'+marker)
            answer=events[-1]['data']['answer']
            assert marker in answer
            write(EVIDENCE/'sse-after-cancel.json',events)
        else:
            marker='B_SERVER_ANSWER_'+ROOT.name[-8:]
            answer=client.ok('/api/agent','POST',{'conversationId':cid,'question':'不要调用工具，只回复这个标记：'+marker})['answer']
            assert marker in answer
        conversation=client.ok('/api/conversations/'+cid)
        index=next(i for i in reversed(range(len(conversation['messages']))) if conversation['messages'][i]['role']=='user')
        note=client.ok('/api/notes','POST',{'conversationId':cid,'indexes':[index]})['saved'][0]
        assert any(marker in a['text'] for a in note['answers'])
        exported=client.ok('/api/notes/export')
        assert any(n==note for n in exported['notes'])
        core=label+'_NONEMPTY_CORE_'+ROOT.name[-8:]
        client.ok('/api/memory/core','PUT',{'text':core})
        memory=client.ok('/api/memory','POST',{'conversationId':cid,'text':label+'_NONEMPTY_MEMORY_'+ROOT.name[-8:]})
        owned[label].update(uid=client.uid,nid=note['id'],mid=memory['id'],core=core,answerMarker=marker)
        result.append({'account':label,'nonempty_chat':len(conversation['messages']),
                       'noteSavedFromChat':True,'exportMatches':True,'core':core,'memoryId':memory['id']})
    assert owned['A']['uid'] != owned['B']['uid']
    for label,client in clients.items():
        other=owned['B' if label=='A' else 'A']
        assert clients['B' if label=='A' else 'A'].ok('/api/notes/'+other['nid'])['id']==other['nid']
        for path,method,data in [('/api/conversations/'+other['cid'],'GET',None),
                                ('/api/conversations/'+other['cid'],'PATCH',{'title':'FORBIDDEN'}),
                                ('/api/notes/'+other['nid'],'GET',None),
                                ('/api/notes/'+other['nid'],'PATCH',{'question':'FORBIDDEN'}),
                                ('/api/notes/'+other['nid'],'DELETE',None)]:
            code,body=client.req(path,method,data)
            assert code in (400,403,404), (path,code)
        assert client.ok('/api/auth/me')['userId']==owned[label]['uid']
    write(ROOT/'owned.json',owned)
    write(EVIDENCE/'learning-results.json',result)
    write(EVIDENCE/'persist-before.json',snapshot())
    rag(admin,clients['A'])
    write(EVIDENCE/'resource-before-restart.json',{'isolation':properties(UNIT),'production':production()})

def snapshot():
    owned=json.loads((ROOT/'owned.json').read_text()); result={}
    for label,ids in owned.items():
        c=Client('verify_'+label)
        conversation=c.ok('/api/conversations/'+ids['cid'])
        core=c.ok('/api/memory/core')['text']
        memory=c.ok('/api/memory?conversationId='+ids['cid'])['memories']
        notes=c.ok('/api/notes/export')
        assert c.uid==ids['uid'] and core==ids['core']
        assert any(m['id']==ids['mid'] and m['text'].startswith(label+'_NONEMPTY_MEMORY_') for m in memory)
        assert any(ids['answerMarker'] in m['content'] for m in conversation['messages'] if m['role']=='assistant')
        assert any(n['id']==ids['nid'] for n in notes['notes'])
        result[label]={'uid':c.uid,'conversation':conversation,'core':core,'memory':memory,'notes':notes}
    return result

def rag(admin,client):
    runtime=pathlib.Path(json.loads(STATE.read_text())['runtime'])
    initial=admin.ok('/api/knowledge/vectors/status',admin=True)
    assert initial['total']==20 and initial['missing']==20
    begin=time.monotonic()
    backfill=admin.ok('/api/knowledge/vectors/backfill?limit=20','POST',{},admin=True)
    assert backfill['processed']==20 and backfill['remaining']==0
    side=json.loads((runtime/'knowledge/base.vectors.json').read_text())
    write(EVIDENCE/'vectors.json',{'initial':initial,'backfill':backfill,'seconds':time.monotonic()-begin,
          'model':side['model'],'dimensions':side['dim'],'count':len(side['vectors'])})
    items=json.loads((runtime/'knowledge/base.json').read_text()); cases=[]
    for item in [items[0],items[7],items[14]]:
        cid=client.ok('/api/conversations','POST',{'title':'RAG_'+item['name'][:20]})['id']
        question='请先调用 search_knowledge 查找“'+item['name']+'”，依据教材解释，并标明教材名和页码。'
        answer=client.ok('/api/agent','POST',{'conversationId':cid,'question':question})
        trace=answer.get('trace') or []
        call=next(t for t in trace if t.startswith('search_knowledge('))
        args=json.loads(call[len('search_knowledge('):-1]); query=args['query']
        # 用同一候选包的真实检索器重放该查询，不新增产品 API，也不把重放冒充原始工具响应。
        pid=properties(UNIT)['MainPID']
        environment=dict(entry.split('=',1) for entry in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in entry)
        probe_env={k:v for k,v in environment.items() if k.startswith(('FA_EMBEDDING_','FA_KNOWLEDGE_'))}
        probe_env.update(PATH='/usr/bin:/bin',LANG='C.UTF-8')
        output=EVIDENCE/('topk-'+str(len(cases))+'.json')
        cmd=['systemd-run','--quiet','--scope','-p','MemoryMax=256M','-p','CPUQuota=100%',
             '/usr/bin/java','-Xmx128m','-Dloader.main=io.github.likeelysia.formalagent.knowledge.KnowledgeSearchProbe',
             '-Dloader.path='+str(ROOT/'probe-classes'),'-cp',str(ROOT/'app.jar'),
             'org.springframework.boot.loader.launch.PropertiesLauncher',str(runtime/'knowledge/base.json'),query,str(output)]
        with open(ROOT/('probe-'+str(len(cases))+'.log'),'w') as log:
            completed=subprocess.run(cmd,env=probe_env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
        assert completed.returncode==0, 'KnowledgeSearchProbe failed; inspect restricted log'
        hits=json.loads(output.read_text())['hits']
        assert item['id'] in [h['id'] for h in hits]
        assert item['location'] in answer['answer'], 'Expected page missing from answer'
        cases.append({'question':question,'expected':item,'trace':trace,'answer':answer['answer'],
                      'replayedQuery':query,'replayedTopK':hits})
    write(EVIDENCE/'rag-cases.json',cases)

def after():
    before=json.loads((EVIDENCE/'persist-before.json').read_text())
    actual=snapshot()
    write(EVIDENCE/'persist-after.json',actual)
    assert before==actual, 'Nonempty data changed after restart'
    admin=Client('verify_admin')
    vectors=admin.ok('/api/knowledge/vectors/status',admin=True)
    assert vectors['missing']==0
    write(EVIDENCE/'persist-result.json',{'A':True,'B':True,'nonemptyAssertions':True,'vectorsAfterRestart':vectors})

def cleanup():
    stop()
    state=properties(UNIT)
    UNIT_FILE.unlink(missing_ok=True)
    command('systemctl','daemon-reload')
    command('systemctl','reset-failed',UNIT,check=False)
    (ROOT/'verify.env').unlink(missing_ok=True)
    after=production()
    write(EVIDENCE/'cleanup.json',{'isolationStopped':True,'credentialsRemoved':not(ROOT/'verify.env').exists(),
                                 'isolationResources':state,'productionAfter':after})
    assert after['health']['status']=='UP'
    print(json.dumps({'cleaned':True,'evidence':str(EVIDENCE)}))

if __name__ == '__main__':
    try:
        action=sys.argv[2]
        if action=='prepare': prepare()
        elif action=='start': start()
        elif action=='seed': seed()
        elif action=='restart': stop(); start(); after()
        elif action=='cleanup': cleanup()
        else: raise RuntimeError('Unknown action')
        print(json.dumps({'action':action,'ok':True}))
    except Exception as error:
        if ROOT.exists(): write(EVIDENCE/'failure.json',{'action':sys.argv[2],'type':type(error).__name__,'error':str(error)[:400]})
        raise
