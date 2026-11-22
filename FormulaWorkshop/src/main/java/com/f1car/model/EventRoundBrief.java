package com.f1car.model;

import java.util.*;

/** Explanation of one actual result checkpoint. Does not rescore, diagnose incidents or infer missing laps. */
public final class EventRoundBrief {
    public record Row(int driver,int grid,int classification,EventSeries.Qualifying qualifying,EventSeries.Finish finish,int points,Long gapMilliseconds,boolean rankingTie) {
        public Double total() {return finish.finished()?Double.valueOf(finish.total()):null;}
    }
    private final EventSeries.State source;private final int round;private final List<Row> rows;private final List<String> focus,details;
    private final List<List<String>> rowDetails;
    private final String settlementSummary;
    private final EventSeries.Result result;
    public EventRoundBrief(EventSeries.State source,int round) {
        this.source=Objects.requireNonNull(source);if(round<0 || round>=source.results().size()) throw new IllegalArgumentException("Choose an existing completed round");this.round=round;
        result=source.results().get(round);var values=new ArrayList<Row>();var winner=result.race().get(0);
        for(int index=0;index<result.race().size();index++) {
            var finish=result.race().get(index);int grid=0;while(result.qualifying().get(grid).driver()!=finish.driver()) grid++;var qualifying=result.qualifying().get(grid);
            Long gap=finish.finished() && winner.finished()?Long.valueOf(milliseconds(finish.total())-milliseconds(winner.total())):null;
            boolean tie=result.race().stream().anyMatch(other->other.driver()!=finish.driver() && other.finished()==finish.finished() && (finish.finished()?milliseconds(other.total())==milliseconds(finish.total()):Float.compare(other.distance(),finish.distance())==0));
            values.add(new Row(finish.driver(),grid+1,index+1,qualifying,finish,result.points(finish.driver()),gap,tie));
        }
        rows=List.copyOf(values);var hints=new ArrayList<String>();var you=driver(0);
        if(!you.finish().finished()) hints.add("下次可以先以完成整圈为目标；本记录没有未完赛原因，不据此判断碰撞、超时或主动退出。");
        if(you.finish().penalty()>0) hints.add("本次有罚时，可先复习边界与复位规则；没有逐次原因记录，不只凭罚时数值判断驾驶问题。");
        if(!you.qualifying().valid()) hints.add("本次排位没有有效圈。下次可先熟悉完整路线与检查点，再尝试有效排位。");
        if(hints.isEmpty()) hints.add("你已完成本站。下一次可以选择一个弯道或稳定完赛目标；名次本身不能说明刹车点或配置应如何调整。");
        focus=List.copyOf(hints);var lines=new ArrayList<String>();var track=source.calendar().get(round);
        lines.add("赛事 "+source.eventId()+" · 第 "+(round+1)+" / "+source.calendar().size()+" 站的原始结果；本页不重算积分。");lines.add("原路线 "+track.trackId()+"；几何 "+track.geometry());lines.add("原物理 "+source.physicsVersion()+"；难度 "+source.difficulty()+"；正赛 "+source.laps()+" 圈；正赛操作 "+source.raceControls().get(round));
        var settlement=result.settlement();
        if(settlement==null) {
            settlementSummary="正赛结束条件与结算时刻未记录。";
            lines.add(settlementSummary);
        } else {
            String label="正赛结算时刻 ";
            String reason=" s · "+settlement.limit().description();
            settlementSummary=label+String.format(Locale.ROOT,"%.3f",settlement.elapsed())+reason;
            lines.add(label+Double.toString(settlement.elapsed())+reason);
        }
        lines.add(replayId()==null?"本站未关联视觉回看。":"本站关联回看编号 "+replayId()+"。"
                +"可用情况取决于本机回看库。");
        lines.add("赛事创建时固定的实际参数：");for(var field:PartsCatalog.Field.values()) lines.add(field.title+"："+Float.toString(field.ratio(source.tuning()))+" 倍。");
        lines.add("旧赛事仅记录这些数值，不按当前车库或目录补写安装来源；排位操作条件未单独记录。");
        lines.add("排序规则：完赛者在前，按总用时取整到毫秒升序；同毫秒按固定车手序号。未完赛者按已记录进度降序，同进度按固定序号。未完赛没有完赛用时差，积分为零。");
        lines.add("排位：有效用时取整到毫秒；相同按先做出成绩的原时刻，再按固定序号；无有效圈排在后面。");
        var byDriver=new ArrayList<List<String>>(Collections.nCopies(EventSeries.DRIVERS,List.of()));
        for(var row:rows) {
            var readings=describeRow(row);
            lines.addAll(readings);
            byDriver.set(row.driver(),readings);
        }
        rowDetails=List.copyOf(byDriver);
        lines.addAll(focus);lines.add("此存档没有本站逐圈遥测、事故过程或未完赛原因；不从名次、罚时或进度推断参数收益。");details=List.copyOf(lines);
    }
    private static long milliseconds(double value) {return Math.round(value*1000);}
    public String settlementSummary() {return settlementSummary;}
    public String replayId() {return result.settlement()==null?null:result.settlement().replayId();}
    public boolean matchesReplay(RaceReplay replay) {
        return EventSeries.matchesLinkedReplay(replay,source,round);
    }
    public EventSeries.State source() {return source;}public int round() {return round;}public List<Row> rows() {return rows;}public List<String> focus() {return focus;}public List<String> details() {return details;}
    public Row driver(int id) {return rows.stream().filter(row->row.driver()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown driver"));}
    public List<String> rowDetails(int id) {
        if(id<0 || id>=rowDetails.size()) throw new IllegalArgumentException("Unknown driver");
        return rowDetails.get(id);
    }
    private static List<String> describeRow(Row row) {
        var q=row.qualifying();var f=row.finish();var lines=new ArrayList<String>();lines.add(EventSeries.driverName(row.driver())+" · 排位发车 P"+row.grid()+" · 正赛分类 P"+row.classification()+" · 本站积分 "+row.points());
        lines.add(q.valid()?"原排位用时 "+Double.toString(q.lap())+" s；做出原时刻 "+Double.toString(q.setAt())+" s。":"排位无有效圈，不补造排位用时。");
        if(f.finished()) lines.add("原比赛用时 "+Double.toString(f.elapsed())+" s + 原罚时 "+Double.toString(f.penalty())+" s = 总用时 "+Double.toString(f.total())+" s；排序毫秒 "+milliseconds(f.total())+"。");
        else lines.add("未完赛，不计算总用时或与完赛者的时间差；记录进度 "+Float.toString(f.distance())+" m；原罚时字段 "+Double.toString(f.penalty())+" s，不能推断未完赛原因。");
        if(row.gapMilliseconds()!=null) lines.add("相对本站首名的排序毫秒差 "+row.gapMilliseconds()+" ms；不同原始小数落在同毫秒时差为零，排序仍遵守固定规则。");
        if(row.rankingTie()) lines.add(f.finished()?"另有车手总用时落在相同毫秒；固定车手序号决定显示顺序，不称为测得的速度优势。":"另有未完赛车手进度相同；固定车手序号决定显示顺序。");return List.copyOf(lines);
    }
}
