#!/usr/bin/env python3
"""Render sampled stacks, compact hotspots and GC pauses from selected JFR data."""
import collections
from datetime import datetime
import gzip
import hashlib
import html
import math
import pathlib
import re
import sys
import zlib

from package_profiles import EVENTS, parse_json, pause_ms, privacy_check


BACKGROUND = "#101e28"
FOREGROUND = "#f2eee1"
MUTED = "#b8cbd1"


def seconds(value):
    result = pause_ms(value) / 1000
    if not math.isfinite(result):
        raise ValueError("GC duration must be finite")
    return result


def method(frame):
    info = frame.get("method") or {}
    return ((info.get("type") or {}).get("name", "unknown").replace("/", ".")
            + "." + info.get("name", "unknown"))


def short_name(name):
    """Keep a useful class/method suffix; full identifiers remain in SVG titles."""
    cleaned = re.sub(r"\+0x[0-9a-fA-F]+(?:\.\d+)?", "", name)
    return ".".join(cleaned.rsplit(".", 2)[-2:])


def label(name, characters):
    return name if len(name) <= characters else name[:max(0, characters - 1)] + "…"


def svg_start(title, subtitle, width, height):
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img">',
        f'<title>{html.escape(title)}</title><desc>{html.escape(subtitle)}</desc>',
        f'<rect width="{width}" height="{height}" fill="{BACKGROUND}"/>',
        f'<text x="28" y="34" font-family="sans-serif" font-size="22" fill="{FOREGROUND}">{html.escape(title)}</text>',
        f'<text x="28" y="61" font-family="sans-serif" font-size="13" fill="{MUTED}">{html.escape(subtitle)}</text>',
    ]


def selected(events, kind):
    return [event for event in events if event["type"] == kind]


def allocation_weight(event):
    weight = event["values"].get("weight")
    if type(weight) is not int or weight < 0:
        raise ValueError("allocation sample weight must be nonnegative integer bytes")
    return weight


def flamegraph(events, event_type, weighted):
    matching = selected(events, event_type)
    tree = {"name": "all samples", "weight": 0, "children": {}}
    maximum_depth = supplied_depth = 0
    truncated = missing = zero_weight = 0
    for event in matching:
        trace = event["values"].get("stackTrace") or {}
        frames = trace.get("frames") or []
        supplied_depth = max(supplied_depth, len(frames))
        weight = allocation_weight(event) if weighted else 1
        truncated += bool(trace.get("truncated"))
        missing += not bool(frames)
        zero_weight += weight == 0
        names = [method(frame) for frame in reversed(frames)] or ["[stack unavailable]"]
        maximum_depth = max(maximum_depth, len(names))
        node = tree
        node["weight"] += weight
        for name in names:
            node = node["children"].setdefault(name, {"name": name, "weight": 0, "children": {}})
            node["weight"] += weight
    title = "Sampled allocation stacks" if weighted else "Sampled CPU stacks"
    unit = "sampled bytes" if weighted else "samples"
    subtitle = f"Width: {unit}. Whole selected JFR export; warmup and measurement harness included."
    row, header, footer = 24, 145, 40
    height = header + (maximum_depth + 1) * row + footer if tree["weight"] else 240
    svg = svg_start(title, subtitle, 1280, height)
    svg += [
        f'<text x="28" y="86" font-family="sans-serif" font-size="13" fill="{MUTED}">{len(matching):,} events; total {tree["weight"]:,} {unit}; observed depth {supplied_depth}; source-marked truncated stacks {truncated}.</text>',
        f'<text x="28" y="109" font-family="sans-serif" font-size="13" fill="{MUTED}">All positive-weight supplied stack frames retained. Open this SVG directly for tooltips and zoom.</text>',
        f'<text x="28" y="130" font-family="sans-serif" font-size="12" fill="{MUTED}">Unavailable stacks: {missing}; zero-weight events: {zero_weight}. Export/recording limits cannot be inferred from observed depth.</text>',
    ]
    if not tree["weight"]:
        message = "No sampled events" if not matching else "No positive sampled allocation weight"
        svg.append(f'<text x="28" y="180" font-family="sans-serif" font-size="20" fill="{FOREGROUND}">{message}</text>')
    else:
        pending = [(tree, 28.0, 1224.0, 0)]
        while pending:
            node, x, width, depth = pending.pop()
            if not node["weight"]:
                continue
            y = header + (maximum_depth - depth) * row
            hue = (160 if weighted else 18) + int(hashlib.sha256(node["name"].encode()).hexdigest()[:2], 16) % 32
            color = f"hsl({hue},68%,66%)" if depth else "#38576b"
            ink = "#152430" if depth else FOREGROUND
            tooltip = f'{node["name"]}: {node["weight"]:,} {unit} ({100 * node["weight"] / tree["weight"]:.3f}%)'
            stroke = 0.35 if width >= 1 else 0
            svg.append(f'<g data-depth="{depth}" data-weight="{node["weight"]}"><title>{html.escape(tooltip)}</title>'
                       f'<rect x="{x:.9g}" y="{y}" width="{width:.12g}" height="22" fill="{color}" stroke="{BACKGROUND}" stroke-width="{stroke}"/>')
            characters = int(width / 7.3) - 1
            if characters >= 5:
                name = node["name"] if not depth else short_name(node["name"])
                svg.append(f'<text x="{x + 4:.9g}" y="{y + 16}" font-family="monospace" font-size="12" fill="{ink}">{html.escape(label(name, characters))}</text>')
            svg.append('</g>')
            children = []
            current = x
            for child in sorted(node["children"].values(), key=lambda child: child["name"]):
                child_width = width * child["weight"] / node["weight"]
                children.append((child, current, child_width, depth + 1))
                current += child_width
            pending.extend(reversed(children))
    svg.append('</svg>')
    return "\n".join(svg) + "\n"


