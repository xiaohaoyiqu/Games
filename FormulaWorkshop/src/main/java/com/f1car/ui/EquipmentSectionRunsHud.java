package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.CircuitSectionPractice.GatePosition;
import com.f1car.model.ControlIdentity;
import com.f1car.model.EquipmentSectionPracticeSession;
import com.f1car.model.EquipmentSectionPracticeSession.Attempt;
import com.f1car.model.EquipmentSectionPracticeSession.Car;
import com.f1car.model.EquipmentSectionPracticeSession.Interruption;
import com.f1car.model.EquipmentSectionPracticeSession.Interruptions;
import com.f1car.model.EquipmentSectionPracticeSession.Progress;
import com.f1car.model.EquipmentSectionPracticeSession.SplitComparison;
import com.f1car.model.PracticeSession;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Frozen readings for the two equipment choices under a single set of launch conditions. */
final class EquipmentSectionRunsHud {
    private static final int SPLITS_PER_PAGE=7;
    private static final Color PANEL=new Color(0x13242e),TABLE=new Color(0x1b3440);
    private static final Color INK=new Color(0xeaf4ef),SOFT=new Color(0xb0c6c8),A=new Color(0xffb267),B=new Color(0x63e6bc);

    private final FormulaGame.EquipmentSectionPreview source;
    private final BooleanSupplier stillSelected;
    private final Progress a,b;
    private final Interruptions stoppedA,stoppedB;
    private final Map<Integer,EquipmentSectionPracticeSession> subsections=new HashMap<>();
    private Attempt selectedA,selectedB;
    private List<SplitComparison> comparison=List.of();
    private EquipmentInterruptionMapHud interruptionMap;
    private int page,selectedSplit;

    EquipmentSectionRunsHud(FormulaGame.EquipmentSectionPreview source,BooleanSupplier stillSelected) {
        this.source=source;
        this.stillSelected=stillSelected;
        var session=source.session();
        var start=source.start();
        var controls=source.controls().identity();
        a=session.progress(Car.A,start,controls);
        b=session.progress(Car.B,start,controls);
        stoppedA=session.interruptions(Car.A,start,controls);
        stoppedB=session.interruptions(Car.B,start,controls);
    }

    boolean stillSelected() {return stillSelected.getAsBoolean();}

    FormulaGame.EquipmentSectionPreview subsection(SplitComparison split) {
        if(comparison.size()<2 || !comparison.contains(split) || !stillSelected()) return null;
        var road=source.track();
        var practice=subsections.computeIfAbsent(split.number(),
                ignored->source.session().subsection(road,split,source.start(),source.car()));
        var reference=EquipmentSectionPracticeSession.subsectionReference(split,selectedA,selectedB,source.start(),source.controls().identity());
        return new FormulaGame.EquipmentSectionPreview(source.origin(),practice,road,practice.lastStart(road),source.controls(),
                practice.lastCar(),source.sourceResult(),reference);
    }

    void openComparison() {
        if(selectedA==null || selectedB==null || !comparison.isEmpty()) return;
        comparison=EquipmentSectionPracticeSession.compare(selectedA,selectedB);
        page=0;
        selectedSplit=0;
    }

    void nextAttempt(Car car) {
        if(a==null || b==null || !comparison.isEmpty()) return;
        boolean first=car==Car.A;
        var recent=(first?a:b).recent();
        var selection=first?selectedA:selectedB;
        int index=selection==null?-1:recent.indexOf(selection);
        select(car,recent.get(index<=0?recent.size()-1:index-1));
    }

    private void select(Car car,Attempt run) {
        if(car==Car.A) selectedA=run;
        else selectedB=run;
    }

    boolean backFromComparison() {
        if(comparison.isEmpty()) return false;
        comparison=List.of();
        return true;
    }

    void movePage(int step) {
        if(comparison.isEmpty()) return;
        int next=Math.max(0,Math.min(page+step,pages()-1));
        if(next!=page) {
            page=next;
            selectedSplit=page*SPLITS_PER_PAGE;
        }
    }

    void moveSplit(int step) {
        if(comparison.size()<2) return;
        selectedSplit=Math.max(0,Math.min(selectedSplit+step,comparison.size()-1));
        page=selectedSplit/SPLITS_PER_PAGE;
    }

    SplitComparison splitToPractice() {return comparison.size()<2?null:comparison.get(selectedSplit);}

    private int pages() {return (comparison.size()+SPLITS_PER_PAGE-1)/SPLITS_PER_PAGE;}

