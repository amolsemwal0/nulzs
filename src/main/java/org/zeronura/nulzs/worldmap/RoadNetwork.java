package org.zeronura.nulzs.worldmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A map's roads as a graph: junctions (nodes) joined by straight stretches of road (edges). What the map editor's road
 * tool draws and what {@link RoadBuilder} turns into road pieces — a straight, a bend, a T, a crossroads or a
 * roundabout wherever roads meet, so crossings always join up cleanly however they were drawn.
 *
 * <p>Drawing a road across another splits both at the crossing and joins them there, and a road drawn to end on another
 * joins it with a T: two roads never just overlap. Plain data, saved with the map.
 */
public final class RoadNetwork {

    /** A point roads meet at, or end at. */
    public static final class Node {
        public final int id;
        public float x;
        public float z;
        /** A roundabout here instead of a plain junction. */
        public boolean roundabout;
        /** Crosswalks across each road leaving a junction of three or more. */
        public boolean crosswalks = true;
        /**
         * Where only one road reaches: closed with the end piece's curb, or left open, the road simply stopping — where
         * a town's street carries on into a dirt track, say.
         */
        public boolean capped = true;

        public Node(int id, float x, float z) {
            this.id = id;
            this.x = x;
            this.z = z;
        }

        public Node copy() {
            Node copy = new Node(id, x, z);
            copy.roundabout = roundabout;
            copy.crosswalks = crosswalks;
            copy.capped = capped;
            return copy;
        }

        float distance(float px, float pz) {
            return (float) Math.hypot(px - x, pz - z);
        }
    }

    /**
     * A straight stretch of road between two nodes.
     *
     * @param lane a country lane: the same road, with no sidewalks along it
     */
    public record Edge(int a, int b, boolean lane) {
        public Edge(int a, int b) {
            this(a, b, false);
        }

        public boolean touches(int node) {
            return a == node || b == node;
        }

        public int other(int node) {
            return a == node ? b : a;
        }
    }

    /** Where a point lies along an edge: the nearest point on it, how far along (0 to 1), and how far away. */
    public record EdgeHit(Edge edge, float x, float z, float t, float distance) {
    }

