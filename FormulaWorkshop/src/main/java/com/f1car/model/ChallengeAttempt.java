package com.f1car.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static com.f1car.model.ChallengeBook.Medal.*;

/** Grades measured outcomes; it never moves a car or invents a reference lap time. */
public final class ChallengeAttempt {
    public static final double CLEAN_LAP_LIMIT=600;
    private final ChallengeBook.Entry entry;
    private final LapConsistency rolling=new LapConsistency();
    private ChallengeBook.Medal medal=NONE;
    private boolean complete;
    private double elapsed;
    private double measuredLapTime;
    private String detail="按目标驾驶；阈值仍待试驾标定。";
    public ChallengeAttempt(ChallengeBook.Entry entry) { this.entry=Objects.requireNonNull(entry); }
    public ChallengeBook.Entry entry() { return entry; }
    public ChallengeBook.Medal medal() { return medal; }
    public boolean complete() { return complete; }
    public double elapsed() { return elapsed; }
    public String detail() { return detail; }
    public int consecutiveLaps() { return rolling.laps().size(); }
    public void clock(double time) {
        if(complete) return;
        if(!Double.isFinite(time) || time<elapsed) { end(NONE,"计时异常，请重试。");return; }
        elapsed=Math.min(time,CLEAN_LAP_LIMIT);
        if(elapsed>=CLEAN_LAP_LIMIT && entry.kind()==ChallengeBook.Kind.CLEAN_LAPS) end(medal,"本次时间已到，保留已获得的奖牌。");
    }
    /** Credit the line crossing before closing a clean-lap challenge at the inclusive deadline. */
    public void lapAt(double crossing,double duration,boolean valid) {
        require(ChallengeBook.Kind.CLEAN_LAPS);
        if(complete) return;
        if(Double.isFinite(crossing) && crossing>=elapsed && crossing<=CLEAN_LAP_LIMIT) {
            elapsed=crossing;
            lap(duration,valid);
        }
        clock(crossing);
    }
    public void lap(double seconds,boolean valid) {
        require(ChallengeBook.Kind.CLEAN_LAPS);if(complete) return;
        if(valid && Double.isFinite(seconds) && seconds>0) {
            if(measuredLapTime+seconds>elapsed+.05) { end(NONE,"圈速与计时记录不一致，请重试。");return; }
            measuredLapTime+=seconds;
        }
        rolling.complete(seconds,valid);
        if(rolling.laps().isEmpty()) { detail="此圈无效，连续圈数清空；已获得的奖牌保留。";return; }
        promote(rolling.laps().size()>=2?SILVER:BRONZE);
        detail=String.format(Locale.ROOT,"连续有效 %d 圈，极差 %.3f 秒；可结束并保存当前奖牌。",rolling.laps().size(),rolling.spread());
        if(rolling.laps().size()==3 && rolling.spread()<=Math.max(.75,rolling.mean()*.025)) end(GOLD,"连续三个有效圈满足稳定目标。");
    }
    public void claim() {
        require(ChallengeBook.Kind.CLEAN_LAPS);if(!complete) end(medal,medal==NONE?"尚未完成有效圈。":"已结束本次挑战，保存已获得的奖牌。");
    }
    public void course(boolean success,double time,List<Double> laps,double reaction) {
        if(entry.kind()!=ChallengeBook.Kind.CONSISTENCY && entry.kind()!=ChallengeBook.Kind.START) throw new IllegalStateException("Wrong challenge outcome");
        if(complete) return;elapsed=validTime(time)?time:0;
        if(!success || !validTime(time)) { end(NONE,"课程未完成，重试可以重新挑战奖牌。");return; }
        if(entry.kind()==ChallengeBook.Kind.START) {
            if(!Double.isFinite(reaction) || reaction<0 || reaction>time) { end(NONE,"起步反应记录无效。");return; }
            end(reaction<=.25?GOLD:reaction<=.45?SILVER:BRONZE,String.format(Locale.ROOT,"起步反应 %.3f 秒；加速区与反应分开计量。",reaction));
        } else {
            var consistency=new LapConsistency();
            if(laps!=null && laps.size()==3 && laps.stream().allMatch(t->t!=null && Double.isFinite(t) && t>0)) laps.forEach(t->consistency.complete(t,true));
            if(!consistency.success()) { end(NONE,"需要三个有效圈，且满足课程的稳定要求。");return; }
            double spread=consistency.spread(),mean=consistency.mean();
            end(spread<=Math.max(.75,mean*.02)?GOLD:spread<=Math.max(1,mean*.03)?SILVER:BRONZE,
                String.format(Locale.ROOT,"三圈均值 %.3f 秒，极差 %.3f 秒。",mean,spread));
        }
    }
    public void race(boolean success,double time,float leadMeters) {
        require(ChallengeBook.Kind.CHASE);if(complete) return;elapsed=validTime(time)?time:0;
        if(!success || !validTime(time) || !Float.isFinite(leadMeters) || leadMeters<0) { end(NONE,"需要干净获胜后才能获得奖牌。");return; }
        end(leadMeters>=30?GOLD:leadMeters>=15?SILVER:BRONZE,String.format(Locale.ROOT,"冲线时领先 %.1f 米；没有推算对手未产生的完赛时间。",leadMeters));
    }
    public void cup(boolean success,double time,float spill) {
        require(ChallengeBook.Kind.SMOOTH);if(complete) return;elapsed=validTime(time)?time:0;
        if(!success || !validTime(time) || time>=CupChallenge.TIME_LIMIT || !Float.isFinite(spill) || spill<0 || spill>100) { end(NONE,"需要在时间内完成有效整圈。");return; }
        end(spill<=15?GOLD:spill<=40?SILVER:BRONZE,String.format(Locale.ROOT,"有效整圈 %.3f 秒，洒水 %.1f%%。",time,spill));
    }
    private boolean validTime(double time) { return Double.isFinite(time) && time>0 && time<=600; }
    private void require(ChallengeBook.Kind kind) { if(entry.kind()!=kind) throw new IllegalStateException("Wrong challenge outcome"); }
    private void promote(ChallengeBook.Medal candidate) { if(candidate.ordinal()>medal.ordinal()) medal=candidate; }
    private void end(ChallengeBook.Medal value,String message) { promote(value);complete=true;detail=message; }
}
