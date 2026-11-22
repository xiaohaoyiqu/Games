package com.f1car.model;

import java.util.List;

/** An unfinished authoring recipe. Map references and goals still require normal publication checks. */
public record CircuitPackDraft(int version, String title, String author, String notes, List<Stage> stages,
                               int selectedStage, long seed, int difficulty, int laps, int place,
                               boolean timed, PartsActivityRule rule, String suggestionNotes, Origin origin) {
    public CircuitPackDraft(int version,String title,String author,String notes,List<Stage> stages,int selectedStage,
                            long seed,int difficulty,int laps,int place,boolean timed,PartsActivityRule rule,
                            String suggestionNotes) {
        this(version,title,author,notes,stages,selectedStage,seed,difficulty,laps,place,timed,rule,suggestionNotes,null);
    }

    public record Origin(String id,String digest,String title,String author) {
        public Origin {
            if(!ActivityCard.identity(id) || digest==null || !digest.matches("[a-f0-9]{64}")
                    || !text(title,40) || title.isBlank() || !text(author,40) || author.isBlank())
                throw new IllegalArgumentException("Invalid package draft origin");
        }
    }
    public record Stage(ActivityCard.Kind kind, String map, ActivityObjective objective, StageOptions options) {
        public Stage(ActivityCard.Kind kind, String map, ActivityObjective objective) {
            this(kind, map, objective, null);
        }
        public Stage {
            if (kind == null || map == null || !map.matches("user-[a-f0-9]{32}"))
                throw new IllegalArgumentException("Invalid draft stage");
            if (options != null) {
                if (!options.equals(options.forKind(kind))) throw new IllegalArgumentException("Stage options do not apply");
                if (options.empty()) options = null;
            }
        }
    }

    /** Null choices follow the corresponding package default. */
    public record StageOptions(Integer difficulty,Integer laps,Integer place,Boolean timed,CircuitBenchmark.Reference reference) {
        public static final StageOptions INHERIT = new StageOptions(null, null, null, null);
        public StageOptions(Integer difficulty,Integer laps,Integer place,Boolean timed) {
            this(difficulty,laps,place,timed,null);
        }
        public StageOptions {
            if (difficulty != null && (difficulty < 0 || difficulty > 2)
                    || laps != null && laps != 1 && laps != 3 && laps != 5
                    || place != null && (place < 1 || place > 4)
                    || reference!=null && !Boolean.TRUE.equals(timed))
                throw new IllegalArgumentException("Invalid stage options");
        }
        public boolean empty() { return difficulty == null && laps == null && place == null && timed == null; }
        public StageOptions forKind(ActivityCard.Kind kind) {
            return new StageOptions(difficulty,kind==ActivityCard.Kind.RACE?laps:null,kind==ActivityCard.Kind.RACE?place:null,
                    kind==ActivityCard.Kind.LAP?timed:null,kind==ActivityCard.Kind.LAP?reference:null);
        }
        public StageOptions withDifficulty(int value) { return new StageOptions(value,laps,place,timed,reference); }
        public StageOptions withLaps(int value) { return new StageOptions(difficulty,value,place,timed,reference); }
        public StageOptions withPlace(int value) { return new StageOptions(difficulty,laps,value,timed,reference); }
        public StageOptions withTiming(boolean value) { return new StageOptions(difficulty, laps, place, value); }
        public StageOptions withReference(CircuitBenchmark.Reference value) {
            return new StageOptions(difficulty,laps,place,true,value);
        }
    }

    public record StagePlan(ActivityCard.Kind kind,String map,int difficulty,int laps,int place,boolean timed,
                            CircuitBenchmark.Reference reference) {
        public StagePlan(ActivityCard.Kind kind,String map,int difficulty,int laps,int place,boolean timed) {
            this(kind,map,difficulty,laps,place,timed,null);
        }
        public ActivityCard.Stage on(TrackLayout track, CircuitBenchmark benchmark) {
            if(reference!=null && benchmark!=null && !reference.equals(benchmark.reference()))
                throw new IllegalArgumentException("Selected author lap differs");
            return new ActivityCard.Stage(kind, track.id, track.name, track.geometryKey(), difficulty,
                    kind == ActivityCard.Kind.RACE ? laps : kind.laps, kind == ActivityCard.Kind.RACE ? place : 0,
                    timed && kind == ActivityCard.Kind.LAP ? benchmark : null);
        }
    }

    public CircuitPackDraft {
        if (version != 1 || !text(title, 40) || title.isBlank() || !text(author, 40) || author.isBlank()
                || !validNotes(notes) || !text(suggestionNotes, 120) || stages == null || stages.isEmpty() || stages.size() > 8
                || selectedStage < 0 || selectedStage >= stages.size() || difficulty < 0 || difficulty > 2
                || laps != 1 && laps != 3 && laps != 5 || place < 1 || place > 4 || timed && rule != null)
            throw new IllegalArgumentException("Invalid package draft");
        stages = List.copyOf(stages);
        for (int i = 0; i < stages.size(); i++) {
            var stage = stages.get(i);
            var objective = stage.objective();
            if (objective != null && objective.stage() != i) throw new IllegalArgumentException("Invalid draft objective index");
            if (rule != null && stage.options() != null && Boolean.TRUE.equals(stage.options().timed()))
                throw new IllegalArgumentException("Parts activity cannot use an original author target");
        }
    }

    public static CircuitPackDraft fromPackage(CircuitEventPack.Entry entry,String author) {
        var source=entry.original();
        var activity=entry.activity();
        var first=activity.stages().get(0);
        var firstRace=activity.stages().stream().filter(stage->stage.kind()==ActivityCard.Kind.RACE).findFirst().orElse(null);
        int defaultLaps=firstRace==null?1:firstRace.laps();
        int defaultPlace=firstRace==null?2:firstRace.targetPlace();
        var stages=new java.util.ArrayList<Stage>(activity.stages().size());
        for(int i=0;i<activity.stages().size();i++) {
            var row=activity.stages().get(i);
            var options=new StageOptions(row.difficulty()==first.difficulty()?null:row.difficulty(),
                    row.kind()!=ActivityCard.Kind.RACE || row.laps()==defaultLaps?null:row.laps(),
                    row.kind()!=ActivityCard.Kind.RACE || row.targetPlace()==defaultPlace?null:row.targetPlace(),
                    row.target()==null?null:Boolean.TRUE);
            stages.add(new Stage(row.kind(),row.trackId(),activity.objective(i),options));
        }
        String suffix=" · 副本";
        int end=Math.min(source.title().length(),40-suffix.length());
        if(end<source.title().length() && Character.isHighSurrogate(source.title().charAt(end-1))) end--;
        String title=source.title().substring(0,end)+suffix;
        String suggestion=source.partsBundle()==null?"":source.partsBundle().suggestion().notes();
        var origin=new Origin(source.id(),source.digest(),source.title(),source.author());
        return new CircuitPackDraft(1,title,author,source.notes(),stages,0,activity.seed(),first.difficulty(),
                defaultLaps,defaultPlace,false,activity.partsRule(),suggestion,origin);
    }

    public StagePlan plan(int index) {
        var stage = stages.get(index);
        var options = stage.options() == null ? StageOptions.INHERIT : stage.options();
        return new StagePlan(stage.kind(), stage.map(), options.difficulty() == null ? difficulty : options.difficulty(),
                options.laps() == null ? laps : options.laps(), options.place() == null ? place : options.place(),
                stage.kind() == ActivityCard.Kind.LAP && (options.timed() == null ? timed : options.timed()),options.reference());
    }

    public List<StagePlan> plans() {
        var result = new java.util.ArrayList<StagePlan>(stages.size());
        for (int i = 0; i < stages.size(); i++) result.add(plan(i));
        return List.copyOf(result);
    }

    public List<ActivityObjective> objectives() {
        return stages.stream().map(Stage::objective).filter(java.util.Objects::nonNull).toList();
    }

    public static boolean validNotes(String value) {
        return value!=null && text(value.replace('\n',' '),320);
    }

    private static boolean text(String value, int maximum) {
        return PartsCatalog.text(value, maximum);
    }
}
