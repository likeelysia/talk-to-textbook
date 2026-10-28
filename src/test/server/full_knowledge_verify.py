"""在服务器副本验证现有完整教材；约束模型、数据量、输入体积与重试，保留每批进度。"""
import hashlib, json, os, pathlib, shutil, subprocess, sys, time, urllib.parse
import server_verify as v

ROOT=v.ROOT
STAGE=pathlib.Path(__file__).resolve().parent

def expand():
    source=STAGE/'full-knowledge.json'
    assert hashlib.sha256(source.read_bytes()).hexdigest()==(STAGE/'full-knowledge.sha256').read_text().strip()
    items=json.loads(source.read_text(encoding='utf-8'))
    assert len(items)==12925 and len({i['id'] for i in items})==len(items)
    input_bytes=sum(len((i['name']+' '+(i.get('detail') or '')).encode('utf-8')) for i in items)
    if input_bytes>2_000_000: raise RuntimeError('Input volume exceeds this verification budget')
    runtime=ROOT/'expanded'
    if not runtime.exists():
        shutil.copytree(ROOT/'synthetic',runtime)
        shutil.copyfile(source,runtime/'knowledge/base.json')
        (runtime/'knowledge/base.vectors.json').unlink(missing_ok=True)
        for p in [runtime,*runtime.rglob('*')]: shutil.chown(p,user='lamplit',group='lamplit')
    else:
        assert hashlib.sha256((runtime/'knowledge/base.json').read_bytes()).hexdigest()==hashlib.sha256(source.read_bytes()).hexdigest()
    for p in STAGE.glob('KnowledgeSearchProbe*.class'):
        shutil.copyfile(p,ROOT/'probe-classes/io/github/likeelysia/formalagent/knowledge'/p.name)
    envfile=ROOT/'verify.env'
    values=v.settings(); values['FA_EMBEDDING_MAX_ATTEMPTS']='1'
    envfile.write_text(''.join(k+'='+value+'\n' for k,value in values.items()))
    os.chmod(envfile,0o600)
    v.configure(runtime); v.start()
    pid=v.properties(v.UNIT)['MainPID']
    env=dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)
    host=urllib.parse.urlsplit(env.get('FA_EMBEDDING_URL','')).hostname or ''
    assert host.endswith('aliyuncs.com') and env.get('FA_EMBEDDING_MODEL')=='text-embedding-v4', 'Provider budget must be reviewed'
    v.write(v.EVIDENCE/'full-input-budget.json',{'count':len(items),'nameDetailUTF8Bytes':input_bytes,
        'embeddingModel':env['FA_EMBEDDING_MODEL'],'endpointHost':host,'maxAttemptsPerHttpRequest':1,
        'referencePriceCNYPerMillionTokens':0.5,'priceSource':'https://help.aliyun.com/zh/model-studio/text-embedding-v4',
        'estimateUsingUTF8BytesAsConservativeTokenProxyCNY':round(input_bytes/1_000_000*.5,4),
        'actualBillingNotClaimed':True})
    admin=v.Client('verify_admin')
    state=admin.ok('/api/knowledge/vectors/status',admin=True)
    batches=[]; started=time.monotonic()
    while state['missing']>0:
        previous=state['missing']; begin=time.monotonic()
        response=admin.ok('/api/knowledge/vectors/backfill?limit=500','POST',{},admin=True)
        state=admin.ok('/api/knowledge/vectors/status',admin=True)
        assert response['processed']>0 and state['missing']<previous
        batches.append({'response':response,'status':state,'seconds':round(time.monotonic()-begin,2)})
        v.write(v.EVIDENCE/'full-vector-batches.json',batches)
        print(json.dumps({'processed':len(items)-state['missing'],'remaining':state['missing'],'seconds':round(time.monotonic()-started,1)}),flush=True)
    v.write(v.EVIDENCE/'full-vector-result.json',{'status':state,'seconds':round(time.monotonic()-started,2),
        'sidecarSHA256':hashlib.sha256((runtime/'knowledge/base.vectors.json').read_bytes()).hexdigest(),
        'sidecarBytes':(runtime/'knowledge/base.vectors.json').stat().st_size,'resources':v.properties(v.UNIT)})
    v.stop(); v.start()
    verify_restart()


def verify_restart():
    # 单独入口允许启动探针超时后继续核验，不重复付费生成向量。
    v.start()
    admin=v.Client('verify_admin')
    assert admin.ok('/api/knowledge/vectors/status',admin=True)['missing']==0
    assert v.snapshot(), 'Previously seeded synthetic data must survive expanded catalog'
    v.write(v.EVIDENCE/'full-after-restart.json',{'health':v.health(),'resources':v.properties(v.UNIT),'syntheticDataStillValid':True})
    print(json.dumps({'fullCatalogReady':True}),flush=True)

def evaluate():
    runtime=ROOT/'expanded'
    pid=v.properties(v.UNIT)['MainPID']
    env=dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)
    probe_env={k:value for k,value in env.items() if k.startswith(('FA_EMBEDDING_','FA_KNOWLEDGE_'))}
    probe_env.update(PATH='/usr/bin:/bin',LANG='C.UTF-8')
    output=v.EVIDENCE/'full-retrieval-eval.json'
    cmd=['systemd-run','--quiet','--scope','-p','MemoryMax=384M','-p','CPUQuota=100%',
        '/usr/bin/java','-Xmx192m','-Dloader.main=io.github.likeelysia.formalagent.knowledge.KnowledgeSearchProbe',
        '-Dloader.path='+str(ROOT/'probe-classes'),'-cp',str(ROOT/'app.jar'),
        'org.springframework.boot.loader.launch.PropertiesLauncher',str(runtime/'knowledge/base.json'),
        '@'+str(STAGE/'retrieval_eval.json'),str(output)]
    v.budget()
    with open(ROOT/'full-probe.log','w') as log:
        p=subprocess.run(cmd,env=probe_env,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    assert p.returncode==0, 'Batch retrieval probe failed'
    result=json.loads(output.read_text());print(json.dumps({k:value for k,value in result.items() if k!='cases'}))
    assert result['recallAt5']>=.8 and result['mrr']>=.7 and result['pageHitAt5']>=.8
    assert result['refused']/result['unanswerable']>=.9

if __name__=='__main__':
    if sys.argv[2]=='expand': expand()
    elif sys.argv[2]=='verify-restart': verify_restart()
    elif sys.argv[2]=='evaluate': evaluate()
    else: raise SystemExit('Unknown action')
