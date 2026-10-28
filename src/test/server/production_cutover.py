"""本轮已验证候选的受控发布：停写备份、成对替换知识文件、业务烟测、失败成套回滚。

用法：sudo python3 production_cutover.py /srv/lamplit-codex-<runId> publish <源码提交>
只支持已确认的单机默认目录布局；不修改域名、代理、口令或服务资源上限。
辅助 Java 入口只在本机运行，临时普通账号验后停用；凭据不写入输出/参数。
"""
import hashlib, json, os, pathlib, re, secrets, shutil, sqlite3, subprocess, sys, tarfile, time
import migration_verify as m
import server_verify as v

ROOT=v.ROOT
PROD=pathlib.Path('/srv/lamplit')
CURRENT=pathlib.Path('/opt/lamplit/current')
BACKUP=pathlib.Path('/var/backups/lamplit')/(ROOT.name+'-cutover')


def wait_production():
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        try:
            result=v.health(8080)
            if result['status']=='UP': return result
        except Exception: pass
        if v.properties('lamplit')['ActiveState']=='failed':
            raise RuntimeError('Production failed; inspect restricted journal')
        time.sleep(1)
    raise RuntimeError('Production health timeout')


def stopped():
    v.command('systemctl','stop','lamplit')
    state=v.properties('lamplit')
    assert state['MainPID']=='0' and state['ActiveState'] in ('inactive','failed')
    with v.socket.socket() as s:
        assert s.connect_ex(('127.0.0.1',8080))!=0


def switch_release(path):
    assert path.parent==pathlib.Path('/opt/lamplit/releases') and path.is_dir()
    temp=CURRENT.with_name('current.codex-new')
    if temp.exists() or temp.is_symlink(): raise RuntimeError('Unexpected pending release link')
    temp.symlink_to(path); os.replace(temp,CURRENT)


def environment():
    pid=v.properties('lamplit')['MainPID']
    return dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)


def account_probe(action, username, password=''):
    cmd=['/usr/bin/java','-Xmx128m','-Dloader.main=io.github.likeelysia.formalagent.account.AccountSmokeProbe',
         '-Dloader.path='+str(ROOT/'probe-classes'),'-cp',str(CURRENT/'app.jar'),
         'org.springframework.boot.loader.launch.PropertiesLauncher',str(PROD/'data/accounts/accounts.db'),action,username]
    with open(ROOT/'production-smoke-probe.log','ab') as log:
        result=subprocess.run(cmd,cwd=PROD,user='lamplit',group='lamplit',
            env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LAMPLIT_SMOKE_PASSWORD':password},
            stdout=log,stderr=subprocess.STDOUT,timeout=60)
    assert result.returncode==0, 'Local account probe failed; inspect restricted log'


def smoke():
    env=environment()
    username='codex_smoke_'+ROOT.name[-8:]
    password=secrets.token_urlsafe(24)
    account_probe('create',username,password)
    try:
        # Client 始终走正常登录、CSRF、预期身份头，无新增产品接口或鉴权后门。
        v.BASE='http://127.0.0.1:8080'
        v.settings=lambda:{'FA_AUTH_PASSWORD':password}
        c=v.Client(username)
        assert c.ok('/api/auth/me')['role']=='USER'
        assert not c.ok('/api/conversations/default')['messages']
        assert c.ok('/api/memory/core')['text']=='' and c.ok('/api/notes/export')['count']==0
        cid=c.ok('/api/conversations','POST',{'title':'CODEX_RELEASE_SMOKE'})['id']
        question='请调用 search_knowledge 检索牛顿第一定律，依据检索结果简要解释，并注明教材和页码。'
        answer=c.ok('/api/agent','POST',{'conversationId':cid,'question':question})
        assert answer.get('answer') and any(t.startswith('search_knowledge(') for t in answer.get('trace',[]))
        assert '页' in answer['answer'], 'RAG answer lacks a page citation'
        marker='CODEX_RELEASE_OK_'+ROOT.name[-8:]
        events=c.stream(cid,'不要调用工具，只回复这个标记：'+marker)
        assert marker in ''.join(e['data'].get('text','') for e in events if e['event']=='delta')
        conversation=c.ok('/api/conversations/'+cid)
        index=next(i for i in reversed(range(len(conversation['messages']))) if conversation['messages'][i]['role']=='user')
        saved=c.ok('/api/notes','POST',{'conversationId':cid,'indexes':[index]})['saved'][0]
        assert any(marker in a['text'] for a in saved['answers'])
        assert any(n['id']==saved['id'] for n in c.ok('/api/notes/export')['notes'])
        c.ok('/api/memory/core','PUT',{'text':marker})
        memory=c.ok('/api/memory','POST',{'conversationId':cid,'text':marker})
        assert c.ok('/api/memory/core')['text']==marker
        assert any(x['id']==memory['id'] for x in c.ok('/api/memory?conversationId='+cid)['memories'])
        assert c.req('/api/admin/accounts')[0] in (403,404)
        v.write(v.EVIDENCE/'production-smoke.json',{'login':True,'role':'USER','userId':c.uid,
            'initialPrivateDataEmpty':True,'ragQuestion':question,'ragAnswer':answer['answer'],'ragTrace':answer.get('trace'),
            'sseDone':events[-1]['event']=='done','chatToNoteAndExport':True,'nonemptyCoreAndMemory':True,
            'adminDenied':True,'temporaryUsername':username})
    finally:
        account_probe('disable',username)
    # 原管理员的口令配置没有覆盖；如使用哈希，逐字验证迁入库中的值。
    with sqlite3.connect('file:'+str(PROD/'data/accounts/accounts.db')+'?mode=ro',uri=True) as db:
        admin=db.execute('select user_id,role,enabled,password_hash from accounts where username=?',
                         (env.get('FA_AUTH_USERNAME','pengge'),)).fetchone()
        assert admin and admin[0]=='admin' and admin[1]=='ADMIN' and admin[2]==1
        if env.get('FA_AUTH_PASSWORD_HASH','').strip(): assert admin[3]==env['FA_AUTH_PASSWORD_HASH'].strip()
        assert db.execute('select enabled from accounts where username=?',(username,)).fetchone()[0]==0
    return {'temporaryAccountDisabled':True,'originalAdminRetained':True,
            'configuredAdminHashCompared':bool(env.get('FA_AUTH_PASSWORD_HASH','').strip())}


