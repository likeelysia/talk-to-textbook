"""真实旧数据的一致性备份及隔离迁移。生产正文只在明确 backup 动作时短暂停写。
备份与私人数据留在服务器受限目录，evidence/ 仅含计数、摘要和比较结果。
"""
import hashlib, json, os, pathlib, shutil, sqlite3, subprocess, sys, tarfile, time
import server_verify as v

ROOT=v.ROOT
BACKUP_ROOT=pathlib.Path('/var/backups/lamplit')/ROOT.name
PROD=pathlib.Path('/srv/lamplit')

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def manifest(root):
    return {p.relative_to(root).as_posix():sha(p)
            for name in ['data','knowledge','sessions'] for p in sorted((root/name).rglob('*')) if p.is_file()}

def production_paths():
    pid=v.properties('lamplit')['MainPID']
    env=dict(e.split('=',1) for e in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in e)
    defaults={'FA_CONVERSATION_DIR':'data/conversations','FA_MEMORY_DIR':'data/memory',
              'FA_MEMORY_CORE':'data/memory/core.md','FA_MEMORY_CONFLICTS':'data/memory/conflicts.json',
              'FA_NOTE_DIR':'data/notes','FA_UPLOAD_DIR':'data/uploads','FA_VOCAB_NOTEBOOK_FILE':'data/vocab/notebook.json',
              'FA_KNOWLEDGE_FILE':'knowledge/base.json','FA_ACCOUNT_DB':'data/accounts/accounts.db'}
    paths={}
    for key,default in defaults.items():
        p=pathlib.Path(env.get(key) or default)
        p=(PROD/p).resolve() if not p.is_absolute() else p.resolve()
        if PROD not in p.parents: raise RuntimeError('Custom data path outside backup root: '+key)
        paths[key]=p.relative_to(PROD).as_posix()
    return paths

def backup():
    v.stop()
    before=v.production()
    assert before['health']['status']=='UP'
    paths=production_paths()
    if BACKUP_ROOT.exists() and any(BACKUP_ROOT.iterdir()):
        raise RuntimeError('Never overwrite an existing backup')
    BACKUP_ROOT.mkdir(mode=0o700, exist_ok=True)
    started=time.monotonic(); archive=BACKUP_ROOT/'data.tar.gz'; restore_error=None
    try:
        v.command('systemctl','stop','lamplit')
        state=v.properties('lamplit')
        # 旧 unit 未声明 SuccessExitStatus=143，正常 TERM 退出也可能标 failed。
        # 先确认真正停止写入，不能只根据这个标签判断。
        assert state['ActiveState'] in ('inactive', 'failed') and state['MainPID']=='0'
        with v.socket.socket() as connection:
            assert connection.connect_ex(('127.0.0.1',8080)) != 0, 'Production port still open'
        source=manifest(PROD)
        v.write(BACKUP_ROOT/'files.json',source)
        v.write(BACKUP_ROOT/'metadata.json',{'production':before,'paths':paths})
        shutil.copyfile('/etc/lamplit/lamplit.env',BACKUP_ROOT/'lamplit.env')
        os.chmod(BACKUP_ROOT/'lamplit.env',0o600)
        v.command('tar','-czf',str(archive),'-C',str(PROD),'data','knowledge','sessions')
        os.chmod(archive,0o600)
        digest=sha(archive)
        (BACKUP_ROOT/'data.tar.gz.sha256').write_text(digest+'  data.tar.gz\n')
        archived={}
        with tarfile.open(archive,'r:gz') as t:
            for member in t.getmembers():
                if member.isfile(): archived[member.name.removeprefix('./')]=hashlib.sha256(t.extractfile(member).read()).hexdigest()
        assert archived==source, 'Stopped-source / archive manifest mismatch'
    finally:
        try:
            v.command('systemctl','start','lamplit')
            for _ in range(90):
                try:
                    if v.health(8080)['status']=='UP': break
                except Exception: pass
                time.sleep(.5)
            else: raise RuntimeError('Production health did not return after backup')
        except Exception as e: restore_error=e
        if restore_error: raise RuntimeError('Production restart requires attention') from restore_error
    after=v.production()
    assert after['release']==before['release'] and after['sha256']==before['sha256']
    v.write(v.EVIDENCE/'production-backup.json',{'backup':str(BACKUP_ROOT),'sha256':digest,
        'files':len(source),'archiveMatchesStoppedSource':True,'serviceRestored':True,
        'stopBackupStartSeconds':round(time.monotonic()-started,2),'productionAfter':after})
    print(json.dumps({'backup':str(BACKUP_ROOT),'ok':True}))

def restore(runtime):
    archive=BACKUP_ROOT/'data.tar.gz'
    assert sha(archive)==(BACKUP_ROOT/'data.tar.gz.sha256').read_text().split()[0]
    if runtime.exists(): raise RuntimeError('Restore target must be fresh')
    runtime.mkdir()
    with tarfile.open(archive,'r:gz') as t:
        for member in t.getmembers():
            p=(runtime/member.name).resolve()
            if runtime not in p.parents or member.issym() or member.islnk():
                raise RuntimeError('Unsafe archive member')
        t.extractall(runtime,filter='data')
    assert manifest(runtime)==json.loads((BACKUP_ROOT/'files.json').read_text()), 'Restored files differ'
    (runtime/'logs').mkdir(exist_ok=True)
    for p in [runtime,*runtime.rglob('*')]: shutil.chown(p,user='lamplit',group='lamplit')

