# Assignment 3: Graph Algorithms

## Implementation and reproducibility

The selected language is **Java** in `submission.json`. The six algorithms and
the route helper are implemented in `src/Java/Algorithms.java`; the supplied
input conversion, JSON parser and JSON Lines entry point are preserved. The
program accepts several independent requests in one process and writes exactly
one result object per input line. `ping` still returns `{"status":"ready"}`.

Requirements: Python 3.12+ for the supplied checking tools and JDK 17+ for Java.
The recorded local checks used Python 3.12.14 and JDK **21.0.10**, compiling with
`javac --release 17`. This verifies Java 17 source/API/bytecode compatibility;
it is not a claim that execution on a native JDK 17 was separately tested.
No downloaded libraries are needed.

Run these commands from the repository root:

```sh
python tools/run.py --setup
python tools/run.py --check
python tests/run_own.py --full
python tools/run.py --input tests/my_cases.jsonl
python tests/benchmark/run_benchmark.py
python -m unittest discover -s tools -p "test_*.py"
```

The first two commands build and check the selected implementation. The third
also compiles and calls the route helper directly. The fourth prints actual
responses for the saved small inputs. The fifth regenerates benchmark evidence;
the last checks the template's own checking tools and starter transport.
On Windows, `py -3` can replace `python`; ensure `java` and `javac` resolve to the
same installed JDK. To build and run the application directly:

```sh
mkdir -p .build/Java
javac --release 17 -encoding UTF-8 -d .build/Java src/Java/*.java
java -Xmx384m -cp .build/Java Main < tests/my_cases.jsonl
```

That direct example uses a POSIX shell. The Python launcher above works on
Windows too and creates the output directory automatically.

Let `n` denote vertices, `m` edges, `q` union operations, and `L` vertices in a
reconstructed route. All memory bounds below include the returned result.

| Method | Time | Memory |
|---|---|---|
| DSU | `O(n + q alpha(n))` amortized | `O(n+q)` |
| Kruskal | `O(n + m log(m+1))` | `O(n+m)` |
| Lazy-heap Prim | `O(n + m log(m+1))` | `O(n+m)` |
| Kosaraju SCC and condensation | `O(n+m)` | `O(n+m)` |
| Three-state DFS topological order | `O(n+m)` | `O(n+m)` |
| DAG shortest paths | `O(n+m)` | `O(n+m)` |

`alpha(n)` is the inverse Ackermann function. It arises from path compression
combined with union by size. The bounds do not hide a recursive call stack.

## DSU: online connectivity

Initially each vertex is its own root, has size one, and contributes one
component. `find` first walks to the root and then walks the same path again,
redirecting every encountered parent to that root. `union` attaches the smaller
root beneath the larger root, updates its size, decrements the component count,
and updates the largest size. A result row is appended after every operation.

**Invariant.** Parent pointers form rooted trees representing exactly the
connected components of the processed operation prefix. Each root's stored
size is the number of vertices in its component, and the maintained counters
describe that partition.

Initially the invariant holds for singleton components. Path compression only
changes the internal representation of one component. If two roots differ,
their union joins exactly those two components and their sizes add. If the
roots agree, the partition and counters remain unchanged. Induction over the
operations proves every returned `[components,largest]` row is correct.

Self-unions and repeated unions are therefore harmless. There is no initial
output row. Empty operations return an empty array, including `n=0`; initially
the largest component is zero only when there are no vertices. Initialization
uses `O(n)` time; the union/find sequence has the stated amortized bound, and
the `q` recorded states account for the output memory.

## Kruskal: minimum spanning forest

An array of original edge IDs is sorted by weight, breaking ties by ID. The
original input rows remain unchanged. Starting with an empty forest, the method
accepts an edge exactly when its endpoints belong to different DSU components.
The accepted weight is added to a `long`, and the original ID is returned.

**Invariant.** Accepted edges form a forest, its connected components are the
DSU components, and some minimum spanning forest contains all accepted edges.
The empty forest satisfies this statement. A rejected edge would create a
cycle. For an accepted edge, consider the cut around one current DSU component.
There cannot be a lighter unprocessed crossing edge, since edges are scanned
in nondecreasing weight order. The cut exchange argument lets this edge replace
an edge crossing the same cut in an optimal forest without increasing cost.
Thus accepting it preserves the invariant. At the end, all vertices connected
by input edges are connected in the selected forest, proving minimum total
cost in every original connected component.

