package com.f1car.mini4wd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** A board containing grid pieces and deformable rails. */
public final class MiniTrack {
    public static final int COLUMNS = 16;
    public static final int ROWS = 10;
    public static final int SIZE = COLUMNS * ROWS;

    public enum Direction {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        public final int dx;
        public final int dy;

        Direction(int dx, int dy) { this.dx = dx; this.dy = dy; }
        Direction turn(int quarterTurns) { return values()[Math.floorMod(ordinal() + quarterTurns, 4)]; }
        Direction opposite() { return turn(2); }
    }

    public enum Kind {
        STRAIGHT("直轨"), CURVE("弯轨"), RAMP("坡道"), START("起跑线");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public record Piece(Kind kind, int rotation) {
        public Piece {
            Objects.requireNonNull(kind);
            rotation = Math.floorMod(rotation, kind == Kind.STRAIGHT || kind == Kind.RAMP ? 2 : 4);
        }

        public Direction first() { return (kind == Kind.CURVE ? Direction.NORTH : Direction.WEST).turn(rotation); }
        public Direction second() { return Direction.EAST.turn(rotation); }

        Direction other(Direction port) {
            if (port == first()) return second();
            return port == second() ? first() : null;
        }
    }

    public record Point(double x, double y) {}

    public interface Rail {
        Kind kind();
        double length();
        Point point(double t);
        double heading(double t);
        Rail reversed();
    }

    public record Segment(int cell, Kind kind, Direction entry, Direction exit) implements Rail {
        public double length() { return kind == Kind.CURVE ? Math.PI / 4 : 1; }

        /** Position in board coordinates, with the top-left corner as the origin. */
        public Point point(double t) {
            double x = column(cell) + .5, y = row(cell) + .5;
            if (kind != Kind.CURVE) return new Point(x + entry.dx * (.5 - t), y + entry.dy * (.5 - t));
            double angle = t * Math.PI / 2;
            double inward = (1 - Math.sin(angle)) / 2;
            double outward = (1 - Math.cos(angle)) / 2;
            return new Point(x + entry.dx * inward + exit.dx * outward, y + entry.dy * inward + exit.dy * outward);
        }

        public double heading(double t) {
            double angle = t * Math.PI / 2;
            if (kind != Kind.CURVE) return Math.atan2(exit.dy, exit.dx);
            return Math.atan2(exit.dy * Math.sin(angle) - entry.dy * Math.cos(angle),
                exit.dx * Math.sin(angle) - entry.dx * Math.cos(angle));
        }

        @Override public Segment reversed() { return new Segment(cell, kind, exit, entry); }
    }

    public record Inspection(String message, List<Rail> route, Set<Integer> problemCells, int pieces,
                             Set<Integer> problemShapes, boolean closed) {
        Inspection(String message, List<Rail> route, Set<Integer> problemCells, int pieces) {
            this(message, route, problemCells, pieces, Set.of(), true);
        }

        public Inspection {
            route = List.copyOf(route);
            problemCells = Set.copyOf(problemCells);
            problemShapes = Set.copyOf(problemShapes);
        }

        public boolean ready() { return !route.isEmpty(); }
    }

    private final Piece[] pieces;
    private final List<MiniShape> shapes;
    private transient volatile String recordKey;
    private transient volatile Inspection inspected;

    public MiniTrack() { this(new Piece[SIZE], List.of()); }
    private MiniTrack(Piece[] pieces, List<MiniShape> shapes) { this.pieces = pieces; this.shapes = List.copyOf(shapes); }

    static MiniTrack fromPieces(Piece[] pieces,List<MiniShape> shapes) {
        if (pieces.length != SIZE) throw new IllegalArgumentException("Invalid board size");
        return new MiniTrack(pieces.clone(),shapes);
    }

