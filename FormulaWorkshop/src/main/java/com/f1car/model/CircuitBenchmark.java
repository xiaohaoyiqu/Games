package com.f1car.model;

import java.util.*;

/** An author's measured stock activity lap. Local/imported files are not trusted leaderboards. */
public record CircuitBenchmark(int version,String runId,String resultId,CircuitDocument source,String physics,String controls,String protocol,double lap,long recordedAt) {
    public record Reference(String runId,String resultId) {
        public Reference {
            if(!identity(runId) || !identity(resultId)) throw new IllegalArgumentException("Invalid author lap identity");
        }
    }
    public record Source(Reference reference,String controls,double lap,long recordedAt) {
        public CircuitBenchmark benchmark(CircuitDocument document,String physics) {
            return new CircuitBenchmark(1,reference.runId(),reference.resultId(),document,physics,controls,
                ActivityCard.RULES,lap,recordedAt);
        }
    }
    public CircuitBenchmark {
        if(version!=1 || !identity(runId) || !identity(resultId) || source==null || physics==null || physics.isBlank() || physics.length()>120 || !ControlIdentity.valid(controls) || !ActivityCard.RULES.equals(protocol) || !Double.isFinite(lap) || lap<=0 || lap>ActivityTrial.LIMIT || recordedAt<0 || !CircuitInspector.inspect(source).ready()) throw new IllegalArgumentException("Invalid author lap reference");
    }
    public static CircuitBenchmark from(CircuitDocument document,ActivityRun run,int stage,long timestamp) {
        if(run==null || run.card().partsRule()!=null || stage<0 || stage>=run.results().size()) throw new IllegalArgumentException("No accepted stock author lap");
        var result=run.results().get(stage);
        var track=document.track();
        if(!matchesLap(run.card().stages().get(stage),result,track.id,track.geometryKey()))
            throw new IllegalArgumentException("Author lap conditions differ");
        return new CircuitBenchmark(1,run.id(),result.id(),document,run.card().physics(),run.controls(),ActivityCard.RULES,result.value(),timestamp);
    }
    public static CircuitBenchmark fastestFor(CircuitDocument document,String physics,List<ActivityRun> runs) {
        var candidates=sourcesFor(document,physics,runs);
        if(candidates.isEmpty()) return null;
        try {return candidates.get(0).benchmark(document,physics);}
        catch(IllegalArgumentException invalidDocument) {return null;}
    }
    public static List<Source> sourcesFor(CircuitDocument document,String physics,List<ActivityRun> runs) {
        var candidates=new ArrayList<Source>();
        String geometry=null;
        try {
            for(var run:runs) {
                var card=run.card();
                if(card.partsRule()!=null || !card.physics().equals(physics)) continue;
                for(int i=0;i<run.results().size();i++) {
                    var stage=card.stages().get(i);
                    if(!stockLap(stage,document.id())) continue;
                    var result=run.results().get(i);
                    if(geometry==null) geometry=document.track().geometryKey();
                    if(matchesLap(stage,result,document.id(),geometry)) {
                        candidates.add(new Source(new Reference(run.id(),result.id()),run.controls(),result.value(),run.createdAt()));
                    }
                }
            }
        } catch(IllegalArgumentException invalidDocument) {
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble(Source::lap));
        return List.copyOf(candidates);
    }
    public Reference reference() {return new Reference(runId,resultId);}
    private static boolean stockLap(ActivityCard.Stage stage,String trackId) {
        return stage.kind()==ActivityCard.Kind.LAP && stage.target()==null && stage.trackId().equals(trackId);
    }
    private static boolean matchesLap(ActivityCard.Stage stage,ActivityRun.Result result,String trackId,String geometry) {
        return stockLap(stage,trackId) && stage.geometry().equals(geometry) && result.success()
                && result.laps().size()==1 && result.value()>0 && Math.abs(result.value()-result.laps().get(0))<=.000001;
    }
    public boolean matches(TrackLayout track,String physics) {
        return this.physics.equals(physics) && track.closed && track.width==source.width()
                && Objects.equals(track.scene(),source.scene()) && track.matchesAnchors(source.anchors());
    }
    public double gold() { return lap; }
    public double silver() { return Math.min(ActivityTrial.LIMIT,lap*1.05); }
    public double bronze() { return Math.min(ActivityTrial.LIMIT,lap*1.10); }
    public String medal(double seconds) { if(!Double.isFinite(seconds) || seconds<=0 || seconds>bronze()+.000001) return "未达目标";return seconds<=gold()+.000001?"金牌":seconds<=silver()+.000001?"银牌":"铜牌"; }
    private static boolean identity(String id) { return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
}