def restore_old(before):
    stopped()
    runtime=BACKUP/'restore'
    runtime.mkdir()
    with tarfile.open(BACKUP/'data.tar.gz','r:gz') as archive:
        for member in archive.getmembers():
            path=(runtime/member.name).resolve()
            assert runtime in path.parents and not member.issym() and not member.islnk()
        archive.extractall(runtime,filter='data')
    assert m.manifest(runtime)==json.loads((BACKUP/'files.json').read_text())
    failed=BACKUP/'failed-new-state'; failed.mkdir()
    # 保留失败现场，不递归删除任何业务目录。
    for name in ['data','knowledge','sessions']:
        assert (PROD/name).resolve().parent==PROD and (runtime/name).is_dir()
        (PROD/name).rename(failed/name); (runtime/name).rename(PROD/name)
        for p in [PROD/name,*(PROD/name).rglob('*')]: shutil.chown(p,user='lamplit',group='lamplit')
    switch_release(pathlib.Path(before['release']))
    v.command('systemctl','start','lamplit'); health=wait_production()
    assert v.production()['sha256']==before['sha256']
    v.write(v.EVIDENCE/'production-rollback.json',{'restoredCodeAndData':True,'health':health,'backup':str(BACKUP)})


def publish(revision):
    if not re.fullmatch('[0-9a-f]{7,40}',revision): raise RuntimeError('Invalid revision')
    assert not BACKUP.exists(), 'Never overwrite a backup / retry a publication blindly'
    before=v.production()
    assert before['release']=='/opt/lamplit/releases/0709e5e', 'Re-review changed production'
    paths=m.production_paths()
    assert paths['FA_ACCOUNT_DB']=='data/accounts/accounts.db' and not (PROD/paths['FA_ACCOUNT_DB']).exists()
    assert all(paths[k]==expected for k,expected in {'FA_CONVERSATION_DIR':'data/conversations',
        'FA_MEMORY_DIR':'data/memory','FA_MEMORY_CORE':'data/memory/core.md','FA_NOTE_DIR':'data/notes',
        'FA_KNOWLEDGE_FILE':'knowledge/base.json'}.items()), 'Re-review custom layout'
    expanded=ROOT/'expanded/knowledge'
    evaluation=json.loads((v.EVIDENCE/'full-retrieval-eval.json').read_text())
    assert evaluation['missingVectors']==0 and evaluation['recallAt5']>=.8 and evaluation['mrr']>=.7
    assert evaluation['pageHitAt5']>=.8 and evaluation['refused']/evaluation['unanswerable']>=.9
    expected=json.loads((v.EVIDENCE/'full-vector-result.json').read_text())
    assert m.sha(expanded/'base.vectors.json')==expected['sidecarSHA256']
    new_items=json.loads((expanded/'base.json').read_text()); old_items=json.loads((PROD/'knowledge/base.json').read_text())
    new_by_id={i['id']:i for i in new_items}
    assert len(new_by_id)==12925 and len(old_items)==7287
    assert all(new_by_id.get(i['id'])==i for i in old_items), 'Do not overwrite changed production knowledge'
    v.stop()
    release=pathlib.Path('/opt/lamplit/releases')/revision
    assert not release.exists(), 'Use a fresh immutable release'
    release.mkdir(mode=0o755); shutil.copyfile(ROOT/'app.jar',release/'app.jar'); os.chmod(release/'app.jar',0o644)
    candidate=m.sha(ROOT/'app.jar'); assert m.sha(release/'app.jar')==candidate
    BACKUP.mkdir(mode=0o700)
    backup_valid=False; changed=False; started=time.monotonic()
    try:
        stopped()
        files=m.manifest(PROD); v.write(BACKUP/'files.json',files)
        v.write(BACKUP/'metadata.json',{'production':before,'paths':paths,'candidate':candidate,'revision':revision})
        shutil.copyfile('/etc/lamplit/lamplit.env',BACKUP/'lamplit.env'); os.chmod(BACKUP/'lamplit.env',0o600)
        v.command('tar','-czf',str(BACKUP/'data.tar.gz'),'-C',str(PROD),'data','knowledge','sessions')
        os.chmod(BACKUP/'data.tar.gz',0o600)
        digest=m.sha(BACKUP/'data.tar.gz'); (BACKUP/'data.tar.gz.sha256').write_text(digest+'  data.tar.gz\n')
        with tarfile.open(BACKUP/'data.tar.gz','r:gz') as archive:
            archived={x.name.removeprefix('./'):hashlib.sha256(archive.extractfile(x).read()).hexdigest()
                      for x in archive.getmembers() if x.isfile()}
        assert archived==files
        backup_valid=True
        changed=True
        for name in ['base.vectors.json','base.json']:
            target=PROD/'knowledge'/name; temp=target.with_name(name+'.codex-new')
            assert not temp.exists()
            shutil.copyfile(expanded/name,temp); shutil.chown(temp,user='lamplit',group='lamplit'); os.chmod(temp,0o640)
            assert m.sha(temp)==m.sha(expanded/name); os.replace(temp,target)
        switch_release(release)
        v.command('systemctl','start','lamplit'); state=wait_production()
        assert state['knowledgeCount']==12925 and state['missingVectors']==0
        # 迁移保留旧来源文件，且管理员目录里的会话/记忆逐文件与切换前一致。
        user_root=PROD/'data/conversations/users/admin'
        assert (user_root/'.legacy-userdata-v1').read_text().strip()=='v2'
        for relative,sha in files.items():
            if relative.startswith('knowledge/'): continue
            assert m.sha(PROD/relative)==sha, 'Original source changed during migration'
            if relative.startswith('data/conversations/'):
                tail=relative.removeprefix('data/conversations/')
                target=user_root/('legacy-owners.json' if tail=='owners.json' else 'conversations/'+tail)
                assert m.sha(target)==sha
            if relative.startswith('data/memory/'):
                assert m.sha(user_root/'memory'/relative.removeprefix('data/memory/'))==sha
        checks=smoke()
        after=v.production(); assert after['sha256']==candidate
        v.write(v.EVIDENCE/'production-cutover.json',{'ok':True,'revision':revision,'before':before,'after':after,
            'backup':str(BACKUP),'archiveSHA256':digest,'archiveMatchesStoppedSource':True,
            'originalDataAndMigratedFilesCompared':True,'checks':checks,
            'stopToSmokeCompleteSeconds':round(time.monotonic()-started,2),
            'knowledgeFiles':{name:m.sha(PROD/'knowledge'/name) for name in ['base.json','base.vectors.json']},
            'domainUnchanged':True,'productionConfigurationUnchanged':m.sha(BACKUP/'lamplit.env')==m.sha(pathlib.Path('/etc/lamplit/lamplit.env'))})
        print(json.dumps({'published':True,'revision':revision,'knowledgeCount':12925,'missingVectors':0}))
    except Exception as error:
        v.write(v.EVIDENCE/'production-cutover-failure.json',{'type':type(error).__name__,'error':str(error)[:300],
            'backupValid':backup_valid,'changed':changed})
        if changed and backup_valid: restore_old(before)
        else:
            v.command('systemctl','start','lamplit'); wait_production()
        raise


if __name__=='__main__':
    if len(sys.argv)!=4 or sys.argv[2]!='publish': raise SystemExit('Explicit publish and revision required')
    publish(sys.argv[3])
