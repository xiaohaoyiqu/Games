package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Inspection;
import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Point;
import com.f1car.mini4wd.MiniTrack.Rail;
import com.f1car.mini4wd.MiniTrack.Segment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Connections are made at rail endpoints. Geometric crossings do not create junctions. */
final class MiniShapeTopology {
    private static final double CONNECTION_BOUNDS=.125;
    private static final int BUCKET_COLUMNS=MiniTrack.COLUMNS+1;

    static List<Rail> rails(MiniTrack track) {
        List<Rail> rails = new ArrayList<>();
        for (int cell = 0; cell < MiniTrack.SIZE; cell++) {
            var piece = track.piece(cell);
            if (piece != null) rails.add(new Segment(cell, piece.kind(), piece.first(), piece.second()));
        }
        rails.addAll(track.shapes());
        return rails;
    }

    static Inspection inspect(MiniTrack track) {
        List<Rail> rails = rails(track);
        int[] connections = new int[rails.size() * 2];
        Arrays.fill(connections, -1);
        Set<Integer> problems = new LinkedHashSet<>();
        int start = 0, starts = 0;
        for (int i = 0; i < rails.size(); i++) {
            if (rails.get(i).kind() == Kind.START) { start = i * 2; starts++; }
        }
        if (starts > 1) return failed("请只保留一块起跑线。", rails, all(rails.size()), track.shapes().size());
        connectEndpoints(rails,connections,problems);
        if (!problems.isEmpty()) return failed("一个端点只能连接一条轨道，请移开重叠接口。", rails, problems, track.shapes().size());
        List<Integer> ends = new ArrayList<>();
        for (int i = 0; i < connections.length; i++) if (connections[i] < 0) ends.add(i);
        boolean closed = ends.isEmpty();
        if (!closed && ends.size() != 2) return failed("请对齐端点的位置和方向，连接分开的轨道。", rails, all(rails.size()), track.shapes().size());
        if (!closed) start = ends.get(0);
        List<Rail> route = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        int endpoint = start;
        while (endpoint >= 0 && visited.add(endpoint / 2)) {
            Rail rail = rails.get(endpoint / 2);
            route.add(endpoint % 2 == 0 ? rail : rail.reversed());
            endpoint = connections[endpoint ^ 1];
        }
        if (visited.size() != rails.size()) {
            Set<Integer> detached = all(rails.size());
            detached.removeAll(visited);
            return failed("存在独立轨道，请把它连接到主轨道。", rails, detached, track.shapes().size());
        }
        return new Inspection(closed ? "轨道已闭合，可以进行三圈计时。" : "起终点已确定，可以进行单程试跑。",
            route, Set.of(), rails.size(), Set.of(), closed);
    }

    private static void connectEndpoints(List<Rail> rails,int[] connections,Set<Integer> problems) {
        var endpoints=new Point[connections.length];
        var outward=new double[connections.length];
        var buckets=new int[BUCKET_COLUMNS*(MiniTrack.ROWS+1)];
        var next=new int[endpoints.length];
        Arrays.fill(buckets,-1);
        boolean finite=true;
        for(int i=endpoints.length-1;i>=0;i--) {
            var rail=rails.get(i/2);
            var point=rail.point(i%2);
            endpoints[i]=point;
            outward[i]=outwardAngle(rail,i%2);
            finite&=Double.isFinite(point.x()) && Double.isFinite(point.y());
            int bucket=bucketCoordinate(point.y(),MiniTrack.ROWS)*BUCKET_COLUMNS+bucketCoordinate(point.x(),MiniTrack.COLUMNS);
            next[i]=buckets[bucket];
            buckets[bucket]=i;
        }

        var candidates=new int[endpoints.length];
        for (int i=0;i<endpoints.length;i++) {
            var first=endpoints[i];
            if(!finite) {
                for(int j=i+1;j<endpoints.length;j++) connect(i,j,endpoints,outward,connections,problems);
                continue;
            }
            int left=bucketCoordinate(first.x()-CONNECTION_BOUNDS,MiniTrack.COLUMNS);
            int right=bucketCoordinate(first.x()+CONNECTION_BOUNDS,MiniTrack.COLUMNS);
            int top=bucketCoordinate(first.y()-CONNECTION_BOUNDS,MiniTrack.ROWS);
            int bottom=bucketCoordinate(first.y()+CONNECTION_BOUNDS,MiniTrack.ROWS);
            int count=0;
            for(int row=top;row<=bottom;row++) {
                for(int column=left;column<=right;column++) {
                    int bucket=row*BUCKET_COLUMNS+column;
                    for(int j=buckets[bucket];j>=0;j=next[j]) {
                        if(j>i) candidates[count++]=j;
                    }
                }
            }
            // The first valid pair owns the joint. Preserve original endpoint order across buckets.
            Arrays.sort(candidates,0,count);
            for(int k=0;k<count;k++) connect(i,candidates[k],endpoints,outward,connections,problems);
        }
    }

    private static int bucketCoordinate(double coordinate,int limit) {
        return Math.max(0,Math.min(limit,(int)Math.floor(coordinate)));
    }

    private static void connect(int firstIndex,int secondIndex,Point[] endpoints,double[] outward,
                                int[] connections,Set<Integer> problems) {
        if(firstIndex/2==secondIndex/2) return;
        var first=endpoints[firstIndex];
        var second=endpoints[secondIndex];
        if(Math.abs(first.x()-second.x())>CONNECTION_BOUNDS || Math.abs(first.y()-second.y())>CONNECTION_BOUNDS) return;
        if(MiniShape.distance(first,second)>.08 || !(Math.cos(outward[firstIndex]-outward[secondIndex])<-.97)) return;
        if(connections[firstIndex]>=0 || connections[secondIndex]>=0) {
            problems.add(firstIndex/2);
            problems.add(secondIndex/2);
        } else {
            connections[firstIndex]=secondIndex;
            connections[secondIndex]=firstIndex;
        }
    }

    static double outwardAngle(Rail rail, int endpoint) {
        return rail.heading(endpoint) + (endpoint == 0 ? Math.PI : 0);
    }

    private static Set<Integer> all(int count) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) result.add(i);
        return result;
    }

    private static Inspection failed(String message, List<Rail> rails, Set<Integer> problems, int shapes) {
        Set<Integer> cells = new LinkedHashSet<>(), shapeIds = new LinkedHashSet<>();
        int firstShape = rails.size() - shapes;
        for (int index : problems) {
            if (index < firstShape) cells.add(((Segment) rails.get(index)).cell());
            else shapeIds.add(index - firstShape);
        }
        return new Inspection(message, List.of(), cells, rails.size(), shapeIds, false);
    }
}
