# Kruskal and Prim benchmark

Run from the repository root, with Python 3.12+ and JDK 17+ on PATH:

```sh
python tests/benchmark/run_benchmark.py
```

The driver compiles all Java solution files and `Benchmark.java` with `--release 17`, then starts one JVM with a fixed 384 MiB initial/maximum heap. Compilation and JVM startup are outside every recorded interval. `results.json` contains the actual environment, hardware, source/input hashes, input sizes and seeds, raw nanosecond samples, medians, execution order, and validation outcome. Re-running replaces this measurement file with the new actual results.

The three sizes are `(n,m)=(1000,2500)`, `(10000,25000)`, and `(100000,250000)`. Each graph uses `java.util.Random(20261025+n)`. First, a spanning chain receives negative weights in `[-1000000000,-1]`; extra edges receive positive weights in `[1,1000000000]`. Extras include forced loops and parallel edges. Fisher-Yates shuffling removes construction order from the final input. The exact generator is in `Benchmark.java`; input hashes use SHA-256 over big-endian int32 `n,m,u,v,w,...`.

The independently known unique optimum is the sum of the negative chain weights. For each chain edge, the cut formed by deleting it has that edge as its only negative crossing edge, so every MST must contain it. All chain edges together form a spanning tree. This proves the expected cost without trusting either implementation.

Before collecting samples, each method runs once for correctness and three times for warm-up on each size. Five measured calls per method follow, alternating which method runs first. Both methods receive the same unchanged `int[][]` graph. `System.nanoTime()` surrounds only the typed core method call. Internal sorting/DSU or adjacency/heap construction and result allocation are included. Input generation, JSON adapters, input/output, independent validation, hashing, and compilation are excluded for both methods. No explicit garbage collection is requested; JIT, collection, and scheduling effects inside timed calls remain part of the observations.

Every result, including warm-up and measured results, is validated after its call. The independent verifier checks expected cost and component count, original edge IDs, no repeated IDs or selected loops, exact sum of selected weights, exactly `n-1` selected edges, and connectivity by BFS. A connected graph with `n-1` distinct edges is a tree. Final input hashes confirm the algorithms did not mutate the input.

Limitations: this is one JVM execution and a sparse, connected planted-tree family with `m=2.5n`; it includes negative weights, loops, and parallel edges but does not measure disconnected graphs or dense graphs. The weight separation makes the planted tree preferable to every extra edge. Five samples can show JIT/GC/OS noise and do not establish a universal speed ranking. Interpret measured results separately from the asymptotic complexity proof in `ANALYSIS.md`.
