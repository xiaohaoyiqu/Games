package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Direction;
import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Piece;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class MiniTrackCatalog {
    record Course(String name, String description, MiniTrack track) {}

    static final List<Course> COURSES = List.of(
        new Course("工坊椭圆", "两段长直道，适合练习冲刺。",
            loop(new int[][] {{2, 2}, {13, 2}, {13, 7}, {2, 7}}, 6, 2, Set.of())),
        new Course("双弯回廊", "连续折弯与缓坡，提前松开冲刺。",
            loop(new int[][] {{1, 2}, {8, 2}, {8, 4}, {13, 4}, {13, 7}, {5, 7}, {5, 5}, {1, 5}},
                4, 2, Set.of(MiniTrack.cell(10, 4), MiniTrack.cell(9, 7)))),
        new Course("折返高架", "长直道接折返弯，四段坡道考验车速。",
            loop(new int[][] {{1, 1}, {14, 1}, {14, 8}, {10, 8}, {10, 4}, {6, 4}, {6, 8}, {1, 8}},
                4, 1, Set.of(MiniTrack.cell(8, 1), MiniTrack.cell(14, 5), MiniTrack.cell(8, 4), MiniTrack.cell(1, 4)))),
        spiral("单圈回旋", "一圈向外展开，可拉伸成椭圆螺旋。", 7.5, 7.5, 360, 0),
        spiral("双圈螺旋", "两圈连续绕行，内外端点保持敞开。", 8, 8, 720, 0),
        spiral("三圈盘旋", "三圈紧凑盘绕，稳健马达更容易控制。", 8.6, 8.6, 1080, 90),
        spiral("横向螺旋", "横向拉长的双圈模板，可继续变形。", 12, 7.5, 720, 15)
    );

    private MiniTrackCatalog() {}

    private static Course spiral(String name, String description, double width, double height, int extent, int angle) {
        MiniShape shape = new MiniShape(new MiniShape.Parameters(MiniShape.Type.SPIRAL, 8, 5, width, height, angle, extent));
        return new Course(name, description, new MiniTrack().addShape(shape));
    }

    private static MiniTrack loop(int[][] corners, int startX, int startY, Set<Integer> ramps) {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < corners.length; i++) {
            int[] from = corners[i], to = corners[(i + 1) % corners.length];
            int dx = Integer.compare(to[0], from[0]), dy = Integer.compare(to[1], from[1]);
            for (int x = from[0], y = from[1]; x != to[0] || y != to[1]; x += dx, y += dy) {
                cells.add(MiniTrack.cell(x, y));
            }
        }

        MiniTrack track = new MiniTrack();
        for (int i = 0; i < cells.size(); i++) {
            int cell = cells.get(i);
            Direction entry = direction(cell, cells.get(Math.floorMod(i - 1, cells.size())));
            Direction exit = direction(cell, cells.get((i + 1) % cells.size()));
            Kind kind = cell == MiniTrack.cell(startX, startY) ? Kind.START : ramps.contains(cell) ? Kind.RAMP :
                entry.opposite() == exit ? Kind.STRAIGHT : Kind.CURVE;
            track = track.with(cell, oriented(kind, entry, exit));
        }
        if (!track.inspect().ready()) throw new IllegalStateException("Invalid built-in Mini 4WD course");
        return track;
    }

    private static Direction direction(int from, int to) {
        int dx = MiniTrack.column(to) - MiniTrack.column(from), dy = MiniTrack.row(to) - MiniTrack.row(from);
        for (Direction direction : Direction.values()) {
            if (direction.dx == dx && direction.dy == dy) return direction;
        }
        throw new IllegalArgumentException("Track cells must be adjacent");
    }

    private static Piece oriented(Kind kind, Direction entry, Direction exit) {
        for (int rotation = 0; rotation < 4; rotation++) {
            Piece piece = new Piece(kind, rotation);
            if (piece.first() == entry && piece.second() == exit) return piece;
            if (kind != Kind.START && piece.first() == exit && piece.second() == entry) return piece;
        }
        throw new IllegalArgumentException("Track piece cannot connect the requested directions");
    }
}
