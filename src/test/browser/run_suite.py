import json, os, subprocess, sys, time, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PY = os.environ.get('PYTHON', sys.executable)
RUN = ROOT / 'target/browser-runs' / (time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
RUN.mkdir(parents=True, exist_ok=False)
INFO = RUN / 'fixture.json'
os.environ['PROJECT_ROOT'] = str(ROOT)
os.environ['BROWSER_RUN_DIR'] = str(RUN / 'runtime')
os.environ['BROWSER_INFO'] = str(INFO)
os.environ['BROWSER_OUT'] = str(RUN / 'evidence')
os.environ['BROWSER_RESULTS'] = str(RUN)
FIXTURE = ROOT / 'src/test/browser/browser_fixture.py'

def run(script):
    print('RUN', script, flush=True)
    return subprocess.call([PY, str(ROOT / 'src/test/browser' / script)], cwd=str(ROOT), env=os.environ, timeout=180)

def kill_tree(pid):
    if pid:
        subprocess.call(['taskkill', '/PID', str(pid), '/T', '/F'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

log = open(RUN / 'suite.log', 'w', encoding='utf-8')
fixture = subprocess.Popen([PY, str(FIXTURE)], cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT,
                           env={**os.environ, 'PYTHONIOENCODING': 'utf-8'},
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
exit_code = 1
try:
    for _ in range(120):
        if INFO.exists():
            try:
                json.loads(INFO.read_text(encoding='utf-8'))
                break
            except Exception:
                pass
        if fixture.poll() is not None:
            raise RuntimeError('fixture exited before ready')
        time.sleep(.5)
    else:
        raise RuntimeError('fixture ready timeout')
    negative = os.environ.get('FIXTURE_FAIL_AFTER_CANCEL') == '1'
    a = 0 if negative else run('slow_json_test.py')
    b = 0 if negative else run('sse_same_page_test.py')
    c = 0 if negative else run('expected_user_test.py')
    d = run('learning_chain_test.py')
    exit_code = 0 if a == 0 and b == 0 and c == 0 and d == 0 else 1
    print(f'SUITE slow_json={a} sse_same_page={b} expected_user={c} learning_chain={d} exit={exit_code}', flush=True)
finally:
    pid_file = RUN / 'runtime' / 'java.pid'
    pid = None
    if pid_file.exists():
        try: pid = int(pid_file.read_text(encoding='ascii').strip())
        except Exception: pid = None
    kill_tree(pid)
    if fixture.poll() is None:
        kill_tree(fixture.pid)
        try: fixture.wait(timeout=15)
        except subprocess.TimeoutExpired: fixture.kill(); fixture.wait()
    INFO.unlink(missing_ok=True)
    log.close()
    print(f'RUN_DIR={RUN} EXIT={exit_code}', flush=True)
sys.exit(exit_code)
