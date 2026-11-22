package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.EquipmentLapShareCodec;
import java.nio.file.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.*;

/** Explicit local selection. Source checks and distance indices rebuild only on user actions. */
final class EquipmentLapsHud {
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),A=new Color(0xffb267),B=new Color(0x63e6bc),GOLD=new Color(245,215,132);
    private static final Color INTERVAL_FILL=new Color(245,215,132,38);
    private static final Color TIME_DELTA=new Color(0xc4d3ff),ZERO_LINE=new Color(0x7e96a1);
    private static final BasicStroke TRACE_STROKE=new BasicStroke(2);
    private static final int PLOT_X=140,PLOT_WIDTH=800,PLOT_TOP=260,PLOT_STEP=80,PLOT_HEIGHT=62;
    private static final List<String> CONTROL_LABELS=List.of("速度","油门","制动","转向");
    private boolean open,chart,receivedMode;private List<EquipmentLap> entries=List.of();private EquipmentLap pending,a,b;private TelemetryComparison comparison;private float distance;
    private Float intervalStart,intervalEnd;
    private TelemetryComparison.Interval interval;
    private EquipmentSectionPracticeSession intervalPractice;
    private EquipmentIntervalMap intervalMap;
    private TelemetryComparison.DeltaTrace timeDelta;
    private Path2D timeDeltaPath,fullTimeDeltaPath;
    private List<ControlLine> controlPlot;
    private TelemetryComparison.Point cursorPoint;
    private record ControlLine(float min,float max,Path2D a,Path2D b) {}
    private String intervalNotice="";
    private List<EquipmentLapShare> received=List.of();private EquipmentLapShare receivedPending,aShare,bShare;private List<Row> visible=List.of();
    private record Row(EquipmentLap lap,EquipmentLapShare share) {}
    private EquipmentLapShare reference;
    private boolean reportView;
    private String pairALabel,pairBLabel;
    boolean showing() {return open;}
    void open(FormulaGame app) {reportView=false;reference=null;refresh(app);open=true;}
    void openReference(FormulaGame app,EquipmentLapShare value) {reportView=false;reference=Objects.requireNonNull(value);receivedMode=false;refresh(app);open=true;}
    void openReportPair(EquipmentLap a,EquipmentLapShare aShare,EquipmentLap b,EquipmentLapShare bShare,TelemetryComparison comparison) {
        openReportPair(a,aShare,b,bShare,comparison,null,null);
    }
    void openReportPair(EquipmentLap a,EquipmentLapShare aShare,EquipmentLap b,EquipmentLapShare bShare,TelemetryComparison comparison,String aLabel,String bLabel) {
        Objects.requireNonNull(a);Objects.requireNonNull(b);Objects.requireNonNull(comparison);
        if(!comparison.a().equals(a.lap()) || !comparison.b().equals(b.lap()) || aShare!=null && !aShare.recording().equals(a) || bShare!=null && !bShare.recording().equals(b)) throw new IllegalArgumentException("Pair view differs from selected laps");
        this.a=a;this.b=b;this.aShare=aShare;this.bShare=bShare;
        resetComparison(comparison);
        reference=null;distance=0;chart=true;reportView=true;pairALabel=aLabel;pairBLabel=bLabel;open=true;
    }
    private void refresh(FormulaGame app) {entries=app.equipmentLaps.entries();pending=app.equipmentLaps.pending();received=app.receivedEquipmentLaps.entries();receivedPending=app.receivedEquipmentLaps.pending();a=b=null;aShare=bShare=null;resetComparison(null);chart=false;distance=0;rows();}
    private void resetComparison(TelemetryComparison value) {
        comparison=value;
        controlPlot=null;
        cursorPoint=null;
        fullTimeDeltaPath=null;
        clearInterval();
    }
    private void close() {
        open=reportView=chart=false;
        a=b=pending=null;
        aShare=bShare=reference=receivedPending=null;
        entries=List.of();received=List.of();visible=List.of();
        pairALabel=pairBLabel=null;
        resetComparison(null);
    }
    private void rows() {visible=receivedMode?received.stream().map(s->new Row(s.recording(),s)).toList():entries.stream().map(e->new Row(e,null)).toList();if(reference!=null && a==null) {a=reference.recording();aShare=reference;}}
    private String origin(EquipmentLapShare share) {return share==null?"本机 ":share==reference?"引用 ":"收到 ";}
    private boolean current(FormulaGame app) {return entries==app.equipmentLaps.entries() && pending==app.equipmentLaps.pending() && received==app.receivedEquipmentLaps.entries() && receivedPending==app.receivedEquipmentLaps.pending();}
    private boolean check(FormulaGame app) {if(current(app)) return true;app.notifyUser("装备圈记录库已变化；冻结读数保持，请明确刷新后重选。");return false;}
    boolean click(float x,float y) {
        if(!open || !chart || comparison==null || !Float.isFinite(x) || !Float.isFinite(y)
                || x<PLOT_X || x>PLOT_X+PLOT_WIDTH || y<260 || y>652) return false;
        distance=(x-PLOT_X)/PLOT_WIDTH*comparison.a().conditions().length();
        return true;
    }
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(reportView) {
            h.clearButtons();h.panel(28,28,1224,738,new Color(0x13242e));h.text("报告 A／B 对应的独立装备整圈",60,110,28,INK,true);
            h.text("按打开时的选择只读查看 · 关闭后回到报告的整圈选择",60,149,16,GOLD);
            h.smallButton(1030,57,184,40,"返回原对照页",this::close);
            plot(h,g,app);return;
        }
        h.clearButtons();h.panel(28,28,1224,738,new Color(0x13242e));h.text("EQUIPMENT LAPS",60,65,14,B,true);h.text("装备整圈 · 明确选择与取舍",60,110,30,INK,true);
        h.smallButton(620,57,182,40,"明确刷新并重选",()->refresh(app));h.smallButton(820,57,190,40,chart?"返回记录库":"查看两圈曲线",()->{if(chart) {chart=false;clearInterval();}else if(check(app)) compare(h);});
        h.smallButton(1030,57,184,40,reference==null?"返回原遥测":"返回引用库",this::close);
        h.wrapped(current(app)?"独立收录的完整圈；来源在开赛时冻结。比较不会采用装备、写入成绩或合并样本。":"记录库已变化 · 保留本页冻结读数；刷新后重新选择。",60,149,1154,15,current(app)?SOFT:GOLD,22);
        if(chart) {plot(h,g,app);return;}
        var latest=app.latestEquipmentLap();if(reference!=null) h.text("引用圈已选为 A；本页只读，明确选择另一圈为 B",60,220,17,GOLD,true);
        else if(!receivedMode) h.smallButton(60,194,550,38,latest==null?"本场尚无带来源的有效完整圈":"收录最近有效完整圈 · "+shortId(latest)+" · "+number(latest.lap().duration())+" s",()->{if(latest==null) app.notifyUser("先用当前版本完成有效计时圈；旧圈不会按当前装备补写来源。");else if(check(app)) {app.captureEquipmentLap(latest);refresh(app);}});
        else h.text("收到的提供者记录 · 不写成本机测量",60,220,19,GOLD,true);
        h.text("A "+(a==null?"未选":origin(aShare)+shortId(a))+"  B "+(b==null?"未选":origin(bShare)+shortId(b)),650,220,15,INK);
        String warning=receivedMode?app.receivedEquipmentLaps.warning():app.equipmentLaps.warning();h.wrapped(warning.isEmpty()?(receivedMode?"收到库 ":"本机库 ")+visible.size()+" / 8 份；各库独立，接收不安装或授权，署名仅为声明。":warning,60,253,1154,14,GOLD,22);
        for(int i=0;i<visible.size();i++) {var entry=visible.get(i);var row=entry.lap();var share=entry.share();int y=286+48*i;
            h.text(shortId(row)+" · "+row.lap().conditions().trackId()+" · "+number(row.lap().duration())+" s",60,y+16,16,INK);
            h.text((share==null?"本机 · ":"收到 · "+cut(share.author(),12)+" · ")+(row.source().installed()==null?"来源未声明 · ":"")+cut(Tuning.performanceDescription(row.lap().conditions().performance()),36),60,y+36,12,SOFT);
            h.smallButton(690,y,108,36,row==a?"A 已选择":"选为 A",()->{if(check(app)) {a=row;aShare=share;resetComparison(null);}});h.smallButton(810,y,108,36,row==b?"B 已选择":"选为 B",()->{if(check(app)) {b=row;bShare=share;resetComparison(null);}});
            h.smallButton(930,y,126,36,"来源与范围",()->details(h,row,share));if(reference==null) h.smallButton(1068,y,146,36,"移除此份",()->{if(check(app)) h.review("移除独立装备圈？",List.of(shortId(row)+" · "+number(row.lap().duration())+" s","仅移除此库的完整圈与来源，其他记录库、遥测、幽灵、装备和成绩保持。"),"明确移除此份",()->{if(check(app)) {if(share==null) app.equipmentLaps.remove(row);else app.receivedEquipmentLaps.remove(share);refresh(app);}});});
        }
        if(visible.isEmpty()) h.wrapped(receivedMode?"可读取玩家提供的独立装备圈文件，先预览完整内容，再明确收录；不转为本机测量。":"先在车库安装装备并微调，再完成计时挑战有效整圈。返回此页明确收录；普通旧遥测继续在原页查看。",60,330,1100,20,SOFT,32);
        if(reference!=null) {h.smallButton(60,662,350,31,"重选原引用为 A",()->{if(check(app)) {a=reference.recording();aShare=reference;resetComparison(null);}});h.smallButton(430,662,350,31,receivedMode?"查看本机收录":"查看收到记录",()->{if(check(app)) {receivedMode=!receivedMode;rows();}});h.smallButton(800,662,414,31,"查看完整原引用圈来源",()->details(h,reference.recording(),reference));h.text("引用圈不写入本机或收到库；仅从已有记录选择 B 作只读比较。",60,720,16,GOLD);return;}
        h.smallButton(60,662,350,31,"导出所选 A 完整装备圈",()->{if(check(app)) export(h,app);});h.smallButton(430,662,350,31,receivedMode?"查看本机收录":"查看收到记录",()->{if(check(app)) {receivedMode=!receivedMode;rows();}});h.smallButton(800,662,414,31,"读取装备圈交换文件",()->importFile(h,app));
        boolean awaiting=receivedMode?app.receivedEquipmentLaps.pending()!=null:app.equipmentLaps.pending()!=null;
        if(awaiting) {h.smallButton(60,704,350,38,"重试同份待存记录",()->{if(receivedMode) app.receivedEquipmentLaps.retry();else app.equipmentLaps.retry();refresh(app);});h.smallButton(430,704,350,38,"明确取消待存",()->{if(receivedMode) app.receivedEquipmentLaps.cancelPending();else app.equipmentLaps.cancelPending();refresh(app);});}
        h.smallButton(800,704,414,38,"修复文件后重新读取当前库",()->{if(receivedMode) app.receivedEquipmentLaps.reload();else app.equipmentLaps.reload();refresh(app);});
        h.text("已存圈不证明收益或因果；不同路线、车型、物理或操作身份不进入同一比较。",60,755,13,SOFT);
    }
    private void compare(HudRenderer h) {
        if(a==null || b==null) {h.review("先明确选择两份圈",List.of("每侧选择一份完整记录；不自动挑最快圈或最近圈。"),"返回记录库",()->{});return;}
        if(a.id().equals(b.id())) {h.review("请选择两份不同记录",List.of("同一圈的本机与收到副本也不能当作两份独立驾驶记录。"),"返回记录库",()->{});return;}
        var issues=EquipmentLap.differences(a,b);if(!issues.isEmpty()) {h.review("这两圈条件不同",issues,"返回记录库",()->{});return;}
        if(comparison==null) resetComparison(TelemetryComparison.equipment(a,b));
        distance=0;
        chart=true;
    }
    private void plot(HudRenderer h,Graphics2D g,FormulaGame app) {
        h.text("A "+(reportView && pairALabel!=null?pairALabel:origin(aShare))+shortId(a)+" · "+number(a.lap().duration())+" s",60,198,17,A,true);h.text("B "+(reportView && pairBLabel!=null?pairBLabel:origin(bShare))+shortId(b)+" · "+number(b.lap().duration())+" s",470,198,17,B,true);h.text(String.format(Locale.ROOT,"整圈 A − B %+.3f s",a.lap().duration()-b.lap().duration()),870,198,17,INK,true);
        h.smallButton(60,211,350,32,"查看 A 冻结来源与实际参数",()->details(h,a,aShare));h.smallButton(430,211,350,32,"查看 B 冻结来源与实际参数",()->details(h,b,bShare));
        var shown=comparison;
        float length=shown.a().conditions().length();
        h.smallButton(800,211,414,32,"按圈长百分比移动游标",()->h.editNumber("游标位置 · 圈长百分比",
                Math.round(distance/length*100),0,100,"%",value->{
                    if(open && chart && comparison==shown) distance=length*(value/100f);
                    return true;
                }));
        if(controlPlot==null) controlPlot=buildControlPlot();
        for(int channel=0;channel<CONTROL_LABELS.size();channel++) {
            int top=PLOT_TOP+PLOT_STEP*channel;
            var lines=controlPlot.get(channel);
            h.text(CONTROL_LABELS.get(channel),60,top+24,15,INK);
            h.text(channel==0?"km/h":"%",60,top+47,12,SOFT);
            drawPlotBackground(h,g,top);
            h.text(Integer.toString(Math.round(lines.max())),944,top+12,11,SOFT);
            h.text(Integer.toString(Math.round(lines.min())),944,top+PLOT_HEIGHT,11,SOFT);
            g.setStroke(TRACE_STROKE);g.setColor(A);g.draw(lines.a());g.setColor(B);g.draw(lines.b());
            drawPlotMarkers(g,top);
            if(channel==2 && interval!=null) {
                drawBrakeMarkers(g,interval.a().braking(),top+12,A);
                drawBrakeMarkers(g,interval.b().braking(),top+PLOT_HEIGHT-12,B);
            }
        }
        if(cursorPoint==null || cursorPoint.distance()!=distance) cursorPoint=shown.at(distance);
        var point=cursorPoint;
        if(timeDelta==null) {
            timeDelta=shown.timeDeltaTrace(interval);
            if(interval==null) {
                if(fullTimeDeltaPath==null) fullTimeDeltaPath=buildDeltaPath(timeDelta);
                timeDeltaPath=fullTimeDeltaPath;
            } else timeDeltaPath=buildDeltaPath(timeDelta);
        }
        double cursorDelta=timeDelta.at(point);
        drawTimeDelta(h,g,PLOT_TOP+PLOT_STEP*CONTROL_LABELS.size());
        h.text(String.format(Locale.ROOT,"位置 %.0f m",distance),990,284,16,INK,true);
        h.text(String.format(Locale.ROOT,"到此位置 A − B %+.3f s",point.delta()),990,315,15,SOFT);
        h.text(interval==null?"用时差为正时，A 用时更长":String.format(Locale.ROOT,"自区间起点 %+.3f s",cursorDelta),990,342,14,TIME_DELTA);
        for(int i=0;i<3;i++) h.text(String.format(Locale.ROOT,"S%d %+.3f s",i+1,a.lap().sectors().get(i)-b.lap().sectors().get(i)),990,365+i*21,15,SOFT);
        drawSegmentShortcuts(h,shown.segmentExtremes());
        h.text("0 m",140,653,12,SOFT);h.text(Math.round(a.lap().conditions().length())+" m",880,653,12,SOFT);
        drawInterval(h,app);
    }
    private int plotX(float meters) {
        return PLOT_X+Math.round(meters/comparison.a().conditions().length()*PLOT_WIDTH);
    }
    private static int plotY(int top,double value,double min,double max) {
        double fraction=Math.max(0,Math.min(1,(value-min)/(max-min)));
        return top+PLOT_HEIGHT-(int)Math.round(fraction*PLOT_HEIGHT);
    }
    private void drawPlotBackground(HudRenderer h,Graphics2D g,int top) {
        h.panel(PLOT_X,top,PLOT_WIDTH,PLOT_HEIGHT,new Color(0x1d3540));
        if(interval!=null) {
            g.setColor(INTERVAL_FILL);
            int x=plotX(interval.from());
            g.fillRect(x,top,plotX(interval.to())-x,PLOT_HEIGHT);
        }
        g.setColor(SOFT);
        g.setStroke(new BasicStroke(1));
        for(int grid=0;grid<=3;grid++) drawVertical(g,PLOT_X+PLOT_WIDTH*grid/3,top);
    }
    private void drawPlotMarkers(Graphics2D g,int top) {
        g.setStroke(new BasicStroke(1));
        g.setColor(GOLD);
        if(intervalStart!=null) drawVertical(g,plotX(intervalStart),top);
        if(intervalEnd!=null) drawVertical(g,plotX(intervalEnd),top);
        g.setColor(INK);
        drawVertical(g,plotX(distance),top);
    }
    private static void drawVertical(Graphics2D g,int x,int top) {
        g.drawLine(x,top,x,top+PLOT_HEIGHT);
    }
    private List<ControlLine> buildControlPlot() {
        float peak=20;
        for(var point:comparison.plot()) peak=Math.max(peak,Math.max(point.a().driving().speed(),point.b().driving().speed())*3.6f);
        peak=(float)Math.ceil(peak/20)*20;
        var lines=new ArrayList<ControlLine>(CONTROL_LABELS.size());
        for(int channel=0;channel<CONTROL_LABELS.size();channel++) {
            int top=PLOT_TOP+PLOT_STEP*channel;
            float min=channel==1 || channel==3?-100:0,max=channel==0?peak:100;
            lines.add(new ControlLine(min,max,controlPath(channel,false,top,min,max),controlPath(channel,true,top,min,max)));
        }
        return List.copyOf(lines);
    }
    private Path2D controlPath(int channel,boolean reference,int top,float min,float max) {
        var path=new Path2D.Float();
        for(var sample:comparison.plot()) {
            var driving=(reference?sample.b():sample.a()).driving();
            float value=switch(channel) {
                case 0->driving.speed()*3.6f;
                case 1->driving.throttle()*100;
                case 2->driving.brake()*100;
                default->driving.steer()*100;
            };
            int x=plotX(sample.distance()),y=plotY(top,value,min,max);
            if(sample.distance()==0) path.moveTo(x,y);
            else path.lineTo(x,y);
        }
        return path;
    }
    private Path2D buildDeltaPath(TelemetryComparison.DeltaTrace trace) {
        var path=new Path2D.Float();
        int top=PLOT_TOP+PLOT_STEP*CONTROL_LABELS.size();
        double extent=trace.extent();
        boolean first=true;
        for(var sample:trace.points()) {
            int x=plotX(sample.distance()),y=plotY(top,sample.seconds(),-extent,extent);
            if(first) path.moveTo(x,y);
            else path.lineTo(x,y);
            first=false;
        }
        return path;
    }
    private void drawTimeDelta(HudRenderer h,Graphics2D g,int top) {
        double extent=timeDelta.extent();
        h.text("用时差",60,top+24,15,TIME_DELTA);
        h.text("A − B · s",60,top+47,12,SOFT);
        drawPlotBackground(h,g,top);
        h.text(String.format(Locale.ROOT,"%+.2f",extent),944,top+12,11,SOFT);
        h.text("0",944,top+PLOT_HEIGHT/2+4,11,SOFT);
        h.text(String.format(Locale.ROOT,"%+.2f",-extent),944,top+PLOT_HEIGHT,11,SOFT);
        g.setColor(ZERO_LINE);
        g.drawLine(PLOT_X,top+PLOT_HEIGHT/2,PLOT_X+PLOT_WIDTH,top+PLOT_HEIGHT/2);
        g.setColor(TIME_DELTA);
        g.setStroke(TRACE_STROKE);
        g.draw(timeDeltaPath);
        drawPlotMarkers(g,top);
    }
    private void drawBrakeMarkers(Graphics2D g,TelemetryComparison.BrakeReadout value,int y,Color color) {
        if(value==null) return;
        g.setColor(color);
        if(value.firstPress()!=null) drawBrakeMarker(g,value.firstPress(),y,1);
        if(value.lastRelease()!=null) drawBrakeMarker(g,value.lastRelease(),y,-1);
    }
    private void drawBrakeMarker(Graphics2D g,TelemetryComparison.Brake point,int y,int direction) {
        int x=plotX(point.distance());
        g.fillPolygon(new int[]{x-5,x+5,x},new int[]{y-5*direction,y-5*direction,y+5*direction},3);
    }
    private void markInterval(boolean start) {
        Float other=start?intervalEnd:intervalStart;
        if(other!=null && (start?distance>=other:distance<=other)) {
            intervalNotice="终点须在起点之后。请移动图表游标，再标记位置。";
            return;
        }
        if(start) intervalStart=distance;
        else intervalEnd=distance;
        if(intervalStart!=null && intervalEnd!=null) updateInterval();
        intervalNotice="";
    }
    private void updateInterval() {
        if(interval!=null && interval.from()==intervalStart && interval.to()==intervalEnd) return;
        interval=comparison.interval(intervalStart,intervalEnd);
        intervalPractice=null;
        intervalMap=null;
        timeDelta=null;
        timeDeltaPath=null;
    }
    private void selectSegment(TelemetryComparison.Segment segment) {
        intervalStart=segment.from();
        intervalEnd=segment.to();
        distance=segment.to();
        updateInterval();
        intervalNotice="";
    }
    private void drawSegmentShortcuts(HudRenderer h,TelemetryComparison.SegmentExtremes extremes) {
        h.text(String.format(Locale.ROOT,"%d 等距段 · |差| > %.2f s",TelemetryComparison.ANALYSIS_SEGMENTS,
                TelemetryComparison.MIN_SEGMENT_DELTA_SECONDS),990,431,13,SOFT);
        if(extremes.mostTimeLost()==null) h.text("A 无多用段",990,462,13,SOFT);
        else h.smallButton(990,440,109,34,"A 多用最多",()->selectSegment(extremes.mostTimeLost()));
        if(extremes.mostTimeGained()==null) h.text("A 无省时段",1106,462,13,SOFT);
        else h.smallButton(1106,440,108,34,"A 省时最多",()->selectSegment(extremes.mostTimeGained()));
    }
    private void clearInterval() {
        intervalStart=intervalEnd=null;
        interval=null;
        intervalPractice=null;
        intervalMap=null;
        timeDelta=null;
        timeDeltaPath=null;
        intervalNotice="";
    }

    private void openIntervalPractice(HudRenderer h,TelemetryComparison.Interval selected) {
        if(!open || !chart || interval!=selected) return;
        if(intervalPractice==null) intervalPractice=new EquipmentSectionPracticeSession(a,b,selected.from(),selected.to());
        var practice=intervalPractice;
        h.reviewEquipmentSection(practice,()->open && chart && interval==selected && intervalPractice==practice);
    }
    private void drawInterval(HudRenderer h,FormulaGame app) {
        h.smallButton(60,686,275,43,markLabel("设为区间起点",intervalStart),()->markInterval(true));
        h.smallButton(353,686,275,43,markLabel("设为区间终点",intervalEnd),()->markInterval(false));
        String hint="点图或输入位置移动游标，再标记区间起点和终点。中心线距离及边界插值均为近似。";
        if(intervalStart!=null || intervalEnd!=null) h.smallButton(646,686,96,43,"清除区间",this::clearInterval);
        if(interval!=null) {
            var selected=interval;
            h.text("所选区间",990,498,16,GOLD,true);
            h.text(String.format(Locale.ROOT,"%.1f – %.1f m",selected.from(),selected.to()),990,521,15,INK);
            h.text("A  "+number(selected.a().elapsed())+" s",990,545,15,A);
            h.text("B  "+number(selected.b().elapsed())+" s",990,568,15,B);
            h.text(String.format(Locale.ROOT,"区间 A − B %+.3f s",selected.delta()),990,591,15,INK,true);
            h.text("制动 A "+brakingTime(selected.a().braking())+" / B "+brakingTime(selected.b().braking()),990,616,14,INK);
            h.text("滑行 A "+coastingTime(selected.a().coasting())+" / B "+coastingTime(selected.b().coasting()),990,640,14,INK);
            h.smallButton(862,686,218,43,"查看区间读数",()->intervalDetails(h,selected));
            h.smallButton(1094,686,120,43,"走线叠图",()->openIntervalMap(h,app,selected));
            h.smallButton(752,686,92,43,"练本段",()->openIntervalPractice(h,selected));
            hint="制动图 ▼ 首次按下，▲ 末次松开；A 在上，B 在下。区间用时含停顿与倒退。";
        } else h.wrapped("装备设置和驾驶操作都可能影响两圈差异。",990,506,214,15,SOFT,24);
        h.text(intervalNotice.isEmpty()?hint:intervalNotice,60,751,15,intervalNotice.isEmpty()?SOFT:GOLD);
    }
    private static String markLabel(String label,Float value) {
        return value==null?label:String.format(Locale.ROOT,"%s · %.1f m",label,value);
    }
    private void openIntervalMap(HudRenderer h,FormulaGame app,TelemetryComparison.Interval selected) {
        if(!open || !chart || interval!=selected) return;
        var shown=comparison;
        var road=app.equipmentLapTrack(shown.a().conditions());
        if(intervalMap==null || !intervalMap.matchesRoad(road)) {
            intervalMap=new EquipmentIntervalMap(shown,road,selected.from(),selected.to(),distance,value->{
                if(open && chart && comparison==shown && interval==selected) distance=value;
            },()->openIntervalPractice(h,selected));
        }else intervalMap.resetCursor(distance);
        h.reviewEquipmentInterval(intervalMap);
    }
    private void intervalDetails(HudRenderer h,TelemetryComparison.Interval selected) {
        h.review("两圈区间读数",List.of(
                String.format(Locale.ROOT,"中心线 %.1f – %.1f m；区间 A − B %+.3f s。",selected.from(),selected.to(),selected.delta()),
                intervalReadout("A "+shortId(a),selected.a()),intervalReadout("B "+shortId(b),selected.b()),
                brakingReadout("A",selected.a().braking()),brakingReadout("B",selected.b().braking()),
                coastingReadout("A",selected.a().coasting()),coastingReadout("B",selected.b().coasting()),
                "速度按 km/h 显示；峰值制动读取该区间内的车辆制动指令。",
                "入口与出口按中心线距离插值。最低速度及峰值制动同时检查两端之间的原样本。",
                "制动从指令首次达到 10% 开始，到首次降至 5% 或以下结束，只计持续至少 0.1 s 的制动段。标记使用原样本位置，倒退可能使标记落在涂色范围外。",
                "滑行选取前进且两端原样本均符合条件的时间。阈值为油门 0–2%、制动不超过 2%、转向幅度不超过 10%、车速至少 5 km/h。",
                "滑行速度变化为各段有符号的速度差之和。路线、驾驶和车辆状态都可能影响读数。"),"返回区间曲线",()->{});
    }
    private static String coastingTime(TelemetryComparison.Coasting value) {
        return value==null?"无":number(value.seconds())+" s";
    }
    private static String brakingTime(TelemetryComparison.BrakeReadout value) {
        return value==null?"无":number(value.seconds())+" s";
    }
    private static String brakingReadout(String label,TelemetryComparison.BrakeReadout value) {
        if(value==null) return label+" · 无符合条件的制动段。";
        String entry=value.activeAtEntry()?"；起点已在制动":"";
        String exit=value.activeAtExit()?"；终点仍在制动":"";
        return String.format(Locale.ROOT,"%s · 制动 %d 段，区间内合计 %.3f s%s%s。\n首次按下 %s；末次松开 %s。",
                label,value.windows(),value.seconds(),entry,exit,brakePoint(value.firstPress()),brakePoint(value.lastRelease()));
    }
    private static String brakePoint(TelemetryComparison.Brake point) {
        return point==null?"区间内无记录":String.format(Locale.ROOT,"%.1f m（圈内 %.3f s）",point.distance(),point.time());
    }
    private static String coastingReadout(String label,TelemetryComparison.Coasting value) {
        if(value==null) return label+" · 无符合低转向滑行条件的记录。";
        return String.format(Locale.ROOT,"%s · 滑行 %d 段，合计 %.3f s、前进 %.1f m。\n速度 %.1f–%.1f km/h，累计变化 %+.1f km/h。",
                label,value.windows(),value.seconds(),value.forwardMetres(),value.minimumSpeed()*3.6,value.maximumSpeed()*3.6,value.speedChange()*3.6);
    }
    private static String intervalReadout(String label,TelemetryComparison.Traversal t) {
        return String.format(Locale.ROOT,"%s · %.3f s；入口 / 最低 / 出口 %.1f / %.1f / %.1f km/h；峰值制动 %.0f%%。",
                label,t.elapsed(),t.entrySpeed()*3.6,t.minimumSpeed()*3.6,t.exitSpeed()*3.6,t.peakBrake()*100);
    }
    static List<String> detailsLines(EquipmentLap value,EquipmentLapShare share) {
        var lines=new ArrayList<String>();if(share!=null) {lines.add("提供者署名声明："+share.author());lines.add("原说明："+share.notes());}var source=value.source();lines.add("完整圈 "+value.id()+" · "+number(value.lap().duration())+" s");lines.add("车型 "+source.vehicle()+"；物理 "+source.physics()+"；路线 "+value.lap().conditions().trackId());lines.add("操作身份 "+value.lap().conditions().controls());
        for(var field:PartsCatalog.Field.values()) lines.add(String.format(Locale.ROOT,"本圈实际 %s：%.4f%%",field.title,field.ratio(source.tuning())*100));
        if(source.installed()==null) lines.add("开赛时未记录可用的已装装备；不根据当前目录补写。实际参数保持原精度。");else {lines.add("开赛时冻结的装备定义与安装基准；基准可与本圈微调不同。");for(var slot:PartsCatalog.Slot.values()) {var part=source.installed().definitions().get(slot);lines.add(part.name()+"（"+part.id()+"）；点数 "+part.points());lines.add("部件说明："+part.purpose()+"；代价："+part.tradeoff());for(var field:slot.fields()) {var range=part.settings().get(field);lines.add(field.title+" 允许 "+range.min()+"–"+range.max()+"%，默认 "+range.initial()+"%，安装基准 "+source.installed().build().slots().get(slot).values().get(field)+"%。");}}}
        lines.add("来源与署名是提供者声明，不是作者认证或装备收益证明；查看不会应用任何设置。");return List.copyOf(lines);
    }
    private static void details(HudRenderer h,EquipmentLap value,EquipmentLapShare share) {h.review("装备圈的原始来源",detailsLines(value,share),"返回装备整圈",()->{});}
    private void importFile(HudRenderer h,FormulaGame app) {
        h.prompt("装备圈交换 JSON 完整路径",exportDefault(app).toString(),512,text->{try {previewImport(h,app,EquipmentLapShareCodec.read(Path.of(text.strip())));}catch(java.io.IOException | RuntimeException error) {app.notifyUser("交换文件无法读取；请核对类型、大小、字段与内容校验，已有记录保持。");}});
    }
    private void previewImport(HudRenderer h,FormulaGame app,EquipmentLapShare value) {
        var lines=new ArrayList<>(detailsLines(value.recording(),value));lines.add("仅收录收到记录库；不写本机遥测、成绩、试车报告、部件或车库。不会安装地图或授予驾驶资格。");
        h.review("接收提供者的完整装备圈？",lines,"只存收到记录库",()->{app.receiveEquipmentLap(value);receivedMode=true;refresh(app);});
    }
    private void export(HudRenderer h,FormulaGame app) {
        if(a==null) {app.notifyUser("先明确选择要导出的 A 圈；导出保留完整原始内容。");return;}
        var frozen=a;var original=aShare;
        if(original!=null) {exportPreview(h,app,original);return;}
        String name=app.store.profile.playerName;if(!PartsCatalog.text(name,40) || name.isBlank()) name="玩家";
        h.prompt("本次提供者署名声明",name,40,author->h.editText("分享说明（可换行）","","",320,notes->{try {exportPreview(h,app,new EquipmentLapShare(1,author,notes,frozen));}catch(IllegalArgumentException error) {app.notifyUser("署名或说明无效，原始装备圈保持。");}}));
    }
    private void exportPreview(HudRenderer h,FormulaGame app,EquipmentLapShare value) {
        var lines=new ArrayList<>(detailsLines(value.recording(),value));lines.add("收到记录再次导出时保留原署名与说明；内容校验只检测变化，不认证作者或真实驾驶。");
        h.review("导出完整装备圈？",lines,"填写独立导出路径",()->h.promptExportPath(
            "导出到玩家资料目录以外",exportDefault(app),(path,replace)->app.exportEquipmentLap(value,path,replace)));
    }
    private static Path exportDefault(FormulaGame app) {return app.store.directory().toAbsolutePath().getParent().resolve("equipment-lap-export.json");}
    private static String shortId(EquipmentLap value) {return value.id().substring(0,8);}private static String number(double value) {return String.format(Locale.ROOT,"%.3f",value);}private static String cut(String v,int n) {return v.length()<=n?v:v.substring(0,n-1)+"…";}
}
