import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Six graph algorithms. Every mutable data structure belongs to one request. */
public final class Algorithms {
    private Algorithms() { }

    /** Iterative path compression and union by size. */
    private static final class DisjointSet {
        final int[] parent;
        final int[] size;
        int components;
        int largest;

        DisjointSet(int n) {
            parent = new int[n];
            size = new int[n];
            components = n;
            largest = n == 0 ? 0 : 1;
            for (int v = 0; v < n; v++) {
                parent[v] = v;
                size[v] = 1;
            }
        }

        int find(int vertex) {
            int root = vertex;
            while (parent[root] != root) root = parent[root];
            while (vertex != root) {
                int previous = parent[vertex];
                parent[vertex] = root;
                vertex = previous;
            }
            return root;
        }

        boolean union(int u, int v) {
            int a = find(u), b = find(v);
            if (a == b) return false;
            if (size[a] < size[b]) {
                int temporary = a;
                a = b;
                b = temporary;
            }
            parent[b] = a;
            size[a] += size[b];
            components--;
            largest = Math.max(largest, size[a]);
            return true;
        }
    }

    /** Compact adjacency lists: head[v] is the first arc, next[a] the next arc. */
    private static final class Graph {
        final int[] head, to, next, edgeId;
        int arcCount;

        Graph(int n, int capacity) {
            head = new int[n];
            Arrays.fill(head, -1);
            to = new int[capacity];
            next = new int[capacity];
            edgeId = new int[capacity];
        }

        void add(int u, int v, int originalId) {
            to[arcCount] = v;
            edgeId[arcCount] = originalId;
            next[arcCount] = head[u];
            head[u] = arcCount++;
        }
    }

    private static Graph directedGraph(int n, int[][] edges, boolean transpose) {
        Graph graph = new Graph(n, edges.length);
        for (int id = 0; id < edges.length; id++) {
            int u = edges[id][0], v = edges[id][1];
            graph.add(transpose ? v : u, transpose ? u : v, id);
        }
        return graph;
    }

    /**
     * Iterative DFS finish order. A frame keeps its next unexamined arc.
     * If rejectCycles is true, an edge to an active vertex returns null.
     */
    private static int[] finishOrder(Graph graph, boolean rejectCycles) {
        int n = graph.head.length;
        byte[] state = new byte[n]; // 0 = unvisited, 1 = active, 2 = finished
        int[] stack = new int[n], nextArc = new int[n], finished = new int[n];
        int count = 0;
        for (int start = 0; start < n; start++) {
            if (state[start] != 0) continue;
            int top = 0;
            stack[0] = start;
            nextArc[0] = graph.head[start];
            state[start] = 1;
            while (top >= 0) {
                int vertex = stack[top], arc = nextArc[top];
                if (arc == -1) {
                    state[vertex] = 2;
                    finished[count++] = vertex;
                    top--;
                    continue;
                }
                nextArc[top] = graph.next[arc];
                int neighbour = graph.to[arc];
                if (state[neighbour] == 0) {
                    state[neighbour] = 1;
                    stack[++top] = neighbour;
                    nextArc[top] = graph.head[neighbour];
                } else if (rejectCycles && state[neighbour] == 1) {
                    return null;
                }
            }
        }
        return finished;
    }

    static Map<String, Object> dsu(Map<String, Object> input) {
        DisjointSet sets = new DisjointSet(Input.vertexCount(input));
        int[][] operations = Input.operations(input);
        int[][] states = new int[operations.length][2];
        for (int i = 0; i < operations.length; i++) {
            sets.union(operations[i][0], operations[i][1]);
            states[i][0] = sets.components;
            states[i][1] = sets.largest;
        }
        return Map.of("states", states);
    }

    static Map<String, Object> kruskal(Map<String, Object> input) {
        return kruskal(Input.vertexCount(input), Input.edges(input));
    }

    /** Typed overload lets the benchmark exclude parsing and input conversion. */
    static Map<String, Object> kruskal(int n, int[][] edges) {
        Integer[] ids = new Integer[edges.length];
        for (int i = 0; i < ids.length; i++) ids[i] = i;
        Arrays.sort(ids, Comparator.comparingInt((Integer id) -> edges[id][2])
                .thenComparingInt(Integer::intValue));
        DisjointSet sets = new DisjointSet(n);
        List<Integer> chosen = new ArrayList<>();
        long cost = 0;
        for (int id : ids) {
            int[] edge = edges[id];
            if (sets.union(edge[0], edge[1])) {
                chosen.add(id);
                cost += (long) edge[2];
            }
        }
        return Map.of("cost", cost, "edge_ids", chosen, "components", sets.components);
    }

    private record Candidate(int weight, int edgeId, int destination) { }

    static Map<String, Object> prim(Map<String, Object> input) {
        return prim(Input.vertexCount(input), Input.edges(input));
    }

    /** Independent lazy-heap Prim; each unvisited root starts a new component. */
    static Map<String, Object> prim(int n, int[][] edges) {
        Graph graph = new Graph(n, 2 * edges.length);
        for (int id = 0; id < edges.length; id++) {
            int u = edges[id][0], v = edges[id][1];
            if (u == v) continue;
            graph.add(u, v, id);
            graph.add(v, u, id);
        }
        boolean[] visited = new boolean[n];
        PriorityQueue<Candidate> heap = new PriorityQueue<>(
                Comparator.comparingInt(Candidate::weight).thenComparingInt(Candidate::edgeId));
        List<Integer> chosen = new ArrayList<>();
        long cost = 0;
        int components = 0;
        for (int start = 0; start < n; start++) {
            if (visited[start]) continue;
            components++;
            int vertex = start;
            // At the root there is no incoming selected edge.
            while (true) {
                visited[vertex] = true;
                for (int arc = graph.head[vertex]; arc != -1; arc = graph.next[arc]) {
                    if (!visited[graph.to[arc]]) {
                        int id = graph.edgeId[arc];
                        heap.add(new Candidate(edges[id][2], id, graph.to[arc]));
                    }
                }
                while (!heap.isEmpty() && visited[heap.peek().destination()]) heap.remove();
                if (heap.isEmpty()) break;
                Candidate edge = heap.remove();
                chosen.add(edge.edgeId());
                cost += (long) edge.weight();
                vertex = edge.destination();
            }
        }
        return Map.of("cost", cost, "edge_ids", chosen, "components", components);
    }

