package com.f1car.model;

import java.util.*;

/** Additional AND conditions over existing ordered laps, clock and opponent contact observations. */
public record ActivityObjective(int stage,Double timeLimit,Integer contactLimit,Double spreadLimit,ActivitySection section) {
    public ActivityObjective(int stage,Double timeLimit,Integer contactLimit,Double spreadLimit) {this(stage,timeLimit,contactLimit,spreadLimit,null);}
    public static final String PROTOCOL="activity-objectives-v1";
    private static final double TIME_MARGIN=.000001;
    public enum Status { PENDING("未完成"),PASSED("已满足"),FAILED("未满足");public final String title;Status(String title) {this.title=title;} }
    public record Condition(String name,Status status,String detail) {}
    public record Evidence(int contacts,List<Double> sections) {public Evidence(int contacts) {this(contacts,null);}public Evidence {if(contacts<0 || contacts>1000000 || sections!=null && (sections.size()>5 || sections.stream().anyMatch(value->value==null || !Double.isFinite(value) || value<=0 || value>ActivityTrial.LIMIT))) throw new IllegalArgumentException("Invalid activity contact/section evidence");if(sections!=null) sections=List.copyOf(sections);}}
    public ActivityObjective {if(stage<0 || stage>7 || timeLimit==null && contactLimit==null && spreadLimit==null && section==null || timeLimit!=null && (!Double.isFinite(timeLimit) || timeLimit<1 || timeLimit>ActivityTrial.LIMIT) || contactLimit!=null && (contactLimit<0 || contactLimit>20) || spreadLimit!=null && (!Double.isFinite(spreadLimit) || spreadLimit<.1 || spreadLimit>120)) throw new IllegalArgumentException("Invalid compound activity conditions");}
    public ActivityObjective at(int value) {return new ActivityObjective(value,timeLimit,contactLimit,spreadLimit,section);}
    public boolean applicable(ActivityCard.Stage value) {return (spreadLimit==null || value.laps()>=2) && (contactLimit==null || value.kind().drivers>=2 && (value.kind()!=ActivityCard.Kind.CHASE || contactLimit==0));}
    public String key() {return stage+":"+(timeLimit==null?"-":Double.toHexString(timeLimit))+":"+Objects.toString(contactLimit,"-")+":"+(spreadLimit==null?"-":Double.toHexString(spreadLimit))+(section==null?"":":section-v1:"+section.key());}
    double finishDeadline() {return timeLimit==null?Double.POSITIVE_INFINITY:timeLimit+TIME_MARGIN;}
    /** No presentation strings or result lists on the normal observation path. */
    public boolean failed(double elapsed,List<Double> laps,int contacts) {if(!Double.isFinite(elapsed) || elapsed<0 || contacts<0) throw new IllegalArgumentException("Invalid objective observation");if(elapsed>finishDeadline() || contactLimit!=null && contacts>contactLimit) return true;return spreadLimit!=null && laps.size()>=2 && Collections.max(laps)-Collections.min(laps)>spreadLimit+.000001;}
    public List<String> describe() {var lines=new ArrayList<String>();if(timeLimit!=null) lines.add(String.format(Locale.ROOT,"总完赛用时 ≤ %.3f 秒（含起点前行驶）",timeLimit));if(contactLimit!=null) lines.add("与对手接触段 ≤ "+contactLimit+"；完全分离至少 0.25 秒后再接触算新一段，不统计场景物件");if(spreadLimit!=null) lines.add(String.format(Locale.ROOT,"全部有效圈极差 ≤ %.3f 秒；仍需满足原本活动目标",spreadLimit));if(section!=null) lines.add(section.describe());return List.copyOf(lines);}
    public List<Condition> assess(double elapsed,List<Double> laps,Evidence evidence,boolean finished) {
        if(!Double.isFinite(elapsed) || elapsed<0 || elapsed>ActivityTrial.LIMIT || evidence==null || laps==null || laps.size()>5 || laps.stream().anyMatch(value->value==null || !Double.isFinite(value) || value<=0 || value>ActivityTrial.LIMIT)) throw new IllegalArgumentException("Invalid activity objective metrics");var rows=new ArrayList<Condition>();
        if(timeLimit!=null) rows.add(new Condition("总用时",elapsed>finishDeadline()?Status.FAILED:finished?Status.PASSED:Status.PENDING,String.format(Locale.ROOT,"%.3f / %.3f 秒",elapsed,timeLimit)));
        if(contactLimit!=null) rows.add(new Condition("对手接触段",evidence.contacts()>contactLimit?Status.FAILED:finished?Status.PASSED:Status.PENDING,evidence.contacts()+" / "+contactLimit));
        if(spreadLimit!=null) {double spread=laps.size()<2?0:Collections.max(laps)-Collections.min(laps);var status=laps.size()<2?finished?Status.FAILED:Status.PENDING:spread>spreadLimit+.000001?Status.FAILED:finished?Status.PASSED:Status.PENDING;rows.add(new Condition("有效圈极差",status,laps.size()<2?"至少需要两圈":String.format(Locale.ROOT,"%.3f / %.3f 秒",spread,spreadLimit)));}
        if(section!=null) {var values=evidence.sections();boolean missing=values==null || values.isEmpty() || values.size()!=laps.size();double worst=values==null || values.isEmpty()?0:Collections.max(values);var status=worst>section.limit()+.000001?Status.FAILED:finished?missing?Status.FAILED:Status.PASSED:Status.PENDING;rows.add(new Condition("路门分段",status,values==null?"缺失分段观测":values.isEmpty()?"尚无完整分段观测":String.format(Locale.ROOT,"%d 圈 · 最慢 %.3f / %.3f 秒",values.size(),worst,section.limit())));}
        return List.copyOf(rows);
    }
}
