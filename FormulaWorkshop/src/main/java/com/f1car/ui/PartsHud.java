package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import com.f1car.persistence.PartsPackCodec;
import com.f1car.persistence.PartsImportException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.nio.file.*;
import java.util.*;

/** Bounded part browser: cached search and review, draft-only changes, one explicit apply. */
final class PartsHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72),PANEL=new Color(10,29,39,247),CARD=new Color(0x19323d);
    private static final BasicStroke STROKE=new BasicStroke(2);
    private Slot slot=Slot.POWER,cachedSlot;
    private String query="",cachedQuery,selectedPack;
    private PartsCatalog cachedCatalog;
    private List<Part> filtered=List.of();
    private final Map<String,Color> colors=new HashMap<>();
    private int page,issue;
    private boolean packsView,plansView;
    private final PartsPlansHud plansHud=new PartsPlansHud();
    private final PartsEffectsHud effectsHud=new PartsEffectsHud();
    private final PartsAuthoringHud authoringHud=new PartsAuthoringHud();
    void captureDrafts(FormulaGame app) {plansHud.captureDrafts(app);}
    private FormulaGame.PartsEvidenceRequest evidenceRequest;
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        if(authoringHud.showing()) {authoringHud.render(h,app);return;}
        if(effectsHud.showing()) {effectsHud.render(h,app);return;}
        var bench=app.partsWorkbench();if(bench==null) return;if(app.partsEvidenceRequest()!=null && app.partsEvidenceRequest()!=evidenceRequest) {evidenceRequest=app.partsEvidenceRequest();plansView=true;packsView=false;plansHud.openEvidence();}var catalog=bench.catalog();var build=bench.build();
        if(cachedCatalog!=catalog) {colors.clear();for(var pack:catalog.packs()) for(var part:pack.parts()) colors.put(part.id(),new Color(part.color()));}
        if(cachedCatalog!=catalog || cachedSlot!=slot || !Objects.equals(cachedQuery,query)) {filtered=catalog.search(slot,query);cachedCatalog=catalog;cachedSlot=slot;cachedQuery=query;}
        h.panel(28,24,1224,752,PANEL);h.text("PARTS WORKBENCH",60,65,14,MINT,true);h.text("部件方案与试装",60,108,32,INK,true);
        h.smallButton(1025,57,190,42,app.partsBackLabel(),app::backFromPartsWorkbench);
        h.smallButton(800,57,200,42,plansView?"返回部件目录":"命名方案与 A／B",()->{plansView=!plansView;packsView=false;});
        if(plansView) h.smallButton(600,57,180,42,"试车报告",app::openPartsTuningReport);
        else h.smallButton(600,57,180,42,"整套沿用车库微调",()->transferGarage(h,app));
        if(plansView) h.smallButton(390,57,190,42,"方案试车记录",plansHud::openEvidence);
        else h.smallButton(390,57,190,42,"模型作用对照",()->effectsHud.open(app));
        if(plansView) h.smallButton(60,119,500,27,"方案讨论 · 多轮回复与离线交换",plansHud::openDiscussion);
        else h.smallButton(60,119,500,27,"读取已装装备当前微调为草稿",()->readFitted(h,app));
        h.smallButton(600,117,614,30,app.partsEditing.status(),()->editingRecovery(h,app));
        for(var current:Slot.values()) h.smallButton(60+current.ordinal()*130,165,120,38,(slot==current && !packsView && !plansView?"● ":"")+current.title,()->{slot=current;page=0;packsView=false;plansView=false;});
        if(plansView) {plansHud.render(h,app);if(plansHud.sharedPreviewOpen()) return;}else if(packsView) packs(h,g,app);else {
            h.smallButton(60,214,365,34,query.isEmpty()?"搜索部件名称、用途或身份":"搜索："+shorten(query,17),()->h.prompt("搜索部件；留空显示全部",query,80,value->{query=value.strip();page=0;}));
            h.smallButton(440,214,120,34,"清空搜索",()->{query="";page=0;});
            int pages=Math.max(1,(filtered.size()+4)/5);page=Math.max(0,Math.min(page,pages-1));
            for(int index=page*5;index<Math.min(filtered.size(),page*5+5);index++) {
                var part=filtered.get(index);int y=262+(index-page*5)*68;boolean selected=build.slots().get(slot).part().equals(part.id());
                h.panel(60,y,500,61,CARD);icon(g,part.slot(),78,y+14,colors.get(part.id()));
                h.smallButton(122,y+6,425,29,(selected?"● ":"")+shorten(part.name(),19)+" · "+part.points()+" 点",()->bench.select(part.id()));
                h.text(shorten(part.purpose(),23),122,y+53,12,SOFT);
                h.smallButton(455,y+38,92,22,"沿用微调",()->replaceKeepingValues(h,app,part.id()));
            }
            if(filtered.isEmpty()) h.wrapped("没有匹配部件。可清空搜索，或导入玩家部件包。",72,296,470,19,SOFT,30);
            h.smallButton(60,618,130,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,278,642,15,SOFT);h.smallButton(430,618,130,34,"下一页",()->page=Math.min(pages-1,page+1));
            details(h,g,app,catalog.find(build.slots().get(slot).part()));
        }
        h.smallButton(60,681,116,34,"导入部件包",()->importPack(h,app));h.smallButton(188,681,116,34,"导入方案",()->importBundle(h,app));h.smallButton(316,681,116,34,"导出方案",()->{if(plansView) plansHud.exportSelected(h,app);else exportBundle(h,app);});h.smallButton(444,681,116,34,packsView?"返回部件":"管理部件包",()->{packsView=!packsView;plansView=false;page=0;});
        h.smallButton(60,734,140,34,bench.canUndo()?"撤销草稿":"尚无可撤销",bench::undo);
        h.smallButton(214,734,140,34,"新建原厂草稿",()->h.confirm("改为原厂部件草稿？","当前草稿可撤销。\n已存草稿、车库配置和快捷槽保留。",()->bench.adopt(PartsBuild.stock(catalog,app.vehicleSpec.id,app.vehicleSpec.physicsVersion))));
        h.smallButton(368,734,192,34,"按当前目录重建",()->rebase(h,app));
        var review=bench.review(app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());
        if(plansView) plansHud.trials(h,app);else {h.panel(600,585,614,91,CARD);h.text("方案点数 "+review.points()+" / "+build.budget()+" · "+(review.candidate().equals(app.store.profile.tuning)?"参数与车库一致":"草稿参数与车库不同"),617,611,17,review.valid()?MINT:AMBER,true);
        if(review.issues().isEmpty()) h.wrapped("点数用于本工作台试装；部件参数仍需在赛道上比较。",617,642,560,14,SOFT,21);
        else {issue=Math.floorMod(issue,review.issues().size());String message=review.issues().get(issue);h.wrapped(shorten(message,60),617,642,462,14,AMBER,21);h.smallButton(1092,628,105,30,(issue+1)+"/"+review.issues().size()+" 查看",()->h.review("草稿问题",List.of(message),"下一项",()->issue++));}}
        if(!plansView) h.text("采用参数或安装整套分别选择；安装后按装备范围微调。",600,693,13,SOFT);
        h.smallButton(600,707,184,38,plansView?"另存命名方案":"保存上次草稿",()->{if(plansView) plansHud.save(h,app);else save(h,app);});
        h.smallButton(800,707,200,38,review.valid()?"仅采用草稿参数":"请先处理草稿问题",()->{
            if(!bench.review(app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery()).valid()) {app.notifyUser("请先处理草稿问题，再应用。");return;}
            h.confirm("应用部件草稿的参数？","当前车库参数将更新为草稿值，不安装装备。\n已有装备范围仍需符合，可在车库明确解除。\n部件草稿、快捷槽和操作保留。",app::applyPartsDraft);
        });
        h.smallButton(1014,707,200,38,"安装整套并应用",()->{var expected=app.reviewFitting();if(expected!=null) h.confirm("安装这套装备？","冻结四件部件的定义并应用草稿参数。\n车库微调将受所选部件范围限制；涂装保留。\n可在车库查看、重试安装或解除装备范围。",()->app.installFitting(expected));});
        h.smallButton(600,751,184,25,app.partsStore.last()==null?"尚无已存草稿":"读取上次草稿",app::loadPartsDraft);h.smallButton(800,751,184,25,"试验上限 "+build.budget()+" 点",()->{var expected=bench.build();var before=bench.catalog();long revision=bench.revision();h.editNumber("工作台试验点数上限",expected.budget(),0,1000," 点",value->{if(app.screen!=FormulaGame.Screen.PARTS || app.partsWorkbench()!=bench || bench.revision()!=revision || bench.catalog()!=before) {app.notifyUser("工作台已变化，请重新打开上限输入。");return false;}bench.adopt(expected.budget(value));return true;});});
        h.smallButton(1000,751,214,25,plansView?"重新读取命名方案":"重新读取部件资料",()->{if(plansView) app.reloadPartsPlans();else app.reloadPartsData();});
        if(!app.partsStore.warning().isEmpty()) h.text(shorten(app.partsStore.warning(),70),60,665,13,AMBER);
    }
    private void details(HudRenderer h,Graphics2D g,FormulaGame app,Part part) {
        var bench=app.partsWorkbench();if(part==null || part.slot()!=slot) {h.text(part==null?"当前槽位的部件缺失":"这个部件的槽位已改变",600,194,25,AMBER,true);h.wrapped("原草稿保留。选择另一个部件，或先导入需要的部件包，再明确重建草稿。",600,239,600,18,SOFT,29);return;}
        icon(g,slot,600,169,colors.get(part.id()));h.text(shorten(part.name(),22),647,194,24,MINT,true);h.text(shorten(part.id(),46)+" · "+part.points()+" 点",647,220,12,SOFT);
        h.wrapped(shorten(part.purpose(),30),600,252,500,16,INK,24);h.smallButton(1114,232,100,30,"部件详情",()->h.review("部件完整说明",List.of("名称："+part.name(),"身份："+part.id(),"用途："+part.purpose(),"代价："+part.tradeoff(),"需要："+String.join(", ",new TreeSet<>(part.requires())),"禁配："+String.join(", ",new TreeSet<>(part.excludes()))),"关闭",()->{}));
        h.wrapped(shorten(part.tradeoff(),60),600,302,500,15,AMBER,23);h.smallButton(1114,282,100,30,"代价全文",()->h.review("部件代价说明",List.of(part.name(),part.tradeoff()),"关闭",()->{}));
        h.smallButton(1114,315,100,28,"部件默认",()->{
            var selectedSlot=slot;var before=bench.state();var catalog=bench.catalog();long revision=bench.revision();
            h.confirm("还原此部件默认微调？",part.name()+"\n只改变当前槽位，其他部件和车库保持。\n本次修改可撤销；目录变化后需要重新查看。",()->{
                if(app.screen!=FormulaGame.Screen.PARTS || app.partsWorkbench()!=bench || bench.revision()!=revision || bench.catalog()!=catalog || !bench.state().equals(before)) {app.notifyUser("草稿或目录已变化，请重新查看默认值。");return;}
                app.notifyUser(bench.resetSelected(selectedSlot)?"此部件已还原默认微调；可撤销，车库保持。":"部件缺失或定义已改变，请先查看目录差异。");
            });
        });
        h.text("项目 / 部件范围",600,344,13,SOFT);h.text("当前车库",850,344,13,SOFT);h.text("草稿",1025,344,13,SOFT);
        var selection=bench.build().slots().get(slot);int index=0;
        for(var field:slot.fields()) {
            var range=part.settings().get(field);int y=373+index++*40;int value=selection.values().get(field);
            h.text(field.title,600,y,16,INK);h.text(range.min()+"%–"+range.max()+"%",600,y+16,11,SOFT);h.text(field.percent(app.store.profile.tuning)+"%",855,y,17,SOFT);
            var selectedSlot=slot;
            h.smallButton(964,y-23,34,30,"−",()->bench.change(selectedSlot,field,-1));
            h.smallButton(1006,y-23,110,30,value+"%",()->{var expected=bench.build();var before=bench.catalog();long revision=bench.revision();
                if(!selection.fingerprint().equals(before.fingerprint(part.id()))) {app.notifyUser("部件定义已改变，请先重建草稿。");return;}
                h.editNumber(part.name()+" · "+field.title,value,range.min(),range.max(),"%",next->{
                    if(app.screen!=FormulaGame.Screen.PARTS || app.partsWorkbench()!=bench || bench.catalog()!=before || bench.revision()!=revision) {app.notifyUser("部件或草稿已变化，请取消后重新打开数值。");return false;}
                    try {bench.setValue(selectedSlot,field,next);return true;}catch(IllegalArgumentException error) {app.notifyUser("输入值或部件范围已无效，请重新查看草稿。");return false;}
                });
            });
            h.smallButton(1128,y-23,34,30,"+",()->bench.change(selectedSlot,field,1));
        }
    }
    private void readFitted(HudRenderer h,FormulaGame app) {
        var expected=app.reviewFittedDraft();if(expected==null) return;
        var snapshot=expected.setup().snapshot();var lines=new ArrayList<String>();
        lines.add("将当前已装装备与车库微调复制为可撤销草稿。不会更换装备、应用参数或替换目录；之后可另存命名方案。");
        lines.add("车型 "+snapshot.build().vehicle()+" · 物理 "+snapshot.build().physics()+" · 试验上限 "+snapshot.build().budget()+" 点。");
        for(var slot:Slot.values()) {var part=snapshot.definitions().get(slot);lines.add(slot.title+"："+part.name()+" · "+part.id());for(var field:slot.fields()) lines.add(field.title+" "+snapshot.build().slots().get(slot).values().get(field)+"%");}
        if(!expected.issues().isEmpty()) {lines.add("当前目录与已装定义存在差异，读取会保留原指纹，不会自动修复或采用新定义：");lines.addAll(expected.issues());}
        h.review("读取已装装备当前微调",lines,"读为草稿 · 可撤销",()->app.readFittedDraft(expected));
    }
    private void replaceKeepingValues(HudRenderer h,FormulaGame app,String id) {
        var expected=app.reviewPartReplacement(id);if(expected==null) return;var change=expected.replacement();var part=change.target();var lines=new ArrayList<String>();
        lines.add("只替换草稿中的"+part.slot().title+"槽位；其他槽位、车库、已装装备和命名方案保持。本次草稿修改可撤销。");
        lines.add("原部件："+change.before().slots().get(part.slot()).part()+"。目标："+part.name()+" · "+part.id()+" · "+part.points()+" 点。");
        lines.add("目标用途："+part.purpose());lines.add("目标代价："+part.tradeoff());
        for(var value:change.values()) lines.add(value.field().title+"："+value.before()+"% → "+value.after()+"% · 新范围 "+value.range().min()+"–"+value.range().max()+"% · "+switch(value.adjustment()) {case KEPT->"原值可用";case MINIMUM->"需提高到新下限";case MAXIMUM->"需降低到新上限";});
        lines.addAll(change.sourceIssues());lines.add("共有 "+change.adjustedCount()+" 项需要调整到新范围。这里只比较参数数值，不能据此判断换件收益。");
        lines.add("当前目录可计算的方案点数 "+expected.validation().points()+" / "+change.after().budget()+"；不自动增加试验上限或补装依赖。");
        lines.addAll(expected.validation().issues());
        h.review("换件并沿用可用微调",lines,expected.validation().valid()?"读为换件草稿":"读为待处理草稿",()->app.adoptPartReplacement(expected));
    }
    private void transferGarage(HudRenderer h,FormulaGame app) {
        var expected=app.reviewGarageTransfer();if(expected==null) return;var transfer=expected.transfer();var lines=new ArrayList<String>();
        lines.add("沿用当前车库的实际微调，保留草稿已选的四件部件；有 "+transfer.adjustedCount()+" 项需调整到目标范围。确认后只修改可撤销草稿，安装另行确认。");
        lines.add(transfer.installed()==null?"车库当前为自由调校，源范围采用现有模型支持范围；不推断已装有原厂装备。":"源装备按安装时冻结定义比较，使用当前车库微调；不以已存装备基准替代当前值。");
        for(var slot:Slot.values()) {
            var target=transfer.target(slot);var source=transfer.installed()==null?null:transfer.installed().definitions().get(slot);
            lines.add(slot.title+"："+(source==null?"自由调校":source.name()+" · "+source.id())+" → "+target.name()+" · "+target.id()+" · "+target.points()+" 点。");
            lines.add("目标用途："+target.purpose());lines.add("目标代价："+target.tradeoff());
            for(var value:transfer.values()) if(value.field().slot()==slot) lines.add(value.field().title+"：车库 "+value.garage()+"%，草稿 "+value.draft()+"% → 拟采用 "+value.proposed()+"%；源范围 "+value.source().min()+"–"+value.source().max()+"%，目标 "+value.target().min()+"–"+value.target().max()+"%。"+switch(value.adjustment()) {case KEPT->"车库原值可用。";case MINIMUM->"需提高到目标下限。";case MAXIMUM->"需降低到目标上限。";});
        }
        lines.add("本次草稿数值变化 "+transfer.changedSettings()+" 项；点数 "+expected.validation().points()+" / "+transfer.after().budget()+"。不自动补装依赖或增加上限。");
        lines.addAll(expected.validation().issues());lines.add("范围与参数比较不能证明换件收益；需要用相同条件分别试车。");
        h.review("整套沿用车库微调 · 查看全部范围",lines,expected.validation().valid()?"只读为整套草稿":"读为待处理草稿",()->app.adoptGarageTransfer(expected));
    }
    private void editingRecovery(HudRenderer h,FormulaGame app) {
        var review=app.reviewPartsEditing();if(review==null) return;
        h.review("编辑恢复与自动保存",review.lines(),review.backup()?"保留磁盘副本并保存本次编辑":"重试自动保存",()->{
            if(review.backup()) h.confirm("保留两份编辑记录？","先备份所查看的磁盘文件，再保存本次工作台。\n本次草稿或磁盘内容变化后需要重新查看。\n车库、已存草稿和命名方案保持。",()->app.recoverPartsEditing(review));
            else app.retryPartsEditing();
        });
    }
    private void packs(HudRenderer h,Graphics2D g,FormulaGame app) {
        var values=app.partsStore.packs();if(selectedPack==null || values.stream().noneMatch(pack->pack.id().equals(selectedPack))) selectedPack=values.isEmpty()?null:values.get(0).id();
        h.text("玩家部件包 "+values.size()+" / 8",60,239,19,INK,true);int pages=Math.max(1,(values.size()+4)/5);page=Math.max(0,Math.min(page,pages-1));
        for(int index=page*5;index<Math.min(values.size(),page*5+5);index++) {var pack=values.get(index);int y=262+(index-page*5)*68;h.panel(60,y,500,61,CARD);h.smallButton(72,y+7,475,30,(pack.id().equals(selectedPack)?"● ":"")+shorten(pack.name(),24),()->selectedPack=pack.id());h.text(pack.id()+" · 修订 "+pack.revision()+" · "+pack.parts().size()+" 个部件",82,y+53,13,SOFT);}
        if(values.isEmpty()) h.wrapped("尚未收录玩家包。可在游戏内制作范围与组合规则，也可导出模板后编辑。",72,294,470,18,SOFT,28);
        h.smallButton(60,618,130,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,278,642,15,SOFT);h.smallButton(430,618,130,34,"下一页",()->page=Math.min(pages-1,page+1));
        var pack=values.stream().filter(value->value.id().equals(selectedPack)).findFirst().orElse(null);
        h.text(pack==null?"制作自己的部件包":shorten(pack.name(),23),600,194,25,MINT,true);
        h.wrapped(pack==null?"部件包可定义已有车辆参数的范围、默认值、点数及依赖／禁配规则。导入不会立即改变车库。":"作者："+shorten(pack.author(),20)+"。同名包替换后，旧草稿保留；定义不匹配时会阻止应用，等待明确重建。",600,244,600,18,INK,29);
        h.wrapped("只接受受支持的 JSON 数据。定义新部件不自动增加新的车辆物理效果。",600,348,600,16,SOFT,25);
        h.smallButton(600,416,292,41,pack==null?"导出可编辑部件模板":"导出所选部件包",()->exportPack(h,app,pack==null?template():pack));
        if(pack!=null) h.smallButton(914,416,300,41,"移除此部件包",()->h.confirm("移除玩家部件包？",pack.name()+"\n使用这些部件的草稿保留，并标记缺失。\n当前车库参数、外部源文件与快捷槽保留。",()->app.removePartsPack(pack.id())));
        h.smallButton(600,478,292,41,app.partsAuthoring()==null?"游戏内制作部件包":"继续制作草稿",()->authoringHud.open(slot));
        if(pack!=null) h.smallButton(914,478,300,41,"编辑所选包的副本",()->authoringHud.editPack(h,app,pack));
    }
    private static PartsCatalog.Pack template() {
        var part=new Part("my-parts:adjustable-wing",Slot.AERO,"我的尾翼方案","编辑范围与点数，再导入试装。","沿用现有阻力与下压力模型；需实际试车。",3,0x7daeff,Map.of(Field.WING,new Range(80,120,100)),Set.of(),Set.of());return new PartsCatalog.Pack("my-parts",1,"我的部件包","本地创作者",List.of(part));
    }
    private void importPack(HudRenderer h,FormulaGame app) {h.prompt("导入部件包 JSON 完整路径","",512,value->{try {
        var pack=PartsPackCodec.readPack(Path.of(value.strip()));var before=app.partsStore.catalog();var preview=app.partsStore.preview(new PartsPackCodec.Bundle(new PartsPackCodec.Saved("当前草稿",app.partsWorkbench().build()),List.of(pack)));var paragraphs=revisionDetails(app,List.of(pack),preview);
        h.review("收录部件包 · 修订检查",paragraphs,"收录这个部件包",()->{if(app.partsStore.catalog()!=before) {app.notifyUser("部件目录已变化，请重新导入并检查修订。");return;}app.installPartsPack(pack,true);});
    }catch(Exception error) {importError(h,app,error);}});}
    private void importBundle(HudRenderer h,FormulaGame app) {h.prompt("导入部件方案 JSON 完整路径","",512,value->{try {
        var bundle=PartsPackCodec.readBundle(Path.of(value.strip()));var before=app.partsStore.catalog();var preview=app.partsStore.preview(bundle);var checked=bundle.plan().build().review(preview,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());var paragraphs=new ArrayList<String>();paragraphs.add("方案："+bundle.plan().name()+"。收录后替换当前草稿，允许撤销；不会应用到车库。");paragraphs.addAll(revisionDetails(app,bundle.packs(),preview));if(checked.valid()) paragraphs.add("导入方案通过当前目录检查，仍需单独决定试车或应用。");else {paragraphs.add("导入方案待处理问题："+checked.issues().size());paragraphs.addAll(checked.issues());}
        h.review("收录部件方案 · 修订检查",paragraphs,"收录方案与部件包",()->{if(app.partsStore.catalog()!=before) {app.notifyUser("部件目录已变化，请重新导入并检查修订。");return;}app.importPartsBundle(bundle);});
    }catch(Exception error) {importError(h,app,error);}});}
    static List<String> revisionDetails(FormulaGame app,List<PartsCatalog.Pack> incoming,PartsCatalog preview) {
        var paragraphs=new ArrayList<String>();paragraphs.add("导入只收录目录；同名包会被替换，已有草稿、车库参数与命名方案保留。可翻页查看完整差异。");
        for(var pack:incoming) {var before=app.partsStore.packs().stream().filter(value->value.id().equals(pack.id())).findFirst().orElse(null);var revision=PartsRevision.compare(before,pack);paragraphs.add(pack.name()+" · "+pack.id()+" · 修订 "+revision.fromRevision()+" → "+revision.toRevision()+" · 功能变化 "+revision.functionalChanges()+" 项。");if(revision.changes().isEmpty()) paragraphs.add("此包与当前定义一致。");for(var change:revision.changes()) paragraphs.add(change.description());}
        var current=app.partsWorkbench().build().review(preview,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());paragraphs.add("保留的当前草稿："+(current.valid()?"通过新目录检查。":"有 "+current.issues().size()+" 项待处理，需明确重建或调节。"));
        var reference=app.partsWorkbench().reference();if(reference!=null) {var checked=reference.build().review(preview,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());paragraphs.add("固定的对照 A“"+reference.name()+"”："+(checked.valid()?"通过新目录检查。":"有 "+checked.issues().size()+" 项待处理，快照仍保留。"));}
        for(var plan:app.partsPlans.entries()) {var after=plan.build().review(preview,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());if(!after.valid()) paragraphs.add("保留的命名方案“"+plan.name()+"”：有 "+after.issues().size()+" 项待处理。可稍后读取查看，不自动改写。");}return List.copyOf(paragraphs);
    }
    private static void importError(HudRenderer h,FormulaGame app,Exception error) {
        if(error instanceof PartsImportException diagnostic) {app.notifyUser("导入未生效，请查看具体字段与修复建议。");h.review("部件文件诊断 · "+diagnostic.code(),List.of("位置（JSON Pointer）："+(diagnostic.pointer().isEmpty()?"根对象":diagnostic.pointer()),"原因："+diagnostic.detail(),"修复建议："+diagnostic.hint(),"本次没有收录包、替换草稿或应用车库。修好外部文件后重新导入。"),"关闭诊断",()->{});}
        else {app.notifyUser("导入未生效，请检查路径、文件和目录容量。");h.review("部件文件无法收录",List.of(error instanceof IllegalArgumentException && error.getMessage()!=null?error.getMessage():"文件无法读取。请确认完整路径、访问权限，以及所选导入入口。","最多八个玩家包；请先导出并移除不需要的包，再重试。","已有目录、草稿、命名方案与车库保留。"),"关闭",()->{});}
    }
    private void exportBundle(HudRenderer h,FormulaGame app) {
        var reviewed=app.reviewPartsDraftExport();
        if(reviewed==null) return;
        h.promptExportPath("导出部件方案完整路径",app.store.directory().resolve("parts-export-build.json"),
                (path,replace)->app.exportPartsDraft(reviewed,path,replace));
    }
    private void exportPack(HudRenderer h,FormulaGame app,PartsCatalog.Pack pack) {
        h.promptExportPath("导出部件包完整路径",app.store.directory().resolve("parts-export-"+pack.id()+".json"),
                (path,replace)->app.exportPartsTemplate(path,pack,replace));
    }
    private void save(HudRenderer h,FormulaGame app) {h.editText("部件草稿名称",app.partsStore.last()==null?"我的部件方案":app.partsStore.last().name(),"我的部件方案",40,name->{if(name.isBlank()) throw new IllegalArgumentException();if(app.partsStore.last()!=null) h.confirm("替换本地上次部件草稿？","当前仅暂存一份部件草稿，可先导出保存多套。\n当前车库与普通命名配置库保留。",()->app.savePartsDraft(name));else app.savePartsDraft(name);});}
    private void rebase(HudRenderer h,FormulaGame app) {var bench=app.partsWorkbench();var result=bench.build().rebase(bench.catalog(),app.vehicleSpec.id,app.vehicleSpec.physicsVersion);if(result.changes().isEmpty()) {app.notifyUser("草稿已匹配当前部件定义。");return;}rebasePage(h,bench,result,0);}
    private void rebasePage(HudRenderer h,PartsWorkbench bench,PartsBuild.Rebased result,int page) {
        int pages=(result.changes().size()+1)/2;boolean last=page==pages-1;
        String detail="变化 "+(page+1)+" / "+pages+" 页\n"+String.join("\n",result.changes().subList(page*2,Math.min(result.changes().size(),page*2+2)))+"\n"+(last?"确认后替换草稿，可撤销；之后仍需单独应用到车库。":"确认查看下一页；返回检查保留原草稿。");
        h.confirm("按当前目录重建草稿？",detail,()->{if(last) bench.adopt(result.build());else rebasePage(h,bench,result,page+1);});
    }
    private static String shorten(String value,int limit) {return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…";}
    private static String lines(String value) {var result=new StringBuilder();int count=0;for(int cp:value.codePoints().toArray()) {if(count++>0 && (count-1)%32==0) result.append('\n');result.appendCodePoint(cp);}return result.toString();}
    private static void icon(Graphics2D g,Slot slot,int x,int y,Color color) {
        g.setColor(color);g.setStroke(STROKE);
        switch(slot) {
            case POWER -> {g.drawRect(x+5,y+8,23,16);g.drawLine(x+10,y+3,x+24,y+3);g.drawLine(x+17,y+3,x+17,y+8);g.drawLine(x+1,y+12,x+5,y+12);g.drawLine(x+28,y+17,x+32,y+17);}
            case BRAKES -> {g.drawOval(x+3,y+3,27,27);g.drawOval(x+11,y+11,11,11);g.drawLine(x+4,y+16,x+10,y+16);g.drawLine(x+22,y+16,x+29,y+16);g.drawLine(x+16,y+4,x+16,y+10);}
            case SUSPENSION -> {g.drawLine(x+9,y+2,x+23,y+2);g.drawLine(x+16,y+2,x+16,y+6);for(int i=0;i<4;i++) {g.drawLine(x+9,y+7+i*5,x+23,y+10+i*5);g.drawLine(x+23,y+10+i*5,x+9,y+12+i*5);}g.drawLine(x+16,y+27,x+16,y+31);g.drawLine(x+9,y+31,x+23,y+31);}
            case AERO -> {g.drawLine(x+1,y+9,x+32,y+9);g.drawLine(x+4,y+14,x+29,y+14);g.drawLine(x+8,y+14,x+10,y+27);g.drawLine(x+24,y+14,x+22,y+27);g.drawLine(x+5,y+27,x+27,y+27);}
        }
    }
}
