"""六本书通过副本验收后，仅发布知识/向量匹配对；不更换 JAR、账号、环境和域名。"""
import hashlib, json, os, pathlib, re, secrets, shutil, sqlite3, subprocess, sys, time
import server_verify as v

ROOT=v.ROOT
PROD=pathlib.Path('/srv/lamplit')
BACKUP=pathlib.Path('/var/backups/lamplit')/ROOT.name


def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def wait():
    end=time.monotonic()+180
    while time.monotonic()<end:
        try:
            if v.health(8080)['status']=='UP':return v.health(8080)
        except Exception:pass
        if v.properties('lamplit')['ActiveState']=='failed':raise RuntimeError('Production failed startup')
        time.sleep(1)
    raise RuntimeError('Production startup timed out')


def stop():
    v.command('systemctl','stop','lamplit')
    assert v.properties('lamplit')['MainPID']=='0'
    with v.socket.socket() as s: assert s.connect_ex(('127.0.0.1',8080))!=0


def account_probe(action,user,password=''):
    cmd=['/usr/bin/java','-Xmx128m','-Dloader.main=io.github.likeelysia.formalagent.account.AccountSmokeProbe',
         '-Dloader.path='+str(ROOT/'probe-classes'),'-cp',str(ROOT/'app.jar'),
         'org.springframework.boot.loader.launch.PropertiesLauncher',str(PROD/'data/accounts/accounts.db'),action,user]
    with open(ROOT/'production-account-probe.log','ab') as log:
        result=subprocess.run(cmd,cwd=PROD,user='lamplit',group='lamplit',
            env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LAMPLIT_SMOKE_PASSWORD':password},
            stdout=log,stderr=subprocess.STDOUT,timeout=60)
    assert result.returncode==0,'Account helper failed; inspect restricted log'


def smoke():
    manifest=json.loads((ROOT/'candidate-manifest.json').read_text())
    user='codex_smoke_books_'+ROOT.name[-8:];password=secrets.token_urlsafe(24)
    account_probe('create',user,password)
    try:
        v.BASE='http://127.0.0.1:8080';v.settings=lambda:{'FA_AUTH_PASSWORD':password}
        client=v.Client(user)
        overview=client.ok('/api/knowledge?sample=0')
        expected=json.loads((v.EVIDENCE/'staged-counts.json').read_text())
        assert overview['count']==expected['total'] and overview['sourceCounts']==expected['sources']
        assert len(overview['sources'])==11
        results=[]
        cases=json.loads((pathlib.Path(__file__).parent/'new-cases.json').read_text())
        for source in [b['source'] for b in manifest['sources']]:
            case=next(c for c in cases if c['expectSource']==source and c.get('productionSmoke'))
            cid=client.ok('/api/conversations','POST',{'title':'BOOK_VERIFY_'+source})['id']
            question='请先用 search_knowledge 检索“'+source.removesuffix('.pdf')+' '+case['query']+'”，仅依据《'+source.removesuffix('.pdf')+'》回答：'+case['query']+'。最后注明书名和物理页码。'
            answer=client.ok('/api/agent','POST',{'conversationId':cid,'question':question})
            trace=answer.get('trace') or []
            assert any(t.startswith('search_knowledge(') for t in trace),'Model did not retrieve'
            title=source.removesuffix('.pdf').split('（')[0]
            assert title in answer['answer'],'Wrong or missing book citation'
            page_cited=any(re.search(r'第\s*'+str(p)+r'\s*页',answer['answer']) for p in case['expectPages'])
            assert page_cited,'Grounded physical page not cited'
            results.append({'source':source,'question':question,'answer':answer['answer'],'trace':trace,'expectedPageCited':page_cited})
        v.write(v.EVIDENCE/'production-overview.json',overview)
        v.write(v.EVIDENCE/'production-rag.json',results)
    finally:account_probe('disable',user)
    return user


