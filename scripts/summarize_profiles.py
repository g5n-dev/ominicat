#!/usr/bin/env python3
"""Summarize selected, sanitized JFR events without requiring external packages."""
import collections
import gzip
import hashlib
import html
import json
import pathlib
import re
import sys


def seconds(value):
    match = re.fullmatch(r"PT([0-9.]+)S", value or "PT0S")
    return float(match.group(1)) if match else 0.0


def method(frame):
    info = frame.get("method") or {}
    return (info.get("type", {}).get("name", "unknown").replace("/", ".") + "." + info.get("name", "unknown"))


def flamegraph(events, event_type, weighted, destination):
    tree = {"name": "all samples", "weight": 0, "children": {}}
    for event in events:
        if event["type"] != event_type:
            continue
        values = event["values"]
        frames = (values.get("stackTrace") or {}).get("frames") or []
        weight = values.get("weight", 1) if weighted else 1
        node = tree
        node["weight"] += weight
        for frame in reversed(frames[:48]):
            name = method(frame)
            node = node["children"].setdefault(name, {"name": name, "weight": 0, "children": {}})
            node["weight"] += weight
    def depth(node):
        return 1 + max((depth(child) for child in node['children'].values()), default=0)
    height = 100 + 18 * min(depth(tree), 48)
    title = "Sampled allocation weights" if weighted else "Sampled CPU stacks"
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="{height}" viewBox="0 0 1280 {height}" role="img">',
           f'<title>{title}</title><rect width="1280" height="{height}" fill="#101e28"/>',
           f'<text x="24" y="32" font-family="sans-serif" font-size="20" fill="#f2eee1">{title}</text>',
           '<text x="24" y="57" font-family="sans-serif" font-size="13" fill="#b8cbd1">Width represents sampled weight. Hover rectangles for methods and counts. Harness frames are included.</text>']

    def draw(node, x, width, depth):
        if width < 1 or depth > 47:
            return
        y = height - 40 - depth * 18
        color = 20 + int(hashlib.sha256(node["name"].encode()).hexdigest()[:2], 16) % 35
        label = html.escape(node["name"])
        svg.append(f'<g><title>{label}: {node["weight"]:,}</title><rect x="{x:.2f}" y="{y}" width="{max(0.1,width-.5):.2f}" height="17" rx="1" fill="hsl({color},75%,65%)"/>')
        chars = int(width / 7) - 1
        if chars > 4:
            svg.append(f'<text x="{x+3:.2f}" y="{y+12}" font-family="monospace" font-size="10" fill="#152430">{html.escape(node["name"][:chars])}</text>')
        svg.append('</g>')
        current = x
        for child in sorted(node["children"].values(), key=lambda child: child["name"]):
            child_width = width * child["weight"] / max(node["weight"], 1)
            draw(child, current, child_width, depth + 1)
            current += child_width

    draw(tree, 24, 1232, 0)
    svg.append('</svg>')
    destination.write_text('\n'.join(svg) + '\n')


def main(directory):
    root = pathlib.Path(directory)
    source = root / "jfr-events.json"
    data = source.read_bytes() if source.exists() else gzip.decompress((root / "jfr-events.json.gz").read_bytes())
    # Public profiles must exclude environment, command-line and machine inventory events.
    payload = json.loads(data)
    events = payload["recording"]["events"]
    allowed = {"jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.JavaMonitorEnter", "jdk.VirtualThreadPinned"}
    if any(event["type"] not in allowed for event in events):
        raise SystemExit("Refusing to publish unexpected JFR event categories")
    if b'/Users/' in data or b'Bearer ' in data:
        raise SystemExit("Refusing to publish a profile containing local paths or bearer text")
    with (root / "jfr-events.json.gz").open('wb') as output:
        output.write(gzip.compress(data, mtime=0))
    counts = collections.Counter(event["type"] for event in events)
    cpu = collections.Counter()
    allocations = collections.Counter()
    pauses = []
    java_major = int(json.loads((root / "load.json").read_text())["environment"]["javaVersion"].split(".")[0])
    pinning = str(counts["jdk.VirtualThreadPinned"]) if java_major >= 21 else "not available on this runtime"
    for event in events:
        values = event["values"]
        if event["type"] == "jdk.ExecutionSample":
            frames = (values.get("stackTrace") or {}).get("frames") or []
            if frames:
                cpu[method(frames[0])] += 1
        if event["type"] == "jdk.ObjectAllocationSample":
            allocations[values.get("objectClass", {}).get("name", "unknown").replace("/", ".")] += values.get("weight", 0)
        if event["type"] == "jdk.GarbageCollection":
            pauses.append(seconds(values.get("sumOfPauses")) * 1000)
    lines = ['# CPU, allocation and GC profile', '',
             'Generated from the selected JFR events for the bounded kernel load. Samples include the load harness and warmup; they are not a model or network profile.', '',
             '![CPU sampled stacks](cpu-flamegraph.svg)', '',
             '## CPU leaf samples', '', '| Method | Samples |', '|---|---:|']
    lines += [f'| `{name}` | {count:,} |' for name, count in cpu.most_common(12)]
    lines += ['', '## Sampled allocation weights', '',
              'Weights estimate sampled allocation distribution, not retained heap or exact object counts. The harness retains an exact latency array; its `long[]` allocation is intentionally visible. Use JMH `gc.alloc.rate.norm` for per-operation allocation estimates.', '',
              '| Class | Sampled weight (MiB) |', '|---|---:|']
    lines += [f'| `{name}` | {weight / 1024**2:,.2f} |' for name, weight in allocations.most_common(12)]
    lines += ['', '![Allocation sampled stacks](allocation-flamegraph.svg)', '', '## GC and contention', '',
              f'- Garbage collections recorded: {len(pauses)}.',
              f'- Recorded GC pause sum: {sum(pauses):.3f} ms; longest collection pause sum: {max(pauses, default=0):.3f} ms.',
              f'- Monitor contention events: {counts["jdk.JavaMonitorEnter"]}; virtual-thread pinning events: {pinning}.',
              '- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.', '',
              'Evidence: [selected events, gzip JSON](jfr-events.json.gz), [GC log](gc.log), [measurement report](report.md).',
              'Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.', '']
    (root / 'profiles.md').write_text('\n'.join(lines))
    flamegraph(events, 'jdk.ExecutionSample', False, root / 'cpu-flamegraph.svg')
    flamegraph(events, 'jdk.ObjectAllocationSample', True, root / 'allocation-flamegraph.svg')
    print('Created profile summary, two flame graphs and compressed selected evidence.')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'reports/baseline')
