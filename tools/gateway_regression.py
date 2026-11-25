#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""container-gateway 回归验证：生命周期 / exec / 文件 / 日志 / 错误契约 / 并发 / 审计结构。"""
import io
import json
import random
import string
import sys
import tarfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = 'http://127.0.0.1:3010/api/v1/containers'
ENV_FILE = '/etc/container-gateway.env'
IMAGE = 'sandbox-runtime:2026.1104'
AUDIT = '/opt/container-gateway/logs/audit.log'


def load_token():
    for line in open(ENV_FILE, encoding='utf-8'):
        if line.startswith('GATEWAY_TOKENS='):
            value = line.split('=', 1)[1].strip().strip('"')
            return value.split(',')[0].split(':')[0].strip()
    raise RuntimeError('GATEWAY_TOKENS not found')


TOKEN = load_token()
RESULTS = []


def record(name, ok, detail=''):
    RESULTS.append({'name': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + (' | ' + detail if detail else ''))


def http(method, path, body=None, timeout=60, auth=True, raw=False):
    data = None if body is None else (body if isinstance(body, (bytes, bytearray)) else json.dumps(body).encode())
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if auth:
        req.add_header('Authorization', 'Bearer ' + TOKEN)
    if body is not None and not isinstance(body, (bytes, bytearray)):
        req.add_header('Content-Type', 'application/json')
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        payload = exc.read()
        status = exc.code
    except Exception as exc:
        return {'status': None, 'error': repr(exc), 'elapsed': time.time() - started}
    parsed = payload
    if not raw:
        try:
            parsed = json.loads(payload) if payload else None
        except json.JSONDecodeError:
            parsed = payload[:200].decode('utf-8', 'replace')
    return {'status': status, 'body': parsed, 'elapsed': time.time() - started}


def create(name):
    return http('POST', '', {
        'image': IMAGE, 'memoryBytes': 536870912, 'cpus': 0.5,
        'ttlSeconds': 1200, 'name': name, 'meta': {'purpose': 'regression'},
    })


def exec_cmd(cid, cmd, timeout_seconds):
    return http('POST', '/' + cid + '/exec',
                {'cmd': ['sh', '-c', cmd], 'timeoutSeconds': timeout_seconds},
                timeout=timeout_seconds + 60)


def make_tar(name, content):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w') as tar:
        data = content.encode()
        info = tarfile.TarInfo(name)
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def read_tar_member(blob, name):
    with tarfile.open(fileobj=io.BytesIO(blob), mode='r') as tar:
        member = tar.getmember(name)
        return tar.extractfile(member).read().decode()


def audit_since(epoch):
    rows = []
    for line in open(AUDIT, encoding='utf-8', errors='replace'):
        line = line.strip()
        if not line:
            continue
        try:
            outer = json.loads(line)
            if isinstance(outer, str):
                outer = json.loads(outer)
        except json.JSONDecodeError:
            continue
        ts = outer.get('ts')
        if not ts:
            continue
        from datetime import datetime
        if datetime.fromisoformat(ts.replace('Z', '+00:00')).timestamp() >= epoch:
            rows.append(outer.get('event', outer))
    return rows


def main():
    started = time.time()
    suffix = ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))
    created = []
    try:
        for i in range(3):
            resp = create(f'reg-{suffix}-{i}')
            if resp['status'] != 201:
                record('create', False, str(resp))
                continue
            created.append(resp['body'])
        record('create 3 containers', len(created) == 3, 'nodes=' + ','.join(c.get('nodeId', '') for c in created))
        w1 = [c for c in created if c.get('nodeId') == 'w1']
        local = [c for c in created if c.get('nodeId') == 'local']
        if not w1 or not local:
            record('node coverage', False, 'need both w1 and local')
            return 1

        # 生命周期
        cid = local[0]['id']
        r = http('GET', '/' + cid)
        record('get container', r['status'] == 200 and r['body'].get('id') == cid)
        r = http('GET', '')
        record('list contains container', r['status'] == 200 and any(x.get('id') == cid for x in r['body']))
        record('stop', http('POST', '/' + cid + '/stop?timeoutSeconds=5')['status'] == 200)
        record('start', http('POST', '/' + cid + '/start')['status'] == 200)
        r = exec_cmd(cid, 'echo lifecycle-ok', 30)
        record('exec after restart', r['status'] == 200 and 'lifecycle-ok' in (r['body'].get('stdout') or ''))

        # exec 契约与超时
        target = w1[0]['id']
        r = exec_cmd(target, 'echo exec-ok', 30)
        body = r['body'] or {}
        record('exec shape unchanged',
               r['status'] == 200 and set(body) == {'exitCode', 'stdout', 'stderr', 'truncated', 'durationMs'})
        r = exec_cmd(target, 'sleep 75; echo silent-ok', 90)
        record('exec silent 75s', r['status'] == 200 and 70 <= r['elapsed'] <= 100,
               f"status={r['status']} elapsed={r['elapsed']:.1f}s")
        r = exec_cmd(target, 'sleep 8', 5)
        record('exec timeout -> 409', r['status'] == 409 and (r['body'] or {}).get('error', {}).get('code') == 'STATE_CONFLICT')
        r = exec_cmd(target, 'echo x', 0)
        record('invalid timeout -> 400', r['status'] == 400)
        r = http('GET', '/' + ('a' * 64))
        record('unknown container -> 404', r['status'] == 404 and (r['body'] or {}).get('error', {}).get('code') == 'NOT_FOUND')
        r = http('GET', '', auth=False)
        record('missing token -> 401', r['status'] == 401)

        # 文件 API
        r = exec_cmd(target, "mkdir -p /workspace/out && printf 'file-reg-ok' > /workspace/out/reg.txt", 30)
        record('prepare output file', r['status'] == 200)
        r = http('GET', '/' + target + '/files?path=/workspace/out')
        record('file list', r['status'] == 200 and any(x.get('name') == 'reg.txt' for x in r['body']),
               str(r['body'])[:120] if r['status'] == 200 else str(r['status']))
        r = http('GET', '/' + target + '/files/download?path=/workspace/out/reg.txt', raw=True, timeout=60)
        ok = r['status'] == 200 and isinstance(r['body'], bytes)
        if ok:
            try:
                ok = read_tar_member(r['body'], 'reg.txt') == 'file-reg-ok'
            except Exception:
                ok = False
        record('file download', ok)
        r = http('POST', '/' + target + '/files/upload?dest=/workspace/input', make_tar('reg-upload.txt', 'upload-ok'),
                 timeout=60)
        record('file upload', r['status'] == 201 and (r['body'] or {}).get('fileCount') == 1, str(r['body'])[:120])
        r = exec_cmd(target, 'cat /workspace/input/reg-upload.txt', 30)
        record('uploaded content readable', 'upload-ok' in ((r['body'] or {}).get('stdout') or ''))

        # 日志
        r = http('GET', '/' + target + '/logs?tail=20', raw=True)
        record('logs', r['status'] == 200)

        # 30 并发静默命令
        pool_ids = [c['id'] for c in w1] or [target]
        def job(index):
            cid = pool_ids[index % len(pool_ids)]
            resp = exec_cmd(cid, 'sleep 65; echo ok', 90)
            return resp['status']
        batch_started = time.time()
        with ThreadPoolExecutor(max_workers=30) as pool:
            statuses = list(pool.map(job, range(30)))
        record('30 concurrent silent exec', all(s == 200 for s in statuses),
               f"ok={sum(1 for s in statuses if s == 200)}/30 wall={time.time() - batch_started:.1f}s")

        # 审计结构
        time.sleep(1)
        events = audit_since(started)
        success_keys = {'requestId', 'clientId', 'sourceIp', 'action', 'containerId',
                        'image', 'hostPorts', 'params', 'result', 'errorCode', 'durationMs'}
        bad_schema = [e for e in events if e.get('result') == 'ok' and set(e) != success_keys]
        record('audit success schema unchanged', not bad_schema, f'events={len(events)}')
        bad_codes = [e for e in events if e.get('errorCode') in ('NODE_UNAVAILABLE', 'EXEC_TIMEOUT')]
        record('no 503 / exec-timeout in audit', not bad_codes, str([e.get('errorCode') for e in bad_codes]))
        conflicts = [e for e in events if e.get('errorCode') == 'STATE_CONFLICT']
        record('expected 409 audited', len(conflicts) >= 1)
        bad_kind = [e for e in events if (e.get('params') or {}).get('failureKind') not in
                    (None, 'exec-timeout', 'backend-timeout', 'capacity', 'node-unreachable')]
        record('failureKind values valid', not bad_kind)
    finally:
        for c in created:
            http('POST', '/' + c['id'] + '/destroy', timeout=60)
        print(json.dumps(RESULTS, ensure_ascii=False, indent=2))

    failed = [r for r in RESULTS if not r['ok']]
    print(f'\nTOTAL {len(RESULTS)}  PASS {len(RESULTS) - len(failed)}  FAIL {len(failed)}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
