package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/** Linked distance cursor, real controls and a track-position preview. */
final class TelemetryHud {
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),A=new Color(0xffb267),B=new Color(0x63e6bc),PANEL=new Color(0x13242e);
    private static final Color CHART_PANEL=new Color(0x1d3540),CHART_GRID=new Color(0x36515c);
    private static final BasicStroke GRID_STROKE=new BasicStroke(1),TRACE_STROKE=new BasicStroke(2);
    private static final int X=134,WIDTH=730,TOP=223,ROW=103,HEIGHT=73;
    private static final int OBSERVATION_PICK_RADIUS=3;
    private static final String[] INPUT_NAMES={"速度","油门","制动","转向指令"};
    private static final String[] RESPONSE_NAMES={"前压缩","后压缩","前轮转角","横摆速度"};
    private record Trace(Path2D line,Path2D dots,BitSet dotSamples) {
        void draw(Graphics2D graphics) {graphics.draw(line);if(dots!=null) graphics.fill(dots);}
    }
    private record Channel(float minimum,float maximum,Trace a,Trace b) {}
    private record Charts(TelemetryComparison source,boolean responses,boolean reference,boolean observed,Channel[] channels) {}
    private record CursorReadout(boolean responses,TelemetryLap.Sample brake,String position,String delta,String first,String second,String third,String fourth) {}
    private boolean responses;
    private Charts charts,otherCharts;
    private TrackLayout mapTrack;
    private TrackMapView.Outline mapOutline;
    private TelemetryComparison cursorSource;
    private TelemetryComparison.Point cursorPoint;
    private CursorReadout cursorReadout;
    private final EquipmentLapsHud equipmentHud=new EquipmentLapsHud();
    void keepCharts(TelemetryComparison comparison) {
        if(charts!=null && charts.source()!=comparison) charts=null;
        if(otherCharts!=null && otherCharts.source()!=comparison) otherCharts=null;
        if(cursorSource!=comparison) {cursorSource=null;cursorPoint=null;cursorReadout=null;}
        if(comparison==null) {mapTrack=null;mapOutline=null;}
    }
    boolean click(FormulaGame app,float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y)) return false;
        if(app.screen==FormulaGame.Screen.TELEMETRY && equipmentHud.showing()) return !app.helpVisible && equipmentHud.click(x,y);
        if(app.screen!=FormulaGame.Screen.TELEMETRY || app.helpVisible || app.telemetryComparison()==null || x<X || x>X+WIDTH || y<TOP || y>TOP+ROW*3+HEIGHT) return false;
        int channel=(int)((y-TOP)/ROW);
        if(y>TOP+channel*ROW+HEIGHT) return false;
        var comparison=app.telemetryComparison();
        float length=comparison.a().conditions().length();
        float distance=(x-X)/WIDTH*length;
        if(responses && matchesCharts(charts,comparison,app.telemetryHasReference())) distance=selectObservationDot(x,y,channel,length,distance);
        app.telemetryCursor(distance);return true;
    }
    private float selectObservationDot(float x,float y,int channel,float length,float distance) {
        int top=TOP+channel*ROW;
        var scale=charts.channels()[channel];
        var source=charts.source();
        float nearest=OBSERVATION_PICK_RADIUS*OBSERVATION_PICK_RADIUS;
        float selected=distance;
        // B is painted last, so its visible dot wins an equal pixel-distance hit.
        for(int slot=charts.reference()?1:0;slot>=0;slot--) {
            var points=source.observationTrace(slot==1);
            var dots=(slot==1?scale.b():scale.a()).dotSamples();
            int split=firstObservationAtOrAfter(points,distance);
            int left=dots.previousSetBit(split-1),right=dots.nextSetBit(split);
            while(left>=0 || right>=0) {
                // Visit the closest recorded distance first when several samples occupy one dot.
                boolean takeLeft=right<0 || left>=0 && distance-points.get(left).distance()<=points.get(right).distance()-distance;
                int index=takeLeft?left:right;
                var point=points.get(index);
                float dx=x-abscissa(point.distance(),length);
                if(takeLeft) left=dx>=OBSERVATION_PICK_RADIUS?-1:dots.previousSetBit(index-1);
                else right=dx<=-OBSERVATION_PICK_RADIUS?-1:dots.nextSetBit(index+1);
                if(Math.abs(dx)>=OBSERVATION_PICK_RADIUS) continue;
                float dy=y-ordinate(responseAmount(point.observation(),channel),scale.minimum(),scale.maximum(),top);
                float error=dx*dx+dy*dy;
                if(error>=nearest) continue;
                var at=source.at(point.distance());
                if(!point.observation().equals((slot==1?at.b():at.a()).observation())) continue;
                if(error==0) return point.distance();
                nearest=error;
                selected=point.distance();
            }
        }
        return selected;
    }
    private static int firstObservationAtOrAfter(List<TelemetryComparison.ObservationPoint> points,float distance) {
        int low=0,high=points.size();
        while(low<high) {
            int middle=(low+high)/2;
            if(points.get(middle).distance()<distance) low=middle+1;
            else high=middle;
        }
        return low;
    }
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(equipmentHud.showing()) {equipmentHud.render(h,g,app);return;}
        h.panel(28,28,1224,738,PANEL);h.text("LAP ANALYSIS",60,67,14,B,true);h.text("圈速与操作对比",60,110,32,INK,true);
        h.smallButton(1050,62,164,43,"返回",app::backFromTelemetry);
        h.smallButton(800,62,232,43,"装备整圈记录与比较",()->equipmentHud.open(app));
        var book=app.telemetryBook();if(book==null) return;
        h.text(app.telemetryTrack().name+" · "+Tuning.performanceDescription(book.conditions().performance())+" · "+(ControlIdentity.KEYBOARD.equals(book.conditions().controls())?"键盘":"手柄"),60,143,13,SOFT);
        var comparison=app.telemetryComparison();
        if(comparison==null) {
            h.text("还没有当前条件的完整遥测圈",60,260,27,B,true);
            h.wrapped("在计时挑战中完成有效整圈，之后可比较速度、油门、制动与转向。每组条件保留最近六圈和最快圈；完成第二圈后可选参照。",60,315,1060,20,INK,36);
            h.wrapped("旧版幽灵没有操作数据，保留原幽灵，不反推油门和制动。复位、越界、未完成或采样中断的圈不加入对比。",60,435,1060,18,SOFT,32);
        } else {
            boolean reference=app.telemetryHasReference();
            if(reference) h.text(String.format(Locale.ROOT,"整圈 A − B  %+.3f s",comparison.a().duration()-comparison.b().duration()),965,143,15,comparison.a().duration()>comparison.b().duration()?A:B);
            h.smallButton(60,164,554,40,"A  "+label(book,comparison.a())+" · 切换",()->app.cycleTelemetry(false));
            h.smallButton(638,164,576,40,reference?"B  "+label(book,comparison.b())+" · 切换":"B  再完成一圈即可选择参照",()->app.cycleTelemetry(true));
            float length=book.conditions().length();
            if(!matchesCharts(charts,comparison,reference)) {
                var next=matchesCharts(otherCharts,comparison,reference)?otherCharts:buildCharts(comparison,reference);
                otherCharts=charts;
                charts=next;
            }
            float distance=app.telemetryDistance();
            int cursor=X+Math.round(distance/length*WIDTH);
            for(int channel=0;channel<4;channel++) {
                int y=TOP+channel*ROW;
                var trace=charts.channels()[channel];
                float min=trace.minimum(),max=trace.maximum();
                String name=(responses?RESPONSE_NAMES:INPUT_NAMES)[channel];h.text(name,60,y+17,15,INK,true);h.text(responses?channel<2?"mm":channel==2?"°":"°/s":channel==0?"km/h":"%",60,y+40,13,SOFT);
                h.panel(X,y,WIDTH,HEIGHT,CHART_PANEL);g.setStroke(GRID_STROKE);g.setColor(CHART_GRID);
                for(int i=0;i<=3;i++) { int x=X+WIDTH*i/3;g.drawLine(x,y,x,y+HEIGHT); }
                int zero=ordinate(0,min,max,y);g.drawLine(X,zero,X+WIDTH,zero);
                h.text(Math.round(max)+"",X+WIDTH+2,y+10,10,SOFT);h.text(Math.round(min)+"",X+WIDTH+2,y+HEIGHT,10,SOFT);
                g.setStroke(TRACE_STROKE);
                g.setColor(A);trace.a().draw(g);
                if(reference) {g.setColor(B);trace.b().draw(g);}
                if(responses && !charts.observed()) h.text("所选圈没有车辆观测；重新完成计时圈后可查看。",X+30,y+43,15,SOFT);
                g.setColor(INK);g.setStroke(GRID_STROKE);g.drawLine(cursor,y,cursor,y+HEIGHT);
            }
            h.text("0 m",X,TOP+ROW*3+HEIGHT+22,13,SOFT);h.text(Math.round(length)+" m",X+WIDTH-55,TOP+ROW*3+HEIGHT+22,13,SOFT);
            if(cursorSource!=comparison || cursorPoint==null || cursorPoint.distance()!=distance) {
                cursorSource=comparison;
                cursorPoint=comparison.at(distance);
                cursorReadout=null;
            }
            var point=cursorPoint;
            var brake=app.telemetryBrakeSample();
            if(cursorReadout==null || cursorReadout.responses()!=responses || cursorReadout.brake()!=brake)
                cursorReadout=readout(point,reference,brake);
            h.panel(890,222,324,197,CHART_PANEL);
            var track=app.telemetryTrack();
            if(mapTrack!=track || mapOutline==null) {
                mapTrack=track;
                mapOutline=new TrackMapView.Outline(track,new Rectangle(902,232,300,172),18,SOFT,3);
            }
            var map=mapOutline.draw(g);
            dot(g,map,brake==null?point.a():brake,A);if(reference) dot(g,map,point.b(),B);
            h.text(cursorReadout.position(),906,450,brake==null?20:17,INK,true);
            h.text(cursorReadout.delta(),906,480,18,reference?point.delta()>0?A:B:SOFT);
            if(responses) {
                h.text(cursorReadout.first(),906,510,14,A);
                h.text(cursorReadout.second(),906,536,14,reference?B:SOFT);
                h.text(cursorReadout.third(),906,565,14,INK);
                h.text(cursorReadout.fourth(),906,591,14,INK);
                h.text("前后压缩共用刻度；单点用圆点，缺口断开。",906,623,13,SOFT);
                h.text("转角与横摆采用引擎坐标符号。",906,647,13,SOFT);
            } else {
                h.text(cursorReadout.first(),906,510,14,INK);
                h.text(cursorReadout.second(),906,536,14,INK);
                h.text(cursorReadout.third(),906,562,14,INK);
                if(reference) {
                    h.text("A 多用时间的区段",906,599,17,A,true);int row=0;
                    for(var loss:comparison.losses()) h.text(String.format(Locale.ROOT,"%.0f–%.0f m  +%.3f s",loss.from(),loss.to(),loss.delta()),906,625+row++*23,14,SOFT);
                }
            }
            h.text("点击任一图表联动位置；橙色 A，绿色 B。中心线距离对齐，时间差为 A − B。",60,676,14,SOFT);
            h.smallButton(302,627,140,30,"位置 −10 m",()->app.telemetryCursor(app.telemetryDistance()-10));
            h.smallButton(460,627,140,30,"位置 +10 m",()->app.telemetryCursor(app.telemetryDistance()+10));
            h.smallButton(60,705,264,36,"导出 A 圈 CSV",h::chooseTelemetryExport);
            h.smallButton(708,705,156,36,"下个制动点",app::nextTelemetryBrake);
            h.smallButton(906,705,308,36,responses?"查看速度与操作":"查看悬挂与转向响应",()->responses=!responses);
        }
        if(app.telemetryStore.pending(book.conditions())) {
            h.smallButton(342,705,142,36,"重试保存本组",()->app.retryTelemetrySave(book));
            h.smallButton(494,705,190,36,"核对并合并本组",app::reviewTelemetryMerge);
        }else if(app.telemetryStore.pendingCount()>0) {
            h.smallButton(342,705,342,36,"重试全部待存遥测 · "+app.telemetryStore.pendingCount()+" 组",app::retryTelemetrySave);
        }
        h.text(responses?"压缩 = 静长 − 当前长度；正值压缩，负值伸长。位移不能代表轮胎载荷或判定接地；横摆为世界 Y 轴角速度。":"操作曲线是车辆指令；采样与距离投影为近似。区段差异不直接判定失误原因。",60,754,13,SOFT);
    }

    private CursorReadout readout(TelemetryComparison.Point point,boolean reference,TelemetryLap.Sample brake) {
        var selectedA=brake==null?point.a():brake;
        String position=brake==null?"位置 "+Math.round(point.distance())+" m":
                String.format(Locale.ROOT,"A 制动 %.1f m · %.3f s",point.distance(),brake.time());
        String delta=reference?String.format(Locale.ROOT,brake==null?"A 相对 B  %+.3f s":"曲线时差 A − B  %+.3f s",point.delta()):"再完成一圈后显示时间差";
        if(responses) {
            var a=selectedA.observation();
            var b=point.b().observation();
            return new CursorReadout(true,brake,position,delta,compression("A",a),reference?compression("B",b):"B 尚无参照圈",
                    "前轮 ° A / B  "+angle(a,false)+" / "+(reference?angle(b,false):"—"),
                    "横摆 °/s A / B  "+angle(a,true)+" / "+(reference?angle(b,true):"—"));
        }
        var a=selectedA.driving();
        var b=point.b().driving();
        String speed=reference?String.format(Locale.ROOT,"A / B 速度 %.1f / %.1f km/h",a.speed()*3.6,b.speed()*3.6)
                :String.format(Locale.ROOT,"A 速度 %.1f km/h",a.speed()*3.6);
        String pedals=reference?String.format(Locale.ROOT,"油门 %.0f / %.0f · 制动 %.0f / %.0f",a.throttle()*100,b.throttle()*100,a.brake()*100,b.brake()*100)
                :String.format(Locale.ROOT,"A 油门 %.0f · 制动 %.0f %%",a.throttle()*100,a.brake()*100);
        String steer=reference?String.format(Locale.ROOT,"转向指令 %.0f / %.0f %%",a.steer()*100,b.steer()*100)
                :String.format(Locale.ROOT,"A 转向指令 %.0f %%",a.steer()*100);
        return new CursorReadout(false,brake,position,delta,speed,pedals,steer,"");
    }
    private String label(TelemetryBook book,TelemetryLap lap) { int index=book.recent().indexOf(lap);return (index<0?"保留最佳":"最近第 "+(index+1)+" 圈")+"  "+PracticeSession.format(lap.duration())+(book.best()!=null && lap.id().equals(book.best().id())?"  最佳":""); }
    private void dot(Graphics2D g,TrackMapView.Transform map,TelemetryLap.Sample sample,Color color) { g.setColor(color);g.fillOval(Math.round(map.x(sample.x()))-4,Math.round(map.y(sample.z()))-4,8,8); }
    private boolean matchesCharts(Charts value,TelemetryComparison comparison,boolean reference) {
        return value!=null && value.source()==comparison && value.responses()==responses && value.reference()==reference;
    }
    private Charts buildCharts(TelemetryComparison comparison,boolean reference) {
        float peak=20;
        float[] ranges={20,20,5,10};
        float length=comparison.a().conditions().length();
        var a=responses?comparison.observationTrace(false):List.<TelemetryComparison.ObservationPoint>of();
        var b=responses && reference?comparison.observationTrace(true):List.<TelemetryComparison.ObservationPoint>of();
        var inputs=responses?List.<TelemetryComparison.Point>of():comparison.plot();
        for(var point:inputs) {
            float speed=reference?Math.max(point.a().driving().speed(),point.b().driving().speed()):point.a().driving().speed();
            peak=Math.max(peak,speed*3.6f);
        }
        includeResponseRanges(a,ranges);
        includeResponseRanges(b,ranges);
        // Both axles share one displacement scale, across both laps.
        ranges[0]=ranges[1]=(float)Math.ceil(Math.max(ranges[0],ranges[1])/10)*10;
        ranges[2]=(float)Math.ceil(ranges[2]/5)*5;
        ranges[3]=(float)Math.ceil(ranges[3]/10)*10;
        float speedMaximum=(float)Math.ceil(peak/20)*20;
        var channels=new Channel[ranges.length];
        for(int channel=0;channel<channels.length;channel++) {
            float min=responses?-ranges[channel]:channel==1 || channel==3?-100:0;
            float max=responses?ranges[channel]:channel==0?speedMaximum:100;
            int y=TOP+channel*ROW;
            var traceA=responses?responseTrace(a,length,channel,min,max,y):inputTrace(inputs,length,channel,false,min,max,y);
            var traceB=reference?(responses?responseTrace(b,length,channel,min,max,y):inputTrace(inputs,length,channel,true,min,max,y)):null;
            channels[channel]=new Channel(min,max,traceA,traceB);
        }
        return new Charts(comparison,responses,reference,!a.isEmpty() || !b.isEmpty(),channels);
    }
    private static void includeResponseRanges(List<TelemetryComparison.ObservationPoint> points,float[] ranges) {
        for(var point:points) for(int channel=0;channel<ranges.length;channel++)
            ranges[channel]=Math.max(ranges[channel],Math.abs(responseAmount(point.observation(),channel)));
    }
    private Trace inputTrace(List<TelemetryComparison.Point> points,float length,int channel,boolean reference,float min,float max,int y) {
        var path=new Path2D.Float();
        for(var point:points) {
            var value=(reference?point.b():point.a()).driving();
            float amount=switch(channel) {case 0->value.speed()*3.6f;case 1->value.throttle()*100;case 2->value.brake()*100;default->value.steer()*100;};
            int x=abscissa(point.distance(),length),cy=ordinate(amount,min,max,y);
            if(point.distance()==0) path.moveTo(x,cy);else path.lineTo(x,cy);
        }
        return new Trace(path,null,null);
    }
    private Trace responseTrace(List<TelemetryComparison.ObservationPoint> points,float length,int channel,float min,float max,int y) {
        var line=new Path2D.Float();
        var dots=new Path2D.Float();
        var dotSamples=new BitSet();
        var dot=new Ellipse2D.Float();
        for(int index=0;index<points.size();) {
            var point=points.get(index);
            int x=abscissa(point.distance(),length);
            int first=ordinate(responseAmount(point.observation(),channel),min,max,y);
            int low=first,high=first,last=first,lowAt=index,highAt=index,next=index+1;
            // Reduce each connected run in a pixel column to its endpoints and both extrema.
            for(;next<points.size();next++) {
                var nextPoint=points.get(next);
                if(!nextPoint.connected() || abscissa(nextPoint.distance(),length)!=x) break;
                last=ordinate(responseAmount(nextPoint.observation(),channel),min,max,y);
                if(last<low) {low=last;lowAt=next;}
                if(last>high) {high=last;highAt=next;}
            }
            if(point.connected()) line.lineTo(x,first);else line.moveTo(x,first);
            if(low!=high) {
                int firstExtreme=lowAt<highAt?low:high,secondExtreme=lowAt<highAt?high:low;
                if(first!=firstExtreme) line.lineTo(x,firstExtreme);
                line.lineTo(x,secondExtreme);
                if(last!=secondExtreme) line.lineTo(x,last);
            } else if(!point.connected() && (next==points.size() || !points.get(next).connected())) {
                dot.setFrame(x-2,first-2,4,4);
                dots.append(dot,false);
                dotSamples.set(index,next);
            }
            index=next;
        }
        return new Trace(line,dots,dotSamples);
    }
    private static int abscissa(float distance,float length) {return X+Math.round(distance/length*WIDTH);}
    private static float responseAmount(VehicleObservation value,int channel) {
        return switch(channel) {case 0->value.frontCompression()*1000;case 1->value.rearCompression()*1000;case 2->(float)Math.toDegrees(value.frontSteer());default->(float)Math.toDegrees(value.yawRate());};
    }
    private String compression(String slot,VehicleObservation value) {return value==null?slot+" 此位置没有车辆观测":String.format(Locale.ROOT,"%s 前 / 后  %+.1f / %+.1f mm",slot,value.frontCompression()*1000,value.rearCompression()*1000);}
    private String angle(VehicleObservation value,boolean yaw) {return value==null?"—":String.format(Locale.ROOT,"%+.1f",Math.toDegrees(yaw?value.yawRate():value.frontSteer()));}
    private int ordinate(float value,float min,float max,int y) { return y+HEIGHT-Math.round(Math.max(0,Math.min(1,(value-min)/(max-min)))*HEIGHT); }
}
