"""固定本轮六书的服务器副本验证。运行前审查 runId/清单，沿用既有应用和原库向量。"""
import hashlib, json, os, pathlib, secrets, shutil, sqlite3, subprocess, sys, time, urllib.parse
import server_verify as v

ROOT=v.ROOT
STAGE=pathlib.Path(__file__).resolve().parent
PROD=pathlib.Path('/srv/lamplit')


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare():
    v.prepare()
    shutil.copyfile(PROD/'knowledge/base.json',ROOT/'original-base.json')
    shutil.copyfile(PROD/'knowledge/base.vectors.json',ROOT/'synthetic/knowledge/base.vectors.json')
    v.write(v.EVIDENCE/'original-knowledge.json',{'baseSHA256':digest(PROD/'knowledge/base.json'),
        'vectorSHA256':digest(PROD/'knowledge/base.vectors.json'),'production':v.production()})
    for file in STAGE.glob('KnowledgeSearchProbe*.class'):
        shutil.copyfile(file,ROOT/'probe-classes/io/github/likeelysia/formalagent/knowledge'/file.name)
    account=ROOT/'probe-classes/io/github/likeelysia/formalagent/account'
    account.mkdir(parents=True)
    shutil.copyfile(STAGE/'AccountSmokeProbe.class',account/'AccountSmokeProbe.class')
    values=v.settings();values['FA_EMBEDDING_MAX_ATTEMPTS']='1'
    (ROOT/'verify.env').write_text(''.join(k+'='+val+'\n' for k,val in values.items()))
    os.chmod(ROOT/'verify.env',0o600)
    stage()


def stage():
    manifest=json.loads((STAGE/'manifest.json').read_text())
    incoming=json.loads((STAGE/'new-items.json').read_text())
    assert digest(STAGE/'new-items.json')==manifest['itemsSHA256']
    assert 0<len(incoming)<15000 and manifest['embeddingInputUTF8Bytes']<=20_000_000
    assert all(i['id'].startswith('book:') and i['detail'] and i['location'].startswith('第 ') for i in incoming)
    original=json.loads((ROOT/'original-base.json').read_text())
    assert len(original)==12925 and len({i['source'] for i in original})==5
    assert not ({i['source'] for i in original}&{i['source'] for i in incoming})
    merged=original+incoming
    assert len({i['id'] for i in merged})==len(merged)
    runtime=ROOT/'synthetic'; destination=runtime/'knowledge/base.json'
    v.stop()
    # 已补过的片段必须逐字相同，不能让续跑把变化后的正文配上旧向量。
    previous=json.loads(destination.read_text())
    by_id={i['id']:i for i in merged}
    assert all(by_id[i['id']]==i for i in previous if i['id'].startswith('book:') and i['id'] in by_id)
    temp=destination.with_suffix('.new');v.write(temp,merged);shutil.chown(temp,user='lamplit',group='lamplit');os.replace(temp,destination)
    v.write(ROOT/'candidate-manifest.json',manifest)
    v.write(v.EVIDENCE/'staged-counts.json',{'complete':manifest['complete'],'oldCount':len(original),'newCount':len(incoming),
        'total':len(merged),'sources':{s:sum(i['source']==s for i in merged) for s in dict.fromkeys(i['source'] for i in merged)}})
    if manifest['complete']:
        assert digest(STAGE/'source-pages.json.gz')==manifest['sourcePageArchiveSHA256']
        assert len({i['source'] for i in incoming})==6
    if (STAGE/'source-pages.json.gz').exists():shutil.copyfile(STAGE/'source-pages.json.gz',ROOT/'source-pages.json.gz')
    v.start()
    print(json.dumps({'staged':True,'total':len(merged),'complete':manifest['complete']}),flush=True)


def backfill():
    v.start();admin=v.Client('verify_admin');state=admin.ok('/api/knowledge/vectors/status',admin=True)
    pid=v.properties(v.UNIT)['MainPID']
    env=dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)
    assert (urllib.parse.urlsplit(env.get('FA_EMBEDDING_URL','')).hostname or '').endswith('aliyuncs.com')
    assert env.get('FA_EMBEDDING_MODEL')=='text-embedding-v4' and env.get('FA_EMBEDDING_MAX_ATTEMPTS')=='1'
    path=v.EVIDENCE/'vector-batches.json';batches=json.loads(path.read_text()) if path.exists() else []
    while state['missing']:
        begin=time.monotonic();previous=state['missing']
        response=admin.ok('/api/knowledge/vectors/backfill?limit=250','POST',{},admin=True)
        state=admin.ok('/api/knowledge/vectors/status',admin=True)
        assert response['processed']>0 and state['missing']<previous
        batches.append({'response':response,'status':state,'seconds':round(time.monotonic()-begin,2)})
        v.write(path,batches)
        print(json.dumps({'total':state['total'],'missing':state['missing']}),flush=True)
    v.write(v.EVIDENCE/'vectors-complete.json',{'status':state,'baseSHA256':digest(ROOT/'synthetic/knowledge/base.json'),
        'vectorSHA256':digest(ROOT/'synthetic/knowledge/base.vectors.json'),'resources':v.properties(v.UNIT)})


def evaluate():
    pid=v.properties(v.UNIT)['MainPID']
    env=dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)
    probe_env={k:val for k,val in env.items() if k.startswith(('FA_EMBEDDING_','FA_KNOWLEDGE_'))}
    probe_env.update(PATH='/usr/bin:/bin',LANG='C.UTF-8')
    for name in ['old','new']:
        output=v.EVIDENCE/(name+'-retrieval-eval.json')
        command=['systemd-run','--quiet','--scope','-p','MemoryMax=384M','-p','CPUQuota=100%',
            '/usr/bin/java','-Xmx192m','-Dloader.main=io.github.likeelysia.formalagent.knowledge.KnowledgeSearchProbe',
            '-Dloader.path='+str(ROOT/'probe-classes'),'-cp',str(ROOT/'app.jar'),
            'org.springframework.boot.loader.launch.PropertiesLauncher',str(ROOT/'synthetic/knowledge/base.json'),
            '@'+str(STAGE/(name+'-cases.json')),str(output)]
        v.budget()
        with open(ROOT/(name+'-eval.log'),'w') as log:
            p=subprocess.run(command,env=probe_env,stdout=log,stderr=subprocess.STDOUT,timeout=300)
        assert p.returncode==0,'Retrieval evaluation process failed'
        result=json.loads(output.read_text())
        print(json.dumps({'suite':name}|{k:val for k,val in result.items() if k!='cases'}),flush=True)
        assert result['missingVectors']==0 and result['recallAt5']>=.8 and result['mrr']>=.7
        assert result['pageHitAt5']>=.8
        if result['unanswerable']:assert result['refused']/result['unanswerable']>=.9
        if name=='new':
            groups={}
            for case in result['cases']:
                group=groups.setdefault(case['expectSource'],{'total':0,'matched':0})
                group['total']+=1;group['matched']+=int(case['matched'])
            complete=json.loads((ROOT/'candidate-manifest.json').read_text())['complete']
            assert len(groups)==(6 if complete else 5) and all(g['matched']>=2 for g in groups.values())
            v.write(v.EVIDENCE/'per-book-eval.json',groups)


if __name__=='__main__':
    action=sys.argv[2]
    if action=='prepare':prepare()
    elif action=='stage':stage()
    elif action=='backfill':backfill()
    elif action=='evaluate':evaluate()
    else:raise SystemExit('Unknown action')
