#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exec 超时修复验收：静默长命令 + 10/20/30 并发，全部要求 200。"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = 'http://127.0.0.1:3010/api/v1/containers'
ENV_FILE = '/etc/container-gateway.env'
IMAGE = 'sandbox-runtime:2026.1104'


def load_token():
    for line in open(ENV_FILE, encoding='utf-8'):
        line = line.strip()
        if line.startswith('GATEWAY_TOKENS='):
            value = line.split('=', 1)[1].strip().strip('"')
            first = value.split(',')[0].strip()
            return first.split(':')[0].strip()
    raise RuntimeError('GATEWAY_TOKENS not found')


TOKEN = load_token()


def call(method, path, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        status = exc.code
    except Exception as exc:  # 连接/读超时等
        return {'status': None, 'error': repr(exc), 'elapsed': time.time() - started, 'body': None}
    elapsed = time.time() - started
    try:
        parsed = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        parsed = raw[:500].decode('utf-8', 'replace')
    return {'status': status, 'body': parsed, 'elapsed': elapsed}


def create(name):
    return call('POST', '', {
        'image': IMAGE,
        'memoryBytes': 536870912,
        'cpus': 0.5,
        'ttlSeconds': 1200,
        'name': name,
        'meta': {'purpose': 'exec-timeout-acceptance'},
    }, timeout=60)


def exec_cmd(cid, cmd, timeout_seconds):
    return call('POST', '/' + cid + '/exec', {
        'cmd': ['sh', '-c', cmd],
        'timeoutSeconds': timeout_seconds,
    }, timeout=timeout_seconds + 60)


def destroy(cid):
    call('POST', '/' + cid + '/destroy', timeout=60)


def main():
    suffix = str(int(time.time()))
    created = []
    results = {'single': [], 'boundary': [], 'concurrency': []}
    try:
        for i in range(6):
            resp = create(f'accept-exec-{suffix}-{i}')
            if resp['status'] != 201:
                print('create failed', resp)
                continue
            created.append({'id': resp['body']['id'], 'node': resp['body'].get('nodeId')})
        w1 = [c for c in created if c['node'] == 'w1']
        print('created', len(created), 'w1', len(w1))
        if not w1:
            raise RuntimeError('没有创建到 w1 节点容器，无法验证远程 TLS 路径')

        target = w1[0]['id']
        # 1) 旧版本必现 503 的场景：105 秒完全静默
        r = exec_cmd(target, 'sleep 105; echo silent_done', 120)
        results['single'].append({
            'name': 'sleep105_silent', 'status': r['status'],
            'duration_s': round(r['elapsed'], 1),
            'exitCode': (r.get('body') or {}).get('exitCode') if isinstance(r.get('body'), dict) else None,
        })
        # 2) 边界：执行超时仍必须返回 409，而不是 503
        r = exec_cmd(target, 'sleep 8', 5)
        results['boundary'].append({
            'name': 'sleep8_timeout5', 'status': r['status'],
            'errorCode': (r.get('body') or {}).get('error', {}).get('code') if isinstance(r.get('body'), dict) else None,
        })
        # 3) 10/20/30 并发静默命令（每条 65s，旧版本会 503）
        pools = [c['id'] for c in created]
        for concurrency in (10, 20, 30):
            def job(index):
                cid = pools[index % len(pools)]
                resp = exec_cmd(cid, 'sleep 65; echo ok', 90)
                return resp['status'], round(resp['elapsed'], 1), cid
            started = time.time()
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                rows = list(pool.map(job, range(concurrency)))
            ok = [row for row in rows if row[0] == 200]
            results['concurrency'].append({
                'concurrency': concurrency,
                'requests': len(rows),
                'ok': len(ok),
                'failed': len(rows) - len(ok),
                'wall_s': round(time.time() - started, 1),
                'durations': sorted(row[1] for row in rows),
            })
    finally:
        for c in created:
            destroy(c['id'])
        print(json.dumps(results, ensure_ascii=False, indent=2))
        with open('/root/container-gateway/exec-timeout-acceptance.json', 'w', encoding='utf-8') as fh:
            json.dump({'created': created, 'results': results}, fh, ensure_ascii=False, indent=2)

    failed = 0
    for row in results['single']:
        failed += 0 if row['status'] == 200 and row['duration_s'] >= 100 else 1
    for row in results['boundary']:
        failed += 0 if row['status'] == 409 else 1
    for row in results['concurrency']:
        failed += row['failed']
    return 0 if failed == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
