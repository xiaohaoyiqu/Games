package com.f1car.model;

import com.f1car.model.PartsCatalog.*;
import java.util.*;

/** Explicit A-to-B comparison. Names, UUIDs and revision integers do not establish ancestry. */
public record CircuitPackageDiff(List<String> changes,boolean usesDeclaredMaps) {
    public CircuitPackageDiff {changes=List.copyOf(changes);}
    public static CircuitPackageDiff compare(CircuitEventPack a,CircuitEventPack b) {
        return compare(a,b,List.of());
    }
    public static CircuitPackageDiff compare(CircuitEventPack a,CircuitEventPack b,List<CircuitLineage> lineages) {
        Objects.requireNonNull(a);Objects.requireNonNull(b);var lines=new ArrayList<String>();
        add(lines,"包格式",a.version(),b.version());add(lines,"标题",a.title(),b.title());add(lines,"作者",a.author(),b.author());add(lines,"说明",a.notes(),b.notes());
        var left=a.circuits();var right=b.circuits();
        var rightById=new HashMap<String,CircuitDocument>();right.forEach(map->rightById.put(map.id(),map));
        var pairs=new HashMap<String,String>();
        boolean declared=declaredPairs(a,b,lineages,pairs);
        if(!declared) for(var map:left) if(rightById.containsKey(map.id())) pairs.put(map.id(),map.id());
        maps(lines,left,right,rightById,pairs,declared);
        var ac=a.activity();var bc=b.activity();add(lines,"活动物理",ac.physics(),bc.physics());add(lines,"活动种子",ac.seed(),bc.seed());add(lines,"活动名称",ac.name(),bc.name());
        for(int i=0;i<Math.max(ac.stages().size(),bc.stages().size());i++) {var before=i<ac.stages().size()?ac.stages().get(i):null;var after=i<bc.stages().size()?bc.stages().get(i):null;if(Objects.equals(before,after)) continue;lines.add("活动位置 #"+(i+1)+"（按位置比较，不推断移动身份）");lines.add("A："+stage(ac,before));lines.add("B："+stage(bc,after));if(before!=null && after!=null) {
            if(!before.trackId().equals(after.trackId())) lines.add(after.trackId().equals(pairs.get(before.trackId()))
                    ?"该项沿用作者声明的地图对应，源地图编号改变。":"该项源地图身份改变，同名也分别保留。");
            if(before.trackId().equals(after.trackId()) && !before.geometry().equals(after.geometry())) lines.add("该项固定路线条件改变。");if(!Objects.equals(before.target(),after.target())) lines.add("该项作者计时参考记录改变，不沿用旧成绩。");}}
        for(int i=0;i<Math.max(ac.stages().size(),bc.stages().size());i++) {var before=ac.objective(i);var after=bc.objective(i);if(!Objects.equals(before,after)) {lines.add("活动位置 #"+(i+1)+" · 复合条件改变（仍须满足各自原目标）");lines.add("A："+(before==null?"无附加条件":String.join("；",before.describe())));lines.add("B："+(after==null?"无附加条件":String.join("；",after.describe())));}}
        rule(lines,ac.partsRule(),bc.partsRule());parts(lines,a.partsBundle(),b.partsBundle());suggestion(lines,a.partsBundle(),b.partsBundle());return new CircuitPackageDiff(lines,declared);
    }

    private static boolean declaredPairs(CircuitEventPack a,CircuitEventPack b,List<CircuitLineage> lineages,Map<String,String> pairs) {
        for(var declaration:lineages) {
            if(declaration.matches(a,b)) {
                declaration.links().forEach(link->pairs.put(link.before(),link.after()));
                return true;
            }
            if(declaration.matches(b,a)) {
                declaration.links().forEach(link->pairs.put(link.after(),link.before()));
                return true;
            }
        }
        return false;
    }