    /** How close to an existing node or road a new road's end must come to join it rather than start afresh. */
    public static final float JOIN_DISTANCE = 4f;

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private int nextId = 1;
    /** Sidewalks along every road. */
    private boolean sidewalks = true;

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Edge> edges() {
        return Collections.unmodifiableList(edges);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public boolean sidewalks() {
        return sidewalks;
    }

    public void setSidewalks(boolean sidewalks) {
        this.sidewalks = sidewalks;
    }

    public Node node(int id) {
        for (Node node : nodes) {
            if (node.id == id) {
                return node;
            }
        }
        return null;
    }

    public Node addNode(float x, float z) {
        Node node = new Node(nextId++, x, z);
        nodes.add(node);
        return node;
    }

    /** Adds a node with a known id, as a saved map reads them back. */
    public Node addNode(int id, float x, float z) {
        Node node = new Node(id, x, z);
        nodes.add(node);
        nextId = Math.max(nextId, id + 1);
        return node;
    }

    /** Joins two nodes with a road; does nothing for a node and itself or two already joined. */
    public void connect(int a, int b) {
        connect(a, b, false);
    }

    /** Joins two nodes with a road, or with a country lane. */
    public void connect(int a, int b, boolean lane) {
        if (a == b || node(a) == null || node(b) == null || connected(a, b)) {
            return;
        }
        edges.add(new Edge(a, b, lane));
    }

    /** Makes a road a country lane, or a street with sidewalks again. Returns the road as it now is. */
    public Edge setLane(Edge edge, boolean lane) {
        int index = edges.indexOf(edge);
        if (index < 0 || edge.lane() == lane) {
            return edge;
        }
        Edge changed = new Edge(edge.a(), edge.b(), lane);
        edges.set(index, changed);
        return changed;
    }

    public boolean connected(int a, int b) {
        return edges.stream().anyMatch(e -> e.touches(a) && e.touches(b));
    }

    public void disconnect(Edge edge) {
        edges.remove(edge);
    }

    /** Removes a node and every road to it, and any node left with no road at all. */
    public void removeNode(int id) {
        edges.removeIf(e -> e.touches(id));
        nodes.removeIf(n -> n.id == id);
        removeLoose();
    }

    /** Removes a road, and its ends if no other road reaches them. */
    public void removeEdge(Edge edge) {
        edges.remove(edge);
        removeLoose();
    }

    /** Drops nodes no road reaches, unless they are roundabouts. */
    public void removeLoose() {
        nodes.removeIf(n -> !n.roundabout && degree(n.id) == 0);
    }

    public List<Edge> edgesOf(int node) {
        List<Edge> list = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.touches(node)) {
                list.add(edge);
            }
        }
        return list;
    }

    public int degree(int node) {
        int count = 0;
        for (Edge edge : edges) {
            if (edge.touches(node)) {
                count++;
            }
        }
        return count;
    }

    /** The nearest node within {@code reach} of a point, or null. */
    public Node nearestNode(float x, float z, float reach) {
        Node best = null;
        float bestDistance = reach;
        for (Node node : nodes) {
            float distance = node.distance(x, z);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        return best;
    }

    /** The nearest road within {@code reach} of a point, or null. */
    public EdgeHit nearestEdge(float x, float z, float reach) {
        EdgeHit best = null;
        for (Edge edge : edges) {
            EdgeHit hit = project(edge, x, z);
            if (hit != null && hit.distance() <= reach && (best == null || hit.distance() < best.distance())) {
                best = hit;
            }
        }
        return best;
    }

    private EdgeHit project(Edge edge, float x, float z) {
        Node a = node(edge.a());
        Node b = node(edge.b());
        if (a == null || b == null) {
            return null;
        }
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float lengthSquared = dx * dx + dz * dz;
        float t = lengthSquared == 0f ? 0f : Math.clamp(((x - a.x) * dx + (z - a.z) * dz) / lengthSquared, 0f, 1f);
        float px = a.x + dx * t;
        float pz = a.z + dz * t;
        return new EdgeHit(edge, px, pz, t, (float) Math.hypot(x - px, z - pz));
    }

    /** Splits a road at a point along it with a new node there, joined to both ends. Returns the new node. */
    public Node split(Edge edge, float x, float z) {
        edges.remove(edge);
        Node middle = addNode(x, z);
        edges.add(new Edge(edge.a(), middle.id, edge.lane()));
        edges.add(new Edge(middle.id, edge.b(), edge.lane()));
        return middle;
    }

    /**
     * The node a road drawn to a point should end on: an existing node within {@link #JOIN_DISTANCE}, else a new node
     * splitting a road that close (a T), else a new node on its own.
     */
    public Node nodeAt(float x, float z) {
        Node near = nearestNode(x, z, JOIN_DISTANCE);
        if (near != null) {
            return near;
        }
        EdgeHit hit = nearestEdge(x, z, JOIN_DISTANCE);
        if (hit != null) {
            Node a = node(hit.edge().a());
            Node b = node(hit.edge().b());
            // Too near an end to split cleanly: join that end instead.
            if (a.distance(hit.x(), hit.z()) < JOIN_DISTANCE * 2f) {
                return a;
            }
            if (b.distance(hit.x(), hit.z()) < JOIN_DISTANCE * 2f) {
                return b;
            }
            return split(hit.edge(), hit.x(), hit.z());
        }
        return addNode(x, z);
    }

    /**
     * Draws a straight road from one point to another, joining whatever it starts or ends on and splitting every road it
     * crosses on the way, so each crossing becomes a junction. Returns the nodes along it, first to last.
     */
    public List<Node> addRoad(float x1, float z1, float x2, float z2) {
        return addRoad(x1, z1, x2, z2, false);
    }

    /** {@link #addRoad(float, float, float, float)}, drawing a country lane when {@code lane} is set. */
    public List<Node> addRoad(float x1, float z1, float x2, float z2, boolean lane) {
        Node start = nodeAt(x1, z1);
        Node end = nodeAt(x2, z2);
        List<Node> path = new ArrayList<>();
        path.add(start);
        if (start == end) {
            return path;
        }
        // Every road the new one crosses, in order along it, gets a junction where they cross.
        List<float[]> crossings = new ArrayList<>();
        for (Edge edge : new ArrayList<>(edges)) {
            if (edge.touches(start.id) || edge.touches(end.id)) {
                continue;
            }
            Node a = node(edge.a());
            Node b = node(edge.b());
            float[] cross = intersect(start.x, start.z, end.x, end.z, a.x, a.z, b.x, b.z);
            if (cross != null) {
                crossings.add(new float[]{cross[0], cross[1], cross[2], edges.indexOf(edge)});
            }
        }
        crossings.sort((p, q) -> Float.compare(p[2], q[2]));
        List<Edge> crossed = new ArrayList<>();
        for (float[] crossing : crossings) {
            crossed.add(edges.get((int) crossing[3]));
        }
        for (int k = 0; k < crossings.size(); k++) {
            float[] crossing = crossings.get(k);
            Node near = nearestNode(crossing[0], crossing[1], JOIN_DISTANCE);
            Node junction = near != null ? near : split(crossed.get(k), crossing[0], crossing[1]);
            if (junction != path.getLast() && junction != end) {
                path.add(junction);
            }
        }
        path.add(end);
        for (int k = 1; k < path.size(); k++) {
            connect(path.get(k - 1).id, path.get(k).id, lane);
        }
        return path;
    }

    /**
     * Where segment p1-p2 crosses segment q1-q2, as {x, z, t along p}, or null when they don't cross (ends touching
     * count as not crossing).
     */
    static float[] intersect(float p1x, float p1z, float p2x, float p2z, float q1x, float q1z, float q2x, float q2z) {
        float rx = p2x - p1x;
        float rz = p2z - p1z;
        float sx = q2x - q1x;
        float sz = q2z - q1z;
        float denominator = rx * sz - rz * sx;
        if (Math.abs(denominator) < 1e-6f) {
            return null;
        }
        float t = ((q1x - p1x) * sz - (q1z - p1z) * sx) / denominator;
        float u = ((q1x - p1x) * rz - (q1z - p1z) * rx) / denominator;
        if (t <= 0.001f || t >= 0.999f || u <= 0.001f || u >= 0.999f) {
            return null;
        }
        return new float[]{p1x + rx * t, p1z + rz * t, t};
    }

    /** Moves everything, for a map that grew on its west or north side. */
    public void translate(float dx, float dz) {
        for (Node node : nodes) {
            node.x += dx;
            node.z += dz;
        }
    }

    public RoadNetwork copy() {
        RoadNetwork copy = new RoadNetwork();
        nodes.forEach(n -> copy.nodes.add(n.copy()));
        copy.edges.addAll(edges);
        copy.nextId = nextId;
        copy.sidewalks = sidewalks;
        return copy;
    }

    /** Whether two networks hold the same roads, for undo to tell whether roads changed. */
    public boolean sameAs(RoadNetwork other) {
        if (other == null || nodes.size() != other.nodes.size() || !edges.equals(other.edges)
                || sidewalks != other.sidewalks) {
            return false;
        }
        for (int k = 0; k < nodes.size(); k++) {
            Node a = nodes.get(k);
            Node b = other.nodes.get(k);
            if (a.id != b.id || a.x != b.x || a.z != b.z || a.roundabout != b.roundabout || a.crosswalks != b.crosswalks
                    || a.capped != b.capped) {
                return false;
            }
        }
        return true;
    }

    /** Makes this network the same as another. */
    public void restore(RoadNetwork source) {
        nodes.clear();
        edges.clear();
        source.nodes.forEach(n -> nodes.add(n.copy()));
        edges.addAll(source.edges);
        nextId = source.nextId;
        sidewalks = source.sidewalks;
    }

    @Override
    public String toString() {
        return nodes.size() + " junctions, " + edges.size() + " roads";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoadNetwork other && sameAs(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes.size(), edges);
    }
}
