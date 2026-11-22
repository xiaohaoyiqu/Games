package com.f1car.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** A permanent, local itinerary. Names are cosmetic; driving conditions are frozen. */
public record ActivityCard(int version,String id,String name,long seed,String physics,List<Stage> stages,PartsActivityRule partsRule,List<ActivityObjective> objectives) {
    public ActivityCard(int version,String id,String name,long seed,String physics,List<Stage> stages,PartsActivityRule partsRule) {this(version,id,name,seed,physics,stages,partsRule,null);}
    public ActivityCard(int version,String id,String name,long seed,String physics,List<Stage> stages) {this(version,id,name,seed,physics,stages,null);}
    public static final String RULES="activity-stock-v1";
    public enum Kind {
        LAP("原厂一圈",1,1),CONSISTENCY("三圈稳定",3,1),CHASE("两圈追击",2,2),RACE("短赛争位",1,4);
        public final String title;public final int laps,drivers;
        Kind(String title,int laps,int drivers) { this.title=title;this.laps=laps;this.drivers=drivers; }
    }
    public record Stage(Kind kind,String trackId,String trackName,String geometry,int difficulty,int laps,int targetPlace,CircuitBenchmark target) {
        public Stage(Kind kind,String trackId,String trackName,String geometry,int difficulty,int laps,int targetPlace) { this(kind,trackId,trackName,geometry,difficulty,laps,targetPlace,null); }
        public Stage {
            if(kind==null || trackId==null || !trackId.matches("[a-z0-9-]{1,60}") || !text(trackName,80) || geometry==null || !geometry.matches("[a-f0-9]{64}")
                || difficulty<0 || difficulty>2 || (kind==Kind.RACE?laps!=1 && laps!=3 && laps!=5: laps!=kind.laps)
                || (kind==Kind.RACE?targetPlace<1 || targetPlace>4:targetPlace!=0) || target!=null && kind!=Kind.LAP) throw new IllegalArgumentException("Invalid activity stage");
        }
        public static Stage create(Kind kind,TrackLayout track,int difficulty,int laps,int targetPlace) { return new Stage(kind,track.id,track.name,track.geometryKey(),difficulty,kind==Kind.RACE?laps:kind.laps,kind==Kind.RACE?targetPlace:0); }
        public String trackIssue(TrackLayout track,String physics) {
            if(track==null || !track.id.equals(trackId) || !track.geometryKey().equals(geometry))
                return "地图修订已改变："+trackName;
            if(target!=null && !target.matches(track,physics)) return "作者目标的地图条件不同："+trackName;
            return "";
        }
        public String goal() { return switch(kind) {
            case LAP -> target==null?"完成一圈有效计时；从静止发车，过起点开始圈计时":String.format(Locale.ROOT,"作者参考：金 %.3f / 银 %.3f / 铜 %.3f 秒；需有效一圈",target.gold(),target.silver(),target.bronze());
            case CONSISTENCY -> "连续三圈有效，极差 ≤ max(1.5 秒，均值×4%)";
            case CHASE -> "两圈内干净击败对手；接触或恢复需重试";
            case RACE -> "完成 "+laps+" 圈有效路线，取得前 "+targetPlace+" 名";
        }; }
    }
    public ActivityCard {
        objectives=objectives==null || objectives.isEmpty()?null:objectives.stream().sorted(Comparator.comparingInt(ActivityObjective::stage)).toList();
        if((hasSections(objectives)?version!=4:objectives!=null?version!=3:partsRule==null?version!=1:version!=2) || !identity(id) || !text(name,40) || !text(physics,120) || stages==null || stages.isEmpty() || stages.size()>8) throw new IllegalArgumentException("Invalid activity card");
        stages=List.copyOf(stages);
        if(objectives!=null && (objectives.size()>stages.size() || objectives.stream().map(ActivityObjective::stage).distinct().count()!=objectives.size())) throw new IllegalArgumentException("Duplicate or excessive stage objectives");
        if(objectives!=null) for(var value:objectives) if(value.stage()>=stages.size() || !value.applicable(stages.get(value.stage()))) throw new IllegalArgumentException("Objectives do not apply to their stage");
        if(stages.stream().anyMatch(s->s.target()!=null && !s.target().physics().equals(physics))) throw new IllegalArgumentException("Author lap physics differs");
        if(partsRule!=null && (!partsRule.physics().equals(physics) || stages.stream().anyMatch(s->s.target()!=null))) throw new IllegalArgumentException("Parts activity cannot reuse a stock author target");
    }
    public static ActivityCard create(String name,long seed,String physics,List<Stage> stages) { return new ActivityCard(1,UUID.randomUUID().toString(),name.strip(),seed,physics,stages); }
    public static ActivityCard create(String name,long seed,String physics,List<Stage> stages,PartsActivityRule rule) {return new ActivityCard(rule==null?1:2,UUID.randomUUID().toString(),name.strip(),seed,physics,stages,rule);}
    public static ActivityCard create(String name,long seed,String physics,List<Stage> stages,PartsActivityRule rule,List<ActivityObjective> objectives) {return new ActivityCard(hasSections(objectives)?4:objectives==null || objectives.isEmpty()?rule==null?1:2:3,UUID.randomUUID().toString(),name.strip(),seed,physics,stages,rule,objectives);}
    private static boolean hasSections(List<ActivityObjective> values) {return values!=null && values.stream().anyMatch(value->value.section()!=null);}
    public boolean hasSections() {return hasSections(objectives);}
    public ActivityObjective objective(int stage) {return objectives==null?null:objectives.stream().filter(value->value.stage()==stage).findFirst().orElse(null);}
    public ActivityCard rename(String name) { return new ActivityCard(version,id,name.strip(),seed,physics,stages,partsRule,objectives); }
    public String rulesTitle() {return (partsRule==null?"固定原厂性能":"限定部件 · 上限 "+partsRule.budget()+" 点")+(objectives==null?"":" · 复合条件");}
    public String stageTitle(Stage stage) {return partsRule!=null && stage.kind()==Kind.LAP?"限定部件有效圈":stage.kind().title;}
    public String key() {
        StringBuilder source=new StringBuilder(RULES).append('\n').append(physics).append('\n').append(seed).append('\n').append(Tuning.STOCK.performanceKey());
        for(var s:stages) { source.append('\n').append(s.kind()).append(':').append(s.trackId()).append(':').append(s.geometry()).append(':').append(s.difficulty()).append(':').append(s.laps()).append(':').append(s.targetPlace());if(s.target()!=null) source.append(":author-v1:").append(s.target().runId()).append(':').append(s.target().resultId()).append(':').append(s.target().source().driveKey()).append(':').append(s.target().controls()).append(':').append(Double.toHexString(s.target().lap())); }
        if(partsRule!=null) source.append('\n').append(PartsActivityRule.PROTOCOL).append(':').append(partsRule.key());
        if(objectives!=null) {source.append('\n').append(ActivityObjective.PROTOCOL);for(var value:objectives) source.append('\n').append(value.key());}
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public String incompatibility(String physicsVersion,Function<String,TrackLayout> resolve) {
        if(!physics.equals(physicsVersion)) return "物理版本已改变，请以当前条件另存活动卡。";
        for(var stage:stages) {
            TrackLayout track;
            try { track=resolve.apply(stage.trackId()); } catch(IllegalArgumentException error) { return "地图缺失或未通过四车验证："+stage.trackName(); }
            String issue=stage.trackIssue(track,physicsVersion);
            if(!issue.isEmpty()) return issue;
        }
        return "";
    }
    public float pace(int stage,int driver) {
        var definition=stages.get(stage);if(driver<1 || driver>=definition.kind().drivers) throw new IllegalArgumentException("Invalid activity opponent");
        double variation=new SplittableRandom(seed ^ (0x9e3779b97f4a7c15L*(stage+1)) ^ driver).nextDouble(-.012,.012);
        float base=definition.kind()==Kind.CHASE?.74f*new float[]{.90f,1,1.08f}[definition.difficulty()]:new float[]{.76f,.90f,1}[definition.difficulty()];
        return (float)(base*new float[]{1,.97f,1,1.025f}[driver]+variation);
    }
    public record Template(ActivityCard card,String description) { }
    public static List<ActivityCard> templates(String physics) { return templateLibrary(physics).stream().map(Template::card).toList(); }
    public static List<Template> templateLibrary(String physics) {
        var tracks=TrackCatalog.all();var mixed=new ArrayList<Stage>();
        for(int i=0;i<Kind.values().length;i++) mixed.add(Stage.create(Kind.values()[i],tracks.get(i%tracks.size()).layout(),1,1,2));
        var tour=tracks.stream().map(t->Stage.create(Kind.RACE,t.layout(),1,1,3)).toList();
        return List.of(
            new Template(create("原厂入门双项",1041,physics,List.of(Stage.create(Kind.LAP,tracks.get(0).layout(),0,1,0),Stage.create(Kind.RACE,tracks.get(0).layout(),0,1,3))),"入门 · 海湾熟悉路线后参加一圈短赛，目标前三。"),
            new Template(create("四图混合驾驶",2087,physics,mixed),"综合 · 四图依次体验有效圈、稳定、追击与争位。"),
            new Template(create("四图短赛巡游",3091,physics,tour),"休闲 · 四张地图各赛一圈，标准对手、目标前三。"),
            new Template(create("林地技术练习",4103,physics,List.of(Stage.create(Kind.LAP,tracks.get(1).layout(),0,1,0),Stage.create(Kind.CONSISTENCY,tracks.get(1).layout(),0,3,0))),"技术 · 林地先认连续弯，再用三个有效圈练习稳定节奏。"),
            new Template(create("公园高速进阶",5113,physics,List.of(Stage.create(Kind.LAP,tracks.get(2).layout(),0,1,0),Stage.create(Kind.CHASE,tracks.get(2).layout(),1,2,0),Stage.create(Kind.RACE,tracks.get(2).layout(),1,3,2))),"进阶 · 公园熟悉制动点、干净追击，再争取三圈短赛前二。"),
            new Template(create("港区追击双项",6121,physics,List.of(Stage.create(Kind.LAP,tracks.get(3).layout(),0,1,0),Stage.create(Kind.CHASE,tracks.get(3).layout(),0,2,0))),"街道 · 港区先走有效路线，再挑战轻松对手，追击避免接触。"),
            new Template(create("四图进阶巡回",7129,physics,tracks.stream().map(t->Stage.create(Kind.RACE,t.layout(),2,3,2)).toList()),"挑战 · 四图各赛三圈，挑战对手、每站目标前二；可按项继续。")
        );
    }
    static boolean identity(String id) { return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private static boolean text(String value,int maximum) { return value!=null && !value.isBlank() && value.length()<=maximum && value.chars().noneMatch(Character::isISOControl); }
}
