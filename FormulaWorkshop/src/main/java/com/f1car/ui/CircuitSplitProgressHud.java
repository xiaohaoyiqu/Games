package com.f1car.ui;

import com.f1car.model.CircuitSplitPracticeHistory.Progress;
import com.f1car.model.ControlIdentity;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class CircuitSplitProgressHud {
    private CircuitSplitProgressHud() {}

    static final class Comparison {
        private static final Color PANEL=new Color(0x142d39),INK=new Color(0xeaf4ef),MUTED=new Color(0xa8c2c7);

        private final Progress base;
        private final List<Progress> alternatives;
        private final List<String> baseRows;
        private List<String> alternateRows;
        private String difference;
        private int index;

        Comparison(Progress base,List<Progress> alternatives) {
            this.base=base;
            this.alternatives=List.copyOf(alternatives);
            baseRows=summaryRows(base);
            select(0);
        }

        void move(int step) {
            int next=Math.max(0,Math.min(alternatives.size()-1,index+step));
            if(next!=index) select(next);
        }
        int splitNumber() {return base.selection().number();}

        private void select(int next) {
            index=next;
            var alternative=alternatives.get(index);
            alternateRows=summaryRows(alternative);
            double delta=alternative.medianSeconds()-base.medianSeconds();
            difference=Math.abs(delta)<.0005?"近期中位 · B 与 A 持平"
                    :"近期中位 · B 比 A "+(delta>0?"慢 ":"快 ")+seconds(Math.abs(delta));
        }

        void render(HudRenderer hud,String title,Runnable back,Consumer<Progress> practice) {
            hud.panel(212,147,856,531,PANEL);
            hud.text(title,242,197,25,INK,true);
            hud.text(controlsLabel(base),242,228,16,MUTED);
            renderStart(hud,"A · 基准设定",baseRows,242);
            renderStart(hud,"B · 备选设定",alternateRows,664);
            hud.text(difference,242,454,18,INK,true);
            hud.text("样本数与驾驶动作会影响用时。",242,484,15,MUTED);
            if(alternatives.size()>1) hud.text("← → 切换 B 设定",664,484,15,MUTED);
            hud.smallButton(242,614,796,42,"返回练习记录",back);
            if(index>0) hud.smallButton(664,511,100,36,"上个设定",()->move(-1));
            hud.centered((index+1)+" / "+alternatives.size(),848,534,15,MUTED);
            if(index+1<alternatives.size()) hud.smallButton(938,511,100,36,"下个设定",()->move(1));
            var alternative=alternatives.get(index);
            hud.smallButton(242,560,374,42,"用 A 设定练习",()->practice.accept(base));
            hud.smallButton(664,560,374,42,"用 B 设定练习",()->practice.accept(alternative));
        }

        private static void renderStart(HudRenderer hud,String label,List<String> rows,int x) {
            hud.text(label,x,271,18,INK,true);
            hud.text(rows.get(0),x,302,17,INK);
            for(int i=1;i<rows.size();i++) hud.text(rows.get(i),x,302+i*31,16,MUTED);
        }

        private static List<String> summaryRows(Progress progress) {
            return List.of(CircuitSectionHud.startLabel(progress.selection().start()),recentLabel(progress),
                    fastestLabel(progress),CircuitSectionHud.speedLabel(progress.fastest().speeds()));
        }
    }

    static List<String> lines(Progress progress) {
        var lines=new ArrayList<String>();
        lines.add(CircuitSectionHud.startLabel(progress.selection().start()));
        lines.add(controlsLabel(progress));
        lines.add(recentLabel(progress));
        lines.add(fastestLabel(progress));
        lines.add(CircuitSectionHud.speedLabel(progress.fastest().speeds()));
        for(int index=progress.recent().size()-1;index>=0;index--) {
            var attempt=progress.recent().get(index);
            lines.add("第 "+attempt.number()+" 次 · "+seconds(attempt.seconds()));
            lines.add(CircuitSectionHud.speedLabel(attempt.speeds()));
        }
        return lines;
    }

    private static String controlsLabel(Progress progress) {
        String controls=progress.controls();
        return ControlIdentity.KEYBOARD.equals(controls)?"驾驶控制 · 键盘":"驾驶控制 · 手柄 "+controls.substring(controls.length()-8);
    }

    private static String recentLabel(Progress progress) {
        return "共 "+progress.completedCount()+" 次有效 · 最近 "+progress.recent().size()+" 次中位 "+seconds(progress.medianSeconds());
    }

    private static String fastestLabel(Progress progress) {
        var fastest=progress.fastest();
        return "最快 · 第 "+fastest.number()+" 次 · "+seconds(fastest.seconds());
    }

    private static String seconds(double value) {return String.format(Locale.ROOT,"%.3f s",value);}
}
