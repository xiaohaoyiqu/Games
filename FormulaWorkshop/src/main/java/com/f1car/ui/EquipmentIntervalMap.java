package com.f1car.ui;

import com.f1car.model.TelemetryComparison;
import com.f1car.model.TelemetryLap;
import com.f1car.model.TrackLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Positions recorded between the same centreline-distance crossings, with a shared cursor. */
final class EquipmentIntervalMap {
    private static final Rectangle MAP=new Rectangle(60,162,850,460),RULER=new Rectangle(60,666,850,38);
    private static final Color PANEL=new Color(0x13242e),MAP_BACKGROUND=new Color(0x1b3540);
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8);
    private static final Color A=new Color(0xffb267),B=new Color(0x63e6bc);
    private static final Color CENTERLINE=new Color(0x8da9af),ROAD_CURSOR=new Color(0x56717b);
    private static final BasicStroke SOLID=new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND);
    private static final BasicStroke DASHED=new BasicStroke(2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,10,new float[]{7,5},0);
    private static final int MIN_LAYER_SAMPLES=1_024;
    private static final int MAX_LAYER_PIXELS=1_500_000;
    private static final float BRAKE_PICK_RADIUS=9;
    private record BrakeMarker(boolean reference,TelemetryComparison.BrakeAction action,int x,int y) {}
    private record Trajectory(Path2D path,TelemetryLap.Sample start,TelemetryLap.Sample end) {}
    private record TrajectoryLayer(AffineTransform viewport,Object strokeControl,HudRasterLayer raster) {}

    private final TelemetryComparison comparison;
    private final TrackLayout road;
    private final float from,to;
    private final Consumer<Float> cursorChanged;
    private final Runnable practice;
    private final TrackMapView.Transform transform;
    private final Path2D centerline,brakeMarksA,brakeMarksB;
    private final Trajectory trajectoryA,trajectoryB;
    private final int sampleCount,brakeCountA;
    private final List<BrakeMarker> brakes;
    private final double entryDelta;
    private TelemetryComparison.Point cursor;
    private TelemetryComparison.RoadCursor roadCursor;
    private TrajectoryLayer trajectoryLayer;
    private int brakeIndex=-1;

    EquipmentIntervalMap(TelemetryComparison comparison,TrackLayout road,float from,float to,float distance,
                         Consumer<Float> cursorChanged,Runnable practice) {
        this.comparison=comparison;
        this.road=road;
        this.from=from;
        this.to=to;
        this.cursorChanged=cursorChanged;
        this.practice=practice;
        var paths=comparison.intervalTrajectory(from,to);
        transform=TrackMapView.fit(bounds(paths,road),MAP,34);
        centerline=road==null?null:TrackMapView.circuitPath(road,transform,from,to);
        trajectoryA=trajectory(paths.a());
        trajectoryB=trajectory(paths.b());
        entryDelta=trajectoryA.start().time()-trajectoryB.start().time();
        sampleCount=paths.a().size()+paths.b().size();
        brakeCountA=paths.brakesA().size();
        var markers=new ArrayList<BrakeMarker>(brakeCountA+paths.brakesB().size());
        for(var action:paths.brakesA()) markers.add(new BrakeMarker(false,action,x(action.sample()),y(action.sample())));
        for(var action:paths.brakesB()) markers.add(new BrakeMarker(true,action,x(action.sample()),y(action.sample())));
        brakes=List.copyOf(markers);
        brakeMarksA=brakeMarks(0,brakeCountA);
        brakeMarksB=brakeMarks(brakeCountA,brakes.size());
        readCursor(clamp(distance));
    }

    boolean matchesRoad(TrackLayout track) { return road==track; }

    void releaseRaster() {
        if(trajectoryLayer!=null) trajectoryLayer.raster().pixels().flush();
        trajectoryLayer=null;
    }

    boolean seek(float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y)) return false;
        if(MAP.contains(x,y)) return pickBrake(x,y);
        if(x<RULER.x || x>RULER.x+RULER.width
                || y<RULER.y || y>=RULER.y+RULER.height) return false;
        float fraction=(x-RULER.x)/RULER.width;
        setCursor(fraction==1?to:from+(to-from)*fraction);
        return true;
    }

    private boolean pickBrake(float x,float y) {
        int hit=-1;
        float closest=BRAKE_PICK_RADIUS*BRAKE_PICK_RADIUS;
        for(int i=0;i<brakes.size();i++) {
            var marker=brakes.get(i);
            float dx=x-marker.x(),dy=y-marker.y();
            float squared=dx*dx+dy*dy;
            if(squared>closest) continue;
            // For co-located actions, select the next matching action in the existing button order.
            if(hit<0 || squared<closest || (hit<=brakeIndex && i>brakeIndex)) {
                hit=i;
                closest=squared;
            }
        }
        if(hit<0) return false;
        brakeIndex=hit;
        return true;
    }

    void move(int direction) {
        float distance=cursor.distance();
        float next=distance+(to-from)*.05f*direction;
        if(next==distance) next=direction<0?Math.nextDown(distance):Math.nextUp(distance);
        setCursor(next);
    }

    void moveBrake(int direction) {
        if(brakes.isEmpty()) return;
        if(brakeIndex<0) brakeIndex=direction<0?brakes.size()-1:0;
        else brakeIndex=Math.floorMod(brakeIndex+direction,brakes.size());
    }

    private BrakeMarker selectedBrake() {return brakeIndex<0?null:brakes.get(brakeIndex);}

    private float clamp(float distance) {return Math.max(from,Math.min(to,distance));}

    void resetCursor(float distance) {
        brakeIndex=-1;
        float selected=clamp(distance);
        if(Float.compare(selected,cursor.distance())!=0) readCursor(selected);
    }

    private void setCursor(float distance) {
        resetCursor(distance);
        cursorChanged.accept(cursor.distance());
    }

    private void readCursor(float distance) {
        cursor=comparison.at(distance);
        roadCursor=road==null?null:comparison.roadCursor(road,cursor);
    }

    void openPractice() {practice.run();}

    void render(HudRenderer hud,Graphics2D graphics,Runnable back) {
        hud.panel(28,28,1224,738,PANEL);
        hud.text("区间走线 A／B",60,91,29,INK,true);
        String route=road==null?"原路线未找到 · 显示记录走线":"已匹配原路线 · 灰虚线为中心线，横向正值为右侧";
        hud.text(String.format(Locale.ROOT,"区间 %.1f–%.1f m · %s",from,to,route),60,128,17,SOFT);
        hud.smallButton(780,56,204,40,"练本段  P",this::openPractice);
        hud.smallButton(1000,56,214,40,"返回曲线  Esc",back);
        hud.panel(MAP.x,MAP.y,MAP.width,MAP.height,MAP_BACKGROUND);
        drawPaths(graphics);
        hud.text("X →    Z ↑",MAP.x+20,MAP.y+28,14,SOFT);
        hud.text(String.format(Locale.ROOT,"%.1f m",96d/transform.scale()),MAP.x+126,MAP.y+MAP.height-20,14,SOFT);

        hud.text("A 实线  ○",946,197,18,A,true);
        hud.text("B 虚线  □",1080,197,18,B,true);
        hud.text("● 区间起点    △ 区间终点",946,229,15,SOFT);
        if(!brakes.isEmpty()) hud.text("▼ 踩下制动    ▲ 松开制动",946,252,15,SOFT);
        hud.text(String.format(Locale.ROOT,"游标 %.2f m",cursor.distance()),946,281,19,INK,true);
        drawReadout(hud,"A",cursor.a(),324,A);
        drawReadout(hud,"B",cursor.b(),392,B);
        if(roadCursor!=null) {
            hud.text(String.format(Locale.ROOT,"相对中心线 %+.2f m",roadCursor.offsetA()),946,369,14,A);
            hud.text(String.format(Locale.ROOT,"相对中心线 %+.2f m",roadCursor.offsetB()),946,437,14,B);
        }
        double separation=Math.hypot((double)cursor.a().x()-cursor.b().x(),(double)cursor.a().z()-cursor.b().z());
        hud.text(String.format(Locale.ROOT,"平面位置相差 %.2f m",separation),946,470,16,INK,true);
        hud.text(String.format(Locale.ROOT,"自区间起点 A − B %+.3f s",cursor.delta()-entryDelta),946,503,15,SOFT);
        drawBrakeReadout(hud);

        drawRuler(hud,graphics);
        hud.smallButton(946,666,124,38,"← 前移游标",()->move(-1));
        hud.smallButton(1082,666,132,38,"后移游标 →",()->move(1));
        hud.text("两圈按首次到达的距离对齐 · 点距离尺或用方向键移动游标",60,742,15,SOFT);
    }

    private void drawBrakeReadout(HudRenderer hud) {
        if(brakes.isEmpty()) {
            hud.text("区间内无制动动作标记",946,551,15,SOFT);
            return;
        }
        var selected=selectedBrake();
        if(selected==null) {
            hud.text("原制动动作 · A "+brakeCountA+"  B "+(brakes.size()-brakeCountA),946,545,16,INK,true);
            hud.text("点标记读原样本。重叠时重复点选。",946,572,14,SOFT);
        } else {
            var action=selected.action();
            var point=action.sample();
            String label=(selected.reference()?"B":"A")+(action.pressed()?" · 踩下制动":" · 松开制动");
            hud.text(label+" · "+(brakeIndex+1)+" / "+brakes.size(),946,538,16,selected.reference()?B:A,true);
            hud.text(String.format(Locale.ROOT,"圈内 %.3f s · %.1f km/h",point.time(),point.driving().speed()*3.6),946,560,14,INK);
            hud.text(String.format(Locale.ROOT,"X %.2f m · Z %.2f m",point.x(),point.z()),946,582,14,SOFT);
            hud.text(String.format(Locale.ROOT,"投影 %.1f m · 制动 %.0f%%",point.distance(),point.driving().brake()*100),946,604,14,SOFT);
        }
        hud.smallButton(946,619,124,31,"上一动作 PgUp",()->moveBrake(-1));
        hud.smallButton(1082,619,132,31,"下一动作 PgDn",()->moveBrake(1));
    }

    private void drawPaths(Graphics2D graphics) {
        var g=(Graphics2D)graphics.create();
        try {
            g.clip(MAP);
            if(centerline!=null) drawRoad(g);
            drawTrajectories(g);
            g.setStroke(SOLID);
            int ax=x(cursor.a()),ay=y(cursor.a()),bx=x(cursor.b()),by=y(cursor.b());
            g.setColor(A);g.drawOval(ax-10,ay-10,20,20);
            g.setColor(B);g.drawRect(bx-6,by-6,12,12);
            var selected=selectedBrake();
            if(selected!=null) {
                int x=selected.x(),y=selected.y();
                g.setColor(INK);
                g.drawPolygon(new int[]{x,x+15,x,x-15},new int[]{y-15,y,y+15,y},4);
            }
            int left=MAP.x+20,base=MAP.y+MAP.height-23;
            g.setColor(SOFT);g.drawLine(left,base,left+96,base);
            g.drawLine(left,base-4,left,base+4);g.drawLine(left+96,base-4,left+96,base+4);
        } finally {
            g.dispose();
        }
    }

    private void drawTrajectories(Graphics2D g) {
        if(sampleCount<MIN_LAYER_SAMPLES) {
            paintTrajectories(g);
            return;
        }
        var viewport=g.getTransform();
        if(HudRasterLayer.supports(g,viewport)) {
            Object stroke=g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
            if(trajectoryLayer==null || !trajectoryLayer.viewport().equals(viewport)
                    || !Objects.equals(stroke,trajectoryLayer.strokeControl())) {
                releaseRaster();
                var raster=HudRasterLayer.capture(g,viewport,MAP,MAX_LAYER_PIXELS,this::paintTrajectories);
                trajectoryLayer=raster==null?null:new TrajectoryLayer(viewport,stroke,raster);
            }
            if(trajectoryLayer!=null) {
                trajectoryLayer.raster().draw(g);
                return;
            }
        }
        paintTrajectories(g);
    }

    private void paintTrajectories(Graphics2D g) {
        g.setColor(A);g.setStroke(SOLID);g.draw(trajectoryA.path());
        g.setColor(B);g.setStroke(DASHED);g.draw(trajectoryB.path());
        g.setStroke(SOLID);
        drawEnds(g,trajectoryA,A);
        drawEnds(g,trajectoryB,B);
        g.setColor(A);g.fill(brakeMarksA);
        g.setColor(B);g.fill(brakeMarksB);
    }

    private void drawRoad(Graphics2D g) {
        g.setColor(CENTERLINE);g.setStroke(DASHED);g.draw(centerline);
        float dx=roadCursor.rightX()*road.width*.5f,dz=roadCursor.rightZ()*road.width*.5f;
        float cx=roadCursor.centerX(),cz=roadCursor.centerZ();
        g.setColor(ROAD_CURSOR);g.setStroke(SOLID);
        g.drawLine(Math.round(transform.x(cx-dx)),Math.round(transform.y(cz-dz)),
                Math.round(transform.x(cx+dx)),Math.round(transform.y(cz+dz)));
    }

    private void drawRuler(HudRenderer hud,Graphics2D g) {
        hud.text("区间距离尺",RULER.x,RULER.y-12,15,SOFT);
        hud.panel(RULER.x,RULER.y,RULER.width,RULER.height,MAP_BACKGROUND);
        int baseline=RULER.y+RULER.height/2;
        int end=RULER.x+RULER.width;
        g.setColor(SOFT);g.drawLine(RULER.x,baseline,end,baseline);
        for(int i=0;i<=4;i++) {
            int x=RULER.x+i*RULER.width/4;
            g.drawLine(x,baseline-5,x,baseline+5);
        }
        int at=RULER.x+Math.round(RULER.width*(cursor.distance()-from)/(to-from));
        g.setColor(INK);g.fillRect(at-1,RULER.y+5,3,RULER.height-10);
    }

    private static void drawReadout(HudRenderer hud,String side,TelemetryLap.Sample point,int top,Color color) {
        hud.text(String.format(Locale.ROOT,"%s  %.1f km/h · 圈内 %.3f s",side,point.driving().speed()*3.6,point.time()),946,top,15,color,true);
        hud.text(String.format(Locale.ROOT,"X %.2f m · Z %.2f m",point.x(),point.z()),946,top+25,14,SOFT);
    }

    private void drawEnds(Graphics2D g,Trajectory trajectory,Color color) {
        var start=trajectory.start();
        var end=trajectory.end();
        g.setColor(color);
        g.fillOval(x(start)-4,y(start)-4,8,8);
        int x=x(end),y=y(end);
        g.drawPolygon(new int[]{x-6,x+6,x},new int[]{y+5,y+5,y-6},3);
    }

    private int x(TelemetryLap.Sample point) {return Math.round(transform.x(point.x()));}
    private int y(TelemetryLap.Sample point) {return Math.round(transform.y(point.z()));}

    private Trajectory trajectory(List<TelemetryLap.Sample> samples) {
        var result=new Path2D.Float(Path2D.WIND_NON_ZERO,samples.size());
        var start=samples.get(0);
        result.moveTo(transform.x(start.x()),transform.y(start.z()));
        for(int i=1;i<samples.size();i++) {
            var sample=samples.get(i);
            result.lineTo(transform.x(sample.x()),transform.y(sample.z()));
        }
        return new Trajectory(result,start,samples.get(samples.size()-1));
    }

    private Path2D brakeMarks(int first,int end) {
        var result=new Path2D.Float();
        for(int i=first;i<end;i++) {
            var marker=brakes.get(i);
            int x=marker.x(),y=marker.y(),direction=marker.action().pressed()?1:-1;
            result.moveTo(x-6*direction,y-5*direction);
            result.lineTo(x+6*direction,y-5*direction);
            result.lineTo(x,y+6*direction);
            result.closePath();
        }
        return result;
    }

    private static TrackLayout.Bounds bounds(TelemetryComparison.IntervalTrajectory paths,TrackLayout road) {
        float minX=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(var line:List.of(paths.a(),paths.b())) {
            for(var point:line) {
                minX=Math.min(minX,point.x());maxX=Math.max(maxX,point.x());
                minZ=Math.min(minZ,point.z());maxZ=Math.max(maxZ,point.z());
            }
        }
        if(road!=null) {
            var center=TrackMapView.circuitBounds(road,paths.from(),paths.to());
            float margin=road.width*.5f;
            minX=Math.min(minX,center.minX()-margin);maxX=Math.max(maxX,center.maxX()+margin);
            minZ=Math.min(minZ,center.minZ()-margin);maxZ=Math.max(maxZ,center.maxZ()+margin);
        }
        return new TrackLayout.Bounds(minX,maxX,minZ,maxZ);
    }
}