Self-loops are rejected because their endpoints have the same root. Parallel
edges and equal weights are handled individually; negative weights do not
change the cut argument. Isolates remain singleton DSU components, and `n=0`
returns zero cost, zero components and no IDs. A forest has exactly `n-c` edges.
Sorting dominates the time, while edge IDs, DSU arrays and the result use
`O(n+m)` space.

## Prim: a separate minimum spanning forest algorithm

Prim builds undirected adjacency lists and uses a priority queue of candidate
edges. Each unvisited vertex starts a new component. Visiting a vertex adds
its edges to unvisited neighbors. Heap entries whose destination is already
visited are discarded. The lightest remaining entry supplies the next vertex
and its selected original edge ID. When the heap is empty, the outer loop
continues to the next unvisited vertex.

**Invariant.** Within the current component, selected edges form a tree on the
visited vertices. Every edge crossing from that set to an unvisited vertex has
a candidate in the heap; stale candidates may also be present. After removing
stale entries, the minimum candidate is a lightest crossing edge. The cut
property proves that adding it preserves extendability to a minimum tree.
Its destination is new, so adding it cannot create a cycle. If no crossing edge
remains, the component is complete. Restarting repeats the argument for every
connected component, proving correctness of the entire forest.

Loops are omitted when constructing adjacency lists. Negative weights, ties
and parallel edges remain valid candidates. An isolated root increments the
component count without selecting an edge. This handles disconnected and empty
graphs. Each undirected edge creates at most one useful candidate insertion
when its first endpoint is visited; candidates can later become stale. There
are `O(m)` heap operations and a heap of size `O(m)`, giving the lazy-heap bound
in the table. The algorithm does not call Kruskal or DSU.

## Kosaraju: strongly connected components and condensation

The method constructs the directed graph and its transpose. The first DFS
records vertices when all outgoing arcs are finished. The second pass traverses
the transpose in decreasing first-pass finish order, assigning a new component
label at every unassigned starting vertex. Vertices are marked when pushed,
which prevents duplicate entries in the explicit stack.

**Invariant and correctness.** A first-pass finish event occurs only after all
DFS descendants have finished. For an edge between distinct SCCs in the
original condensation DAG, the source SCC has a larger maximum finish time
than the destination SCC. Consequently the next unassigned SCC chosen by
decreasing finish time has no edge to another unassigned SCC in the transpose.
The second traversal cannot leave that SCC for an unassigned one and reaches
every vertex inside it, since strong connectivity survives transposition.
It therefore labels exactly one SCC. Repeating partitions all vertices into
their maximal mutually reachable groups.

For condensation, vertices are grouped into linked lists by their assigned
source component. When processing component `a`, the array
`seenDestination[b]` stores whether destination `b` was already emitted for
this particular `a`. Each original outgoing arc is scanned once. Internal
edges are ignored; the first cross-component arc emits `[a,b]`, and subsequent
copies are skipped. This yields every distinct pair exactly once. Stamps are
component labels, so no array clearing is needed per component and no hashing
or sorting is needed. Condensation construction is deterministically linear.

Loops stay internal, duplicates are deduplicated only in condensation, and
isolates receive their own labels. Both output arrays are empty for `n=0`.
Two adjacency structures, DFS arrays, component groups and result pairs use
`O(n+m)` memory; every pass takes `O(n+m)` time.

## Topological order: three-state DFS

Each vertex has state unvisited, active or finished. An explicit DFS frame
holds its vertex and the next unexamined adjacency arc. Before descending, the
parent frame advances that arc, so resumption never scans old neighbors again.
The outer loop starts DFS in every still-unvisited component.

**Invariant.** Active vertices are exactly the current DFS ancestry path.
An edge to an active vertex closes a directed cycle, so the method immediately
returns `acyclic=false` and an empty order. An edge to a finished vertex does
not establish a cycle. If no back edge is found, every edge `u -> v` has `v`
finished before `u`: either DFS reaches `v` from `u`, or `v` has already
finished. Reversing finish order therefore places every `u` before its `v`
and contains each vertex exactly once. Conversely, a directed cycle forces a
back edge during a complete DFS, so the cycle decision is correct.

