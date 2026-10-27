import json, os, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PY = os.environ.get('PYTHON', sys.executable)
INFO = ROOT / 'target/browser-fixture.json'
FIXTURE = ROOT / 'src/test/browser/browser_fixture.py'

def run(script):
    print('RUN', script, flush=True)
    return subprocess.call([PY, str(ROOT / 'src/test/browser' / script)], cwd=str(ROOT))

INFO.unlink(missing_ok=True)
log = open(ROOT / 'target/browser-suite.log', 'w', encoding='utf-8')
fixture = subprocess.Popen([PY, str(FIXTURE)], cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT,
                           env={**os.environ, 'PYTHONIOENCODING': 'utf-8'},
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
exit_code = 1
try:
    for _ in range(120):
        if fixture.poll() is not None:
            raise RuntimeError('fixture exited ' + str(fixture.returncode))
        if INFO.exists():
            try:
                json.loads(INFO.read_text(encoding='utf-8'))
                break
            except Exception:
                pass
        time.sleep(.5)
    else:
        raise RuntimeError('fixture ready timeout')
    a = run('slow_json_test.py')
    b = run('sse_same_page_test.py')
    exit_code = 0 if a == 0 and b == 0 else 1
    print(f'SUITE slow_json={a} sse_same_page={b} exit={exit_code}', flush=True)
finally:
    if fixture.poll() is None:
        fixture.terminate()
        try:
            fixture.wait(timeout=15)
        except subprocess.TimeoutExpired:
            fixture.kill(); fixture.wait()
    INFO.unlink(missing_ok=True)
    log.close()
sys.exit(exit_code)
