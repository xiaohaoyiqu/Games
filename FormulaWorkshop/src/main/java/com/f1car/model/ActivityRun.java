package com.f1car.model;

import java.util.*;

/** Accepted stages form an immutable checkpoint; a failed attempt leaves its stage available. */
public record ActivityRun(String id,ActivityCard card,String controls,int livery,long createdAt,List<Result> results,Result last,int attempts,PartsBuild entry) {
    public ActivityRun(String id,ActivityCard card,String controls,int livery,long createdAt,List<Result> results,Result last,int attempts) {this(id,card,controls,livery,createdAt,results,last,attempts,null);}
    public record Result(String id,int stage,boolean success,double elapsed,double value,int place,List<Double> laps,String detail,ActivityObjective.Evidence objectiveEvidence) {
        public Result(String id,int stage,boolean success,double elapsed,double value,int place,List<Double> laps,String detail) {this(id,stage,success,elapsed,value,place,laps,detail,null);}
        public Result {
            if(!ActivityCard.identity(id) || stage<0 || stage>7 || !Double.isFinite(elapsed) || elapsed<0 || elapsed>ActivityTrial.LIMIT || !Double.isFinite(value) || value<0 || value>ActivityTrial.LIMIT
                || place<0 || place>4 || laps==null || laps.size()>5 || laps.stream().anyMatch(t->t==null || !Double.isFinite(t) || t<=0 || t>elapsed+.05)
                || detail==null || detail.isBlank() || detail.length()>160 || detail.chars().anyMatch(Character::isISOControl) || success && elapsed<=0) throw new IllegalArgumentException("Invalid activity result");
            laps=List.copyOf(laps);
            if(laps.stream().mapToDouble(Double::doubleValue).sum()>elapsed+.05) throw new IllegalArgumentException("Activity lap times exceed clock");
        }
    }
    public ActivityRun {
        if(!ActivityCard.identity(id) || card==null || !ControlIdentity.valid(controls) || livery<0 || livery>4 || createdAt<0 || results==null || results.size()>card.stages().size() || attempts<results.size() || attempts>100000) throw new IllegalArgumentException("Invalid activity checkpoint");
        results=List.copyOf(results);var ids=new HashSet<String>();
        for(int i=0;i<results.size();i++) { var result=results.get(i);if(!result.success() || result.stage()!=i || !ids.add(result.id())) throw new IllegalArgumentException("Invalid accepted activity stage");validate(card,result); }
        if(last==null && (attempts!=0 || !results.isEmpty()) || last!=null && (attempts==0 || last.success() && (results.isEmpty() || !last.equals(results.get(results.size()-1))) || !last.success() && last.stage()!=results.size())) throw new IllegalArgumentException("Invalid latest activity attempt");
        if(last!=null) validate(card,last);
        if(card.partsRule()==null?entry!=null:!card.partsRule().acceptsIdentity(entry)) throw new IllegalArgumentException("Activity entry differs from its rules");
    }
    public static ActivityRun begin(ActivityCard card,String controls,int livery,long time) { return new ActivityRun(UUID.randomUUID().toString(),card,controls,livery,time,List.of(),null,0); }
    public static ActivityRun begin(ActivityCard card,String controls,int livery,long time,PartsBuild entry) {return new ActivityRun(UUID.randomUUID().toString(),card,controls,livery,time,List.of(),null,0,entry);}
    public boolean complete() { return results.size()==card.stages().size(); }
    public int stage() { return results.size(); }
    public String key() { return key(card,controls,entry); }
    public static String key(ActivityCard card,String controls,PartsBuild entry) {return card.key()+":"+controls+(card.partsRule()==null?"":":parts-entry-v1:"+card.partsRule().entryKey(entry));}
    public Tuning tuning(int paint) {return entry==null?Tuning.STOCK.withLivery(paint):entry.tuning(paint);}
    public double total() { return results.stream().mapToDouble(Result::elapsed).sum(); }
    public ActivityRun accept(Result result) {
        if(last!=null && last.id().equals(result.id())) { if(!last.equals(result)) throw new IllegalArgumentException("Conflicting activity attempt");return this; }
        for(var old:results) if(old.id().equals(result.id())) { if(!old.equals(result)) throw new IllegalArgumentException("Conflicting accepted stage");return this; }
        if(complete() || result.stage()!=stage()) throw new IllegalStateException("Activity result belongs to another stage");validate(card,result);
        var next=new ArrayList<>(results);if(result.success()) next.add(result);
        return new ActivityRun(id,card,controls,livery,createdAt,next,result,attempts+1,entry);
    }
    private static void validate(ActivityCard card,Result result) {
        if(result.stage()>=card.stages().size()) throw new IllegalArgumentException("Unknown activity stage");
        var objective=card.objective(result.stage());if(objective==null?result.objectiveEvidence()!=null:result.objectiveEvidence()==null) throw new IllegalArgumentException("Activity objective evidence differs from its rules");
        if(objective!=null && (objective.section()==null?result.objectiveEvidence().sections()!=null:result.objectiveEvidence().sections()==null)) throw new IllegalArgumentException("Section evidence differs from activity rules");
        if(objective!=null && objective.section()!=null) {var sections=result.objectiveEvidence().sections();if(sections.size()>card.stages().get(result.stage()).laps() || sections.size()>result.laps().size()+1 || sections.stream().anyMatch(value->value>result.elapsed()+.05)) throw new IllegalArgumentException("Too many observed sections");for(int i=0;i<Math.min(sections.size(),result.laps().size());i++) if(sections.get(i)>result.laps().get(i)+.05) throw new IllegalArgumentException("Section exceeds its lap");}
        if(objective!=null && result.success() && objective.assess(result.elapsed(),result.laps(),result.objectiveEvidence(),true).stream().anyMatch(row->row.status()!=ActivityObjective.Status.PASSED)) throw new IllegalArgumentException("Compound activity objective missed");
        if(!result.success()) return;var stage=card.stages().get(result.stage());
        if(result.laps().size()!=stage.laps()) throw new IllegalArgumentException("Missing completed activity laps");
        if(stage.target()!=null && result.value()>stage.target().bronze()+.000001) throw new IllegalArgumentException("Author target not reached");
        double expected=switch(stage.kind()) { case LAP->result.laps().get(0);case CONSISTENCY->Collections.max(result.laps())-Collections.min(result.laps());case CHASE,RACE->result.elapsed(); };
        if(Math.abs(expected-result.value())>.051) throw new IllegalArgumentException("Activity metric mismatch");
        if(stage.kind()==ActivityCard.Kind.RACE && (result.place()<1 || result.place()>stage.targetPlace()) || stage.kind()==ActivityCard.Kind.CHASE && result.place()!=1
            || (stage.kind()==ActivityCard.Kind.LAP || stage.kind()==ActivityCard.Kind.CONSISTENCY) && result.place()!=0) throw new IllegalArgumentException("Activity placing mismatch");
        if(stage.kind()==ActivityCard.Kind.CONSISTENCY) { var consistent=new LapConsistency();result.laps().forEach(t->consistent.complete(t,true));if(!consistent.success()) throw new IllegalArgumentException("Activity consistency target missed"); }
    }
}