A self-loop reaches the current active vertex and is rejected. Disconnected
cycles are found by the outer loop. Parallel forward edges are harmless, and
the empty graph is acyclic with empty order. Each vertex and arc is processed
a constant number of times, giving linear time and memory even for a deep chain.

## DAG shortest paths

The same cycle-checking DFS first examines the **whole graph**, without
restricting traversal to the source. Any detected cycle returns `acyclic=false`
and two empty arrays. Otherwise, vertices are processed in reverse finish
order. Only the source initially has distance zero; all other distances are
`null`, and every parent ID initially equals `-1`. Outgoing edges are relaxed
only from reachable vertices. A strictly better candidate replaces the target
distance and stores that edge's original ID.

**Invariant.** When a vertex is processed in topological order, its stored
distance is its shortest source distance, or `null` exactly when unreachable.
All possible incoming predecessors occur earlier. By induction their shortest
distances have already been propagated, so the best incoming candidate is the
shortest distance to this vertex. Negative weights do not affect the argument,
because no later vertex can lead back to an earlier one. Every finite update
has a real incoming edge from a reachable predecessor, so following parent IDs
backward moves earlier in topological order and eventually reaches the source.
This proves both distances and the returned witness.

No edge from an unreachable vertex is relaxed; `-1` may be a legitimate finite
distance. The source retains parent `-1`, as a reachable path back to it would
create a cycle. Ties keep the first equally good witness encountered, and
parallel edges retain their separate IDs. Self-loops and cycles unreachable
from the source still invalidate the graph. The graph, DFS, relaxation and
result arrays all satisfy `O(n+m)` time and space.

## Numeric safety, state reset and deep graphs

Weights fit `int`, but costs and finite distances use `long`. A forest or a
simple DAG path has at most `n-1` edges, hence absolute total at most
`99999 * 1000000000 = 99999000000000`, far below `Long.MAX_VALUE`. Comparators
compare integers directly rather than subtracting weights. Vertex counts,
component sizes, arc indices and edge IDs fit `int` under the stated limits.

All mutable structures are created inside the current request. Static methods
and nested classes do not hold reusable mutable algorithm state. Original
edge arrays are never reordered or changed. Consequently changing task, graph
size, source or edge weights between requests cannot reuse a previous result.

DFS and DSU find are iterative. The first DFS stack stores traversal progress;
the second SCC stack marks vertices before pushing. Both hold at most `n`
vertices and need no Java recursion-depth adjustment. The complete tests include
100000-vertex chains, a cycle closing at that depth, and route reconstruction
of the same depth.

## Route reconstruction and recorded calls

`Algorithms.reconstructRoute(source, target, edges, parentEdge)` follows each
incoming parent edge from target toward source, then reverses the collected
vertices. The source returns `[source]`; an unreachable target returns `[]`.
The helper is a Java method, not an extra JSON task. Its input is the parent
array of an acyclic DAG result. Invalid endpoints, broken directions and cyclic
parent chains are rejected. A valid path contains at most `n` vertices; a
backward traversal that has already collected `n` vertices without reaching
source must therefore be cyclic. This length guard needs no visited set.
Each route vertex is processed once, and reversal is linear, giving `O(L)`
time and output space for a valid route. A malformed cycle is rejected after
at most `n` collected vertices.

The following actual calls are recorded in `tests/route_helper_actual.json`.
For the first three, the algorithm itself produced
`parentEdge = [-1,0,2,3,-1]` from:

```java
int[][] edges = {{0,1,3}, {0,2,8}, {1,2,-7}, {2,3,4}};
```

| Call | Returned vertices | Selected original IDs | Weight |
|---|---|---|---|
| `Algorithms.reconstructRoute(0, 3, edges, parentEdge)` | `[0,1,2,3]` | `[0,2,3]` | `3-7+4=0` |
| `Algorithms.reconstructRoute(0, 0, edges, parentEdge)` | `[0]` | `[]` | `0` |
| `Algorithms.reconstructRoute(0, 4, edges, parentEdge)` | `[]` | `[]` | No route (`null` in evidence) |

The tied/parallel tests deliberately supply two different valid shortest-path
parent arrays for the same graph, proving that the helper uses edge IDs:

```java
int[][] tied = {{0,1,2}, {0,1,2}, {0,2,1}, {2,1,1}, {1,3,-1}};
Algorithms.reconstructRoute(0, 3, tied, new int[]{-1,1,2,4});
// [0,1,3]; selected IDs [1,4]; cost 2-1 = 1.
Algorithms.reconstructRoute(0, 3, tied, new int[]{-1,3,2,4});
// [0,2,1,3]; selected IDs [2,3,4]; cost 1+1-1 = 1.
```

For every step, the harness verifies that the selected edge starts at the
previous route vertex and ends at the current one, then adds its `long` weight.
A sixth call, `Algorithms.reconstructRoute(0, 99999, chain, parents)`, returned
all 100000 vertices in increasing order; each consecutive edge weighs `-10^9`,
so the verified total is `-99999000000000`.

## Tests and actual evidence

The saved setup and public logs report a successful ping, **36/36 public
cases**, and two complete public batches in one process. These are local
results, not GitHub Actions results or a calculated assignment grade. The
template's supporting unittest suite also completed successfully: 16 discovered,
12 executed, and four C++/C# transport checks skipped because those optional
toolchains were unavailable. The exact output is in `tests/tool_checks.txt`.
For the local Windows run, UTF-8 mode was enabled with `PYTHONUTF8=1`, the actual
JDK `bin` directory was placed first on PATH, and temporary files were directed
to a writable workspace folder.

`tests/own_results.json` records successful own checks: 30 named cases,
280 seeded random cases, 370 mixed requests in one process, five pairs of
alternative-valid/invalid witness checks, six direct helper calls and nine
full-limit cases. Every full-limit case has `n=100000` and exactly 250000 edges
or union operations. Expected answers are independent of the implementation:

| Task | Small-case reference method |
|---|---|
| DSU | Rebuild connectivity by BFS after each operation prefix. |
| Kruskal and Prim | Exhaustively enumerate spanning edge subsets and minimize weight. |
| SCC | Compute mutual reachability using BFS from every vertex. |
| Topological order | Kahn's indegree algorithm checks global acyclicity. |
| DAG | Kahn checks cycles; Bellman-Ford computes finite shortest distances. |

Random generation uses seed **381726**, forty rounds, at most six vertices and
nine edges per graph. An additional shuffled-vertex DAG per round ensures many
finite shortest-path checks. The supplied semantic validator then checks actual
forest feasibility and optimum cost, SCC partition and exact condensation,
topological precedence, and shortest-path parent chains. The checker accepts
alternative edge choices, SCC labels, orders and parents. It rejects duplicate
forest IDs, duplicate condensation pairs, backward dependencies and parent
edges ending at the wrong vertex. This prevents a comparison against one
arbitrary witness from masquerading as a correctness check.

Named inputs cover empty graphs, isolates, disconnected graphs, repeated/self
unions, loops, negative weights, equal/parallel edges, totals beyond 32 bits,
nonzero source, finite distance -1, unreachable targets and unreachable cycles.
The nine large constructions are a DSU chain plus repeated operations, the
same weighted chain with parallel edges for both forests and DAG, SCC/topo
chains and closing cycles, and a DAG request with an isolated source and a
deep cycle elsewhere. Their expected values follow directly from construction;
for example, every spanning tree of the weighted chain has 99999 edges of
weight `-10^9`, while DAG vertex `i` has distance `-i*10^9`.

The complete small inputs, expectations with explanations, and actual outputs
are saved under `tests/`. Large inputs and outputs are saved losslessly in
`full_inputs.jsonl.gz` and `full_actual.jsonl.gz`; `full_results.json` contains
their hashes and pass results. See `tests/README.md` for the exact file mapping.
The full-size runs use the supplied 20-second request-batch timeout and Java's
384 MiB maximum heap. Their recorded end-to-end times include transport and
are not the algorithm-only benchmark below.

These checks exercise boundaries and provide reproducible evidence; they are
not exhaustive over all allowed graphs. The correctness arguments establish
the general algorithms. Malformed JSON and out-of-range inputs are outside the
assignment contract and are not claimed as covered.

## Kruskal versus Prim benchmark

Command: `python tests/benchmark/run_benchmark.py`. The reproducible generator,
independent verifier and measurement driver are in `tests/benchmark/`.
Graphs have `(n,m) = (1000,2500), (10000,25000), (100000,250000)`.
For each size, `java.util.Random(20261025+n)` generates a spanning chain with
negative weights in `[-10^9,-1]`, then positive extra edges in `[1,10^9]`,
including forced loops and parallel edges. Fisher-Yates shuffling determines
final input order. Both methods receive the identical unchanged `int[][]`.