    private static void maps(List<String> lines,List<CircuitDocument> a,List<CircuitDocument> b,
                             Map<String,CircuitDocument> rightById,Map<String,String> pairs,boolean declared) {
        var matchedRight=new HashSet<>(pairs.values());
        for(var before:a) {
            var after=rightById.get(pairs.get(before.id()));
            if(after==null) {
                lines.add((declared?"A 未声明对应的地图：":"B 不再包含地图：")+before.name()+"；源身份 "+before.id());
            } else if(!before.equals(after)) {
                String label="地图“"+before.name()+"”／“"+after.name()+"” · ";
                for(var change:CircuitRevisionDiff.compare(before,after).changes()) lines.add(label+change.description());
            }
        }
        for(var after:b) if(!matchedRight.contains(after.id()))
            lines.add((declared?"B 未声明对应的地图：":"B 新增地图：")+after.name()+"；源身份 "+after.id());
        var orderA=a.stream().map(CircuitDocument::id).map(pairs::get).filter(Objects::nonNull).toList();
        var orderB=b.stream().map(CircuitDocument::id).filter(matchedRight::contains).toList();
        if(!orderA.equals(orderB)) {
            lines.add("已对应地图的排列改变（两行均使用 B 的源身份）。");
            lines.add("A 顺序："+orderA);
            lines.add("B 顺序："+orderB);
        }
    }
    private static String stage(ActivityCard card,ActivityCard.Stage stage) {return stage==null?"无":card.stageTitle(stage)+"；地图 "+stage.trackName()+"；难度 "+stage.difficulty()+"；"+stage.goal();}
    private static void rule(List<String> lines,PartsActivityRule a,PartsActivityRule b) {
        if(a==null || b==null) {if(!Objects.equals(a,b)) {lines.add("活动规则："+(a==null?"原厂":"限定部件")+" → "+(b==null?"原厂":"限定部件"));var rule=a==null?b:a;lines.add("限定车型／物理："+rule.vehicle()+"／"+rule.physics()+"；预算 "+rule.budget());for(var part:rule.allowed()) lines.add("限定允许项："+part.slot().title+"／"+part.part());}return;}
        add(lines,"限定车型",a.vehicle(),b.vehicle());add(lines,"限定物理",a.physics(),b.physics());add(lines,"规则预算",a.budget(),b.budget());var before=new TreeMap<String,PartsActivityRule.Allowed>();a.allowed().forEach(part->before.put(part.part(),part));var after=new TreeMap<String,PartsActivityRule.Allowed>();b.allowed().forEach(part->after.put(part.part(),part));var ids=new TreeSet<>(before.keySet());ids.addAll(after.keySet());
        for(String id:ids) {var old=before.get(id);var now=after.get(id);if(Objects.equals(old,now)) continue;lines.add("允许项 "+id+"："+(old==null?"未允许":old.slot().title)+" → "+(now==null?"未允许":now.slot().title)+(old!=null && now!=null && !old.fingerprint().equals(now.fingerprint())?"；固定部件定义改变":""));}
    }
    private static void parts(List<String> lines,CircuitPartsBundle a,CircuitPartsBundle b) {
        var before=new TreeMap<String,Pack>();if(a!=null) a.packs().forEach(pack->before.put(pack.id(),pack));var after=new TreeMap<String,Pack>();if(b!=null) b.packs().forEach(pack->after.put(pack.id(),pack));var ids=new TreeSet<>(before.keySet());ids.addAll(after.keySet());
        for(String id:ids) {
            var old=before.get(id);var now=after.get(id);if(Objects.equals(old,now)) continue;
            if(now==null) {lines.add("B 不再携带部件包："+old.name()+"（"+id+"），不删除本地目录。");old.parts().forEach(part->lines.add("不再携带："+part.slot().title+"／"+part.id()+"／"+part.name()));continue;}
            lines.add("部件包 "+id+"：修订 "+(old==null?"无":old.revision())+" → "+now.revision());var diff=PartsRevision.compare(old,now);diff.changes().forEach(change->lines.add(change.description()));if(diff.changes().isEmpty()) lines.add("部件排列改变，功能定义保持。");
            if(old==null) lines.add("新增包名："+now.name()+"；作者："+now.author());
            for(var part:now.parts()) if(old==null || old.parts().stream().noneMatch(previous->previous.id().equals(part.id()))) lines.add("新增部件“"+part.name()+"” · 用途："+part.purpose()+"；代价："+part.tradeoff()+"；颜色："+String.format(Locale.ROOT,"%06x",part.color()));
        }
    }
    private static void suggestion(List<String> lines,CircuitPartsBundle a,CircuitPartsBundle b) {
        var old=a==null?null:a.suggestion();var now=b==null?null:b.suggestion();if(old==null || now==null) {if(!Objects.equals(old,now)) {lines.add("推荐方案："+(old==null?"无":old.name())+" → "+(now==null?"无":now.name()));var value=old==null?now:old;lines.add("推荐说明："+value.notes());describe(lines,value.build());}return;}
        add(lines,"推荐名称",old.name(),now.name());add(lines,"推荐说明",old.notes(),now.notes());var x=old.build();var y=now.build();add(lines,"推荐车型",x.vehicle(),y.vehicle());add(lines,"推荐物理",x.physics(),y.physics());add(lines,"推荐预算",x.budget(),y.budget());
        for(var slot:Slot.values()) {var left=x.slots().get(slot);var right=y.slots().get(slot);add(lines,"推荐"+slot.title+"部件",left.part(),right.part());if(left.part().equals(right.part()) && !left.fingerprint().equals(right.fingerprint())) lines.add("推荐"+slot.title+"：固定的部件定义改变。");for(var field:slot.fields()) add(lines,"推荐"+field.title,left.values().get(field),right.values().get(field));}
    }
    private static void describe(List<String> lines,PartsBuild build) {lines.add("推荐车型／物理："+build.vehicle()+"／"+build.physics()+"；预算 "+build.budget());for(var slot:Slot.values()) {var part=build.slots().get(slot);lines.add(slot.title+"："+part.part());part.values().forEach((field,value)->lines.add(field.title+"："+value+"%"));}}
    private static void add(List<String> lines,String label,Object a,Object b) {if(!Objects.equals(a,b)) lines.add(label+"："+a+" → "+b);}
}
