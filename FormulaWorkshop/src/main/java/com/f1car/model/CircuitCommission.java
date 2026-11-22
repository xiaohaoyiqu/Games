package com.f1car.model;

import java.util.*;

/** Measurable authoring briefs, never an automatic judgement of fun or overtaking quality. */
public record CircuitCommission(int version,String id,Kind kind,CircuitDocument template) {
    public enum Kind {
        REPAIR("修复拥挤弯段","只调整第 5 个路线点，在橙框内拉开至少 12 米；保持其余路线、路宽和场景，完成几何检查与四车验证。"),
        SHORT_LOOP("制作紧凑短环","路线点保持在 ±180 米内，6–20 个点、16–20 米路宽、600–1200 米全长；至少一处路线调整 12 米，完成四车验证。"),
        CLEAR_BUFFER("清理道路缓冲区","保持路线和护栏形状，只移动指定护栏至少 12 米，移出道路缓冲区后完成四车验证。"),
        SIGNPOST("布置路线标牌","保持路线，添加 2–4 块道路外标牌，彼此至少相距 20 米，完成四车验证；方向与可读性另行人工评审。");
        public final String title,description;Kind(String title,String description) { this.title=title;this.description=description; }
    }
    public record Zone(float minX,float maxX,float minZ,float maxZ) {
        public boolean contains(float x,float z) { return Float.isFinite(x) && Float.isFinite(z) && x>=minX && x<=maxX && z>=minZ && z<=maxZ; }
    }
    public record Assessment(boolean scope,boolean changed,boolean geometry,boolean qualified,String detail) {
        public boolean complete() { return scope && changed && geometry && qualified; }
    }
    public record Check(String title,boolean passed,String detail) {}
    public record Review(Assessment assessment,List<Check> checks) {public Review {checks=List.copyOf(checks);}}
    public record Report(String commissionId,Kind kind,String documentKey,String physics,List<Double> laps,long recordedAt) {
        public Report {
            if(!identity(commissionId) || kind==null || documentKey==null || !documentKey.matches("[a-f0-9]{64}") || physics==null || physics.isBlank() || physics.length()>120 || laps==null || laps.size()!=4 || laps.stream().anyMatch(t->t==null || !Double.isFinite(t) || t<=0 || t>CircuitTrial.LIMIT) || recordedAt<0) throw new IllegalArgumentException("Invalid creation report");laps=List.copyOf(laps);
        }
        public String receipt() { return commissionId+":"+documentKey+":"+physics; }
    }
    public CircuitCommission {
        if(version!=1 || !identity(id) || kind==null || template==null || template.anchors().size()!=9) throw new IllegalArgumentException("Invalid creation brief");
        if(kind==Kind.CLEAR_BUFFER && (template.scene().items().size()!=1 || template.scene().items().get(0).kind()!=CircuitScene.Kind.BARRIER) || kind==Kind.SIGNPOST && !template.scene().items().isEmpty()) throw new IllegalArgumentException("Invalid scenery commission template");
    }
    public static CircuitCommission start(Kind kind) {
        var document=CircuitDocument.create().renamed(kind.title);
        if(kind==Kind.REPAIR) { var points=new ArrayList<>(document.anchors());points.set(4,new CircuitDocument.Point(126,96));document=document.points(points); }
        if(kind==Kind.CLEAR_BUFFER) document=document.scenery(new CircuitScene(document.scene().theme(),document.scene().seed(),List.of(new CircuitScene.Item(CircuitScene.Kind.BARRIER,40,-130,(float)Math.PI/2,12,2))));
        return new CircuitCommission(1,UUID.randomUUID().toString(),kind,document);
    }
    public Zone zone() { return switch(kind) {case REPAIR->new Zone(40,140,80,180);case SHORT_LOOP->new Zone(-180,180,-180,180);case CLEAR_BUFFER->new Zone(10,70,-180,-100);case SIGNPOST->new Zone(-220,220,-220,220);}; }
    public String scope(CircuitDocument document) {
        if(!document.id().equals(template.id())) return "委托属于另一张草稿。";
        if(kind==Kind.REPAIR) {
            if(document.width()!=template.width() || !document.scene().equals(template.scene()) || document.anchors().size()!=template.anchors().size()) return "本委托只允许移动第 5 个路线点；其余路线、路宽和场景保持。";
            for(int i=0;i<document.anchors().size();i++) if(i!=4 && !document.anchors().get(i).equals(template.anchors().get(i))) return "请保留其他路线点，只修改第 5 点。";
            var p=document.anchors().get(4);if(!zone().contains(p.x(),p.z())) return "第 5 点需要留在橙色修改区域内。";
        }else if(kind==Kind.SHORT_LOOP) {
            if(document.anchors().size()<6 || document.anchors().size()>20 || document.width()<16 || document.width()>20) return "短环要求 6–20 个路线点，路宽 16–20 米。";
            if(document.anchors().stream().anyMatch(p->!zone().contains(p.x(),p.z()))) return "路线点需要位于 ±180 米的委托区域内。";
        }else {
            if(document.width()!=template.width() || !document.anchors().equals(template.anchors()) || document.scene().theme()!=template.scene().theme() || document.scene().seed()!=template.scene().seed()) return "本委托保持路线、路宽、主题与布景种子，只编辑指定场景内容。";
            var items=document.scene().items();
            if(kind==Kind.CLEAR_BUFFER) {
                if(items.size()!=1) return "保留原来的单件护栏，只修改它的位置。";var old=template.scene().items().get(0);var value=items.get(0);
                if(value.kind()!=old.kind() || value.yaw()!=old.yaw() || value.length()!=old.length() || value.width()!=old.width()) return "护栏类型、朝向和尺寸保持，只修改位置。";
            }else if(items.size()>4 || items.stream().anyMatch(item->item.kind()!=CircuitScene.Kind.SIGN)) return "本委托只允许放置最多四块标牌。";
            if(items.stream().anyMatch(item->!zone().contains(item.x(),item.z()))) return "场景物件需要留在橙色委托区域内。";
        }return "";
    }
    public Assessment assess(CircuitDocument document,CircuitTrial.Proof proof,String physics) {
        return assess(document,proof,physics,null);
    }
    public Assessment assess(CircuitDocument document,CircuitTrial.Proof proof,String physics,CircuitInspector.Report cachedInspection) {
        String scope=scope(document);if(!scope.isEmpty()) return new Assessment(false,false,false,false,scope);
        if(!changed(document)) return new Assessment(true,false,false,false,changeInstruction());
        var inspection=cachedInspection==null?CircuitInspector.inspect(document):cachedInspection;if(!inspection.ready()) return new Assessment(true,true,false,false,inspection.errors().get(0));
        if(kind==Kind.SHORT_LOOP && (inspection.track().length<600 || inspection.track().length>1200)) return new Assessment(true,true,false,false,"短环全长需要在 600–1200 米之间。");
        boolean qualified=proof!=null && proof.matches(document,physics);return new Assessment(true,true,true,qualified,qualified?"委托条件与本机四车验证均已完成。":"几何条件满足；还需要本机四车合法验证。");
    }
    private boolean changed(CircuitDocument document) {
        if(kind==Kind.REPAIR) return distance(document.anchors().get(4),template.anchors().get(4))>=12;
        if(kind==Kind.SHORT_LOOP) return document.anchors().stream().anyMatch(p->template.anchors().stream().allMatch(old->distance(p,old)>=12));
        if(kind==Kind.CLEAR_BUFFER) {var a=template.scene().items().get(0);var b=document.scene().items().get(0);return Math.hypot(a.x()-b.x(),a.z()-b.z())>=12;}
        var items=document.scene().items();if(items.size()<2) return false;for(int a=0;a<items.size();a++) for(int b=a+1;b<items.size();b++) if(Math.hypot(items.get(a).x()-items.get(b).x(),items.get(a).z()-items.get(b).z())<20) return false;return true;
    }
    private String changeInstruction() {return switch(kind) {case REPAIR,SHORT_LOOP->"请至少完成一处 12 米的路线调整；重命名和布景不代替路线修改。";case CLEAR_BUFFER->"将指定护栏移动至少 12 米，再检查道路缓冲区。";case SIGNPOST->"请放置 2–4 块标牌，任意两块相距至少 20 米；可读性还需人工检查。";};}
    public Review review(CircuitDocument document,CircuitTrial.Proof proof,String physics,CircuitInspector.Report inspection) {
        var assessment=assess(document,proof,physics,inspection);boolean scoped=scope(document).isEmpty();boolean edited=scoped && changed(document);boolean geometry=inspection.ready() && (kind!=Kind.SHORT_LOOP || inspection.track().length>=600 && inspection.track().length<=1200);boolean qualified=proof!=null && proof.matches(document,physics);
        return new Review(assessment,List.of(new Check("编辑范围",scoped,scoped?"当前修改符合委托允许范围。":scope(document)),new Check("有效改动",edited,edited?"已满足可计算的改动条件。":changeInstruction()),new Check("几何条件",geometry,geometry?"几何条件通过，尚不代表驾驶体验认证。":inspection.ready()?"短环全长需要在 600–1200 米之间。":inspection.errors().get(0)),new Check("本机四车资格",qualified,qualified?"当前草稿与物理条件的四车证明匹配。":"请在当前草稿与物理条件下完成四车合法验证。")));
    }
    public static final class Cache {
        private CircuitCommission commission;private CircuitDocument document;private CircuitTrial.Proof proof;private String physics;private CircuitInspector.Report inspection;private Review review;private int computations;
        public Review get(CircuitCommission brief,CircuitDocument draft,CircuitTrial.Proof evidence,String version,CircuitInspector.Report report) {if(commission!=brief || document!=draft || proof!=evidence || !Objects.equals(physics,version) || inspection!=report) {review=brief.review(draft,evidence,version,report);commission=brief;document=draft;proof=evidence;physics=version;inspection=report;computations++;}return review;}
        public int computations() {return computations;}
    }
    public Report report(CircuitDocument document,CircuitTrial.Proof proof,String physics,long timestamp) {
        if(!assess(document,proof,physics).complete()) throw new IllegalStateException("Creation brief is incomplete");return new Report(id,kind,document.driveKey(),physics,proof.laps(),timestamp);
    }
    private static double distance(CircuitDocument.Point a,CircuitDocument.Point b) { return Math.hypot(a.x()-b.x(),a.z()-b.z()); }
    private static boolean identity(String id) { return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
}
