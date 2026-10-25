import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct helper calls, independent of the JSON transport task list. */
public final class RouteHelperTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int[] integers(Object value) {
        int length = value instanceof List ? ((List<?>) value).size() : Array.getLength(value);
        int[] result = new int[length];
        for (int i = 0; i < length; i++) {
            Object item = value instanceof List ? ((List<?>) value).get(i) : Array.get(value, i);
            result[i] = ((Number) item).intValue();
        }
        return result;
    }

    private static void example(String name, int source, int target, int[][] edges,
                                int[] parentEdge, List<Integer> expected, Long expectedCost) {
        List<Integer> route = Algorithms.reconstructRoute(source, target, edges, parentEdge);
        require(route.equals(expected), name + ": route " + route + " != " + expected);
        List<Integer> selected = new ArrayList<>();
        long cost = 0;
        for (int i = 1; i < route.size(); i++) {
            int vertex = route.get(i);
            int edgeId = parentEdge[vertex];
            require(edgeId >= 0 && edgeId < edges.length, name + ": invalid parent edge");
            int[] edge = edges[edgeId];
            require(edge[0] == route.get(i - 1) && edge[1] == vertex, name + ": wrong edge direction");
            selected.add(edgeId);
            cost += edge[2];
        }
        require(expectedCost == null ? route.isEmpty() : cost == expectedCost, name + ": wrong weight");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("name", name);
        evidence.put("call", "Algorithms.reconstructRoute(" + source + ", " + target + ", edges, parentEdge)");
        evidence.put("edges", edges);
        evidence.put("parent_edge", parentEdge);
        evidence.put("route", route);
        evidence.put("selected_edge_ids", selected);
        evidence.put("weight", expectedCost == null ? null : cost);
        evidence.put("passed", true);
        System.out.println(Json.write(evidence));
    }

    public static void main(String[] arguments) {
        Map<String, Object> request = Json.parseRequest(
                "{\"task\":\"dag\",\"n\":5,\"source\":0,\"edges\":[[0,1,3],[0,2,8],[1,2,-7],[2,3,4]]}");
        Map<String, Object> answer = Algorithms.dag(request);
        require(Boolean.TRUE.equals(answer.get("acyclic")), "example is a DAG");
        int[][] edges = Input.edges(request);
        int[] parentEdge = integers(answer.get("parent_edge"));
        example("reachable", 0, 3, edges, parentEdge, List.of(0, 1, 2, 3), 0L);
        example("source", 0, 0, edges, parentEdge, List.of(0), 0L);
        example("unreachable", 0, 4, edges, parentEdge, List.of(), null);

        // Both parent arrays are valid shortest-path witnesses for this graph.
        // Test original edge IDs, not a predecessor guessed from vertex IDs.
        int[][] tied = {{0, 1, 2}, {0, 1, 2}, {0, 2, 1}, {2, 1, 1}, {1, 3, -1}};
        example("parallel_second_edge", 0, 3, tied, new int[]{-1, 1, 2, 4}, List.of(0, 1, 3), 1L);
        example("tied_route", 0, 3, tied, new int[]{-1, 3, 2, 4}, List.of(0, 2, 1, 3), 1L);

        int n = 100000;
        int[][] chain = new int[n - 1][3];
        int[] parents = new int[n];
        Arrays.fill(parents, -1);
        for (int i = 0; i < n - 1; i++) {
            chain[i] = new int[]{i, i + 1, -1000000000};
            parents[i + 1] = i;
        }
        List<Integer> route = Algorithms.reconstructRoute(0, n - 1, chain, parents);
        require(route.size() == n, "deep helper route size");
        for (int i = 0; i < n; i++) {
            require(route.get(i) == i, "deep helper route vertex " + i);
        }
        System.out.println(Json.write(Map.of("name", "deep_route", "passed", true,
                "call", "Algorithms.reconstructRoute(0, 99999, chain, parents)",
                "vertices", route.size(), "first", route.get(0), "last", route.get(n - 1),
                "weight", -(n - 1L) * 1000000000L)));
    }
}