def hotspot_chart(counter, title, subtitle, unit, scale=1, top=8):
    def amount(value, digits=3):
        return f"{value:,}" if scale == 1 else f"{value / scale:,.{digits}f}"
    rows = counter.most_common(top)
    other = sum(counter.values()) - sum(value for _, value in rows)
    if other:
        rows.append(("Other", other))
    total = sum(counter.values())
    height = 155 + max(len(rows), 1) * 37
    svg = svg_start(title, subtitle, 1100, height)
    svg.append(f'<text x="28" y="88" font-family="sans-serif" font-size="14" fill="{MUTED}">Top {top} plus Other; total {amount(total)} {unit}. Full stacks are in the separate flame graph.</text>')
    if not total:
        svg.append(f'<text x="28" y="145" font-family="sans-serif" font-size="20" fill="{FOREGROUND}">No sampled events or positive sampled weight</text>')
    else:
        maximum = max(value for _, value in rows)
        for index, (name, value) in enumerate(rows):
            y = 117 + index * 37
            width = 420 * value / maximum
            tooltip = f"{name}: {amount(value, 6)} {unit} ({100 * value / total:.3f}%)"
            svg.append(f'<g data-weight="{value}"><title>{html.escape(tooltip)}</title>'
                       f'<text x="28" y="{y + 18}" font-family="monospace" font-size="15" fill="{FOREGROUND}">{html.escape(label(short_name(name), 48))}</text>'
                       f'<rect x="472" y="{y}" width="{width:.9g}" height="24" rx="2" fill="#75cbb9"/>'
                       f'<text x="912" y="{y + 18}" font-family="sans-serif" font-size="15" fill="{FOREGROUND}">{amount(value)} {unit}</text></g>')
    svg.append('</svg>')
    return "\n".join(svg) + "\n"


def event_time(value):
    if not isinstance(value, str):
        raise ValueError("JFR event startTime must be a timestamp with a timezone")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("JFR event startTime must be a timestamp with a timezone") from None
    if result.tzinfo is None:
        raise ValueError("JFR event startTime must include a timezone")
    return result


