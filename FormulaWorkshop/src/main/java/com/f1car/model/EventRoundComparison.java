package com.f1car.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only comparison of two completed rounds using their original saved results. */
public final class EventRoundComparison {
    public enum Difference {
        ROUTE("路线或几何"), PHYSICS("物理版本"), LAPS("正赛圈数"), DIFFICULTY("对手难度"),
        SETUP("车辆性能参数"), UNKNOWN_CONTROLS("旧记录缺少正赛操作"), CONTROLS("正赛操作"), GRID("发车顺序");

        public final String label;
        Difference(String label) {this.label=label;}
    }

    public record TimeChange(double running, double penalty, double total) {}
    public record Row(int driver, EventRoundBrief.Row first, EventRoundBrief.Row second, TimeChange change) {}

    private final EventRoundBrief first,second;
    private final Set<Difference> differences;
    private final List<Row> rows;

    public EventRoundComparison(EventRoundBrief first,EventRoundBrief second) {
        this.first=Objects.requireNonNull(first);
        this.second=Objects.requireNonNull(second);
        var a=first.source();
        var b=second.source();
        if(a.eventId().equals(b.eventId()) && first.round()==second.round())
            throw new IllegalArgumentException("Choose two different original rounds");

        var changes=EnumSet.noneOf(Difference.class);
        if(!a.calendar().get(first.round()).equals(b.calendar().get(second.round()))) changes.add(Difference.ROUTE);
        if(!a.physicsVersion().equals(b.physicsVersion())) changes.add(Difference.PHYSICS);
        if(a.laps()!=b.laps()) changes.add(Difference.LAPS);
        if(a.difficulty()!=b.difficulty()) changes.add(Difference.DIFFICULTY);
        if(!a.tuning().withLivery(0).equals(b.tuning().withLivery(0))) changes.add(Difference.SETUP);
        String controlsA=a.raceControls().get(first.round());
        String controlsB=b.raceControls().get(second.round());
        if(EventSeries.UNSPECIFIED_CONTROLS.equals(controlsA) || EventSeries.UNSPECIFIED_CONTROLS.equals(controlsB))
            changes.add(Difference.UNKNOWN_CONTROLS);
        else if(!controlsA.equals(controlsB)) changes.add(Difference.CONTROLS);
        for(int driver=0;driver<EventSeries.DRIVERS;driver++) {
            if(first.driver(driver).grid()!=second.driver(driver).grid()) changes.add(Difference.GRID);
        }
        differences=Collections.unmodifiableSet(changes);

        var results=new ArrayList<Row>(EventSeries.DRIVERS);
        for(int driver=0;driver<EventSeries.DRIVERS;driver++) {
            var left=first.driver(driver);
            var right=second.driver(driver);
            var x=left.finish();
            var y=right.finish();
            var time=changes.isEmpty() && x.finished() && y.finished()
                    ?new TimeChange(y.elapsed()-x.elapsed(),y.penalty()-x.penalty(),y.total()-x.total()):null;
            results.add(new Row(driver,left,right,time));
        }
        rows=List.copyOf(results);
    }

    public EventRoundBrief first() {return first;}
    public EventRoundBrief second() {return second;}
    public Set<Difference> differences() {return differences;}
    public List<Row> rows() {return rows;}
}
