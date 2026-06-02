#!/usr/bin/env python3
"""
analyze_trace.py — Post-processing for edt-debug-tracer trace data.

Reads trace.json and produces:
  - calltree.json   — nested call tree via parentSeq
  - deps.json       — module dependency graph
  - deps.dot        — Graphviz DOT format
  - analysis.json   — hot spots, loop collapse, timing

Usage:
    python3 scripts/analyze_trace.py <trace.json> [output_dir]
"""

import json
import sys
import os
from collections import defaultdict, Counter

def load_trace(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)

# ── Call Tree ─────────────────────────────────────────────────────────────

def build_call_tree(steps):
    """Build nested call tree from flat steps using parentSeq."""
    # Index steps by seq (1-based: step index + 1)
    children = defaultdict(list)
    roots = []

    for i, step in enumerate(steps):
        seq = i + 1
        parent = step.get('parent_seq', -1)
        if parent <= 0:
            roots.append(seq)
        else:
            children[parent].append(seq)

    def build_node(seq):
        step = steps[seq - 1] if seq <= len(steps) else None
        if step is None:
            return None
        node = {
            'seq': seq,
            'procedure': step.get('procedure', ''),
            'line': step.get('line', -1),
            'module': step.get('module', ''),
            'thread_name': step.get('thread_name', ''),
            'stack_depth': step.get('stack_depth', 0),
            'children': []
        }
        for child_seq in children.get(seq, []):
            child_node = build_node(child_seq)
            if child_node:
                node['children'].append(child_node)
        # Collapse single-child chains
        node['callCount'] = len(children.get(seq, []))
        return node

    tree = []
    for root_seq in roots:
        node = build_node(root_seq)
        if node:
            tree.append(node)

    return tree

# ── Dependency Graph ──────────────────────────────────────────────────────

def build_dependency_graph(steps):
    """Build module→module dependency graph from parentSeq relationships."""
    edges = Counter()
    modules = set()

    for i, step in enumerate(steps):
        seq = i + 1
        parent = step.get('parent_seq', -1)
        callee_module = step.get('module', '') or '<unknown>'
        modules.add(callee_module)

        if parent > 0 and parent <= len(steps):
            parent_step = steps[parent - 1]
            caller_module = parent_step.get('module', '') or '<unknown>'
            modules.add(caller_module)
            edges[(caller_module, callee_module)] += 1

    graph = {
        'nodes': [{'id': m, 'type': 'module'} for m in sorted(modules)],
        'edges': [
            {
                'source': caller,
                'target': callee,
                'calls': count
            }
            for (caller, callee), count in edges.most_common()
        ]
    }
    return graph

def to_dot(graph):
    """Convert dependency graph to Graphviz DOT format."""
    lines = ['digraph dependencies {', '    rankdir=LR;', '    node [shape=box];']
    for edge in graph['edges']:
        src = edge['source'].replace('"', '\\"')
        tgt = edge['target'].replace('"', '\\"')
        lines.append(f'    "{src}" -> "{tgt}" [label="{edge["calls"]}"];')
    lines.append('}')
    return '\n'.join(lines)

# ── Hot Spots ─────────────────────────────────────────────────────────────

def find_hot_spots(steps, top_n=20):
    """Find procedures with the most steps."""
    counter = Counter()
    for step in steps:
        proc = step.get('procedure', '<unknown>')
        counter[proc] += 1

    return [
        {'procedure': proc, 'count': count, 'rank': i + 1}
        for i, (proc, count) in enumerate(counter.most_common(top_n))
    ]

# ── Loop Collapse ─────────────────────────────────────────────────────────

def collapse_loops(steps, min_repeat=2, max_pattern=20):
    """Detect and collapse repeating step patterns."""
    procedures = [s.get('procedure', '') for s in steps]
    collapsed = []
    i = 0

    while i < len(procedures):
        best_len = 0
        best_count = 0

        # Try pattern lengths from max down to 1
        for plen in range(min(max_pattern, len(procedures) - i), 0, -1):
            pattern = procedures[i:i + plen]
            count = 1
            j = i + plen
            while j + plen <= len(procedures) and procedures[j:j + plen] == pattern:
                count += 1
                j += plen
            if count >= min_repeat and plen > best_len:
                best_len = plen
                best_count = count
                break

        if best_count >= min_repeat and best_len > 0:
            collapsed.append({
                'type': 'loop',
                'pattern': [
                    {'procedure': p, 'line': steps[i + k].get('line', -1)}
                    for k, p in enumerate(procedures[i:i + best_len])
                ],
                'count': best_count,
                'total_steps': best_len * best_count,
                'start_seq': i + 1,
                'end_seq': i + best_len * best_count
            })
            i += best_len * best_count
        else:
            collapsed.append({
                'type': 'step',
                'procedure': procedures[i],
                'line': steps[i].get('line', -1),
                'seq': i + 1
            })
            i += 1

    return collapsed

# ── Timing Analysis ───────────────────────────────────────────────────────