def gc_timeline(events):
    gc = selected(events, "jdk.GarbageCollection")
    svg = svg_start("GC collection pause timeline",
                    "Whole selected JFR export; warmup/harness included. Bars show sumOfPauses per collection, not GC duration.",
                    1100, 420)
    if not gc:
        svg.append(f'<text x="28" y="170" font-family="sans-serif" font-size="20" fill="{FOREGROUND}">No GC collection events recorded</text></svg>')
        return "\n".join(svg) + "\n", []
    timestamps = [event_time(event["values"]["startTime"]) for event in events
                  if "startTime" in event["values"]]
    origin, end = min(timestamps), max(timestamps)
    points = []
    for event in gc:
        values = event["values"]
        started = event_time(values.get("startTime"))
        pause = seconds(values.get("sumOfPauses")) * 1000
        points.append(((started - origin).total_seconds(), pause, values))
    span = max((end - origin).total_seconds(), max(point[0] for point in points), 0.001)
    maximum = max(max(point[1] for point in points), 0.001)
    left, right, upper, bottom = 85, 1040, 125, 330
    svg += [
        f'<text x="28" y="87" font-family="sans-serif" font-size="14" fill="{MUTED}">{len(points)} collections; pause sum {sum(point[1] for point in points):,.3f} ms; maximum collection pause sum {max(point[1] for point in points):,.3f} ms.</text>',
        f'<text x="28" y="109" font-family="sans-serif" font-size="12" fill="{MUTED}">Time zero: first timestamped selected event. Positions do not identify the measured-load interval.</text>',
    ]
    for index in range(5):
        fraction = index / 4
        y = bottom - fraction * (bottom - upper)
        svg.append(f'<line x1="{left}" y1="{y}" x2="{right}" y2="{y}" stroke="#304754"/>'
                   f'<text x="{left - 10}" y="{y + 5}" text-anchor="end" font-family="sans-serif" font-size="13" fill="{MUTED}">{fraction * maximum:.3f}</text>')
        x = left + fraction * (right - left)
        svg.append(f'<text x="{x}" y="{bottom + 23}" text-anchor="middle" font-family="sans-serif" font-size="13" fill="{MUTED}">{fraction * span:.3f}</text>')
    for started, pause, values in sorted(points, key=lambda point: point[0]):
        x = left + started / span * (right - left)
        y = bottom - pause / maximum * (bottom - upper)
        tooltip = f'GC {values.get("gcId", "unknown")}: {started:.6f} s; collection pause sum {pause:.6f} ms; {values.get("name", "unknown")}; {values.get("cause", "unknown")}'
        svg.append(f'<g data-start-seconds="{started:.9g}" data-pause-ms="{pause:.12g}"><title>{html.escape(tooltip)}</title>'
                   f'<line x1="{x:.9g}" y1="{bottom}" x2="{x:.9g}" y2="{y:.9g}" stroke="#efb56f" stroke-width="3"/>'
                   f'<circle cx="{x:.9g}" cy="{y:.9g}" r="3" fill="#efb56f"/></g>')
    svg += [
        f'<text x="{(left + right) / 2}" y="386" text-anchor="middle" font-family="sans-serif" font-size="15" fill="{FOREGROUND}">Seconds since first selected event</text>',
        f'<text x="25" y="230" text-anchor="middle" transform="rotate(-90 25 230)" font-family="sans-serif" font-size="15" fill="{FOREGROUND}">Collection pause sum (ms)</text>',
        '</svg>',
    ]
    return "\n".join(svg) + "\n", [point[1] for point in points]