    static Map<String, Object> scc(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        Graph graph = directedGraph(n, edges, false);
        Graph transpose = directedGraph(n, edges, true);
        int[] finished = finishOrder(graph, false);
        int[] component = new int[n], stack = new int[n];
        Arrays.fill(component, -1);
        int label = 0;
        for (int i = n - 1; i >= 0; i--) {
            int start = finished[i];
            if (component[start] != -1) continue;
            int top = 0;
            stack[0] = start;
            component[start] = label;
            while (top >= 0) {
                int vertex = stack[top--];
                for (int arc = transpose.head[vertex]; arc != -1; arc = transpose.next[arc]) {
                    int neighbour = transpose.to[arc];
                    if (component[neighbour] == -1) {
                        component[neighbour] = label; // Mark before pushing: no duplicate stack entries.
                        stack[++top] = neighbour;
                    }
                }
            }
            label++;
        }
        // Group vertices by SCC, then stamp destinations once per source SCC.
        // This deduplicates in linear time without hash collisions or sorting.
        int[] firstVertex = new int[label], nextVertex = new int[n];
        Arrays.fill(firstVertex, -1);
        for (int vertex = 0; vertex < n; vertex++) {
            int sourceComponent = component[vertex];
            nextVertex[vertex] = firstVertex[sourceComponent];
            firstVertex[sourceComponent] = vertex;
        }
        int[] seenDestination = new int[label];
        Arrays.fill(seenDestination, -1);
        List<int[]> condensation = new ArrayList<>();
        for (int a = 0; a < label; a++) {
            for (int u = firstVertex[a]; u != -1; u = nextVertex[u]) {
                for (int arc = graph.head[u]; arc != -1; arc = graph.next[arc]) {
                    int b = component[graph.to[arc]];
                    if (a != b && seenDestination[b] != a) {
                        seenDestination[b] = a;
                        condensation.add(new int[]{a, b});
                    }
                }
            }
        }
        return Map.of("component", component, "condensation", condensation);
    }

    static Map<String, Object> topo(Map<String, Object> input) {
        int n = Input.vertexCount(input);
        int[][] edges = Input.edges(input);
        int[] order = finishOrder(directedGraph(n, edges, false), true);
        if (order == null) return Map.of("acyclic", false, "order", new int[0]);
        for (int left = 0, right = n - 1; left < right; left++, right--) {
            int temporary = order[left];
            order[left] = order[right];
            order[right] = temporary;
        }
        return Map.of("acyclic", true, "order", order);
    }

    static Map<String, Object> dag(Map<String, Object> input) {
        int n = Input.vertexCount(input), source = Input.source(input);
        int[][] edges = Input.edges(input);
        Graph graph = directedGraph(n, edges, false);
        int[] finished = finishOrder(graph, true);
        if (finished == null) {
            return Map.of("acyclic", false, "distance", new Long[0], "parent_edge", new int[0]);
        }
        Long[] distance = new Long[n]; // null means unreachable; -1 is a valid finite distance.
        int[] parentEdge = new int[n];
        Arrays.fill(parentEdge, -1);
        distance[source] = 0L;
        for (int i = n - 1; i >= 0; i--) {
            int u = finished[i];
            if (distance[u] == null) continue;
            for (int arc = graph.head[u]; arc != -1; arc = graph.next[arc]) {
                int v = graph.to[arc], id = graph.edgeId[arc];
                long candidate = distance[u] + (long) edges[id][2];
                if (distance[v] == null || candidate < distance[v]) {
                    distance[v] = candidate;
                    parentEdge[v] = id;
                }
            }
        }
        return Map.of("acyclic", true, "distance", distance, "parent_edge", parentEdge);
    }

    /**
     * Reconstruct a route from an acyclic DAG result's original parent-edge IDs.
     * Returns [source] for source, [] for unreachable target. Invalid parent
     * directions or cyclic/broken chains throw IllegalArgumentException.
     * Uses O(L) time and space for a route of L vertices.
     */
    public static List<Integer> reconstructRoute(int source, int target,
                                                int[][] edges, int[] parentEdge) {
        int n = parentEdge.length;
        if (source < 0 || source >= n || target < 0 || target >= n) {
            throw new IllegalArgumentException("Source and target must index the parent array");
        }
        if (target == source) return List.of(source);
        if (parentEdge[target] == -1) return List.of();
        List<Integer> route = new ArrayList<>();
        int vertex = target;
        while (vertex != source) {
            // A valid simple route has at most n vertices. This bounds even a
            // malformed cyclic witness without an O(n) visited allocation.
            if (route.size() >= n) throw new IllegalArgumentException("Cyclic parent chain");
            route.add(vertex);
            int id = parentEdge[vertex];
            if (id < 0 || id >= edges.length || edges[id][1] != vertex) {
                throw new IllegalArgumentException("Invalid incoming parent edge");
            }
            vertex = edges[id][0];
            if (vertex < 0 || vertex >= n) throw new IllegalArgumentException("Invalid parent vertex");
        }
        route.add(source);
        Collections.reverse(route);
        return route;
    }
}