    void render(HudRenderer h,String backLabel,Runnable back,Consumer<SplitComparison> practice,Runnable showInterruptions) {
        if(!comparison.isEmpty()) {
            renderComparison(h,practice);
            return;
        }
        h.panel(140,133,1000,534,PANEL);
        h.text("A/B 区间练习明细",174,190,27,INK,true);
        h.text(String.format(Locale.ROOT,"计时 %.3f–%.3f m · %s",source.from(),source.to(),CircuitSectionHud.startLabel(source.start())),174,228,17,SOFT);
        String controls=source.controls().identity();
        String controlLabel=ControlIdentity.KEYBOARD.equals(controls)?"键盘":"手柄 "+controls.substring(controls.length()-8);
        h.text("驾驶控制 · "+controlLabel,174,257,16,SOFT);
        table(h,Car.A,a,stoppedA,selectedA,174,A);
        table(h,Car.B,b,stoppedB,selectedB,652,B);
        h.text("各列最近三次有效试驾。过门速度为估算值，— 表示缺少读数。",174,574,16,SOFT);
        if(stoppedA!=null || stoppedB!=null)
            h.smallButton(840,551,266,36,"查看近期中止原因",showInterruptions);
        if(a!=null && b!=null) {
            h.smallButton(174,601,426,42,backLabel,back);
            if(selectedA!=null && selectedB!=null)
                h.smallButton(628,601,478,42,"对照所选两次的计时门  Enter",this::openComparison);
            else h.text("各选一次试驾 · 可按 A、B 键轮换",652,628,16,SOFT);
        } else h.smallButton(174,601,932,42,backLabel,back);
    }

    private void table(HudRenderer h,Car car,Progress progress,Interruptions stopped,Attempt selected,int x,Color accent) {
        h.panel(x,280,454,264,TABLE);
        long completed=progress==null?0:progress.completedCount();
        h.text(car.label()+" · "+completed+" 次有效 · "+(stopped==null?0:stopped.count())+" 次中止",x+18,313,18,accent,true);
        h.text("次序",x+18,355,15,SOFT);
        h.text("用时",x+109,355,15,SOFT);
        h.text("入段 km/h",x+231,355,15,SOFT);
        h.text("出段 km/h",x+347,355,15,SOFT);
        if(progress==null) {
            h.text("当前条件暂无有效试驾",x+18,406,16,SOFT);
            return;
        }
        var recent=progress.recent();
        for(int i=recent.size()-1,y=398;i>=0;i--,y+=42) {
            var run=recent.get(i);
            String number="#"+run.number();
            if(a!=null && b!=null) h.smallButton(x+14,y-22,76,32,(run==selected?"✓ ":"")+number,()->select(car,run));
            else h.text(number,x+18,y,17,INK);
            h.text(PracticeSession.format(run.seconds()),x+109,y,17,INK);
            h.text(CircuitSectionHud.speedKph(run.speeds().entryMps()),x+231,y,17,INK);
            h.text(CircuitSectionHud.speedKph(run.speeds().exitMps()),x+347,y,17,INK);
        }
    }

    List<String> interruptionLines() {
        var lines=new ArrayList<String>();
        lines.add(String.format(Locale.ROOT,"计时区间 %.3f–%.3f m",source.from(),source.to()));
        lines.add(CircuitSectionHud.startLabel(source.start()));
        lines.add("控制配置\n"+source.controls().identity());
        appendInterruptions(lines,Car.A,stoppedA);
        appendInterruptions(lines,Car.B,stoppedB);
        return List.copyOf(lines);
    }

    EquipmentInterruptionMapHud interruptionMap() {
        if(interruptionMap==null) interruptionMap=new EquipmentInterruptionMapHud(this,source,stoppedA,stoppedB);
        return interruptionMap;
    }

    boolean canPracticeInterruption(Car car,Interruption run) {
        return stillSelected() && source.session().canPracticeInterruption(car,source.start(),source.controls().identity(),run);
    }

    FormulaGame.EquipmentSectionPreview interruptionSubsection(Car car,Interruption run) {
        if(!canPracticeInterruption(car,run)) return null;
        var session=subsections.computeIfAbsent(run.progress().nextGate().index(),ignored->source.session()
                .interruptionSubsection(source.track(),car,source.start(),source.controls().identity(),run));
        return new FormulaGame.EquipmentSectionPreview(source.origin(),session,source.track(),source.start(),source.controls(),
                car,source.sourceResult(),null);
    }

