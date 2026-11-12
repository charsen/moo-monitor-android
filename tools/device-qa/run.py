#!/usr/bin/env python3
"""Explicit, isolated Android monitor scenarios. Uses only the QA package and local SQLite."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
PKG = 'com.mooeen.monitor.deviceqa'
ACTIVITY = PKG + '/.QaActivity'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
    parser.add_argument('--cloud', default=str(ROOT.parent / 'moo-scaffold-cloud'))
    args = parser.parse_args()
    apk = ROOT / 'tools/device-qa/build/outputs/apk/debug/device-qa-debug.apk'
    assert apk.is_file(), 'build with -PmonitorDeviceQa :device-qa:assembleDebug first'
    (ROOT / 'build').mkdir(exist_ok=True)
    state = Path(tempfile.mkdtemp(prefix='monitor-deviceqa-', dir=ROOT / 'build'))
    (state / 'cloud.sqlite').touch()
    (state / 'requests.jsonl').touch()
    control = state / 'control.json'
    env = dict(os.environ, MONITOR_QA_CLOUD_ROOT=args.cloud, MONITOR_QA_STATE=str(state),
               APP_ENV='testing', APP_DEBUG='false', DB_CONNECTION='sqlite', DB_DATABASE=str(state / 'cloud.sqlite'),
               DB_URL='', CACHE_STORE='array', QUEUE_CONNECTION='sync', MAIL_MAILER='array', SESSION_DRIVER='array',
               PULSE_ENABLED='false', TELESCOPE_ENABLED='false', NIGHTWATCH_ENABLED='false')
    adb = [args.adb, '-s', args.serial]
    results = []

    def command(*parts, check=True, include_stderr=False):
        result = subprocess.run([*adb, *parts], capture_output=True, text=True, timeout=30)
        if check:
            assert result.returncode == 0, (parts, result.stdout, result.stderr)
        return (result.stdout + (result.stderr if include_stderr else '')).strip()

    def mode(value, remaining=0):
        temp = control.with_suffix('.tmp')
        temp.write_text(json.dumps({'mode': value, 'remaining': remaining}))
        temp.replace(control)

    mode('accept')
    command('shell', 'am', 'force-stop', PKG)
    seeded = subprocess.run(['php', str(Path(__file__).with_name('cloud-router.php')), 'seed'],
                            env=env, capture_output=True, text=True, timeout=30)
    assert seeded.returncode == 0, seeded.stderr
    with sqlite3.connect(state / 'cloud.sqlite') as db:
        assert db.execute('select count(*) from projects').fetchone()[0] == 1
        assert db.execute('select count(*) from project_tokens').fetchone()[0] == 2
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    url = f'http://127.0.0.1:{port}/qa/summary'
    server_log = (state / 'server.log').open('w')
    server = subprocess.Popen(['php', '-S', f'127.0.0.1:{port}', str(Path(__file__).with_name('cloud-router.php'))],
                              env=env, stdout=server_log, stderr=subprocess.STDOUT)

    def wait(label, predicate, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(.5)
        raise AssertionError('timeout: ' + label)

    def summary():
        return json.load(urllib.request.urlopen(url, timeout=5))

    def requests(start=0):
        return [json.loads(line) for line in (state / 'requests.jsonl').read_text().splitlines()][start:]

    def private(path):
        return command('exec-out', 'run-as', PKG, 'cat', path, check=False)

    def queued():
        return [json.loads(line) for line in private('files/monitor/queue.jsonl').splitlines() if line.startswith('{')]

    def action(name):
        nonce = str(uuid.uuid4())
        # Some devices asynchronously disable newly installed test apps. Re-enable
        # only this isolated package, and fail on launch errors instead of waiting
        # a minute for a result from an Activity that never started.
        for attempt in range(3):
            command('shell', 'pm', 'enable', PKG)
            launch = command('shell', 'am', 'start', '-n', ACTIVITY, '-f', '0x20000000', '--es', 'qa_action', name, '--es', 'qa_nonce', nonce, include_stderr=True)
            if not any(line.startswith('Error') for line in launch.splitlines()):
                break
            if attempt == 2:
                raise AssertionError('QA Activity launch failed: ' + launch)
            time.sleep(.5)
        if name != 'crash':
            wait('action completed: ' + name, lambda: nonce in private('files/device-qa-result.json'))

    def reset(value='accept', remaining=0):
        command('shell', 'am', 'force-stop', PKG)
        assert command('shell', 'pm', 'clear', PKG) == 'Success'
        command('shell', 'pm', 'enable', PKG)
        mode(value, remaining)
        return int(summary()['count']), len(requests())

    def delivered(total, timeout=60):
        return wait('cloud count ' + str(total), lambda: summary() if int(summary()['count']) == total else None, timeout)

    def passed(name, **details):
        entry = {'scenario': name, 'status': 'passed', **details}
        results.append(entry)
        print(json.dumps(entry, ensure_ascii=False), flush=True)
        (state / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))

    try:
        wait('local server', lambda: server.poll() is None and _reachable(url))
        command('reverse', 'tcp:18764', 'tcp:' + str(port))
        assert 'Success' in command('install', '-r', str(apk))
        command('shell', 'pm', 'enable', PKG)
        print('EVIDENCE ' + str(state), flush=True)

        before, start = reset()
        action('capture')
        delivered(before + 1)
        wait('queue acknowledged', lambda: not queued())
        event = requests(start)[0]['records'][0]
        assert event['payload']['password'] == '***'
        metrics = json.loads(private('files/device-qa-result.json'))
        passed('capture-to-laravel', capture_us=metrics['elapsed_us'], kind=event['kind'])

        before, start = reset()
        records = []
        for i in range(180):
            records.append({'event_id': str(uuid.uuid4()), 'platform': 'android', 'kind': 'handled', 'count': 1,
                            'occurred_at': datetime.now(timezone.utc).isoformat(), 'release': '0.1.0', 'build': '1', 'env': 'debug',
                            'exception': {'name': 'DeviceQaBatchException', 'message': 'deviceqa batch'},
                            'context': {'session_id': 'deviceqa-batch'}})
        seed = state / 'queue-seed.jsonl'
        seed.write_text(''.join(json.dumps(record) + '\n' for record in records))
        command('push', str(seed), '/data/local/tmp/monitor-deviceqa-seed.jsonl')
        command('shell', 'run-as', PKG, 'mkdir', '-p', 'files/monitor')
        command('shell', 'run-as', PKG, 'cp', '/data/local/tmp/monitor-deviceqa-seed.jsonl', 'files/monitor/queue.jsonl')
        action('snapshot')
        delivered(before + 180)
        wait('bulk queue acknowledged', lambda: not queued())
        batches = requests(start)
        assert [len(batch['records']) for batch in batches] == [50, 50, 50, 30]
        assert len({r['event_id'] for b in batches for r in b['records']}) == 180
        passed('worker-continuation', batch_sizes=[50, 50, 50, 30])

        before, start = reset('partial', 1)
        action('capture')
        action('capture')
        wait('partial HTTP receipt', lambda: requests(start))
        wait('only unacknowledged remains', lambda: len(queued()) == 1)
        assert int(summary()['count']) == before + 1
        retained = queued()[0]['event_id']
        delivered(before + 2, 75)
        wait('partial queue acknowledged', lambda: not queued())
        assert requests(start)[1]['records'][0]['event_id'] == retained
        passed('partial-receipt-and-retry', batch_sizes=[len(b['records']) for b in requests(start)])

        before, start = reset('retry-after', 1)
        action('capture')
        wait('429', lambda: requests(start))
        assert 'retry_at' in private('shared_prefs/moo-monitor.xml')
        action('flush')
        time.sleep(1)
        assert len(requests(start)) == 1 and len(queued()) == 1
        delivered(before + 1, 75)
        gap = requests(start)[1]['at'] - requests(start)[0]['at']
        assert gap >= 3
        passed('retry-after', gap_seconds=round(gap, 3))

        before, start = reset('hold')
        action('capture')
        wait('offline queued', lambda: len(queued()) == 1)
        event_id = queued()[0]['event_id']
        for _ in range(3):
            command('shell', 'am', 'force-stop', PKG)
            action('snapshot')
            wait('retained across restart', lambda: len(queued()) == 1)
        assert queued()[0]['event_id'] == event_id
        mode('accept')
        action('flush')
        delivered(before + 1, 75)
        passed('offline-three-restarts', stable_event_id=True)

        before, start = reset('unauthorized')
        action('capture')
        wait('401', lambda: requests(start))
        wait('disabled persisted', lambda: 'disabled_scope' in private('shared_prefs/moo-monitor.xml'))
        action('snapshot')
        wait('disabled result', lambda: '"installed":false' in private('files/device-qa-result.json'))
        action('capture')
        assert len(queued()) == 1
        command('shell', 'am', 'force-stop', PKG)
        action('snapshot')
        wait('disabled after restart', lambda: '"installed":false' in private('files/device-qa-result.json'))
        assert len(requests(start)) == 1
        action('token_b')
        mode('accept')
        command('shell', 'am', 'force-stop', PKG)
        action('snapshot')
        delivered(before + 1, 75)
        passed('401-and-token-recovery', retained_records=1)

        before, start = reset('hold')
        action('snapshot')
        wait('initial process', lambda: command('shell', 'pidof', PKG, check=False))
        pid = command('shell', 'pidof', PKG)
        action('crash')
        wait('crash persisted', lambda: 'deviceqa uncaught' in private('files/monitor/crash.jsonl') or
             'deviceqa uncaught' in private('files/monitor/queue.jsonl'))
        wait('system handler terminated process', lambda: command('shell', 'pidof', PKG, check=False) != pid)
        log = command('logcat', '-d', '--pid=' + pid, '-s', 'AndroidRuntime:E', 'MonitorDeviceQa:I', 'MooMonitor:D')
        (state / 'crash-logcat.txt').write_text(log)
        assert 'FATAL EXCEPTION' in log
        metrics = json.loads(private('files/device-qa-crash-metrics.json'))
        command('shell', 'am', 'force-stop', PKG)
        action('snapshot')
        wait('crash merged', lambda: len(queued()) == 1)
        mode('accept')
        action('flush')
        delivered(before + 1, 75)
        assert any(r['kind'] == 'crash' for b in requests(start) for r in b['records'])
        passed('uncaught-crash-and-restart', **metrics)

        before, start = reset()
        action('webview')
        pid = wait('WebView host', lambda: command('shell', 'pidof', PKG, check=False))
        wait('real renderer crash callback', lambda: '"action":"webview_gone"' in private('files/device-qa-result.json'))
        assert command('shell', 'pidof', PKG) == pid
        delivered(before + 1)
        event = requests(start)[0]['records'][0]
        assert event['kind'] == 'webview_gone' and event['payload']['did_crash'] is True
        image = subprocess.check_output([*adb, 'exec-out', 'screencap', '-p'])
        (state / 'renderer-recovered.png').write_bytes(image)
        passed('real-webview-renderer-crash', host_survived=True)

        before, start = reset('hold')
        action('memory')
        wait('manual memory callback', lambda: len(queued()) == 1)
        event = queued()[0]
        assert event['kind'] == 'low_memory' and event['payload']['level'] == 15 and event['context']['foreground'] is True
        mode('accept')
        action('flush')
        delivered(before + 1)
        passed('forwarded-critical-memory-throttle', callbacks=2, recorded=1)

        artifact = {'path': str(apk), 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
        (state / 'artifact.json').write_text(json.dumps(artifact, indent=2))
        (state / 'summary.json').write_text(json.dumps(summary(), indent=2))
        print(json.dumps({'scenarios_passed': len(results), 'evidence': str(state), 'artifact': artifact}), flush=True)
    finally:
        (state / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
        command('shell', 'am', 'force-stop', PKG, check=False)
        command('reverse', '--remove', 'tcp:18764', check=False)
        server.terminate()
        server.wait(timeout=10)
        server_log.close()


def _reachable(url):
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            return response.status == 200
    except OSError:
        return False


if __name__ == '__main__':
    main()
