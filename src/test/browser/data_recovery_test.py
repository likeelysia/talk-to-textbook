"""隔离 JAR 实例的重启/备份/恢复演练，只生成合成账号与数据。"""
import hashlib, json, os, secrets, shutil, socket, subprocess, sys, time, traceback, uuid
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]
PY = sys.executable
PS = os.environ.get('POWERSHELL') or shutil.which('pwsh') or 'powershell'
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def start(run, runtime, reuse=True):
    info = run / 'fixture.json'; info.unlink(missing_ok=True)
    env = {**os.environ, 'PROJECT_ROOT': str(ROOT), 'BROWSER_RUN_DIR': str(runtime),
           'BROWSER_INFO': str(info), 'FIXTURE_REUSE_RUNTIME': '1' if reuse else '0',
           'PYTHONIOENCODING': 'utf-8'}
    log = open(run / ('fixture-' + uuid.uuid4().hex[:8] + '.log'), 'w', encoding='utf-8')
    proc = subprocess.Popen([PY, str(ROOT / 'src/test/browser/browser_fixture.py')],
                            env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
    log.close()
    write(run / 'controller.json', {'pid': proc.pid, 'runtime': str(runtime)})
    for _ in range(120):
        if info.exists(): return json.loads(info.read_text(encoding='utf-8'))
        if proc.poll() is not None: raise RuntimeError('fixture exited before ready')
        time.sleep(.5)
    raise RuntimeError('fixture startup timed out')

def stop(run):
    statefile = run / 'controller.json'
    if not statefile.exists(): return
    state = json.loads(statefile.read_text(encoding='utf-8'))
    pidfile = Path(state['runtime']) / 'java.pid'
    # PID 均来自本次创建的实例；先停 Java 写入，再停模型替身。
    for pid in ([int(pidfile.read_text())] if pidfile.exists() else []) + [state['pid']]:
        subprocess.run(['taskkill', '/PID', str(pid), '/T', '/F'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN)
    infofile = run / 'fixture.json'
    if infofile.exists():
        info = json.loads(infofile.read_text(encoding='utf-8'))
        port = int(info['base'].rsplit(':', 1)[1])
        for _ in range(50):
            with socket.socket() as s:
                if s.connect_ex(('127.0.0.1', port)) != 0: break
            time.sleep(.1)
        else: raise RuntimeError('Java port still open after stop')
        infofile.unlink()
    statefile.unlink()

def manifest(runtime):
    answer = {}
    for name in ('data', 'knowledge', 'sessions'):
        for path in sorted((runtime / name).rglob('*')):
            if path.is_file(): answer[path.relative_to(runtime).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return answer

def quoted(value):
    return "'" + str(value).replace("'", "''") + "'"

if len(sys.argv) > 1:
    run = Path(sys.argv[2])
    if sys.argv[1] == '--start': start(run, Path(sys.argv[3]))
    elif sys.argv[1] == '--stop': stop(run)
    else: raise SystemExit(2)
    raise SystemExit(0)

RUN = ROOT / 'target/recovery-runs' / (time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
RUN.mkdir(parents=True)
RUNTIME = RUN / 'original'
os.environ['FIXTURE_PASSWORD'] = secrets.token_urlsafe(18)
results = []
owned = {}

def rec(name, condition, detail=None):
    results.append({'name': name, 'passed': bool(condition), 'detail': detail or {}})
    assert condition, name

def snapshot(pw, info, seed=False):
    snapshots = {}
    for label in ('A', 'B'):
        client = pw.request.new_context(base_url=info['base'])
        try:
            token = client.get('/api/auth/csrf').json()['token']
            r = client.post('/login', form={'username': 'review_' + label,
                            'password': info['password'], '_csrf': token}, max_redirects=0)
            assert r.status == 302
            me = client.get('/api/auth/me').json()
            client_headers = {'X-XSRF-TOKEN': client.get('/api/auth/csrf').json()['token'],
                              'X-Expected-User-Id': me['userId']}
            def call(path, method='GET', data=None):
                r = client.fetch(path, method=method, data=data, headers=client_headers)
                assert r.status == 200, (path, r.status, r.text()[:300])
                return r.json()
            if seed:
                cid = call('/api/conversations', 'POST', {'title': label + '_RECOVERY'})['id']
                q = 'CHAIN_RECOVERY_' + label
                answer = call('/api/agent', 'POST', {'question': q, 'conversationId': cid})
                assert answer['answer'] == 'ANSWER_' + q
                nid = call('/api/notes', 'POST', {'conversationId': cid, 'indexes': [0]})['saved'][0]['id']
                call('/api/memory/core', 'PUT', {'text': label + '_PRIVATE_CORE'})
                mid = call('/api/memory', 'POST', {'conversationId': cid, 'text': label + '_PRIVATE_MEMORY'})['id']
                owned[label] = {'cid': cid, 'nid': nid, 'mid': mid}
            ids = owned[label]
            snapshots[label] = {'identity': me, 'conversation': call('/api/conversations/' + ids['cid']),
                'core': call('/api/memory/core'),
                'memory': call('/api/memory?conversationId=' + ids['cid']),
                'notes': call('/api/notes/export')}
            assert snapshots[label]['core']['text'] == label + '_PRIVATE_CORE'
            assert any(m['id'] == ids['mid'] and m['text'] == label + '_PRIVATE_MEMORY'
                       for m in snapshots[label]['memory']['memories'])
            assert any(n['id'] == ids['nid'] for n in snapshots[label]['notes']['notes'])
            other = owned.get('B' if label == 'A' else 'A')
            if other:
                r = client.get('/api/conversations/' + other['cid'])
                assert r.status in (400, 403, 404)
                r = client.patch('/api/notes/' + other['nid'], data={'question': 'INTRUSION'},
                                 headers=client_headers)
                assert r.status in (400, 403, 404)
        finally:
            client.dispose()
    return snapshots

try:
    with sync_playwright() as pw:
        info = start(RUN, RUNTIME, reuse=False)
        baseline = snapshot(pw, info, seed=True)
        write(RUN / 'baseline.json', baseline)
        rec('A_B_seeded_chat_memory_core_notes', len(baseline) == 2)
        stop(RUN)  # 真实终止进程，然后从同一数据目录启动新 JVM。
        info = start(RUN, RUNTIME)
        restarted = snapshot(pw, info)
        write(RUN / 'restarted.json', restarted)
        rec('restart_preserves_identity_content_and_ownership', restarted == baseline)
        script = Path(__file__).resolve()
        stop_cmd = '& ' + quoted(PY) + ' ' + quoted(script) + ' --stop ' + quoted(RUN)
        start_cmd = '& ' + quoted(PY) + ' ' + quoted(script) + ' --start ' + quoted(RUN) + ' ' + quoted(RUNTIME)
        with open(RUN / 'backup.log', 'w', encoding='utf-8') as log:
            r = subprocess.run([PS, '-NoProfile', '-File', str(ROOT / 'scripts/backup.ps1'),
                '-Root', str(RUNTIME), '-Out', str(RUN / 'backups'), '-StopWaitSeconds', '0',
                '-StopCommand', stop_cmd, '-StartCommand', start_cmd],
                env=os.environ, stdout=log, stderr=subprocess.STDOUT, timeout=150, creationflags=HIDDEN)
        rec('actual_backup_script_stop_archive_restart', r.returncode == 0, {'exit': r.returncode})
        info = json.loads((RUN / 'fixture.json').read_text(encoding='utf-8'))
        rec('backup_restarted_service_readable', snapshot(pw, info) == baseline)
        stop(RUN)
        source_manifest = manifest(RUNTIME); write(RUN / 'original-manifest.json', source_manifest)
        archive = next((RUN / 'backups').glob('*.tar.gz'))
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        expected = Path(str(archive) + '.sha256').read_text().split()[0].lower()
        rec('archive_checksum', digest == expected, {'sha256': digest})
        restored_dir = RUN / 'restored'
        with open(RUN / 'restore.log', 'w', encoding='utf-8') as log:
            r = subprocess.run([PS, '-NoProfile', '-File', str(ROOT / 'scripts/restore.ps1'),
                '-Archive', str(archive), '-Target', str(restored_dir)],
                stdout=log, stderr=subprocess.STDOUT, timeout=60, creationflags=HIDDEN)
        rec('actual_restore_script', r.returncode == 0, {'exit': r.returncode})
        rec('restored_files_match_source', manifest(restored_dir) == source_manifest)
        info = start(RUN, restored_dir)
        restored = snapshot(pw, info); write(RUN / 'restored.json', restored)
        rec('restored_new_JVM_reads_A_B_data_and_denies_cross_owner', restored == baseline)
        rec('original_directory_untouched_by_restore', manifest(RUNTIME) == source_manifest)
        stop(RUN)
        # 反向验证：已有数据的目录必须拒绝覆盖。
        with open(RUN / 'restore-refused.log', 'w', encoding='utf-8') as log:
            r = subprocess.run([PS, '-NoProfile', '-File', str(ROOT / 'scripts/restore.ps1'),
                '-Archive', str(archive), '-Target', str(RUNTIME)],
                stdout=log, stderr=subprocess.STDOUT, timeout=60, creationflags=HIDDEN)
        rec('restore_refuses_existing_data', r.returncode != 0 and manifest(RUNTIME) == source_manifest)
except Exception as e:
    traceback.print_exc()
    results.append({'name': 'recovery_exception', 'passed': False, 'detail': {'error': str(e)}})
finally:
    try:
        stop(RUN)
        rec('owned_processes_stopped_credentials_removed', not (RUN / 'fixture.json').exists())
    except Exception as e:
        results.append({'name': 'cleanup_failed', 'passed': False, 'detail': {'error': str(e)}})
    os.environ.pop('FIXTURE_PASSWORD', None)
    write(RUN / 'results.json', results)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    print('RUN_DIR=' + str(RUN))
if not results or not all(r['passed'] for r in results): raise SystemExit(1)