The expected optimum is independently known: deleting any chain edge defines
a cut where that edge is the only negative crossing edge. Every minimum tree
must contain it. All negative chain edges together already form a tree, proving
both the selected tree and its summed optimum cost. Before timing, each method
runs once for validation and three times for warm-up at each size. Five measured
calls per method follow, alternating which method goes first. Every result is
validated outside the timed interval for cost, components, unique valid IDs,
no loops, exact weight sum, `n-1` selected edges and BFS connectivity.

`System.nanoTime()` surrounds only the typed core method call. Internal
allocation, sorting/DSU or adjacency/heap construction, and result allocation
are included. Input generation, JSON parsing/conversion, input/output, hashing,
validation, compilation and JVM startup are excluded equally for both methods.
One JVM uses `-Xms384m -Xmx384m`. No explicit garbage collection is requested;
JIT, garbage collection and OS scheduling inside a sample remain observable.

The recorded run completed on 25 October 2026. Hardware was a
**12th Gen Intel Core i5-12450HX**, with 12 logical processors reported to the
JVM, running Windows 11 AMD64 (build 26200). The runtime was Oracle Java
21.0.10, Java HotSpot 64-Bit Server VM; the driver used Python 3.12.14.
Raw nanoseconds, execution order, input SHA-256 values and source hashes are
saved in `tests/benchmark/results.json`. The following actual samples convert
nanoseconds to **milliseconds**; each median is the middle of the five samples.

| n | m | Method | Run 1 | Run 2 | Run 3 | Run 4 | Run 5 | Median |
|---:|---:|---|---:|---:|---:|---:|---:|---:|
| 1000 | 2500 | Kruskal | 1.0774 | 0.8888 | 0.8115 | 0.8012 | 0.9741 | **0.8888** |
| 1000 | 2500 | Prim | 1.1363 | 1.0225 | 1.7531 | 1.7175 | 2.1453 | **1.7175** |
| 10000 | 25000 | Kruskal | 18.1817 | 18.2674 | 16.7989 | 20.6255 | 24.3848 | **18.2674** |
| 10000 | 25000 | Prim | 11.0096 | 7.2360 | 8.4664 | 7.6590 | 7.1884 | **7.6590** |
| 100000 | 250000 | Kruskal | 154.6153 | 151.6197 | 153.1640 | 161.6610 | 170.7371 | **154.6153** |
| 100000 | 250000 | Prim | 196.7325 | 205.5051 | 212.4099 | 220.1588 | 207.9286 | **207.9286** |

All returned forests passed the independent checks, and every input hash was
unchanged after all calls. The exact generation seeds and checked totals were:

| n | Seed | Minimum cost | Components |
|---:|---:|---:|---:|
| 1000 | 20262025 | -497627141390 | 1 |
| 10000 | 20271025 | -4943193831344 | 1 |
| 100000 | 20361025 | -50058277603493 | 1 |

Kruskal had the lower median on the smallest and largest graphs; Prim had the
lower median at the middle size. The changing ranking and visible spread show
that constants, graph shape and runtime effects matter at these sizes. Sorting
all IDs and maintaining a lazy heap have different allocation and access
patterns, but these measurements do not isolate which factor caused a specific
sample. Both methods' median elapsed time increased with graph size.

The theoretical bounds apply to arbitrary allowed graphs, independently of
these measurements. This benchmark measures one sparse, connected planted-tree
family with `m=2.5n`; it does not cover dense or disconnected performance. The
strong weight separation and graph shape can favor particular heap/sorting
behavior. Five samples in one JVM cannot establish a universal speed ranking.

## Sources

The assignment contracts, starter adapters, public cases and semantic validator
come from the course template:
[RZhaniya/daa-assignment-3-graphs](https://github.com/RZhaniya/daa-assignment-3-graphs),
base commit
[`76c1d91daa90ceadcb225f5408d54d4bfcf397f9`](https://github.com/RZhaniya/daa-assignment-3-graphs/commit/76c1d91daa90ceadcb225f5408d54d4bfcf397f9).
The supplied English assignment PDF was also used to check the deliverables.
Java's standard collections, arrays, sorting and priority queue are used; no
external graph-algorithm library is used.

