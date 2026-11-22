package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.CircuitMapMigration.*;
import java.awt.Color;
import java.util.*;
import java.util.List;

/** Explicit correspondence and component choices; full diff/impact is built on a click. */
final class CircuitMigrationHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private String childId;private int map,parentIndex,recordPage;private String mappingKey="";private boolean records;
    private final Map<String,String> links=new HashMap<>();private final EnumMap<Section,Choice> choices=new EnumMap<>(Section.class);private FormulaGame.CircuitMigration migration;
    void open(String childId,int map) {this.childId=childId;this.map=map;mappingKey="";migration=null;records=false;choices.clear();}
    void render(HudRenderer h,FormulaGame app,Runnable back) {
        var child=app.creationStore.pack(childId);h.smallButton(70,236,270,34,"返回赛事包库",()->{migration=null;back.run();});if(child==null) {h.text("原包已移除，修订声明可在关系库中管理。",70,310,20,INK);h.smallButton(70,360,546,40,"打开关系库",()->records=true);if(records) records(h,app);return;}
        if(records) {records(h,app);return;}if(migration!=null) {migration(h,app);pending(h,app);return;}
        var relation=app.circuitLineages.find(child.id());var parents=app.creationStore.packs().stream().filter(entry->!entry.id().equals(child.id())).toList();h.text("当前包："+shorten(child.original().title(),28),370,260,19,MINT,true);
        if(parents.isEmpty()) h.text("先收录作为父版本的原始包。",90,330,20,AMBER);else {
            if(mappingKey.isEmpty() && relation!=null) for(int i=0;i<parents.size();i++) if(parents.get(i).id().equals(relation.parent().id())) parentIndex=i;parentIndex=Math.floorMod(parentIndex,parents.size());var parent=parents.get(parentIndex);String key=parent.original().digest()+child.original().digest();if(!mappingKey.equals(key)) {mappingKey=key;links.clear();if(relation!=null && relation.parent().matches(parent.original())) relation.links().forEach(link->links.put(link.after(),link.before()));else for(var source:child.original().circuits()) if(parent.original().circuits().stream().anyMatch(old->old.id().equals(source.id()))) links.put(source.id(),source.id());}
            h.smallButton(70,286,1136,38,"选择父包："+shorten(parent.original().title(),34)+"（点击切换）",()->{parentIndex=(parentIndex+1)%parents.size();mappingKey="changed";});h.text("作者明确选择源地图对应；不按同名推断",90,346,16,SOFT);
            for(int i=0;i<child.original().circuits().size();i++) {var source=child.original().circuits().get(i);String before=links.get(source.id());String name=parent.original().circuits().stream().filter(value->value.id().equals(before)).map(CircuitDocument::name).findFirst().orElse("无父图对应");h.smallButton(70,362+i*32,546,28,(i+1)+" · "+shorten(source.name(),10)+" ← "+shorten(name,12),()->{var ids=parent.original().circuits().stream().map(CircuitDocument::id).toList();int next=ids.indexOf(links.get(source.id()))+1;if(next>=ids.size()) links.remove(source.id());else links.put(source.id(),ids.get(next));});}
            h.smallButton(70,653,546,38,"预览并保存修订声明",()->{try {var values=links.entrySet().stream().map(row->new CircuitLineage.Link(row.getValue(),row.getKey())).toList();var proposed=CircuitLineage.declare(parent.original(),child.original(),values);var previous=app.circuitLineages.find(child.id());var lines=details(proposed);if(previous!=null && !previous.equals(proposed)) lines.add("将替换当前保存的父包声明；包和地图不改写。");h.review("保存作者修订声明？",lines,"保存声明",()->app.declareCircuitLineage(parent,child,previous,proposed));}catch(IllegalArgumentException error) {app.notifyUser("地图对应需一对一，请检查重复父图或包身份。");}});
        }
        h.smallButton(636,350,570,38,"查看已保存关系与祖先",()->trail(h,app,child));h.smallButton(636,398,570,38,"导出包及修订声明",()->export(h,app,child));h.smallButton(636,446,570,38,"关系库／清理／重新读取",()->records=true);h.smallButton(636,494,570,38,"所选地图 "+(Math.floorMod(map,child.localCircuits().size())+1)+" / "+child.localCircuits().size()+"（点击切换）",()->map=(map+1)%child.localCircuits().size());
        h.smallButton(636,548,570,42,"比较并迁移所选地图的本地修改",()->{migration=app.prepareCircuitMigration(child.id(),map);choices.clear();});h.wrapped("声明只绑定原始包。父包缺失时保留声明；收录匹配父包后，才可比较本地修改。",656,618,530,17,SOFT,26);pending(h,app);
    }
    private void pending(HudRenderer h,FormulaGame app) {if(app.circuitLineagePending()) {h.smallButton(70,707,270,30,"重试保存修订声明",app::retryCircuitLineage);h.smallButton(355,707,261,30,"取消待保存声明",app::cancelCircuitLineageSave);}else h.text("修订声明不转移驾驶资格、部件或成绩；迁移只读为可撤销草稿，之后再决定保存。",72,750,13,SOFT);}
    private void migration(HudRenderer h,FormulaGame app) {
        var context=migration;h.text("父图 → 旧本地／当前新版草稿",370,261,21,MINT,true);h.text("所选新版："+shorten(context.merge().target().name(),32),70,307,19,INK);
        int i=0;for(var section:Section.values()) {var selected=choices.get(section);var automatic=context.merge().automatic(section);String label=selected!=null?selected.title:automatic==null?"冲突 · 待选择":"自动："+automatic.title;h.smallButton(70,338+i++*60,546,44,section.title+" · "+label,()->{var old=choices.get(section);if(old==null) choices.put(section,Choice.LOCAL);else if(old==Choice.LOCAL) choices.put(section,Choice.TARGET);else choices.remove(section);});}
        h.smallButton(636,338,570,40,"查看父图 → 旧版本地完整差异",()->diff(h,context.merge().base(),context.merge().local()));h.smallButton(636,394,570,40,"查看父图 → 当前新版草稿完整差异",()->diff(h,context.merge().base(),context.merge().target()));h.wrapped("道路和全部布景分别作为整体选择，不推断单点或物件身份。当前新版草稿可包含已有本地编辑；两边同改的部分需明确选择。",656,482,530,18,SOFT,29);
        h.smallButton(70,610,546,40,"返回地图对应",()->migration=null);h.smallButton(636,610,570,40,"预览迁移结果与活动影响",()->{try {var selected=Map.copyOf(choices);var candidate=context.merge().candidate(selected);var report=CircuitInspector.inspect(candidate);var lines=new ArrayList<String>();for(var section:Section.values()) {var choice=selected.getOrDefault(section,context.merge().automatic(section));lines.add(section.title+"："+choice.title);}lines.add("结果读入当前新版身份的可撤销草稿，不保存，不改原包、旧图、活动或成绩。若当前编辑其他图，将先按既有规则保存该草稿再载入目标。");if(!report.ready()) {lines.add("候选几何待处理，可读为草稿后继续修正：");lines.addAll(report.errors());}for(var impact:CircuitRevisionImpact.inspect(candidate,report,app.circuitShowcaseProof(candidate.id()),app.vehicleSpec.physicsVersion,app.circuitActivityReferences())) lines.add(impact.source()+"／"+impact.activity()+" #"+impact.stage()+"："+impact.detail());h.review("采用所选迁移内容？",lines,"读为可撤销草稿",()->app.applyCircuitMigration(context,selected));}catch(IllegalStateException error) {app.notifyUser("请先为全部冲突选择旧本地或当前新版内容。");}});
    }
    private static void diff(HudRenderer h,CircuitDocument a,CircuitDocument b) {var lines=new ArrayList<String>();CircuitRevisionDiff.compare(a,b).changes().stream().filter(change->change.kind()!=CircuitRevisionDiff.Kind.IDENTITY).forEach(change->lines.add(change.description()));if(lines.isEmpty()) lines.add("内容没有差异，本地身份分别保留。");h.review("迁移来源 · 完整差异",lines,"关闭",()->{});}
    private static ArrayList<String> details(CircuitLineage relation) {var lines=new ArrayList<String>();lines.add("父包："+relation.parent().title()+"；作者："+relation.parent().author());lines.add("这是作者声明，绑定原始包内容；不认证作者，也不表示旧成绩可用于新图。");for(var link:relation.links()) {var before=relation.parent().maps().stream().filter(map->map.id().equals(link.before())).findFirst().orElseThrow();lines.add("父图“"+before.name()+"” → 当前源地图 "+link.after());}if(relation.links().isEmpty()) lines.add("未声明源地图对应，不能据名称自动迁移。");return lines;}
    private void trail(HudRenderer h,FormulaGame app,CircuitEventPack.Entry child) {var lines=new ArrayList<String>();var cursor=app.circuitLineages.find(child.id());if(cursor==null) lines.add("本包没有已保存的父版本声明。");var visited=new HashSet<String>();while(cursor!=null && visited.add(cursor.childId())) {lines.addAll(details(cursor));var parent=app.creationStore.pack(cursor.parent().id());lines.add(parent!=null && cursor.parent().matches(parent.original())?"父包内容已在本地匹配。":"父包未收录或内容不符；不自动下载或采用。");var next=app.circuitLineages.find(cursor.parent().id());cursor=next!=null && next.childDigest().equals(cursor.parent().digest())?next:null;}if(!app.circuitLineages.warning().isEmpty()) lines.add(app.circuitLineages.warning());h.review("作者声明的版本链",lines,"关闭",()->{});}
    private void export(HudRenderer h,FormulaGame app,CircuitEventPack.Entry child) {
        var relation=app.circuitLineages.find(child.id());
        var reviewed=app.reviewCircuitPackExport(child.id());
        if(relation==null || reviewed==null || !child.equals(reviewed.entry())) {
            app.notifyUser("请先保存或重新选择本包的修订声明。");
            return;
        }
        h.promptReviewedExportPath("带修订声明的赛事包 JSON 完整路径",
                app.store.directory().resolve("circuit-export-edition.json"),
                destination->app.exportReviewedCircuitEdition(reviewed,relation,destination));
    }
    private void records(HudRenderer h,FormulaGame app) {h.smallButton(355,236,261,34,"返回修订页",()->records=false);h.smallButton(636,236,570,34,"重新读取修订声明库",app.circuitLineages::reload);var values=app.circuitLineages.entries();int pages=Math.max(1,(values.size()+4)/5);recordPage=Math.floorMod(recordPage,pages);for(int i=recordPage*5;i<Math.min(values.size(),recordPage*5+5);i++) {var row=values.get(i);int y=292+(i-recordPage*5)*65;h.smallButton(70,y,1136,48,"声明 "+(i+1)+" · 父包 "+shorten(row.parent().title(),25)+" → "+row.childId(),()->h.review("移除这份关系声明？",details(row),"只移除声明",()->{if(!row.equals(app.circuitLineages.find(row.childId()))) {app.notifyUser("声明已变化，请重新查看。");return;}if(!app.circuitLineages.remove(row.childId())) app.notifyUser(app.circuitLineages.warning());}));}if(values.isEmpty()) h.text("尚无已保存声明；返回后选择父包与地图对应。",90,330,20,INK);h.smallButton(70,648,261,36,"上一页",()->recordPage=Math.max(0,recordPage-1));h.smallButton(355,648,261,36,"下一页",()->recordPage=Math.min(pages-1,recordPage+1));h.text(values.size()+" / 24 · "+(recordPage+1)+" / "+pages+" 页 · 移除仅影响关系，不删除包或地图",636,674,15,SOFT);if(!app.circuitLineages.warning().isEmpty()) h.text(shorten(app.circuitLineages.warning(),68),72,713,14,AMBER);}
    private static String shorten(String text,int count) {return text.codePointCount(0,text.length())<=count?text:text.substring(0,text.offsetByCodePoints(0,count-1))+"…";}
}
