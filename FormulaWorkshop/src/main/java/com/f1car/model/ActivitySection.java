package com.f1car.model;

import java.util.Locale;

/** An ordered within-lap interval. Gate 18 is the finishing start-line crossing. */
public record ActivitySection(int startGate,int endGate,double limit) {
    public static final int GATES=18;
    public ActivitySection {if(startGate<0 || startGate>=GATES || endGate<=startGate || endGate>GATES || !Double.isFinite(limit) || limit<.1 || limit>ActivityTrial.LIMIT) throw new IllegalArgumentException("Invalid activity section");}
    public String key() {return startGate+":"+endGate+":"+Double.toHexString(limit);}
    public String describe() {return String.format(Locale.ROOT,"每个有效圈的路门 %d → %d 分段 ≤ %.3f 秒；0 为起点、18 为该圈终点，仍须有效完赛",startGate,endGate,limit);}
}
