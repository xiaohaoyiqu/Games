package com.f1car.ui;

import com.f1car.model.*;
import com.f1car.persistence.TuningTraceStore;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Locale;

/** One cached pair of paths; report switches and successful replacements invalidate by trace identity. */
final class TuningTraceHud {
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xa2babd),A=new Color(0xffb267),B=new Color(0x63e6bc);
    private static final int X=165,W=1025,TOP=423,ROW=51,H=31;
    private String reportId="";
    private int selected,reference;
    private TuningTrace cachedA,cachedB;
    private String summaryA="",summaryB="";
    private Path2D[] paths;
    private double duration;
    private float speedScale;
    private boolean distanceAxis,cachedDistance;
    private double minimum;
    private TuningExperiment.Snapshot queriedReport;
    private long queriedRevision=-1;
    private int queriedSelected=-1,queriedReference=-1;
    private TuningTrace selectedTrace,referenceTrace;
    private TrialTraceShare queriedReceived;
    private FrozenTuningTraces queriedFrozen;
    private boolean citationMode;
    void render(HudRenderer h,Graphics2D g,TuningExperiment.Snapshot report,TuningTraceStore store) {
        render(h,g,report,store,null);
    }
    void render(HudRenderer h,Graphics2D g,TuningExperiment.Snapshot report,TuningTraceStore store,TrialTraceShare received) {
        render(h,g,report,store,received,-1);
    }
    void render(HudRenderer h,Graphics2D g,TuningExperiment.Snapshot report,TuningTraceStore store,TrialTraceShare received,int citationIndex) {
        render(h,g,report,store,received,citationIndex,null);
    }
    void renderFrozen(HudRenderer h,Graphics2D g,FrozenTuningTraces frozen) {render(h,g,frozen.report(),null,null,-1,frozen);}
    void clearFrozen() {queriedFrozen=null;queriedReceived=null;queriedReport=null;cachedA=cachedB=selectedTrace=referenceTrace=null;paths=null;reportId="";summaryA=summaryB="";}
    private void render(HudRenderer h,Graphics2D g,TuningExperiment.Snapshot report,TuningTraceStore store,TrialTraceShare received,int citationIndex,FrozenTuningTraces frozen) {
        String warning=frozen==null && received==null?store.warning:"";
        long revision=frozen==null?store.revision():-1;
        if(citationMode!=(citationIndex>=0)) {citationMode=citationIndex>=0;cachedA=null;}
        int count=report.runs().size();
        if(!report.id().equals(reportId)) {reportId=report.id();selected=Math.max(0,count-1);reference=0;}
        if(count==0) {h.text("尚无成功测量；完成一组后可查看连续记录。",60,401,20,SOFT);return;}
        selected=Math.min(selected,count-1);reference=Math.min(reference,count-1);
        if(frozen!=null && queriedFrozen!=frozen && frozen.find(selected)==null && !frozen.traces().isEmpty()) selected=frozen.traces().get(0).index();
        if(citationIndex>=0) selected=reference=citationIndex;
        h.smallButton(60,668,355,29,distanceAxis?"横轴：路线位置 · 点击切换":"横轴：测量时间 · 点击切换",()->distanceAxis=!distanceAxis);
        if(citationIndex<0) {for(int i=0;i<count;i++) {int index=i;var run=report.runs().get(i);h.smallButton(60+i*145,345,135,32,(i==selected?"● ":"")+(i+1)+"组 "+run.slot()+" #"+run.attempt(),()->selected=index);}
        h.smallButton(943,345,270,32,"对照："+(reference+1)+"组 · 点击切换",()->reference=(reference+1)%count);}
        else h.text("固定引用第 "+(citationIndex+1)+" 组 · 保留原时间与采样间隔",60,367,16,INK);
        if(queriedReport!=report || queriedReceived!=received || queriedFrozen!=frozen || queriedRevision!=revision || queriedSelected!=selected || queriedReference!=reference) {
            queriedReceived=received;queriedFrozen=frozen;
            queriedReport=report;queriedRevision=revision;queriedSelected=selected;queriedReference=reference;
            selectedTrace=frozen!=null?frozen.find(selected):received==null?store.find(report,selected):received.find(selected);referenceTrace=reference==selected?null:frozen!=null?frozen.find(reference):received==null?store.find(report,reference):received.find(reference);
        }
        var a=selectedTrace;var b=referenceTrace;
        if(a==null) {h.text("所选组没有连续记录；旧报告不补造过程数据。",60,423,22,SOFT);h.text(warning,60,464,16,A);return;}
        if(distanceAxis && (a.positions()==null || b!=null && b.positions()==null)) {h.text("旧记录没有位置；请切回时间轴，或重新完成本组测量。",60,450,18,SOFT);return;}
        if(paths==null || cachedA!=a || cachedB!=b || cachedDistance!=distanceAxis) build(a,b);
        h.text("橙实线 "+(selected+1)+"组 #"+report.runs().get(selected).attempt()+" · "+summaryA,60,400,13,A);
        h.text(b==null?"绿虚线：此组无记录或与所选相同": "绿虚线 "+(reference+1)+"组 #"+report.runs().get(reference).attempt()+" · "+summaryB,651,400,13,B);
        String[] labels={"车速 km/h","油门 %","制动 %","转向指令 %"};
        for(int channel=0;channel<4;channel++) {
            int y=TOP+channel*ROW;float min=channel==1 || channel==3?-100:0,max=channel==0?speedScale:100;
            h.text(labels[channel],60,y+16,13,INK);h.text(String.format(Locale.ROOT,"%.0f",max),X-36,y+9,10,SOFT);h.text(String.format(Locale.ROOT,"%.0f",min),X-36,y+H,10,SOFT);
            g.setColor(new Color(0x28454c));g.setStroke(new BasicStroke(1));g.drawRect(X,y,W,H);
            if(min<0) g.drawLine(X,y+H/2,X+W,y+H/2);
            g.setStroke(new BasicStroke(1.8f));g.setColor(A);g.draw(paths[channel]);if(a.samples().size()==1) {var point=paths[channel].getCurrentPoint();if(point!=null) g.fillOval((int)Math.round(point.getX())-2,(int)Math.round(point.getY())-2,4,4);}if(b!=null) {g.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{6,4},0));g.setColor(B);g.draw(paths[channel+4]);}
        }
        h.text(String.format(Locale.ROOT,"%.1f %s",minimum,distanceAxis?"m":"s"),X,TOP+3*ROW+H+20,12,SOFT);h.text(String.format(Locale.ROOT,"%.1f %s",minimum+duration,distanceAxis?"m":"s"),X+W-90,TOP+3*ROW+H+20,12,SOFT);
        h.text(distanceAxis?"按同一路线投影位置对照；倒退、投影异常和超过 75 ms 的间隔断线，不插造计时差。":"共同时间轴从各组测量窗口起算；不同位置可能同一时刻，不能据此判断时间损失。",60,649,13,SOFT);
        h.text("实际帧末读数；旧记录不补造位置，不推断因果或改装收益。",60,720,13,SOFT);
        if(!warning.isEmpty()) h.text(warning,60,743,13,A);
    }
    private static String summary(TuningTrace trace) {
        return String.format(Locale.ROOT,"%d 点 · 首点 %.3f s · 最大间隔 %.0f ms",trace.samples().size(),trace.samples().get(0).seconds(),trace.maximumGap()*1000)+(trace.version()==3?" · 精确参数绑定":" · 旧取整参数绑定");
    }
    private void build(TuningTrace a,TuningTrace b) {
        cachedA=a;cachedB=b;summaryA=summary(a);summaryB=b==null?"":summary(b);cachedDistance=distanceAxis;paths=new Path2D[8];minimum=citationMode?a.samples().get(0).seconds():0;duration=Math.max(.05,Math.max(a.duration(),b==null?0:b.duration())-minimum);
        if(distanceAxis) {double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;for(var trace:b==null?new TuningTrace[]{a}:new TuningTrace[]{a,b}) for(var p:trace.positions()) if(p.aligned()==1) {low=Math.min(low,p.route());high=Math.max(high,p.route());}minimum=Double.isFinite(low)?low:0;duration=Double.isFinite(high)?Math.max(1,high-minimum):1;}
        float speed=0;for(var trace:b==null?new TuningTrace[]{a}:new TuningTrace[]{a,b}) for(var sample:trace.samples()) speed=Math.max(speed,sample.speed()*3.6f);
        speedScale=Math.max(20,(float)Math.ceil(speed/20)*20);
        for(int slot=0;slot<2;slot++) for(int channel=0;channel<4;channel++) {
            var trace=slot==0?a:b;var path=new Path2D.Float();paths[slot*4+channel]=path;if(trace==null) continue;
            double previous=-1,previousRoute=Double.NEGATIVE_INFINITY;boolean previousValid=false;float min=channel==1 || channel==3?-100:0,max=channel==0?speedScale:100;
            for(int i=0;i<trace.samples().size();i++) {var s=trace.samples().get(i);var position=distanceAxis?trace.positions().get(i):null;
                double axis=distanceAxis?position.route():s.seconds();boolean valid=!distanceAxis || position.aligned()==1;
                float value=switch(channel) {case 0->s.speed()*3.6f;case 1->s.throttle()*100;case 2->s.brake()*100;default->s.steer()*100;};
                float x=X+(float)((axis-minimum)/duration*W),y=TOP+channel*ROW+H-(value-min)/(max-min)*H;
                if(valid) {if(previous<0 || !previousValid || s.seconds()-previous>TuningTrace.CONNECTED_GAP || distanceAxis && axis<=previousRoute) {path.moveTo(x-.6,y);path.lineTo(x+.6,y);path.moveTo(x,y);} else path.lineTo(x,y);}
                previous=s.seconds();previousRoute=axis;previousValid=valid;
            }
        }
    }
}
