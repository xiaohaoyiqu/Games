package com.f1car.model;

import com.f1car.model.CircuitSectionPractice.GateSpeeds;
import com.f1car.model.CircuitSectionPractice.Start;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Finished attempts scoped to one original result and identical focused practice conditions. */
public final class CircuitSplitPracticeHistory {
    static final int MAX_REFERENCES=64;
    static final int MAX_RECENT_ATTEMPTS=8;
    public record Selection(int number,Start start) {}
    public record Attempt(long number,double seconds,GateSpeeds speeds) {}
    public record Progress(Selection selection,String controls,Attempt fastest,List<Attempt> recent) {
        public Progress {recent=List.copyOf(recent);}
        public long completedCount() {return recent.get(recent.size()-1).number();}
        public double medianSeconds() {
            var sorted=recent.stream().mapToDouble(Attempt::seconds).sorted().toArray();
            int middle=sorted.length/2;
            return (sorted[(sorted.length-1)/2]+sorted[middle])*.5;
        }
    }
    private record Conditions(Selection selection,String controls) {}
    private record Entry(CircuitSectionPractice.History history,CircuitSectionPractice lastAttempt,Progress progress) {}

    private final CircuitSectionPractice source;
    private final LinkedHashMap<Conditions,Entry> references=new LinkedHashMap<>();
    private Conditions lastLaunch;

    public CircuitSplitPracticeHistory(CircuitSectionPractice source) {
        if(source.state()!=CircuitSectionPractice.State.FINISHED || source.splitCount()<2)
            throw new IllegalArgumentException("A completed multi-split result is required");
        this.source=source;
    }

    public Selection lastSelection() {return lastLaunch==null?null:lastLaunch.selection();}
    public Progress lastLaunchProgress() {return progress(references.get(lastLaunch));}
    public Progress progress(int number,Start start,String controls) {return progress(references.get(conditions(number,start,controls)));}

    public List<Progress> alternateStarts(Progress base) {
        var selection=base.selection();
        if(progress(selection.number(),selection.start(),base.controls())!=base) return List.of();

        var alternatives=new ArrayList<Progress>();
        for(var entry:references.values()) {
            var candidate=entry.progress();
            if(candidate!=base && candidate.selection().number()==selection.number() && candidate.controls().equals(base.controls()))
                alternatives.add(candidate);
        }
        Collections.reverse(alternatives);
        return List.copyOf(alternatives);
    }

    public CircuitSectionPractice newAttempt(int number,Start start,String controls) {
        var previous=references.get(conditions(number,start,controls));
        return previous==null?source.practiceSplit(number,start):previous.lastAttempt().freshAttempt(start,previous.history());
    }

    public void noteLaunch(CircuitSectionPractice attempt,String controls) {
        if(attempt.focusedOn(source)) lastLaunch=conditions(attempt.focusedSplit(),attempt.start(),controls);
    }

    public void rememberFinish(CircuitSectionPractice attempt,String controls) {
        if(!attempt.focusedOn(source) || attempt.state()!=CircuitSectionPractice.State.FINISHED) return;
        var key=conditions(attempt.focusedSplit(),attempt.start(),controls);
        var previous=references.get(key);
        if(previous!=null && previous.lastAttempt()==attempt) return;
        var before=progress(previous);
        var latest=new Attempt(before==null?1:before.completedCount()+1,attempt.elapsed(),attempt.completedGateSpeeds());
        var fastest=before==null || latest.seconds()<before.fastest().seconds()?latest:before.fastest();
        var recent=new ArrayList<>(before==null?List.<Attempt>of():before.recent());
        if(recent.size()==MAX_RECENT_ATTEMPTS) recent.remove(0);
        recent.add(latest);
        references.remove(key);
        var history=attempt.historyAfterFinish(previous==null?null:previous.history());
        references.put(key,new Entry(history,attempt,new Progress(key.selection(),key.controls(),fastest,recent)));
        if(references.size()>MAX_REFERENCES) references.remove(references.keySet().iterator().next());
    }

    private static Conditions conditions(int number,Start start,String controls) {
        return new Conditions(new Selection(number,start),ControlIdentity.require(controls));
    }

    private static Progress progress(Entry entry) {return entry==null?null:entry.progress();}
}
