package com.f1car.model;

import java.util.UUID;

/** Provider declaration and one complete frozen report, optionally its actual retest-history relationship. */
public record PairTrialShare(int version,String id,long sharedAt,String author,String notes,PairExperiment.Snapshot report,PairTrialRevision history) {
    public PairTrialShare {
        if(version!=1 || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || sharedAt<0
            || !PartsCatalog.text(author,40) || author.isBlank() || notes==null || notes.length()>320 || !PartsCatalog.text(notes.replace("\n",""),320) || report==null
            || history!=null && !report.equals(history.before())) throw new IllegalArgumentException("Invalid four-condition share");
    }
    public static PairTrialShare capture(PairExperiment.Snapshot report,PairTrialRevision history,String author,String notes) {return new PairTrialShare(1,UUID.randomUUID().toString(),System.currentTimeMillis(),author,notes,report,history);}
}
