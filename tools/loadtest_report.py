#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""container-gateway 压测报表：把「执行超时失败」与「容量失败」分开统计。

新版审计（本次修复后）在 error 事件的 params.failureKind 中写入：
  exec-timeout / backend-timeout / capacity / node-unreachable
旧版审计没有该字段，只能按 errorCode + 耗时回退推断，报表里标记为 legacy。

用法：
  python3 tools/loadtest_report.py \
    --audit /opt/container-gateway/logs/audit.log \
    --window "10并发|2026-11-04 19:27:00|2026-11-04 19:38:00" \
    --window "20并发|2026-11-04 21:07:00|2026-11-04 21:25:00" \
    --window "30并发|2026-11-04 21:35:00|2026-11-04 21:52:00" \
    --tz +08:00 --out report.md
"""
import argparse
import json
from datetime import datetime, timedelta, timezone


def parse_tz(text):
    sign = -1 if text.startswith('-') else 1
    hh, mm = text.lstrip('+-').split(':')
    return timezone(sign * timedelta(hours=int(hh), minutes=int(mm)))


def parse_local(text, tz):
    return datetime.strptime(text, '%Y-%m-%d %H:%M:%S').replace(tzinfo=tz).timestamp()


def load_events(path):
    events = []
    with open(path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                outer = json.loads(line)
                if isinstance(outer, str):
                    outer = json.loads(outer)
            except json.JSONDecodeError:
                continue
            ev = outer.get('event', outer)
            ts = outer.get('ts') or ev.get('ts')
            if not ts:
                continue
            try:
                epoch = datetime.fromisoformat(ts.replace('Z', '+00:00')).timestamp()
            except ValueError:
                continue
            events.append((epoch, ev))
    events.sort(key=lambda item: item[0])
    return events


def failure_kind(ev):
    """返回 (分类, 是否旧数据推断)。"""
    params = ev.get('params') or {}
    kind = params.get('failureKind')
    if kind:
        return kind, False
    action = ev.get('action')
    code = ev.get('errorCode')
    duration = ev.get('durationMs') or 0
    if action == 'exec' and code in ('EXEC_TIMEOUT', 'NODE_UNAVAILABLE') and 59000 <= duration <= 61000:
        return 'exec-timeout', True
    if action == 'create' and code == 'NODE_UNAVAILABLE':
        return 'capacity-or-node-unreachable', True
    return 'other', False


def percentile(values, ratio):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * ratio))]


def max_alive(events):
    created = {}
    destroyed = {}
    for epoch, ev in events:
        action = ev.get('action')
        cid = ev.get('containerId')
        if not cid:
            continue
        if action == 'create' and ev.get('result') == 'ok':
            created[cid] = epoch
        elif action in ('destroy', 'remove'):
            destroyed[cid] = epoch
    points = []
    for cid, start in created.items():
        points.append((start, 1))
        points.append((destroyed.get(cid, start + 10 ** 9), -1))
    points.sort(key=lambda item: (item[0], -item[1]))
    current = 0
    peak = 0
    for _, delta in points:
        current += delta
        peak = max(peak, current)
    return len(created), peak


def summarize(name, start, end, events):
    window = [item for item in events if start <= item[0] <= end]
    created, peak = max_alive(window)
    execs = [ev for _, ev in window if ev.get('action') == 'exec']
    creates = [ev for _, ev in window if ev.get('action') == 'create']
    ok = [ev for ev in execs if ev.get('result') == 'ok']
    exec_failed = [ev for ev in execs if ev.get('result') == 'error']
    create_failed = [ev for ev in creates if ev.get('result') == 'error']
    buckets = {}
    legacy = []
    for ev in exec_failed + create_failed:
        kind, inferred = failure_kind(ev)
        buckets.setdefault(kind, []).append(ev)
        if inferred:
            legacy.append((kind, ev.get('errorCode'), ev.get('durationMs')))
    durations = [ev.get('durationMs') for ev in ok if isinstance(ev.get('durationMs'), (int, float))]
    short = [value for value in durations if value < 10000]
    timeouts = len(buckets.get('exec-timeout', [])) + len(buckets.get('backend-timeout', []))
    capacity = len(buckets.get('capacity', [])) + len(buckets.get('capacity-or-node-unreachable', []))
    others = len(exec_failed) - timeouts
    failed = len(exec_failed)
    return {
        'name': name,
        'created': created,
        'peak': peak,
        'exec_total': len(execs),
        'ok': len(ok),
        'failed': failed,
        'create_failed': len(create_failed),
        'timeout': timeouts,
        'capacity': capacity,
        'other': others,
        'p50': percentile(durations, 0.50),
        'p95': percentile(durations, 0.95),
        'short_p50': percentile(short, 0.50),
        'short_p95': percentile(short, 0.95),
        'buckets': {key: len(value) for key, value in buckets.items() if value},
        'legacy': legacy,
    }


def render(rows, source, tz_label):
    lines = ['# container-gateway 压测报表（超时失败 / 容量失败分离）', '',
             f'- 数据源：`{source}`',
             f'- 时间基准：本地 {tz_label}',
             '- 分类口径：新数据读审计 params.failureKind；旧数据缺该字段时按“exec + 59~61s”推断并标注 legacy，'
             '创建阶段的 NODE_UNAVAILABLE 旧数据无法区分容量与节点不可达，单列 legacy。', '']
    header = ('| 窗口 | 会话(创建容器) | 最大存活 | exec总数 | 成功 | exec失败 | 超时失败 | 容量失败 '
              '| 其他exec失败 | 创建失败 | 成功率 | 短命令p50(ms) | 短命令p95(ms) | 全部p50(ms) | 全部p95(ms) |')
    lines.append(header)
    lines.append('|' + '---|' * 15)
    for row in rows:
        rate = f"{(100.0 * row['ok'] / row['exec_total']):.1f}%" if row['exec_total'] else '-'
        lines.append('| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |'.format(
            row['name'], row['created'], row['peak'], row['exec_total'], row['ok'], row['failed'],
            row['timeout'], row['capacity'], row['other'], row['create_failed'], rate,
            row['short_p50'] if row['short_p50'] is not None else '-',
            row['short_p95'] if row['short_p95'] is not None else '-',
            row['p50'] if row['p50'] is not None else '-',
            row['p95'] if row['p95'] is not None else '-'))
    lines.append('')
    lines.append('## 分类明细')
    for row in rows:
        detail = ', '.join(f'{key}={value}' for key, value in sorted(row['buckets'].items())) or '无失败'
        lines.append(f"- **{row['name']}**：{detail}")
        if row['legacy']:
            sample = ', '.join(f'{kind}/{code}/{duration}ms' for kind, code, duration in row['legacy'][:5])
            lines.append(f"  - legacy 推断样本（最多 5 条）：{sample}")
    lines.append('')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--audit', required=True)
    parser.add_argument('--window', action='append', required=True,
                        help='名称|开始(本地)|结束(本地)')
    parser.add_argument('--tz', default='+08:00')
    parser.add_argument('--out')
    args = parser.parse_args()

    zone = parse_tz(args.tz)
    events = load_events(args.audit)
    rows = []
    for spec in args.window:
        name, start_text, end_text = (part.strip() for part in spec.split('|'))
        rows.append(summarize(name, parse_local(start_text, zone), parse_local(end_text, zone), events))

    report = render(rows, args.audit, args.tz)
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as fh:
            fh.write(report + '\n')
    print(report)


if __name__ == '__main__':
    main()
