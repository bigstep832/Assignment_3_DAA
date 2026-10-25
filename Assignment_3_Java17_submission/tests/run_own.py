"""Independent small oracles, semantic witnesses, and optional full-limit tests.

Run from the repository root: python tests/run_own.py --full
No third-party package is required. Large inputs and actual outputs are retained
as gzip JSON Lines so the evidence does not inflate the repository unnecessarily.
"""

import argparse
from collections import deque
import copy
import gzip
import hashlib
from itertools import combinations
import json
from pathlib import Path
import platform
import random
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools"))
from public_validate import check
from run import build, execute, select_language


def encode(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def dump_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def reachable(n, edges, source, directed=True):
    adjacent = [[] for _ in range(n)]
    for edge in edges:
        u, v = edge[:2]
        adjacent[u].append(v)
        if not directed:
            adjacent[v].append(u)
    seen = {source}
    queue = deque([source])
    while queue:
        for v in adjacent[queue.popleft()]:
            if v not in seen:
                seen.add(v)
                queue.append(v)
    return seen


def connected_groups(n, edges):
    remaining = set(range(n))
    groups = []
    while remaining:
        group = reachable(n, edges, min(remaining), directed=False)
        groups.append(group)
        remaining.difference_update(group)
    return groups


def kahn_order(n, edges):
    adjacent = [[] for _ in range(n)]
    degree = [0] * n
    for edge in edges:
        u, v = edge[:2]
        adjacent[u].append(v)
        degree[v] += 1
    queue = deque(v for v in range(n) if degree[v] == 0)
    order = []
    while queue:
        u = queue.popleft()
        order.append(u)
        for v in adjacent[u]:
            degree[v] -= 1
            if degree[v] == 0:
                queue.append(v)
    return order if len(order) == n else None


def reference(request):
    """Deliberately different, slow algorithms for tiny generated cases only."""
    task = request["task"]
    if task == "ping":
        return {"status": "ready"}
    n = request["n"]
    edges = request.get("edges", [])
    if task == "dsu":
        prefix = []
        states = []
        for pair in request["operations"]:
            prefix.append(pair)
            groups = connected_groups(n, prefix)
            states.append([len(groups), max(map(len, groups), default=0)])
        return {"states": states}
    if task in ("kruskal", "prim"):
        count = len(connected_groups(n, edges))
        best = None
        best_ids = []
        # Every feasible subset has n-c edges. If it spans all input components,
        # that cardinality also proves acyclicity. Exhaustion proves optimality.
        for ids in combinations(range(len(edges)), n - count):
            chosen = [edges[i] for i in ids]
            if len(connected_groups(n, chosen)) != count:
                continue
            cost = sum(edge[2] for edge in chosen)
            if best is None or cost < best:
                best, best_ids = cost, list(ids)
        return {"cost": best, "edge_ids": best_ids, "components": count}
    if task == "scc":
        reach = [reachable(n, edges, v) for v in range(n)]
        labels = [min(u for u in range(n) if u in reach[v] and v in reach[u]) for v in range(n)]
        pairs = sorted({(labels[u], labels[v]) for u, v in edges if labels[u] != labels[v]})
        return {"component": labels, "condensation": [list(pair) for pair in pairs]}
    order = kahn_order(n, edges)
    if task == "topo":
        return {"acyclic": order is not None, "order": order or []}
    if order is None:
        return {"acyclic": False, "distance": [], "parent_edge": []}
    # Bellman-Ford, independent of the implementation's topological relaxation.
    distance = [None] * n
    parents = [-1] * n
    distance[request["source"]] = 0
    for _ in range(n - 1):
        changed = False
        for edge_id, (u, v, weight) in enumerate(edges):
            if distance[u] is None:
                continue
            value = distance[u] + weight
            if distance[v] is None or value < distance[v]:
                distance[v], parents[v], changed = value, edge_id, True
        if not changed:
            break
    return {"acyclic": True, "distance": distance, "parent_edge": parents}


def small_cases():
    cases = []

    def add(name, request, reason):
        cases.append({"name": name, "input": request, "expected": reference(request), "justification": reason})

    add("ping", {"task": "ping"}, "The transport contract requires status=ready.")
    for task in ("dsu", "kruskal", "prim", "scc", "topo"):
        add("empty_" + task, {"task": task, "n": 0, "operations" if task == "dsu" else "edges": []},
            "There are no vertices, edges, components, or operations; the empty digraph is acyclic.")
    add("dsu_repeats_self_isolates", {"task": "dsu", "n": 6,
        "operations": [[0, 0], [0, 1], [1, 0], [2, 3], [1, 2], [4, 4]]},
        "BFS after each prefix gives states [6,1], [5,2], [5,2], [4,2], [3,4], [3,4]; vertex 5 stays isolated.")
    add("dsu_no_operations", {"task": "dsu", "n": 5, "operations": []},
        "Only post-operation states are returned, so an empty operation list returns no states.")
    forests = [
        ("loops_parallel_ties_isolate", 6, [[0, 0, -100], [0, 1, 8], [0, 1, -2], [1, 2, -2], [0, 2, -2], [3, 4, 7], [4, 4, -10]],
         "Component {0,1,2} needs two -2 edges; {3,4} needs the 7 edge; vertex 5 is isolated: cost=3, components=3. Loops cannot enter a forest."),
        ("positive_overflow", 5, [[i, i + 1, 1000000000] for i in range(4)],
         "The input is already a tree, so all four edges are necessary: cost=4000000000."),
        ("negative_overflow", 5, [[i, i + 1, -1000000000] for i in range(4)],
         "All four tree edges are necessary: cost=-4000000000."),
        ("isolates", 5, [], "Five isolated vertices require no selected edges and have total cost zero."),
    ]
    for label, n, edges, reason in forests:
        for task in ("kruskal", "prim"):
            add(task + "_" + label, {"task": task, "n": n, "edges": edges}, reason)
    add("scc_two_groups_duplicates_isolates", {"task": "scc", "n": 7,
        "edges": [[0, 1], [1, 0], [1, 2], [0, 2], [1, 2], [2, 3], [3, 2], [3, 3], [4, 4]]},
        "Mutual reachability gives {0,1}, {2,3}, {4}, {5}, {6}; the only condensation pair joins the first two groups.")
    add("scc_isolates", {"task": "scc", "n": 4, "edges": []}, "Every vertex is a separate SCC and condensation has no edges.")
    directed = [
        ("finished_neighbor", 4, [[0, 1], [0, 2], [2, 1], [1, 3], [2, 3]],
         "Order [0,2,1,3] satisfies all dependencies; an edge to an already finished vertex is not a cycle."),
        ("self_loop", 3, [[1, 1]], "The self-loop is a directed cycle even in a disconnected component."),
        ("disconnected_cycle", 5, [[0, 1], [2, 3], [3, 4], [4, 2]], "Vertices 2,3,4 form a cycle, so no global order exists."),
        ("parallel_edges", 4, [[0, 2], [0, 2], [1, 2], [2, 3]], "Parallel forward edges do not create a cycle; 0 and 1 precede 2, then 3."),
    ]
    for label, n, edges, reason in directed:
        add("topo_" + label, {"task": "topo", "n": n, "edges": edges}, reason)
    dags = [
        ("single", 1, 0, [], "The only vertex is the source with distance zero and no parent."),
        ("negative_unreachable", 6, 0, [[0, 1, 3], [0, 2, 8], [1, 2, -7], [2, 3, 4], [4, 5, -100]],
         "Distances are [0,3,-4,0,null,null]; an edge from unreachable 4 must not relax 5."),
        ("ties_parallel", 5, 0, [[0, 1, 2], [0, 1, 2], [0, 2, 1], [2, 1, 1], [1, 3, -1], [2, 3, 0]],
         "Distances are [0,2,1,1,null]; either parallel edge may parent 1, and either tied route may reach 3."),
        ("nonzero_source", 5, 3, [[0, 3, -8], [3, 1, -1], [1, 4, 2], [2, 4, -100]],
         "Only source 3 and vertices 1,4 are reachable: [null,-1,null,0,1]. A finite -1 is not null."),
        ("positive_overflow", 5, 0, [[i, i + 1, 1000000000] for i in range(4)],
         "The unique path to vertex i costs i*1000000000, exceeding 32-bit range."),
        ("negative_overflow", 5, 0, [[i, i + 1, -1000000000] for i in range(4)],
         "The unique path to vertex i costs -i*1000000000."),
        ("unreachable_cycle", 5, 0, [[0, 1, 4], [2, 3, -1], [3, 2, 2]],
         "The 2-3 cycle is outside source reachability but must reject the entire graph."),
        ("unreachable_self_loop", 3, 0, [[2, 2, -1]], "A self-loop anywhere rejects the graph."),
    ]
    for label, n, source, edges, reason in dags:
        add("dag_" + label, {"task": "dag", "n": n, "source": source, "edges": edges}, reason)
    return cases


def random_cases(seed=381726, rounds=40):
    rng = random.Random(seed)
    cases = []
    for index in range(rounds):
        n = rng.randint(1, 6)
        weighted = [[rng.randrange(n), rng.randrange(n), rng.randint(-8, 8)] for _ in range(rng.randrange(10))]
        unweighted = [edge[:2] for edge in weighted]
        tasks = [
            {"task": "dsu", "n": n, "operations": unweighted},
            {"task": "kruskal", "n": n, "edges": weighted},
            {"task": "prim", "n": n, "edges": weighted},
            {"task": "scc", "n": n, "edges": unweighted},
            {"task": "topo", "n": n, "edges": unweighted},
            {"task": "dag", "n": n, "source": rng.randrange(n), "edges": weighted},
        ]
        # Also force a DAG with scrambled vertex IDs to exercise finite shortest
        # paths often; arbitrary random digraphs otherwise contain many cycles.
        permutation = list(range(n))
        rng.shuffle(permutation)
        forward = []
        for _ in range(9):
            a, b = sorted([rng.randrange(n), rng.randrange(n)])
            if a != b:
                forward.append([permutation[a], permutation[b], rng.randint(-8, 8)])
        tasks.append({"task": "dag", "n": n, "source": rng.randrange(n), "edges": forward})
        for request in tasks:
            cases.append({"name": "random_%02d_%s_%d" % (index, request["task"], len(cases)),
                          "input": request, "expected": reference(request)})
    return cases


def witness_checks():
    """Prove the checker tolerates non-uniqueness but rejects bad evidence."""
    cases = []
    forest = {"task": "kruskal", "n": 3, "edges": [[0, 1, 1], [1, 2, 1], [0, 2, 1], [0, 0, 0]]}
    valid_forest = {"cost": 2, "components": 1, "edge_ids": [0, 2]}
    bad_forest = {"cost": 2, "components": 1, "edge_ids": [0, 0]}
    for task in ("kruskal", "prim"):
        request = dict(forest, task=task)
        cases.append((task, request, valid_forest, bad_forest, "An alternative minimum tree is accepted; duplicate selected IDs are rejected."))
    request = {"task": "scc", "n": 4, "edges": [[0, 1], [1, 0], [1, 2]]}
    cases.append(("scc", request, {"component": [10, 10, 30, 77], "condensation": [[10, 30]]},
                  {"component": [10, 10, 30, 77], "condensation": [[10, 30], [10, 30]]},
                  "Nonconsecutive renamed labels are accepted; duplicate condensation pairs are rejected."))
    request = {"task": "topo", "n": 3, "edges": [[0, 2], [1, 2]]}
    cases.append(("topo", request, {"acyclic": True, "order": [1, 0, 2]}, {"acyclic": True, "order": [2, 0, 1]},
                  "Either ordering of independent 0 and 1 is accepted; a backward dependency is rejected."))
    request = {"task": "dag", "n": 4, "source": 0, "edges": [[0, 1, 2], [0, 1, 2], [0, 2, 1], [2, 1, 1]]}
    cases.append(("dag", request, {"acyclic": True, "distance": [0, 2, 1, None], "parent_edge": [-1, 3, 2, -1]},
                  {"acyclic": True, "distance": [0, 2, 1, None], "parent_edge": [-1, 2, 2, -1]},
                  "A tied route is accepted; an edge that ends at the wrong vertex is rejected."))
    result = []
    for name, request, valid, invalid, explanation in cases:
        expected = reference(request)
        accepted = check(request, valid, expected)
        rejected = not check(request, invalid, expected)
        assert accepted and rejected, name
        result.append({"task": name, "input": request, "reference": expected, "alternative_valid": valid,
                       "invalid_witness": invalid, "alternative_accepted": accepted, "invalid_rejected": rejected,
                       "justification": explanation})
    return result


def full_cases():
    n, limit = 100000, 250000
    operations = [[i, i + 1] for i in range(n - 1)]
    operations.extend([[0, 0], [0, n - 1]] * ((limit - len(operations) + 1) // 2))
    operations = operations[:limit]
    states = [[n - i - 1, i + 2] for i in range(n - 1)] + [[1, n] for _ in range(limit - n + 1)]
    yield "full_dsu", {"task": "dsu", "n": n, "operations": operations}, {"states": states}, \
        "A chain merges one new vertex per operation; remaining operations are self/repeated unions."
    del operations, states
    weighted = [[i, i + 1, -1000000000] for i in range(n - 1)]
    weighted.extend([[i % (n - 1), i % (n - 1) + 1, -1000000000] for i in range(limit - len(weighted))])
    for task in ("kruskal", "prim"):
        yield "full_" + task, {"task": task, "n": n, "edges": weighted}, \
            {"cost": -(n - 1) * 1000000000, "components": 1}, \
            "The underlying graph is a chain with parallel copies. Every spanning tree has n-1 edges of weight -10^9."
    distance = [-i * 1000000000 for i in range(n)]
    yield "full_dag", {"task": "dag", "n": n, "source": 0, "edges": weighted}, \
        {"acyclic": True, "distance": distance}, "Only consecutive forward steps exist, so distance[i]=-i*10^9."
    del weighted, distance
    directed = [[i, i + 1] for i in range(n - 1)]
    directed.extend([[i % (n - 1), i % (n - 1) + 1] for i in range(limit - len(directed))])
    yield "full_scc_chain", {"task": "scc", "n": n, "edges": directed}, {"component": list(range(n))}, \
        "All edges go forward along a chain. Every SCC is a singleton and condensation has n-1 distinct pairs."
    yield "full_topo_chain", {"task": "topo", "n": n, "edges": directed}, {"acyclic": True}, \
        "The complete chain forces increasing order; duplicate forward edges do not create cycles."
    directed[-1] = [n - 1, 0]
    yield "full_scc_cycle", {"task": "scc", "n": n, "edges": directed}, {"component": [0] * n}, \
        "The chain plus last-to-first edge forms one SCC containing every vertex."
    yield "full_topo_cycle", {"task": "topo", "n": n, "edges": directed}, {"acyclic": False}, \
        "The back edge closes a directed cycle at depth 100000."
    del directed
    cycle = [[i, i + 1, 1] for i in range(1, n - 1)] + [[n - 1, 1, 1]]
    cycle.extend([[1, 2, 1] for _ in range(limit - len(cycle))])
    yield "full_dag_unreachable_cycle", {"task": "dag", "n": n, "source": 0, "edges": cycle}, \
        {"acyclic": False}, "Source 0 is isolated, while vertices 1..99999 form a deep cycle that must still be detected."


def run_small(command):
    named = small_cases()
    randoms = random_cases()
    (HERE / "my_cases.jsonl").write_text("".join(encode(case["input"]) + "\n" for case in named), encoding="utf-8")
    dump_json(HERE / "expected_cases.json", named)
    dump_json(HERE / "random_cases.json", randoms)
    # Multiple complete mixed batches in a single process also test request reset.
    batch = named + randoms + named + list(reversed(named))
    answers = execute(command, [case["input"] for case in batch])
    for case, answer in zip(batch, answers):
        assert check(case["input"], answer, case["expected"]), (case["name"], answer, case["expected"])
    (HERE / "actual_cases.jsonl").write_text("".join(encode(answer) + "\n" for answer in answers[:len(named)]), encoding="utf-8")
    dump_json(HERE / "random_actual.json", answers[len(named):len(named) + len(randoms)])
    dump_json(HERE / "witness_checks.json", witness_checks())
    return {"named_cases": len(named), "random_cases": len(randoms), "mixed_requests_in_one_process": len(batch),
            "random_seed": 381726, "alternative_valid_and_invalid_pairs": 5, "passed": True}


def run_full(command):
    results = []
    with gzip.open(HERE / "full_inputs.jsonl.gz", "wt", encoding="utf-8", newline="\n") as inputs, \
         gzip.open(HERE / "full_actual.jsonl.gz", "wt", encoding="utf-8", newline="\n") as outputs:
        for name, request, expected, reason in full_cases():
            input_text = encode(request)
            inputs.write(input_text + "\n")
            start = time.perf_counter()
            answer = execute(command, [request])[0]
            elapsed = time.perf_counter() - start
            assert check(request, answer, expected), name
            output_text = encode(answer)
            outputs.write(output_text + "\n")
            result = {"name": name, "n": request["n"], "m_or_q": len(request.get("edges", request.get("operations", []))),
                      "passed": True, "seconds_including_transport": elapsed, "justification": reason,
                      "input_sha256": hashlib.sha256(input_text.encode()).hexdigest(),
                      "actual_sha256": hashlib.sha256(output_text.encode()).hexdigest()}
            results.append(result)
            print("PASS %s: n=%d, m/q=%d (%.3fs)" % (name, request["n"], result["m_or_q"], elapsed), flush=True)
    dump_json(HERE / "full_results.json", results)
    return results


def run_helpers(language):
    if language != "Java":
        return {"skipped": "The direct helper harness targets the selected Java implementation."}
    output = ROOT / ".build" / "OwnHelpers"
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src" / "Java").glob("*.java")) + [HERE / "RouteHelperTest.java"]
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(output), *map(str, sources)],
                   check=True, timeout=60)
    result = subprocess.run(["java", "-Xmx384m", "-cp", str(output), "RouteHelperTest"],
                            check=True, timeout=20, capture_output=True, text=True, encoding="utf-8")
    evidence = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(evidence) == 6 and all(row["passed"] for row in evidence)
    dump_json(HERE / "route_helper_actual.json", evidence)
    return {"calls": len(evidence), "passed": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", action="store_true", help="also test n=100000 and m/q=250000")
    args = parser.parse_args()
    language = select_language(None)
    command = build(language)
    result = {"command": "python tests/run_own.py" + (" --full" if args.full else ""),
              "language": language, "python": sys.version, "platform": platform.platform(), "small": run_small(command)}
    print("PASS own small tests: %d mixed requests, %d witness pairs" %
          (result["small"]["mixed_requests_in_one_process"], result["small"]["alternative_valid_and_invalid_pairs"]), flush=True)
    result["helper"] = run_helpers(language)
    print("PASS route helper calls", flush=True)
    if args.full:
        result["full"] = run_full(command)
    dump_json(HERE / "own_results.json", result)
    print("PASS all requested own tests", flush=True)


if __name__ == "__main__":
    main()