def legacy_summary(runtime):
    admin=v.Client('verify_admin'); uid=admin.uid
    root=runtime/'data/conversations/users'/uid
    assert (root/'.legacy-userdata-v1').read_text().strip()=='v2'
    checks=[]; chat_count=0; message_count=0
    for p in (runtime/'data/conversations').rglob('*.json'):
        if 'users' in p.relative_to(runtime/'data/conversations').parts: continue
        rel=p.relative_to(runtime/'data/conversations')
        if p.name=='owners.json': target=root/'legacy-owners.json'
        else: target=root/'conversations'/rel
        assert target.is_file() and sha(target)==sha(p), 'Conversation migration differs'
        if p.name not in ['index.json','owners.json']:
            source=json.loads(p.read_text(encoding='utf-8'))
            actual=admin.ok('/api/conversations/'+source['id'])
            def messages(c):
                return [(m.get('role'),m.get('content'),m.get('createdAt')) for m in c.get('messages',[])]
            assert messages(source)==messages(actual)
            chat_count+=1; message_count+=len(source.get('messages',[]))
        checks.append({'type':'conversation','sha256':sha(p),'equal':True})
    memory_count=0
    for p in (runtime/'data/memory').rglob('*.json'):
        if 'users' in p.relative_to(runtime/'data/memory').parts: continue
        target=root/'memory'/p.relative_to(runtime/'data/memory')
        assert target.is_file() and sha(target)==sha(p), 'Memory migration differs'
        source=json.loads(p.read_text(encoding='utf-8')); memory_count+=len(source.get('entries',[]))
        checks.append({'type':'memory','sha256':sha(p),'equal':True})
    notes_path=runtime/'data/notes/notes.json'
    expected=json.loads(notes_path.read_text()).get('notes',[]) if notes_path.exists() else []
    notes=admin.ok('/api/notes/export')['notes']
    assert {n['id'] for n in expected}.issubset({n['id'] for n in notes})
    core_path=runtime/'data/memory/core.md'
    expected_core=core_path.read_text(encoding='utf-8') if core_path.exists() else ''
    assert admin.ok('/api/memory/core')['text']==expected_core
    return {'uid':uid,'conversationFiles':chat_count,'messages':message_count,
            'memoryEntries':memory_count,'legacyNotes':len(expected),'coreChars':len(expected_core),'files':checks}

def migrate():
    runtime=ROOT/'legacy'
    restore(runtime)
    meta=json.loads((BACKUP_ROOT/'metadata.json').read_text())
    # 本轮实机使用默认数据布局；其他布局应先显式映射，不能猜。
    required={'FA_CONVERSATION_DIR':'data/conversations','FA_MEMORY_DIR':'data/memory',
              'FA_MEMORY_CORE':'data/memory/core.md','FA_MEMORY_CONFLICTS':'data/memory/conflicts.json',
              'FA_NOTE_DIR':'data/notes'}
    if any(meta['paths'][k]!=value for k,value in required.items()):
        raise RuntimeError('Custom legacy paths require explicit mapping before startup')
    assert not (runtime/'data/accounts/accounts.db').exists(), 'Existing account DB requires owner-preserving reset in copy'
    v.configure(runtime); v.start()
    before=legacy_summary(runtime)
    original=json.loads((BACKUP_ROOT/'files.json').read_text())
    assert all(sha(runtime/p)==digest for p,digest in original.items()), 'Legacy source changed'
    admin=v.Client('verify_admin')
    admin.ok('/api/admin/accounts','POST',{'username':'migration_user','password':v.settings()['FA_AUTH_PASSWORD'],'role':'USER'})
    user=v.Client('migration_user')
    assert user.ok('/api/notes/export')['count']==0
    assert user.ok('/api/memory/core')['text']==''
    assert not user.ok('/api/conversations/default')['messages']
    assert user.uid!=before['uid']
    v.stop(); v.start()
    after=legacy_summary(runtime)
    assert before==after
    assert all(sha(runtime/p)==digest for p,digest in original.items())
    v.write(v.EVIDENCE/'legacy-migration.json',{'before':before,'after':after,'sameAfterRestart':True,
        'sourcesPreserved':True,'ordinaryUserDoesNotInheritLegacy':True,'backup':str(BACKUP_ROOT)})
    v.stop()
    print(json.dumps({'legacyMigration':True,'summary':{k:value for k,value in before.items() if k!='files'}}))

def rollback():
    runtime=ROOT/'rollback'
    restore(runtime)
    candidate=ROOT/'app.jar'; saved=ROOT/'candidate-held.jar'
    # 只在隔离目录切换，生产 current 不变。
    candidate.rename(saved)
    try:
        shutil.copyfile('/opt/lamplit/current/app.jar',candidate)
        v.configure(runtime); v.start()
        state=v.health()
        assert state['knowledgeCount']==7287
        assert all(sha(runtime/p)==digest for p,digest in json.loads((BACKUP_ROOT/'files.json').read_text()).items())
        v.write(v.EVIDENCE/'rollback-rehearsal.json',{'oldJarStartsWithRestoredBackup':True,
            'health':state,'backupFilesUnchanged':True,'oldJarSha256':sha(candidate)})
    finally:
        v.stop()
        candidate.unlink(missing_ok=True)
        saved.rename(candidate)
    print(json.dumps({'rollbackRehearsal':True}))

if __name__=='__main__':
    action=sys.argv[2]
    if action=='backup': backup()
    elif action=='migrate': migrate()
    elif action=='rollback': rollback()
    else: raise SystemExit('Unknown action')