def analyze_timing(steps):
    """Analyze timing between steps."""
    if len(steps) < 2:
        return {'total_ms': 0, 'avg_ms': 0, 'steps': len(steps)}

    timestamps = [s.get('ts', 0) for s in steps]
    total = timestamps[-1] - timestamps[0]
    intervals = [timestamps[i+1] - timestamps[i] for i in range(len(timestamps) - 1)]

    # Per-procedure timing
    proc_time = defaultdict(int)
    proc_count = Counter()
    for i in range(len(steps) - 1):
        proc = steps[i].get('procedure', '<unknown>')
        dt = timestamps[i+1] - timestamps[i]
        proc_time[proc] += dt
        proc_count[proc] += 1

    slowest = sorted(proc_time.items(), key=lambda x: x[1], reverse=True)[:10]

    return {
        'total_ms': total,
        'avg_ms': round(total / max(len(steps) - 1, 1), 2),
        'min_interval_ms': min(intervals) if intervals else 0,
        'max_interval_ms': max(intervals) if intervals else 0,
        'steps': len(steps),
        'slowest_procedures': [
            {'procedure': proc, 'total_ms': ms, 'count': proc_count[proc]}
            for proc, ms in slowest
        ]
    }

# ── Thread Transitions ────────────────────────────────────────────────────

def analyze_threads(steps):
    """Analyze thread transitions."""
    transitions = []
    prev_thread = None
    for i, step in enumerate(steps):
        thread = step.get('thread_name', '') or step.get('thread_id', '')
        if prev_thread is not None and thread != prev_thread:
            transitions.append({
                'seq': i + 1,
                'from': prev_thread,
                'to': thread
            })
        prev_thread = thread

    thread_counts = Counter(step.get('thread_name', '') or step.get('thread_id', '') for step in steps)
    return {
        'total_transitions': len(transitions),
        'transitions': transitions[:50],  # first 50
        'thread_distribution': dict(thread_counts.most_common())
    }

# ── Main ──────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 analyze_trace.py <trace.json> [output_dir]")
        sys.exit(1)

    trace_path = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(trace_path)

    print(f"Loading trace: {trace_path}")
    steps = load_trace(trace_path)
    print(f"  Steps: {len(steps)}")

    if not steps:
        print("No steps to analyze.")
        sys.exit(0)

    # 1. Call Tree
    print("Building call tree...")
    tree = build_call_tree(steps)
    tree_path = os.path.join(output_dir, 'calltree.json')
    with open(tree_path, 'w', encoding='utf-8') as f:
        json.dump(tree, f, ensure_ascii=False, indent=2)
    print(f"  Output: {tree_path} ({len(tree)} roots)")

    # 2. Dependency Graph
    print("Building dependency graph...")
    graph = build_dependency_graph(steps)
    deps_path = os.path.join(output_dir, 'deps.json')
    with open(deps_path, 'w', encoding='utf-8') as f:
        json.dump(graph, f, ensure_ascii=False, indent=2)
    print(f"  Output: {deps_path} ({len(graph['nodes'])} nodes, {len(graph['edges'])} edges)")

    # DOT format
    dot_path = os.path.join(output_dir, 'deps.dot')
    with open(dot_path, 'w', encoding='utf-8') as f:
        f.write(to_dot(graph))
    print(f"  Output: {dot_path}")

    # 3. Hot Spots
    print("Finding hot spots...")
    hot_spots = find_hot_spots(steps)

    # 4. Loop Collapse
    print("Collapsing loops...")
    collapsed = collapse_loops(steps)
    loop_count = sum(1 for c in collapsed if c['type'] == 'loop')
    print(f"  Found {loop_count} loops, collapsed {len(steps)} → {len(collapsed)} entries")

    # 5. Timing
    print("Analyzing timing...")
    timing = analyze_timing(steps)
    print(f"  Total: {timing['total_ms']}ms, Avg: {timing['avg_ms']}ms/step")

    # 6. Threads
    print("Analyzing threads...")
    threads = analyze_threads(steps)
    print(f"  Transitions: {threads['total_transitions']}")

    # Combined analysis output
    analysis = {
        'summary': {
            'total_steps': len(steps),
            'unique_procedures': len(set(s.get('procedure', '') for s in steps)),
            'unique_modules': len(set(s.get('module', '') for s in steps if s.get('module'))),
            'call_tree_roots': len(tree),
            'dependency_edges': len(graph['edges']),
            'loops_detected': loop_count,
            'thread_transitions': threads['total_transitions']
        },
        'hot_spots': hot_spots,
        'loop_collapse': collapsed[:100],  # first 100 entries
        'timing': timing,
        'threads': threads
    }

    analysis_path = os.path.join(output_dir, 'analysis.json')
    with open(analysis_path, 'w', encoding='utf-8') as f:
        json.dump(analysis, f, ensure_ascii=False, indent=2)
    print(f"\nAnalysis: {analysis_path}")

    # Print summary
    print("\n=== Summary ===")
    for k, v in analysis['summary'].items():
        print(f"  {k}: {v}")

    if hot_spots:
        print("\n=== Top 5 Hot Spots ===")
        for hs in hot_spots[:5]:
            print(f"  #{hs['rank']} {hs['procedure'][:60]} ({hs['count']} steps)")

    if timing['slowest_procedures']:
        print("\n=== Slowest Procedures ===")
        for sp in timing['slowest_procedures'][:5]:
            print(f"  {sp['procedure'][:50]} — {sp['total_ms']}ms ({sp['count']} calls)")

if __name__ == '__main__':
    main()