def main(directory):
    root = pathlib.Path(directory)
    source = root / "jfr-events.json"
    data = source.read_bytes() if source.exists() else gzip.decompress((root / "jfr-events.json.gz").read_bytes())
    payload = parse_json("selected JFR events", privacy_check("selected JFR events", data))
    events = payload["recording"]["events"]
    if not isinstance(events, list) or any(event["type"] not in EVENTS for event in events):
        raise ValueError("Refusing unexpected JFR event categories")
    counts = collections.Counter(event["type"] for event in events)
    cpu, allocations = collections.Counter(), collections.Counter()
    for event in events:
        values = event["values"]
        if event["type"] == "jdk.ExecutionSample":
            frames = (values.get("stackTrace") or {}).get("frames") or []
            cpu[method(frames[0]) if frames else "[stack unavailable]"] += 1
        if event["type"] == "jdk.ObjectAllocationSample":
            name = (values.get("objectClass") or {}).get("name", "unknown").replace("/", ".")
            allocations[name] += allocation_weight(event)
    load = parse_json("load.json", (root / "load.json").read_text())
    java_major = int(load["environment"]["javaVersion"].split(".")[0])
    pinning = str(counts["jdk.VirtualThreadPinned"]) if java_major >= 21 else "not available on this runtime"
    gc_svg, pauses = gc_timeline(events)
    outputs = {
        "cpu-flamegraph.svg": flamegraph(events, "jdk.ExecutionSample", False),
        "allocation-flamegraph.svg": flamegraph(events, "jdk.ObjectAllocationSample", True),
        "cpu-hotspots.svg": hotspot_chart(cpu, "CPU sampled leaf methods", "Leaf sample counts, not method duration. Whole selected JFR export; warmup/harness included.", "samples"),
        "allocation-hotspots.svg": hotspot_chart(allocations, "Sampled allocation weights by class", "Estimated allocated-byte weights, not retained heap or object counts. Whole selected JFR export.", "MiB", 1024 ** 2),
        "gc-pauses.svg": gc_svg,
    }
    lines = ['# CPU, allocation and GC profile', '',
             'Generated from the selected JFR events for the bounded kernel load. Samples include the load harness and warmup; they are not a model or network profile.', '',
             '![CPU sampled leaf methods](cpu-hotspots.svg)', '',
             '## Complete supplied CPU stacks', '',
             '![CPU sampled stacks](cpu-flamegraph.svg)', '',
             'Every positive-weight supplied stack frame is retained; zero-weight events have no area. Observed depth does not prove that the recording or export was unlimited. Source-marked truncation and unavailable stacks are shown on the figure. Open the SVG directly to inspect full method tooltips.', '',
             '## CPU leaf samples', '', '| Method | Samples |', '|---|---:|']
    lines += [f'| `{name}` | {count:,} |' for name, count in cpu.most_common(12)]
    lines += ['', '## Sampled allocation weights', '',
              'Weights estimate sampled allocation distribution, not retained heap or exact object counts. The harness retains an exact latency array; its `long[]` allocation is intentionally visible. Use JMH `gc.alloc.rate.norm` for per-operation allocation estimates.', '',
              '![Sampled allocation weights by class](allocation-hotspots.svg)', '',
              '| Class | Sampled weight (MiB) |', '|---|---:|']
    lines += [f'| `{name}` | {weight / 1024**2:,.2f} |' for name, weight in allocations.most_common(12)]
    lines += ['', '## Complete supplied allocation stacks', '',
              '![Allocation sampled stacks](allocation-flamegraph.svg)', '', '## GC and contention', '',
              '![GC collection pause timeline](gc-pauses.svg)', '',
              f'- Garbage collections recorded: {len(pauses)}.',
              f'- Recorded GC pause sum: {sum(pauses):.3f} ms; longest collection pause sum: {max(pauses, default=0):.3f} ms.',
              f'- Monitor contention events: {counts["jdk.JavaMonitorEnter"]}; virtual-thread pinning events: {pinning}.',
              '- GC positions are relative to the first timestamped selected event, not the measured-load start. The figure includes warmup/harness events. Collection pause sums are not whole GC durations.',
              '- Zero sampled events does not prove absence. Sampling, event thresholds and workload duration limit these observations.', '',
              'Evidence: [selected events, gzip JSON](jfr-events.json.gz), [measurement report](report.md).',
              'Raw JFR is generated locally and intentionally excluded from Git; do not record real credentials or conversation payloads for public profiling.', '']
    gc_log = 'gc.log' if (root / 'gc.log').exists() else 'gc.txt'
    if (root / gc_log).exists():
        lines += [f'[GC log]({gc_log}).', '']
    # Finish all parsing/rendering before changing outputs, so invalid GC data
    # cannot leave a newly successful-looking profile summary behind.
    for name, content in outputs.items():
        (root / name).write_text(content, encoding="utf-8")
    (root / 'profiles.md').write_text('\n'.join(lines), encoding="utf-8")
    (root / "jfr-events.json.gz").write_bytes(gzip.compress(data, mtime=0))
    print('Created profile summary, complete supplied stacks, two compact hotspot charts and GC timeline.')


if __name__ == '__main__':
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else 'reports/baseline')
    except (OSError, ValueError, KeyError, TypeError, AttributeError, EOFError, OverflowError, zlib.error) as error:
        print(f'Profile summary failed ({type(error).__name__}); no valid profile was produced.', file=sys.stderr)
        sys.exit(1)