    public List<MiniShape> shapes() { return shapes; }
    public MiniTrack withShapes(List<MiniShape> shapes) { return new MiniTrack(pieces, shapes); }
    public MiniTrack addShape(MiniShape shape) {
        List<MiniShape> next = new ArrayList<>(shapes);
        next.add(shape);
        return withShapes(next);
    }

    public Piece piece(int cell) { return pieces[cell]; }
    public static int column(int cell) { return cell % COLUMNS; }
    public static int row(int cell) { return cell / COLUMNS; }

    public static int cell(int x, int y) {
        return x >= 0 && x < COLUMNS && y >= 0 && y < ROWS ? y * COLUMNS + x : -1;
    }

    public MiniTrack with(int cell, Piece piece) {
        if (Objects.equals(pieces[cell], piece)) return this;
        Piece[] changed = pieces.clone();
        changed[cell] = piece;
        return new MiniTrack(changed, shapes);
    }

    public boolean sameLayout(MiniTrack other) { return Arrays.equals(pieces, other.pieces) && shapes.equals(other.shapes); }

    public String recordKey() {
        var key=recordKey;
        if(key==null) {
            key=computeRecordKey();
            recordKey=key;
        }
        return key;
    }

    private String computeRecordKey() {
        byte[] layout = new byte[SIZE];
        for (int cell = 0; cell < SIZE; cell++) {
            Piece piece = pieces[cell];
            if (piece != null) layout[cell] = (byte) (1 + piece.kind.ordinal() * 4 + piece.rotation);
        }
        if (shapes.isEmpty()) return "mini4wd:v1:" + UUID.nameUUIDFromBytes(layout);
        StringBuilder signature = new StringBuilder(Arrays.toString(layout));
        for (MiniShape shape : shapes) signature.append(shape.parameters());
        return "mini4wd:v2:" + UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Inspection inspect() {
        var result=inspected;
        if(result==null) {
            result=shapes.isEmpty()?inspectCells():MiniShapeTopology.inspect(this);
            inspected=result;
        }
        return result;
    }

    private Inspection inspectCells() {
        Set<Integer> starts = new LinkedHashSet<>();
        Set<Integer> open = new LinkedHashSet<>();
        int count = 0;
        for (int cell = 0; cell < SIZE; cell++) {
            Piece piece = pieces[cell];
            if (piece == null) continue;
            count++;
            if (piece.kind == Kind.START) starts.add(cell);
            if (!connected(cell, piece.first()) || !connected(cell, piece.second())) open.add(cell);
        }
        if (starts.isEmpty()) return new Inspection("先放一块起跑线，再把轨道接成一圈。", List.of(), open, count);
        if (starts.size() > 1) return new Inspection("请只保留一块起跑线。", List.of(), starts, count);
        if (!open.isEmpty()) return new Inspection("橙色轨道有未接上的接口。", List.of(), open, count);

        int start = starts.iterator().next();
        int current = start;
        Direction entry = pieces[start].first();
        List<Rail> route = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        while (visited.add(current)) {
            Piece piece = pieces[current];
            Direction exit = piece.other(entry);
            route.add(new Segment(current, piece.kind, entry, exit));
            current = neighbor(current, exit);
            entry = exit.opposite();
        }
        if (route.size() != count) {
            Set<Integer> detached = new LinkedHashSet<>();
            for (int cell = 0; cell < SIZE; cell++) {
                if (pieces[cell] != null && !visited.contains(cell)) detached.add(cell);
            }
            return new Inspection("请移除橙色标出的独立环路。", List.of(), detached, count);
        }
        return new Inspection("轨道已闭合，可以试跑。", route, Set.of(), count);
    }

    private boolean connected(int cell, Direction port) {
        int next = neighbor(cell, port);
        return next >= 0 && pieces[next] != null && pieces[next].other(port.opposite()) != null;
    }

    private static int neighbor(int cell, Direction direction) {
        return cell(column(cell) + direction.dx, row(cell) + direction.dy);
    }
}
