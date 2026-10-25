import java.lang.reflect.Array;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Reproducible core-method benchmark; run through run_benchmark.py. */
public final class Benchmark {
    private static final long BASE_SEED = 20261025L;
    private static final int WARMUPS = 3;
    private static final int REPETITIONS = 5;
    private static volatile long consumed;

    private record Graph(int n, int[][] edges, long seed, long optimum) {}

    private static Graph generate(int n, int m) {
        long seed = BASE_SEED + n;
        Random random = new Random(seed);
        int[][] edges = new int[m][3];
        long optimum = 0;
        // The negative edges are exactly one spanning chain. Every other edge
        // is positive, so these n-1 edges are the unique minimum spanning tree.
        for (int i = 0; i < n - 1; i++) {
            int weight = -1 - random.nextInt(1_000_000_000);
            edges[i] = new int[]{i, i + 1, weight};
            optimum += weight;
        }
        for (int i = n - 1; i < m; i++) {
            int u = random.nextInt(n);
            int v;
            if (i % 37 == 0) v = u;
            else if (i % 17 == 0) {
                u = random.nextInt(n - 1);
                v = u + 1;
            } else v = random.nextInt(n);
            edges[i] = new int[]{u, v, 1 + random.nextInt(1_000_000_000)};
        }
        // Prevent input order from handing the planted chain to either method.
        for (int i = m - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] row = edges[i];
            edges[i] = edges[j];
            edges[j] = row;
        }
        return new Graph(n, edges, seed, optimum);
    }

    private static Map<String, Object> run(String method, Graph graph) {
        return method.equals("kruskal")
                ? Algorithms.kruskal(graph.n, graph.edges)
                : Algorithms.prim(graph.n, graph.edges);
    }

    private static int[] ids(Object value) {
        if (value instanceof List<?> list) {
            int[] ids = new int[list.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = ((Number) list.get(i)).intValue();
            return ids;
        }
        int[] ids = new int[Array.getLength(value)];
        for (int i = 0; i < ids.length; i++) ids[i] = ((Number) Array.get(value, i)).intValue();
        return ids;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** Independent witness verification by BFS; uses no implementation DSU. */
    private static void validate(Graph graph, Map<String, Object> result) {
        require(((Number) result.get("cost")).longValue() == graph.optimum,
                "cost differs from independently known planted-tree optimum");
        require(((Number) result.get("components")).intValue() == 1,
                "planted chain guarantees one component");
        int[] selected = ids(result.get("edge_ids"));
        require(selected.length == graph.n - 1, "tree must contain n-1 edges");
        boolean[] used = new boolean[graph.edges.length];
        int[] head = new int[graph.n];
        Arrays.fill(head, -1);
        int[] to = new int[2 * selected.length];
        int[] next = new int[to.length];
        int position = 0;
        long sum = 0;
        for (int edge : selected) {
            require(edge >= 0 && edge < used.length && !used[edge], "invalid/repeated edge ID");
            used[edge] = true;
            int[] row = graph.edges[edge];
            int u = row[0], v = row[1];
            require(u != v, "selected self-loop");
            sum += row[2];
            to[position] = v; next[position] = head[u]; head[u] = position++;
            to[position] = u; next[position] = head[v]; head[v] = position++;
        }
        require(sum == graph.optimum, "selected edge weights do not sum to optimum");
        boolean[] seen = new boolean[graph.n];
        int[] queue = new int[graph.n];
        seen[0] = true;
        queue[0] = 0;
        int first = 0, last = 1;
        while (first < last) {
            int u = queue[first++];
            for (int edge = head[u]; edge != -1; edge = next[edge]) {
                int v = to[edge];
                if (!seen[v]) { seen[v] = true; queue[last++] = v; }
            }
        }
        require(last == graph.n, "selected edges do not span the graph");
        // Connectivity and n-1 distinct edges imply acyclicity.
        consumed ^= sum;
    }

    private static void hashInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String hash(Graph graph) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        hashInt(digest, graph.n);
        hashInt(digest, graph.edges.length);
        for (int[] edge : graph.edges) for (int value : edge) hashInt(digest, value);
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) output.append(String.format("%02x", value & 255));
        return output.toString();
    }

    private static long median(List<Long> samples) {
        long[] sorted = new long[samples.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = samples.get(i);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Map<String, Object> measure(Graph graph) throws Exception {
        String inputHash = hash(graph);
        for (String method : List.of("kruskal", "prim")) validate(graph, run(method, graph));
        for (int repeat = 0; repeat < WARMUPS; repeat++) {
            String[] order = repeat % 2 == 0
                    ? new String[]{"kruskal", "prim"} : new String[]{"prim", "kruskal"};
            for (String method : order) validate(graph, run(method, graph));
        }
        Map<String, List<Long>> timings = new LinkedHashMap<>();
        timings.put("kruskal", new ArrayList<>());
        timings.put("prim", new ArrayList<>());
        List<List<String>> executionOrder = new ArrayList<>();
        for (int repeat = 0; repeat < REPETITIONS; repeat++) {
            List<String> order = repeat % 2 == 0
                    ? List.of("kruskal", "prim") : List.of("prim", "kruskal");
            executionOrder.add(order);
            for (String method : order) {
                Map<String, Object> answer;
                long elapsed;
                if (method.equals("kruskal")) {
                    long start = System.nanoTime();
                    answer = Algorithms.kruskal(graph.n, graph.edges);
                    elapsed = System.nanoTime() - start;
                } else {
                    long start = System.nanoTime();
                    answer = Algorithms.prim(graph.n, graph.edges);
                    elapsed = System.nanoTime() - start;
                }
                timings.get(method).add(elapsed);
                // All witness validation occurs strictly after the timer stops.
                validate(graph, answer);
            }
        }
        require(inputHash.equals(hash(graph)), "algorithm mutated benchmark input");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("n", graph.n);
        result.put("m", graph.edges.length);
        result.put("seed", graph.seed);
        result.put("input_sha256", inputHash);
        result.put("expected_cost", graph.optimum);
        result.put("expected_components", 1);
        result.put("all_forests_valid", true);
        result.put("input_unchanged", true);
        result.put("execution_order", executionOrder);
        result.put("timings_ns", timings);
        result.put("median_ns", Map.of("kruskal", median(timings.get("kruskal")),
                "prim", median(timings.get("prim"))));
        return result;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java_version", System.getProperty("java.version"));
        environment.put("java_vendor", System.getProperty("java.vendor"));
        environment.put("vm_name", System.getProperty("java.vm.name"));
        environment.put("vm_flags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        environment.put("os", System.getProperty("os.name"));
        environment.put("os_version", System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("available_processors", Runtime.getRuntime().availableProcessors());
        environment.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        report.put("environment", environment);
        report.put("timer", "System.nanoTime(); raw integer nanoseconds");
        report.put("timing_boundary", "Core method call including internal adjacency/heap or sort/DSU construction and returned result allocation; excludes compilation, process launch, input generation, JSON parsing/serialization, validation, and hashing.");
        report.put("warmup_calls_per_method_per_size", WARMUPS);
        report.put("preflight_calls_per_method_per_size", 1);
        report.put("measured_calls_per_method_per_size", REPETITIONS);
        report.put("gc_policy", "Default JVM policy; no forced System.gc(); GC and JIT effects inside measured calls are included.");
        report.put("generator", "java.util.Random(BASE_SEED+n); negative spanning chain, positive extra edges, forced loops/parallel edges, Fisher-Yates shuffle; see Benchmark.java.");
        report.put("base_seed", BASE_SEED);
        report.put("hash_format", "SHA-256 over signed int32 big-endian n, m, then u,v,w for every edge in input order.");
        List<Map<String, Object>> measurements = new ArrayList<>();
        for (int[] size : new int[][]{{1000, 2500}, {10000, 25000}, {100000, 250000}}) {
            measurements.add(measure(generate(size[0], size[1])));
        }
        report.put("measurements", measurements);
        report.put("validation", "Every preflight, warm-up, and measured result passed independent BFS-based witness checks and the analytically known optimum.");
        report.put("limitations", "Single JVM execution on connected sparse planted-tree graphs with m=2.5n; negative chain edges are always preferred over positive extras. This does not represent all graph families or prove a general speed ranking. Five samples may include JIT/GC/scheduling noise.");
        System.out.println(Json.write(report));
    }
}
