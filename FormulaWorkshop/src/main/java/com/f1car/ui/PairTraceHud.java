package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Explicitly opened frozen view; never consults a live trace library or rebuilds paths every frame. */
final class PairTraceHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),SELECTED=new Color(0xffb267),REFERENCE=new Color(0x63e6bc),PANEL=new Color(0x13242e);
    private static final Stroke SOLID=new BasicStroke(1.8f),DASHED=new BasicStroke(1.8f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{6,4},0);
    private static final int X=185,W=1025,TOP=285,ROW=66,H=42;
    private PairTraceView view;private Path2D[][] paths;private List<String> source=List.of(),summaries=List.of();
    private PairTraceCursor.Selection cursor;private List<String> cursorLines=List.of(),cursorDetails=List.of();
    private String historical="";private boolean recoveryView,comparisonView,receivedView;private List<List<String>> comparisonSources=List.of();
    private String comparisonHeading="";
    boolean showing() {return view!=null;}
    void open(PairExperiment.Snapshot report,int index) {historical="";recoveryView=comparisonView=receivedView=false;comparisonSources=List.of();var next=new PairTraceView(report,index,-1,PairTraceView.Axis.TIME);source=PairTrialsHud.source(report);build(next);}
    void openReceived(PairTrialShare share) {open(share.report(),0);receivedView=true;historical="收到的提供者声明 · 只读原过程，不属于本机测量";source=PairTrialSharingHud.details(share);}
    void openComparison(PairTraceView next,List<String> left,List<String> right) {
        if(next.sources().size()!=2) throw new IllegalArgumentException("Two original sources required");recoveryView=receivedView=false;comparisonView=true;historical=next.comparison()==null?"两侧各用自己的完整原快照；只读对照，不回滚、采用或重测。":"跨来源只读：两侧各用原窗口与提供者声明；相同组号或时间不证明相同条件。";
        comparisonSources=List.of(List.copyOf(left),List.copyOf(right));var lines=new java.util.ArrayList<>(left);lines.add("—— 绿侧独立原文 ——");lines.addAll(right);if(next.comparison()!=null) {lines.add("—— 跨来源背景差异 ——");lines.addAll(next.comparison().differences());}source=List.copyOf(lines);build(next);
    }
    void openRecovery(PairExperiment.Snapshot report) {open(report,0);recoveryView=true;historical="待存恢复候选 · 原计数 #"+report.lastAttempt()+" · 只读查看，尚未发布";var lines=new java.util.ArrayList<>(source);lines.add(0,historical);source=List.copyOf(lines);}
    void openHistory(PairTrialRevision revision) {
        open(revision.before(),revision.replacements().get(0).index());historical="重测前计数 #"+revision.before().lastAttempt()+" → #"+revision.afterAttempt()+" · 历史只读，不回滚当前报告";
        var lines=new java.util.ArrayList<>(source);lines.add(0,historical);for(var row:revision.replacements()) lines.add("原第 "+(row.index()+1)+" 组 #"+row.oldAttempt()+" 被成功试次 #"+row.newAttempt()+" 替换。");source=List.copyOf(lines);
        lines=new java.util.ArrayList<>(source);for(int i=0;i<revision.before().runs().size();i++) {var run=revision.before().runs().get(i);var value=run.measurement();lines.add("原第 "+(i+1)+" 组 · "+run.condition().title+" · #"+run.attempt()+"："+Double.toString(value.value())+" "+revision.before().conditions().exercise().unit+"；入口 "+Float.toString(value.entry())+" km/h，出口 "+Float.toString(value.exit())+" km/h。");}source=List.copyOf(lines);
    }
    void clear() {view=null;paths=null;source=List.of();summaries=List.of();historical="";comparisonHeading="";recoveryView=comparisonView=receivedView=false;comparisonSources=List.of();clearCursor();}
    private void clearCursor() {cursor=null;cursorLines=List.of();cursorDetails=List.of();}
    boolean click(float x,float y) {
        if(view==null || x<X || x>X+W) return false;
        boolean inside=false;for(int channel=0;channel<4;channel++) if(y>=TOP+channel*ROW && y<=TOP+channel*ROW+H) inside=true;
        if(!inside || view.axis()==PairTraceView.Axis.ROUTE && !view.routeAvailable()) return false;
        inspect(PairTraceCursor.at(view,view.minimum()+(x-X)/W*(view.maximum()-view.minimum())));return true;
    }
    private void step(int direction) {
        int count=view.source(0).run().trace().samples().size();
        int index=cursor!=null && cursor.readings().get(0).available()?Math.max(0,Math.min(count-1,cursor.readings().get(0).sampleIndex()+direction)):0;
        inspect(PairTraceCursor.atSample(view,index));
    }
    private void inspect(PairTraceCursor.Selection next) {
        var labels=new java.util.ArrayList<String>();var details=new java.util.ArrayList<String>();
        String unit=next.view().axis()==PairTraceView.Axis.TIME?"s":"m";
        details.add(next.requested()==null?"此原样本没有可对齐的位置；原时间与操作保持。":"请求横轴 "+next.requested()+" "+unit+"；下面各行是各自原样本，不是同步插值。");
        for(int slot=0;slot<next.readings().size();slot++) {
            var reading=next.readings().get(slot);String name=(slot==0?"所选":"对照")+"第 "+(reading.runIndex()+1)+" 次";
            if(next.view().comparison()!=null) {var origin=next.view().comparison().pick(slot).source();name=origin.label()+" · "+name;details.addAll(origin.declaration());}
            details.add(name+" · 本侧完整报告 "+reading.source().report().id()+"；原快照计数 #"+reading.source().report().lastAttempt()+"；原试次 #"+reading.source().run().attempt()+"；原完成毫秒值 "+reading.source().run().recordedAt()+"。");
            if(!reading.available()) {labels.add(name+" · "+reading.status().title);details.add(name+"："+reading.status().title+"；没有补造读数。");continue;}
            var sample=reading.sample();var position=reading.position();String route=position==null?"原位置缺失":position.aligned()!=1?"原位置未对齐":String.format(Locale.ROOT,"原路线 %.2f m",position.route());
            labels.add(String.format(Locale.ROOT,"%s · 样本 %d · t %.3f s · %s · %.1f km/h · 油门 %.1f%% · 制动 %.1f%% · 转向 %.1f%%",name,reading.sampleIndex()+1,sample.seconds(),route,sample.speed()*3.6f,sample.throttle()*100,sample.brake()*100,sample.steer()*100));
            details.add(name+" · 原试次 #"+reading.source().run().attempt()+" · 原样本 "+(reading.sampleIndex()+1)+" · "+reading.status().title);
            details.add("原时间 "+Double.toString(sample.seconds())+" s；原速度 "+Float.toString(sample.speed())+" m/s（"+Float.toString(sample.speed()*3.6f)+" km/h）。");
            details.add("原操作：油门 "+Float.toString(sample.throttle())+"；制动 "+Float.toString(sample.brake())+"；转向 "+Float.toString(sample.steer())+"。操作量不是轮胎力。");
            details.add(position==null?"没有原位置。":"原位置 x="+Float.toString(position.x())+" m，z="+Float.toString(position.z())+" m，路线="+Float.toString(position.route())+" m；对齐="+(position.aligned()==1?"是":"否")+"。");
        }
        details.add("近邻仅在已连接的原线段内选取；缺口、范围外和多次到达分别保留。读数不证明因果、装备收益或损失时间。");
        cursor=next;cursorLines=List.copyOf(labels);cursorDetails=List.copyOf(details);
    }
    private void build(PairTraceView next) {
        var built=new Path2D[next.series().size()][4];var labels=new java.util.ArrayList<String>();
        for(int slot=0;slot<next.series().size();slot++) {var series=next.series().get(slot);String origin=next.comparison()==null?"快照":""+next.comparison().pick(slot).source().label()+" · "+next.source(slot).report().id().substring(0,8)+" · 快照";labels.add(String.format(Locale.ROOT,"%s #%d · %d 次 · %s · #%d · %d 点 · 首点 %.3f s · 最大间隔 %.0f ms",origin,next.source(slot).report().lastAttempt(),series.index()+1,series.condition().title,series.attempt(),series.samples(),series.firstSeconds(),series.maximumGap()*1000));
            for(int channel=0;channel<4;channel++) {var path=new Path2D.Float();built[slot][channel]=path;float low=channel==1 || channel==3?-100:0,high=channel==0?next.speedMaximum():100;
                for(var point:series.channels().get(channel)) {double x=X+(point.axis()-next.minimum())/(next.maximum()-next.minimum())*W,y=TOP+channel*ROW+H-(point.value()-low)/(high-low)*H;if(point.connected()) path.lineTo(x,y);else {path.moveTo(x-.6,y);path.lineTo(x+.6,y);path.moveTo(x,y);}}
            }
        }
        comparisonHeading=next.comparison()==null?"":"橙 "+next.source(0).report().id().substring(0,8)+" · "+next.source(0).report().conditions().exercise().title+" / 绿 "+next.source(1).report().id().substring(0,8)+" · "+next.source(1).report().conditions().exercise().title+"；完整背景分别查看。";
        view=next;paths=built;summaries=List.copyOf(labels);clearCursor();
    }
    void render(HudRenderer h,Graphics2D g,FormulaGame app,BiConsumer<PairExperiment.Snapshot,Integer> retry) {
        var current=view;h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text(receivedView?"四条件 · 收到声明的原始过程":comparisonView?"四条件 · 两份原次曲线对照":recoveryView?"四条件 · 恢复候选原始过程":historical.isEmpty()?"四条件 · 本份原始过程":"四条件 · 重测前原始过程",60,107,30,INK,true);
        if(!historical.isEmpty()) h.text(historical,60,130,12,SELECTED);
        h.text(comparisonHeading.isEmpty()?"报告 "+current.report().id().substring(0,8)+" · "+current.report().conditions().exercise().title+"；本页固定打开时的报告，变化后需返回重新选择。":comparisonHeading,60,151,15,SOFT);
        h.smallButton(60,179,350,36,current.axis()==PairTraceView.Axis.TIME?"横轴：原测量时间 · 点击切换":"横轴：原路线位置 · 点击切换",()->{var axis=current.axis()==PairTraceView.Axis.TIME?PairTraceView.Axis.ROUTE:PairTraceView.Axis.TIME;if(axis==PairTraceView.Axis.ROUTE && !current.routeAvailable()) {app.notifyUser(current.routeRestriction());return;}build(current.withAxis(axis));});
        if(comparisonView) {
            h.smallButton(430,179,350,36,"核对橙侧已选原次完整来源",()->h.review("橙侧完整原次",comparisonSources.get(0),"返回两侧曲线",()->{}));h.smallButton(800,179,414,36,"核对绿侧已选原次完整来源",()->h.review("绿侧完整原次",comparisonSources.get(1),"返回两侧曲线",()->{}));
        } else {
        h.smallButton(430,179,350,36,"所选第 "+(current.selected()+1)+" 次 · 点击下一组",()->{int index=(current.selected()+1)%current.report().runs().size(),reference=current.reference()==index?-1:current.reference();build(new PairTraceView(current.report(),index,reference,current.axis()));});
        h.smallButton(800,179,414,36,current.reference()<0?"未选对照 · 明确选择另一原次":"对照第 "+(current.reference()+1)+" 次 · 点击切换",()->{int index=current.reference()+1;if(index==current.selected()) index++;if(index>=current.report().runs().size()) index=-1;build(new PairTraceView(current.report(),current.selected(),index,current.axis()));});
        }
        h.text("橙实线："+summaries.get(0),60,239,14,SELECTED);h.text(current.reference()<0?"绿虚线：未选对照。只看原试次，不计算条件收益或中位曲线。":"绿虚线："+summaries.get(1),60,264,14,REFERENCE);
        if(current.axis()==PairTraceView.Axis.ROUTE && !current.routeAvailable()) h.wrapped("所选组或对照组没有可用原位置，请切回时间轴。未绘制缺失路线数据。",60,390,1100,20,SOFT,30);
        else {
            var oldStroke=g.getStroke();var oldColor=g.getColor();Shape oldClip=g.getClip();String[] labels={"车速 km/h","油门 %","制动 %","转向指令 %"};
            try {for(int channel=0;channel<4;channel++) {int y=TOP+channel*ROW;float low=channel==1 || channel==3?-100:0,high=channel==0?current.speedMaximum():100;h.text(labels[channel],60,y+24,14,INK);h.text(String.format(Locale.ROOT,"%.0f",high),X-34,y+10,11,SOFT);h.text(String.format(Locale.ROOT,"%.0f",low),X-34,y+H,11,SOFT);g.setColor(new Color(0x28454c));g.setStroke(new BasicStroke(1));g.drawRect(X,y,W,H);if(low<0) g.drawLine(X,y+H/2,X+W,y+H/2);g.clipRect(X,y,W+1,H+1);for(int slot=0;slot<paths.length;slot++) {g.setStroke(slot==0?SOLID:DASHED);g.setColor(slot==0?SELECTED:REFERENCE);g.draw(paths[slot][channel]);}g.setClip(oldClip);}}
            finally {g.setStroke(oldStroke);g.setColor(oldColor);g.setClip(oldClip);}
            if(cursor!=null) {var oldCursorStroke=g.getStroke();var oldCursorColor=g.getColor();try {for(int slot=0;slot<cursor.readings().size();slot++) {var reading=cursor.readings().get(slot);if(reading.axis()==null) continue;int x=(int)Math.round(X+(reading.axis()-current.minimum())/(current.maximum()-current.minimum())*W);g.setColor(slot==0?SELECTED:REFERENCE);g.setStroke(slot==0?SOLID:DASHED);for(int channel=0;channel<4;channel++) g.drawLine(x,TOP+channel*ROW,x,TOP+channel*ROW+H);}}finally {g.setStroke(oldCursorStroke);g.setColor(oldCursorColor);}}
            String unit=current.axis()==PairTraceView.Axis.TIME?"s":"m";h.text(String.format(Locale.ROOT,"%.2f %s",current.minimum(),unit),X,TOP+3*ROW+H+22,12,SOFT);h.text(String.format(Locale.ROOT,"%.2f %s",current.maximum(),unit),X+W-100,TOP+3*ROW+H+22,12,SOFT);
        }
        h.text(cursor==null?"点击任一曲线行查看原样本；也可用下方按钮逐个读取所选原次。":cursorLines.get(0),60,572,13,SELECTED);
        h.text(cursor==null?"每条曲线保留自己的原时间与位置；缺口和多次到达不补造为同步读数。":cursorLines.size()>1?cursorLines.get(1):"尚未选择另一原次作对照。",60,595,13,REFERENCE);
        h.wrapped(current.axis()==PairTraceView.Axis.TIME?"共同时间从各自原窗口起算；相同时间不代表相同位置。超过 75 ms 的间隔断线。":"按原路线位置对照；无对齐、倒退与超过 75 ms 的间隔断线，多次到达分别提示。",60,623,1154,13,SOFT,20);
        h.smallButton(60,649,250,32,"前一个原样本",()->step(-1));h.smallButton(324,649,250,32,"后一个原样本",()->step(1));h.smallButton(588,649,350,32,"本点完整原读数与定位说明",()->{if(cursor==null) app.notifyUser("请先点选曲线或用原样本按钮选择一个样本。");else h.review("本页原样本读数",cursorDetails,"返回原过程",()->{});});h.smallButton(952,649,262,32,"清除本页点选",this::clearCursor);
        h.text("原过程只作描述，不证明因果、交互或整圈收益；重测仍须核对当前报告和冻结背景。",60,697,12,SELECTED);
        h.smallButton(60,704,350,38,receivedView?"返回独立收到库":comparisonView?"返回两侧原次选择":recoveryView?"返回恢复候选":historical.isEmpty()?"返回四条件报告":"返回成功重测历史",this::clear);h.smallButton(430,704,350,38,historical.isEmpty()?"预览所选原次重测":"只读查看 · 不从此页试车",()->{if(historical.isEmpty()) retry.accept(current.report(),current.selected());else app.notifyUser("这份原过程只读查看；请先处理恢复副本或返回当前报告，再重新预览试车。");});h.smallButton(800,704,414,38,"查看本页冻结完整来源",()->h.review("原过程的冻结报告与装备",source,"返回原过程",()->{}));
    }
}
