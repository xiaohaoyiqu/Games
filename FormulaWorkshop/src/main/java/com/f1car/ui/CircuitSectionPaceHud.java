package com.f1car.ui;

import com.f1car.model.CircuitSectionPractice.GateComparison;
import com.f1car.model.CircuitSectionPractice.GateType;

import java.awt.Color;
import java.util.Locale;

final class CircuitSectionPaceHud {
    private static final Color FASTER=new Color(0x63e6bc),SLOWER=new Color(0xffbd78),EVEN=new Color(0xa8c2c7);
    private GateComparison shown;
    private String cumulative,split;

    void draw(HudRenderer hud,GateComparison comparison) {
        if(shown!=comparison) {
            shown=comparison;
            cumulative=comparison==null?null:cumulativeLabel(comparison);
            split=comparison==null?null:"本段 "+deltaLabel(comparison.splitDeltaSeconds())+" · 对照最快完整试驾";
        }
        if(comparison==null) return;
        hud.text(cumulative,45,297,16,color(comparison.cumulativeDeltaSeconds()),true);
        hud.text(split,45,321,15,color(comparison.splitDeltaSeconds()));
    }

    static String cumulativeLabel(GateComparison comparison) {
        String gate=comparison.endpoint()==GateType.START_FINISH?"过赛道起终线":"到第 "+comparison.splitNumber()+" 段终点";
        return gate+"累计 "+deltaLabel(comparison.cumulativeDeltaSeconds());
    }

    static String deltaLabel(double seconds) {
        if(Math.abs(seconds)<.0005) return "持平";
        return String.format(Locale.ROOT,"%s %.3f s",seconds<0?"快":"慢",Math.abs(seconds));
    }

    static Color color(double seconds) {
        if(Math.abs(seconds)<.0005) return EVEN;
        return seconds<0?FASTER:SLOWER;
    }
}
