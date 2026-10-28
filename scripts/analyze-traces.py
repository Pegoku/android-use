#!/usr/bin/env python3
"""Summarize an Android Use diagnostics ZIP without extracting private files."""
import collections
import json
import statistics
import sys
import zipfile


def read_events(text):
    events = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            if index != len(lines) - 1:
                raise
            # An export taken while a task runs may end partway through an append.
    return events


def cycles(observations):
    """Find repeated screen sequences, not proof of an agent mistake."""
    found = []
    keys = [(e.get('package'), e.get('signature')) for e in observations]
    for end in range(4, len(keys) + 1):
        for width in range(2, 5):
            if end < width * 2:
                continue
            before, after = keys[end - width * 2:end - width], keys[end - width:end]
            if before == after and len(set(after)) > 1 and all(key[1] is not None for key in after):
                found.append((observations[end - width * 2].get('step'), observations[end - 1].get('step'), width))
                break
    return found


def analyze(path):
    with zipfile.ZipFile(path) as archive:
        chats = json.loads(archive.read('chats.json'))
        messages = [m for chat in chats for m in chat.get('messages', [])]
        tasks = [m for m in messages if m.get('role') == 'Task']
        print(f"Saved chats: {len(chats)}; task entries: {len(tasks)}")
        print(f"Succeeded: {sum(m.get('success') is True for m in tasks)}; failed/stopped: {sum(m.get('success') is False for m in tasks)}; unfinished: {sum(m.get('success') is None for m in tasks)}")
        runs = sorted(n for n in archive.namelist() if n.startswith('runs/') and n.endswith('.jsonl'))
        if not runs:
            print('No action traces. Older chats contain outcomes only; reproduce a task with tracing installed.')
        for name in runs:
            events = read_events(archive.read(name).decode('utf-8'))
            print(f"\n{name}")
            if not events:
                print('  Empty trace')
                continue
            print(f"  Recorded duration: {events[-1].get('elapsedMs', 0) / 1000:.1f}s; " +
                  ('ended' if events[-1].get('kind') == 'run_end' else 'active or interrupted'))
            starts = [e for e in events if e.get('kind') == 'action_start']
            print('  Tool calls: ' + ', '.join(f'{k}={v}' for k, v in collections.Counter(e.get('tool') for e in starts).most_common()))
            models = collections.defaultdict(list)
            for event in events:
                if event.get('kind') == 'model_end':
                    models[event.get('model')].append(float(event['durationMs']) / 1000)
            for model, durations in models.items():
                print(f'  {model}: {len(durations)} calls, median {statistics.median(durations):.1f}s, max {max(durations):.1f}s, total {sum(durations):.1f}s')
            retries = [e for e in events if e.get('kind') == 'retry']
            print(f'  Network retries: {len(retries)}')
            # agent_start also separates the task from the optional reporting agent.
            segment = []
            for event in events + [{'kind': 'agent_start'}]:
                if event.get('kind') == 'agent_start':
                    for first, last, width in cycles(segment):
                        print(f'  Possible navigation loop: steps {first} through {last}, repeating {width} screens')
                    unchanged = sum(a.get('signature') is not None and
                                    (a.get('package'), a.get('signature')) == (b.get('package'), b.get('signature'))
                                    for a, b in zip(segment, segment[1:]))
                    if unchanged:
                        print(f'  Unchanged consecutive screens: {unchanged} (may include legitimate reads/waits)')
                    segment = []
                elif event.get('kind') == 'observation':
                    segment.append(event)
            task_end = next((e for e in events if e.get('kind') == 'task_end'), None)
            if task_end:
                print(f"  After task outcome: {(events[-1]['elapsedMs'] - task_end['elapsedMs']) / 1000:.1f}s (includes optional reporting)")
            print('  Screen repetitions are leads for review, not confirmed failures.')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 scripts/analyze-traces.py android-use-diagnostics.zip')
    analyze(sys.argv[1])
