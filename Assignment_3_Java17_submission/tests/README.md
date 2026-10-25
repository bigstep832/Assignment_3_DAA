# Own tests and reproducible evidence

Run from the repository root with Python 3.12+ and JDK 17+ on PATH:

```sh
python tools/run.py --setup
python tools/run.py --check
python tests/run_own.py --full
python tools/run.py --input tests/my_cases.jsonl
```

The last command prints the small cases without comparing them. `run_own.py`
performs the comparisons and returns a nonzero exit status on any failure.
Omit `--full` for only the small/random/witness/helper tests. The manifest selects
the application language. No package download is needed.

## Inputs, expectations, and actual results

- `my_cases.jsonl`: named boundary and regression inputs, in the recorded order.
- `expected_cases.json`: those inputs, independently derived expected answers,
  names, and a mathematical justification for each case.
- `actual_cases.jsonl`: actual application results for the named cases.
- `random_cases.json` and `random_actual.json`: every seeded random input,
  independent expected result, and actual result.
- `witness_checks.json`: alternative valid answers accepted by the checker and
  invalid witnesses rejected, with the complete inputs and explanations.
- `route_helper_actual.json`: direct calls to the Java helper, returned routes,
  selected original edge IDs, edge directions and checked weights.
- `own_results.json`: command, runtime, platform, counts, and completed results.
- `full_inputs.jsonl.gz` and `full_actual.jsonl.gz`: complete large requests and
  actual responses as UTF-8 JSON Lines compressed with gzip. Decompression is
  lossless. `full_results.json` records their order, expected-answer arguments,
  SHA-256 values of each uncompressed JSON line (excluding its newline), and
  measured end-to-end execution times. `full_cases()` regenerates the inputs.

Files reporting actual outputs are written only after running the program. A
failed run raises an exception rather than producing a new success summary.
Older evidence should therefore be interpreted using the recorded command and
the accompanying current source, not as a claim that every later edit passed.

## Independent expected answers

The test driver does not import the implementation under test. The small
reference algorithms deliberately differ from the required implementations:

| Task | Independent oracle |
|---|---|
| DSU | Rebuild undirected connectivity with BFS after each operation prefix. |
| Kruskal/Prim | Enumerate all subsets of `n-c` edges and minimize cost among spanning subsets. |
| SCC | BFS reachability from each vertex; equal components mean mutual reachability. |
| Topological order | Kahn's indegree algorithm decides whether the entire graph is acyclic. |
| DAG distances | Kahn checks all components; Bellman-Ford obtains finite shortest distances. |

Random generation uses Python's `random.Random(381726)` for 40 rounds, each with
seven task requests (280 cases). Tiny graphs have at most six vertices and nine
edges so exhaustive forest enumeration is practical. A separately generated DAG
with shuffled vertex IDs in each round ensures many noncyclic shortest-path
cases. Named cases cover empty graphs, isolates, disconnected graphs, repeated
and self unions, loops, tied and parallel edges, negative weights, sums outside
32-bit range, a nonzero source, finite distance -1, unreachable vertices, and
cycles outside source reachability.

The semantic checker supplied in `tools/public_validate.py` checks the actual
forest edges, exact SCC partition and condensation, topological precedence, and
shortest-path parent witnesses against these independent expected values. Tests
never require an arbitrary SCC numbering, tied-edge choice, or topological order.
The separate witness tests accept alternative optima/labels/orders/parents and
reject a duplicate forest ID, duplicate condensation edge, backward dependency,
and incorrectly directed parent edge. Both MST algorithms have a witness test.

All named cases, all random cases, the named cases again, then the named cases
in reverse order run in **one application process**, checking state reset across
task types and repetitions.

## Full-limit cases

Every full case uses `n=100000` and exactly `m=250000` edges or `q=250000`
operations. Each runs with the same supplied 20-second transport timeout and,
for Java, `-Xmx384m`.

| Case | Construction and independent expectation |
|---|---|
| DSU | Merge chain vertices, then repeat/self-union: after merge i, the number of components decreases by one and the largest component grows by one. |
| Kruskal and Prim | Chain with parallel copies, all weights -10^9: every spanning tree has cost `-99999000000000`, 99999 selected edges and one component. |
| DAG | The same forward weighted chain: `distance[i]=-i*10^9`; parallel copies may change only parent IDs. |
| SCC chain | Forward chain and duplicates: 100000 singleton SCCs and exactly 99999 distinct condensation pairs. |
| Topo chain | The full chain forces increasing vertex order; duplicate forward edges are harmless. |
| SCC cycle | Replace one duplicate by the last-to-first edge: all vertices form one SCC. |
| Topo cycle | The same back edge closes a cycle at depth 100000. |
| DAG unreachable cycle | Source 0 is isolated; vertices 1..99999 form a cycle. Whole-graph checking must reject the input. |

The large expected answers come from those constructions, not from either MST
algorithm or from a second invocation of the implementation.

## Route helper

`RouteHelperTest.java` is compiled into `.build/OwnHelpers` with the application
sources and called directly by `run_own.py`. It remains outside `src/Java`, so it
does not change the supplied program entry point. Assertions use explicit checks
and do not depend on Java's `-ea` flag.

It tests a reachable route, the source itself, an unreachable target, the second
of two identical parallel edges, a tied route through another vertex, and a
100000-vertex route. Every reported short route checks original parent-edge IDs,
edge direction, and summed `long` weight. Unreachable routes are empty lists.