    private static void appendInterruptions(List<String> lines,Car car,Interruptions history) {
        lines.add(car.label()+" · "+(history==null?0:history.count())+" 次中止");
        if(history==null) return;
        var recent=history.recent();
        for(int index=recent.size()-1;index>=0;index--) {
            var run=recent.get(index);
            var progress=run.progress();
            var passed=progress.lastPassed();
            lines.add("中止 #"+run.number()+" · "+(passed==null?"助跑阶段":"计时阶段")
                    +"\n已计时 "+PracticeSession.format(run.elapsed())+"\n"+run.reason());
            if(passed!=null) {
                lines.add("最后通过 "+gateLabel(passed.gate())+"\n门上计时 "+PracticeSession.format(passed.elapsed())
                        +"\n过门速度（km/h） "+CircuitSectionHud.speedKph(passed.speedMps()));
            }
            lines.add("待过 "+gateLabel(progress.nextGate()));
            var location=progress.lastLocated();
            lines.add(location==null?"暂无可定位的路线观测":String.format(Locale.ROOT,"最近可定位 %.3f m（中心线）\n试车时钟 %s",
                    location.distance(),PracticeSession.format(location.sessionSeconds())));
        }
    }

    static String gateLabel(GatePosition gate) {
        String name=switch(gate.type()) {
            case START -> "区间起点";
            case CHECKPOINT -> "第 "+gate.index()+" 道计时门";
            case START_FINISH -> "赛道起终线";
            case FINISH -> "区间终点";
        };
        return String.format(Locale.ROOT,"%s %.3f m",name,gate.distance());
    }

    private void renderComparison(HudRenderer h,Consumer<SplitComparison> practice) {
        h.panel(140,114,1000,571,PANEL);
        h.text("A/B 计时门对照",174,168,27,INK,true);
        h.text("A #"+selectedA.number()+" · "+PracticeSession.format(selectedA.seconds()),174,207,18,A,true);
        h.text("B #"+selectedB.number()+" · "+PracticeSession.format(selectedB.seconds()),510,207,18,B,true);
        h.text("A − B 为正时，A 用时较长。",174,241,16,SOFT);
        if(comparison.size()>1) h.text("↑ ↓ 选段 · Enter 预览练习",510,241,16,SOFT);
        h.panel(174,264,932,298,TABLE);
        h.text("分段",192,297,15,SOFT);
        h.text("计时门范围 (m)",272,297,15,SOFT);
        h.text("A (s)",518,297,15,SOFT);
        h.text("B (s)",644,297,15,SOFT);
        h.text("本段 A − B (s)",765,297,15,SOFT);
        h.text("到此 A − B (s)",932,297,15,SOFT);
        int first=page*SPLITS_PER_PAGE;
        int end=Math.min(comparison.size(),first+SPLITS_PER_PAGE);
        for(int index=first,y=332;index<end;index++,y+=35) {
            var split=comparison.get(index);
            if(comparison.size()>1) {
                int row=index;
                String label=(row==selectedSplit?"✓ ":"")+"练 "+split.number();
                h.smallButton(188,y-20,68,28,label,()->{
                    selectedSplit=row;
                    practice.accept(split);
                });
            }
            else h.text(Integer.toString(split.number()),192,y,17,INK);
            h.text(String.format(Locale.ROOT,"%.3f–%.3f",split.from(),split.to()),272,y,17,INK);
            h.text(String.format(Locale.ROOT,"%.3f",split.aSeconds()),518,y,17,INK);
            h.text(String.format(Locale.ROOT,"%.3f",split.bSeconds()),644,y,17,INK);
            h.text(delta(split.splitDelta()),765,y,17,INK);
            h.text(delta(split.cumulativeDelta()),932,y,17,INK);
        }
        if(pages()>1) {
            if(page>0) h.smallButton(174,578,156,33,"上一页 · PgUp",()->movePage(-1));
            h.centered((page+1)+" / "+pages(),640,600,15,SOFT);
            if(page+1<pages()) h.smallButton(950,578,156,33,"下一页 · PgDn",()->movePage(1));
        }
        h.smallButton(174,624,932,40,"返回逐次明细  Esc",this::backFromComparison);
    }

    private static String delta(double seconds) {
        return Math.abs(seconds)<.0005?"0.000":String.format(Locale.ROOT,"%+.3f",seconds);
    }
}
