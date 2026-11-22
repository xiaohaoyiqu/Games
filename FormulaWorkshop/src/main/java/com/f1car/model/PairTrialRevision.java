package com.f1car.model;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Complete accepted report before successful run replacements. No reconstructed or merged old report. */
public record PairTrialRevision(int version,String id,PairExperiment.Snapshot before,long afterAttempt,List<Replacement> replacements) {
    public record Replacement(int index,long oldAttempt,long newAttempt) {
        public Replacement {if(index<0 || index>=12 || oldAttempt<1 || newAttempt<=oldAttempt) throw new IllegalArgumentException("Invalid replacement identity");}
    }
    public PairTrialRevision {
        if(version!=1 || before==null || afterAttempt<=before.lastAttempt() || replacements==null || replacements.isEmpty() || replacements.size()>12) throw new IllegalArgumentException("Invalid retest history");
        replacements=List.copyOf(replacements);int previous=-1;var attempts=new HashSet<Long>();
        for(var row:replacements) {if(row.index()<=previous || row.index()>=before.runs().size() || row.oldAttempt()!=before.runs().get(row.index()).attempt() || row.newAttempt()<=before.lastAttempt() || row.newAttempt()>afterAttempt || !attempts.add(row.newAttempt())) throw new IllegalArgumentException("Retest does not identify original runs");previous=row.index();}
        if(!identity(before,afterAttempt,replacements).equals(id)) throw new IllegalArgumentException("Retest history identity differs");
    }
    public static boolean sameSource(PairExperiment.Snapshot a,PairExperiment.Snapshot b) {return a!=null && b!=null && a.id().equals(b.id()) && a.createdAt()==b.createdAt() && a.conditions().equals(b.conditions()) && a.plan().equals(b.plan()) && a.seed()==b.seed() && a.order().equals(b.order());}
    /** Appending a run or recording a failed attempt does not create replacement history. */
    public static PairTrialRevision capture(PairExperiment.Snapshot before,PairExperiment.Snapshot after) {
        if(!sameSource(before,after) || after.lastAttempt()<before.lastAttempt() || after.runs().size()<before.runs().size() || after.failedAttempts()<before.failedAttempts()) throw new IllegalArgumentException("Retest source or counters differ");
        var changed=new ArrayList<Replacement>();for(int i=0;i<before.runs().size();i++) if(!before.runs().get(i).equals(after.runs().get(i))) changed.add(new Replacement(i,before.runs().get(i).attempt(),after.runs().get(i).attempt()));
        return changed.isEmpty()?null:new PairTrialRevision(1,identity(before,after.lastAttempt(),changed),before,after.lastAttempt(),changed);
    }
    public boolean matches(PairExperiment.Snapshot current) {
        if(!sameSource(before,current) || current.lastAttempt()<afterAttempt || current.runs().size()<before.runs().size() || current.failedAttempts()<before.failedAttempts()) return false;
        return replacements.stream().allMatch(row->current.runs().get(row.index()).attempt()>=row.newAttempt());
    }
    private static String identity(PairExperiment.Snapshot before,long after,List<Replacement> rows) {
        var value=new StringBuilder(before.id()).append(':').append(before.lastAttempt()).append(':').append(after);
        for(var row:rows) value.append(':').append(row.index()).append('/').append(row.oldAttempt()).append('/').append(row.newAttempt());
        return UUID.nameUUIDFromBytes(value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
