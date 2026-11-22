package com.f1car.persistence;

import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import com.f1car.persistence.PartsImportException.Code;
import com.f1car.persistence.PartsJson.Node;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Strict, bounded declaration-only exchange; diagnostics identify a JSON Pointer and a repair. */
public final class PartsPackCodec {
    public static final int PACK_BYTES=65536,BUNDLE_BYTES=600000,STORE_BYTES=700000,PLANS_BYTES=100000;
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private PartsPackCodec() {}
    public record Saved(String name,PartsBuild build) {public Saved {if(!PartsCatalog.text(name,40) || name.isBlank() || build==null) throw new IllegalArgumentException("Invalid saved parts plan");}}
    public record Bundle(Saved plan,List<Pack> packs) {
        public Bundle {if(plan==null || packs==null || packs.size()>PartsCatalog.MAX_PACKS) throw new IllegalArgumentException("Invalid parts bundle");packs=List.copyOf(packs);var ids=new HashSet<String>();for(var pack:packs) if(pack.id().equals("builtin") || !ids.add(pack.id())) throw new IllegalArgumentException("Duplicate or reserved pack");}
    }
    public record Library(List<Pack> packs,Saved last) {public Library {packs=List.copyOf(packs);if(packs.size()>PartsCatalog.MAX_PACKS) throw new IllegalArgumentException("Too many packs");}}
    public record PlanLibrary(int version,List<PartsPlan> entries,Map<String,PartsPlanSource> sources,Map<String,PartsPlanShare.Trail> trails) {
        public PlanLibrary(int version,List<PartsPlan> entries,Map<String,PartsPlanSource> sources) {this(version,entries,sources,Map.of());}
        public PlanLibrary {entries=List.copyOf(entries);sources=Map.copyOf(sources);trails=Map.copyOf(trails);var ids=new HashSet<String>();entries.forEach(plan->{if(!ids.add(plan.id())) throw new IllegalArgumentException("Duplicate plan");});if(version<1 || version>3 || entries.size()>PartsPlan.CAPACITY || !ids.containsAll(sources.keySet()) || !ids.containsAll(trails.keySet()) || version==1 && !sources.isEmpty() || version<3 && !trails.isEmpty()) throw new IllegalArgumentException("Invalid plan library");for(var row:trails.entrySet()) if(row.getValue().steps().isEmpty() || !row.getValue().steps().get(0).equals(sources.get(row.getKey()))) throw new IllegalArgumentException("Trail differs from direct source");}
    }
    public static Pack builtin() {return Builtin.VALUE;}
    private static final class Builtin {
        static final Pack VALUE=load();
        private static Pack load() {try(var stream=Objects.requireNonNull(PartsPackCodec.class.getResourceAsStream("/data/parts-builtin.json"))) {return pack(PartsJson.read(stream,PACK_BYTES),true);}catch(IOException error) {throw new IllegalStateException("Invalid built-in parts",error);}}
    }
    public static Pack readPack(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {return pack(PartsJson.read(stream,PACK_BYTES),false);}}
    public static Bundle readBundle(Path path) throws IOException {try(var stream=Files.newInputStream(path)) {
        var root=PartsJson.read(stream,BUNDLE_BYTES).object("kind","version","plan","packs");header(root,"formula-workshop-parts-build");return new Bundle(saved(root.child("plan")),packs(root.child("packs")));
    }}
    public static Library readLibrary(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {return readLibrary(stream);}
    }
    static Library readLibrary(InputStream stream) throws IOException {
        var root=PartsJson.read(stream,STORE_BYTES);boolean hasLast=root.value().isJsonObject() && root.value().getAsJsonObject().has("last");if(hasLast) root.object("kind","version","packs","last");else root.object("kind","version","packs");header(root,"formula-workshop-parts-library");return new Library(packs(root.child("packs")),!hasLast || root.child("last").value().isJsonNull()?null:saved(root.child("last")));
    }
    public static List<PartsPlan> readPlans(Path path) throws IOException {return readPlanLibrary(path).entries();}
    public static PlanLibrary readPlanLibrary(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {return readPlanLibrary(stream);}
    }
    static PlanLibrary readPlanLibrary(InputStream stream) throws IOException {
        var root=PartsJson.read(stream,PLANS_BYTES);int version=root.child("version").integer(1,3);if(version==1) root.object("kind","version","entries");else if(version==2) root.object("kind","version","entries","sources");else root.object("kind","version","entries","sources","trails");if(!root.child("kind").text(64,true).equals("formula-workshop-parts-plans")) throw root.child("kind").error(Code.TYPE,"文件种类与命名方案库不同。","请使用本地命名方案库文件。");var entries=root.child("entries").array(0,PartsPlan.CAPACITY);var result=new ArrayList<PartsPlan>();var ids=new HashSet<String>();
        for(int i=0;i<entries.size();i++) {var entry=root.child("entries").index(i).object("id","name","notes","build");String id=entry.child("id").text(36,true);if(!id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw entry.child("id").error(Code.IDENTITY,"方案身份无效。","请由游戏创建方案身份，保留原始导出文件。");if(!ids.add(id)) throw entry.child("id").error(Code.DUPLICATE,"方案身份重复。","请为复制方案创建新的身份。");result.add(new PartsPlan(id,entry.child("name").text(40,true),entry.child("notes").text(120,false),build(entry.child("build"))));}
        var sources=new LinkedHashMap<String,PartsPlanSource>();if(version>=2) {var value=root.child("sources");if(!value.value().isJsonObject()) throw value.error(Code.TYPE,"来源需要 JSON 对象。","保留以本地方案身份为键的来源表。");for(String id:value.value().getAsJsonObject().keySet()) {var source=value.child(id).object("kind","id","name","author","digest");if(!ids.contains(id)) throw source.error(Code.IDENTITY,"来源没有对应的本地方案。","只为本库中的方案保留来源。");String sourceId=source.child("id").text(36,true),digest=source.child("digest").text(64,true);if(!sourceId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || !digest.matches("[a-f0-9]{64}")) throw source.error(Code.IDENTITY,"来源身份或摘要无效。","请保留游戏记录的来源字段。");sources.put(id,new PartsPlanSource(source.child("kind").enumeration(PartsPlanSource.Kind.class),sourceId,source.child("name").text(40,true),source.child("author").text(40,false),digest));}}
        var trails=new LinkedHashMap<String,PartsPlanShare.Trail>();if(version==3) {var values=root.child("trails");if(!values.value().isJsonObject()) throw new IOException("Expected plan trail map");for(var row:values.value().getAsJsonObject().entrySet()) {try {ActivityCardCodec.validate(row.getValue(),PartsPlanShare.Trail.class);trails.put(row.getKey(),JSON.fromJson(row.getValue(),PartsPlanShare.Trail.class));}catch(RuntimeException | ReflectiveOperationException error) {throw new IOException("Invalid plan trail",error);}}}
        return new PlanLibrary(version,result,sources,trails);
    }
    public static String library(List<Pack> packs,Saved last) {var root=header("formula-workshop-parts-library");root.add("packs",packArray(packs));root.add("last",last==null?JsonNull.INSTANCE:savedJson(last));return JSON.toJson(root);}
    public static String plans(List<PartsPlan> plans) {var root=header("formula-workshop-parts-plans");var entries=new JsonArray();for(var plan:plans) {var entry=savedJson(new Saved(plan.name(),plan.build()));entry.addProperty("id",plan.id());entry.addProperty("notes",plan.notes());entries.add(entry);}root.add("entries",entries);return JSON.toJson(root);}
    public static String plans(List<PartsPlan> plans,Map<String,PartsPlanSource> sources) {new PlanLibrary(sources.isEmpty()?1:2,plans,sources);var root=JsonParser.parseString(plans(plans)).getAsJsonObject();if(!sources.isEmpty()) {root.addProperty("version",2);var values=new JsonObject();new TreeMap<>(sources).forEach((id,source)->values.add(id,JSON.toJsonTree(source)));root.add("sources",values);}return JSON.toJson(root);}
    public static String plans(List<PartsPlan> plans,Map<String,PartsPlanSource> sources,Map<String,PartsPlanShare.Trail> trails) {if(trails.isEmpty()) return plans(plans,sources);new PlanLibrary(3,plans,sources,trails);var root=JsonParser.parseString(plans(plans,sources)).getAsJsonObject();root.addProperty("version",3);var values=new JsonObject();new TreeMap<>(trails).forEach((id,trail)->values.add(id,JSON.toJsonTree(trail)));root.add("trails",values);return JSON.toJson(root);}
    public static String bundle(Bundle bundle) {var root=header("formula-workshop-parts-build");root.add("plan",savedJson(bundle.plan()));root.add("packs",packArray(bundle.packs()));return JSON.toJson(root);}
    public static String packJson(Pack pack) {return JSON.toJson(packObject(pack));}

    static List<Pack> packs(Node node) throws IOException {
        var values=node.array(0,PartsCatalog.MAX_PACKS);var result=new ArrayList<Pack>();var ids=new HashSet<String>();for(int i=0;i<values.size();i++) {var pack=pack(node.index(i),false);if(!ids.add(pack.id())) throw node.index(i).child("id").error(Code.DUPLICATE,"包身份重复。","每个玩家包身份只能出现一次。");result.add(pack);}return List.copyOf(result);
    }
    private static Pack pack(Node node,boolean builtin) throws IOException {
        var root=node.object("kind","version","id","revision","name","author","parts");header(root,"formula-workshop-parts");String id=root.child("id").text(32,true);
        if(!id.matches("[a-z][a-z0-9-]{0,31}")) throw root.child("id").error(Code.IDENTITY,"包身份应以小写字母开头，仅含小写字母、数字和连字符。","长度为 1–32，例如 my-parts。");
        if(id.equals("builtin")!=builtin) throw root.child("id").error(Code.IDENTITY,"不能替换保留的原厂命名空间。","请为玩家包使用自己的身份。");
        int revision=root.child("revision").integer(1,1_000_000);String name=root.child("name").text(60,true),author=root.child("author").text(60,false);var entries=root.child("parts").array(1,PartsCatalog.MAX_PARTS_PER_PACK);var result=new ArrayList<Part>();var ids=new HashSet<String>();
        for(int i=0;i<entries.size();i++) {var p=root.child("parts").index(i);var part=part(p,id);if(!ids.add(part.id())) throw p.child("id").error(Code.DUPLICATE,"部件身份重复。","一个包内每个部件的本地身份应不同。");result.add(part);}return new Pack(id,revision,name,author,result);
    }
    private static Part part(Node node,String namespace) throws IOException {
        var root=node.object("id","slot","name","purpose","tradeoff","points","color","settings","requires","excludes");String id=root.child("id").partId();if(!id.startsWith(namespace+":")) throw root.child("id").error(Code.IDENTITY,"部件身份不属于此包。","冒号前应与包 id 相同。");
        Slot slot=root.child("slot").enumeration(Slot.class);String name=root.child("name").text(40,true),purpose=root.child("purpose").text(120,false),tradeoff=root.child("tradeoff").text(160,false);int points=root.child("points").integer(0,100);String color=root.child("color").text(6,true);
        if(!color.matches("[a-fA-F0-9]{6}")) throw root.child("color").error(Code.TYPE,"颜色需为六位十六进制 RGB。","例如 7daeff，不添加井号。");
        var settings=root.child("settings").object(slot.fields().stream().map(Enum::name).toArray(String[]::new));var ranges=new EnumMap<Field,Range>(Field.class);
        for(var field:slot.fields()) {var range=settings.child(field.name()).object("min","max","initial");int min=range.child("min").integer(field.min,field.max),max=range.child("max").integer(field.min,field.max);if(max<min) throw range.child("max").error(Code.RANGE,"最大值小于最小值。","应满足 min ≤ initial ≤ max。");ranges.put(field,new Range(min,max,range.child("initial").integer(min,max)));}
        var requires=links(root.child("requires"),id);var excludes=links(root.child("excludes"),id);int index=0;for(var value:root.child("excludes").value().getAsJsonArray()) {if(requires.contains(value.getAsString())) throw root.child("excludes").index(index).error(Code.STRUCTURE,"不能同时要求和禁止同一部件。","从 requires 或 excludes 中移除该身份。");index++;}
        return new Part(id,slot,name,purpose,tradeoff,points,Integer.parseInt(color,16),ranges,requires,excludes);
    }
    private static Set<String> links(Node node,String self) throws IOException {
        var values=node.array(0,PartsCatalog.MAX_LINKS);var result=new LinkedHashSet<String>();for(int i=0;i<values.size();i++) {var entry=node.index(i);String id=entry.partId();if(id.equals(self)) throw entry.error(Code.IDENTITY,"部件不能引用自身。","请填写其他部件身份。");if(!result.add(id)) throw entry.error(Code.DUPLICATE,"配套身份重复。","每个身份只填写一次。");}return Set.copyOf(result);
    }
    private static Saved saved(Node node) throws IOException {var root=node.object("name","build");return new Saved(root.child("name").text(40,true),build(root.child("build")));}
    private static PartsBuild build(Node node) throws IOException {
        var root=node.object("version","vehicle","physics","budget","slots");root.child("version").integer(1,1);String vehicle=root.child("vehicle").token(),physics=root.child("physics").token();int budget=root.child("budget").integer(0,1000);var slotsNode=root.child("slots").object(Arrays.stream(Slot.values()).map(Enum::name).toArray(String[]::new));var slots=new EnumMap<Slot,PartsBuild.Selection>(Slot.class);
        for(var slot:Slot.values()) {var entry=slotsNode.child(slot.name()).object("part","fingerprint","values");String id=entry.child("part").partId(),fingerprint=entry.child("fingerprint").text(64,true);if(!fingerprint.matches("[a-f0-9]{64}")) throw entry.child("fingerprint").error(Code.IDENTITY,"功能指纹需为 64 位小写十六进制。","请使用游戏导出的指纹；定义改变需明确重建。");var valuesNode=entry.child("values").object(slot.fields().stream().map(Enum::name).toArray(String[]::new));var values=new EnumMap<Field,Integer>(Field.class);for(var field:slot.fields()) values.put(field,valuesNode.child(field.name()).integer(field.min,field.max));slots.put(slot,new PartsBuild.Selection(id,fingerprint,values));}
        return new PartsBuild(1,vehicle,physics,budget,slots);
    }
    private static JsonObject savedJson(Saved saved) {var root=new JsonObject();root.addProperty("name",saved.name());root.add("build",buildJson(saved.build()));return root;}
    private static JsonObject buildJson(PartsBuild build) {
        var root=new JsonObject();root.addProperty("version",build.version());root.addProperty("vehicle",build.vehicle());root.addProperty("physics",build.physics());root.addProperty("budget",build.budget());var slots=new JsonObject();build.slots().forEach((slot,selection)->{var value=new JsonObject();value.addProperty("part",selection.part());value.addProperty("fingerprint",selection.fingerprint());var settings=new JsonObject();selection.values().forEach((field,amount)->settings.addProperty(field.name(),amount));value.add("values",settings);slots.add(slot.name(),value);});root.add("slots",slots);return root;
    }
    private static JsonArray packArray(List<Pack> packs) {var result=new JsonArray();packs.forEach(pack->result.add(packObject(pack)));return result;}
    private static JsonObject packObject(Pack pack) {
        var root=header("formula-workshop-parts");root.addProperty("id",pack.id());root.addProperty("revision",pack.revision());root.addProperty("name",pack.name());root.addProperty("author",pack.author());var parts=new JsonArray();
        for(var part:pack.parts()) {var p=new JsonObject();p.addProperty("id",part.id());p.addProperty("slot",part.slot().name());p.addProperty("name",part.name());p.addProperty("purpose",part.purpose());p.addProperty("tradeoff",part.tradeoff());p.addProperty("points",part.points());p.addProperty("color",String.format(Locale.ROOT,"%06x",part.color()));var settings=new JsonObject();part.settings().forEach((field,range)->{var r=new JsonObject();r.addProperty("min",range.min());r.addProperty("max",range.max());r.addProperty("initial",range.initial());settings.add(field.name(),r);});p.add("settings",settings);p.add("requires",strings(part.requires()));p.add("excludes",strings(part.excludes()));parts.add(p);}root.add("parts",parts);return root;
    }
    private static JsonArray strings(Set<String> values) {var result=new JsonArray();values.stream().sorted().forEach(result::add);return result;}
    private static JsonObject header(String kind) {var result=new JsonObject();result.addProperty("kind",kind);result.addProperty("version",1);return result;}
    private static void header(Node root,String kind) throws IOException {if(!root.child("kind").text(64,true).equals(kind)) throw root.child("kind").error(Code.TYPE,"文件种类与导入入口不同。","请分别使用部件包、部件方案或本地库入口。");root.child("version").integer(1,1);}
}