def publish():
    manifest=json.loads((ROOT/'candidate-manifest.json').read_text())
    assert manifest['complete'] and len(manifest['sources'])==6
    assert sha(ROOT/'source-pages.json.gz')==manifest['sourcePageArchiveSHA256']
    for name in ['old','new']:
        result=json.loads((v.EVIDENCE/(name+'-retrieval-eval.json')).read_text())
        assert result['recallAt5']>=.8 and result['mrr']>=.7 and result['pageHitAt5']>=.8
        if result['unanswerable']:assert result['refused']/result['unanswerable']>=.9
    groups=json.loads((v.EVIDENCE/'per-book-eval.json').read_text())
    assert len(groups)==6 and all(g['matched']>=2 for g in groups.values())
    before=v.production();original=json.loads((v.EVIDENCE/'original-knowledge.json').read_text())
    assert before['sha256']==original['production']['sha256']
    assert sha(PROD/'knowledge/base.json')==original['baseSHA256']
    assert sha(PROD/'knowledge/base.vectors.json')==original['vectorSHA256']
    vectors=json.loads((v.EVIDENCE/'vectors-complete.json').read_text())
    assert vectors['status']['missing']==0
    candidate=ROOT/'synthetic/knowledge'
    assert sha(candidate/'base.json')==vectors['baseSHA256']
    assert sha(candidate/'base.vectors.json')==vectors['vectorSHA256']
    assert not BACKUP.exists()
    v.stop()
    config_hash=sha(pathlib.Path('/etc/lamplit/lamplit.env'))
    for name in ['base.json','base.vectors.json']:
        target=PROD/'knowledge'/(name+'.books-new');assert not target.exists()
        shutil.copyfile(candidate/name,target);shutil.chown(target,user='lamplit',group='lamplit');os.chmod(target,0o640)
    BACKUP.mkdir(mode=0o700);changed=False;ready=False
    try:
        stop()
        for name in ['base.json','base.vectors.json']:shutil.copyfile(PROD/'knowledge'/name,BACKUP/name)
        v.write(BACKUP/'metadata.json',{'production':before,'original':original,'candidate':vectors})
        assert sha(BACKUP/'base.json')==original['baseSHA256'] and sha(BACKUP/'base.vectors.json')==original['vectorSHA256']
        ready=True;changed=True
        for name in ['base.vectors.json','base.json']:os.replace(PROD/'knowledge'/(name+'.books-new'),PROD/'knowledge'/name)
        v.command('systemctl','start','lamplit');state=wait()
        assert state['knowledgeCount']==vectors['status']['total'] and state['missingVectors']==0
        user=smoke()
        stop();v.command('systemctl','start','lamplit');state=wait()
        assert state['knowledgeCount']==vectors['status']['total'] and state['missingVectors']==0
        assert sha(PROD/'knowledge/base.json')==vectors['baseSHA256'] and sha(PROD/'knowledge/base.vectors.json')==vectors['vectorSHA256']
        with sqlite3.connect('file:'+str(PROD/'data/accounts/accounts.db')+'?mode=ro',uri=True) as db:
            assert db.execute('select enabled from accounts where username=?',(user,)).fetchone()[0]==0
        assert config_hash==sha(pathlib.Path('/etc/lamplit/lamplit.env'))
        assert v.production()['sha256']==before['sha256']
        library=PROD/'library'/ROOT.name;library.mkdir(parents=True)
        shutil.copyfile(ROOT/'candidate-manifest.json',library/'manifest.json')
        shutil.copyfile(ROOT/'source-pages.json.gz',library/'source-pages.json.gz')
        v.write(v.EVIDENCE/'publication.json',{'success':True,'before':before,'after':v.production(),'backup':str(BACKUP),
            'jarAndConfigurationUnchanged':True,'temporaryAccountDisabled':True,'knowledgeHashesMatchAfterRestart':True,
            'sources':11,'sourcePageArchive':str(library/'source-pages.json.gz')})
        print(json.dumps({'published':True,'sources':11,'knowledgeCount':state['knowledgeCount'],'missingVectors':0}),flush=True)
    except Exception as error:
        v.write(v.EVIDENCE/'publication-failure.json',{'type':type(error).__name__,'error':str(error)[:200]})
        if changed and ready:
            stop()
            for name in ['base.vectors.json','base.json']:
                temp=PROD/'knowledge'/(name+'.rollback');shutil.copyfile(BACKUP/name,temp)
                shutil.chown(temp,user='lamplit',group='lamplit');os.chmod(temp,0o640);os.replace(temp,PROD/'knowledge'/name)
        v.command('systemctl','start','lamplit');restored=wait()
        v.write(v.EVIDENCE/'rollback.json',{'health':restored,'restoredOriginalKnowledge':changed and ready})
        raise


def rollback():
    receipt=json.loads((v.EVIDENCE/'publication.json').read_text())
    candidate=json.loads((v.EVIDENCE/'vectors-complete.json').read_text())
    metadata=json.loads((BACKUP/'metadata.json').read_text())
    assert v.production()['sha256']==receipt['after']['sha256'],'Re-review changed application'
    assert sha(PROD/'knowledge/base.json')==candidate['baseSHA256'],'Re-review changed knowledge'
    assert sha(PROD/'knowledge/base.vectors.json')==candidate['vectorSHA256'],'Re-review changed vectors'
    assert sha(BACKUP/'base.json')==metadata['original']['baseSHA256']
    assert sha(BACKUP/'base.vectors.json')==metadata['original']['vectorSHA256']
    preserved=BACKUP/'published-pair';preserved.mkdir()
    stop()
    try:
        for name in ['base.json','base.vectors.json']:shutil.copyfile(PROD/'knowledge'/name,preserved/name)
        for name in ['base.vectors.json','base.json']:
            target=PROD/'knowledge'/(name+'.rollback');shutil.copyfile(BACKUP/name,target)
            shutil.chown(target,user='lamplit',group='lamplit');os.chmod(target,0o640)
            os.replace(target,PROD/'knowledge'/name)
    finally:v.command('systemctl','start','lamplit')
    state=wait();assert state['knowledgeCount']==receipt['before']['health']['knowledgeCount']
    v.write(v.EVIDENCE/'manual-rollback.json',{'health':state,'newPairPreserved':str(preserved)})


if __name__=='__main__':
    if sys.argv[2]=='publish':publish()
    elif sys.argv[2]=='rollback':rollback()
    else:raise SystemExit('Explicit publish or rollback required')
