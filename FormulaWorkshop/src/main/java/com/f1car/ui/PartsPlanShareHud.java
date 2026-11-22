package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PartsPlanShareCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** A received plan is a frozen preview. Saving, installing parts and reading B are separate actions. */
final class PartsPlanShareHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private PartsPlanShare incoming;private PartsPlan copy;private boolean open;
    private PartsPlanShare checkedShare;private PartsCatalog checkedCatalog;private String checkedVehicle,checkedPhysics;private int checkedLivery;private PartsBuild.Review review;
    boolean showing() {return open && incoming!=null;}
    void show(PartsPlanShare value) {if(!value.equals(incoming)) {incoming=value;copy=value.localCopy();}open=true;}
    boolean render(HudRenderer h,FormulaGame app,PartsPlan selected,Consumer<PartsPlanShare> discuss) {
        h.smallButton(600,119,294,27,"分享所选方案的说明与来源",()->export(h,app,selected));h.smallButton(914,119,300,27,incoming==null?"收取带说明的方案":"继续收到的方案",()->{if(incoming==null) importFile(h,app);else open=true;});
        if(!open || incoming==null) return false;var share=incoming;var catalog=app.partsStore.catalog();int livery=app.store.profile.tuning.livery();
        if(checkedShare!=share || checkedCatalog!=catalog || !Objects.equals(checkedVehicle,app.vehicleSpec.id) || !Objects.equals(checkedPhysics,app.vehicleSpec.physicsVersion) || checkedLivery!=livery) {checkedShare=share;checkedCatalog=catalog;checkedVehicle=app.vehicleSpec.id;checkedPhysics=app.vehicleSpec.physicsVersion;checkedLivery=livery;review=share.plan().build().review(catalog,checkedVehicle,checkedPhysics,livery);}
        h.text("收到的方案 · "+cut(share.plan().name(),20),60,194,24,MINT,true);h.text("署名："+cut(share.author(),30),60,235,17,INK);h.text("来源记录 "+share.trail().steps().size()+" 条"+(share.trail().omitted()>0?"，另省略 "+share.trail().omitted()+" 条":""),600,194,17,SOFT);
        h.smallButton(60,264,500,39,"查看完整原文、来源与部件参数",()->h.review("收到的方案 · 完整说明",details(share),"关闭",()->{}));
        h.smallButton(60,316,500,39,"分别收录携带的部件包",()->install(h,app,share));
        var local=app.partsPlans.find(copy.id());h.smallButton(60,368,500,39,local==null?"另存独立命名卡与来源":"此收到方案已另存 · 再检查",()->{var expected=copy;var lines=details(share);lines.add("只创建独立本地身份，不安装部件，不改变工作台、对照 A、车库或成绩。缺件或旧物理方案仍可另存为待处理卡。");h.review("另存收到的方案？",lines,"另存本地命名卡",()->app.receivePortablePartsPlan(share,expected));});
        h.smallButton(60,420,500,39,"把原参数读为草稿 B（可撤销）",()->{var before=app.partsWorkbench().build();var expected=app.partsStore.catalog();var checked=share.plan().build().review(expected,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());var lines=details(share);lines.addAll(checked.issues());lines.add("读取允许保留缺件／失配草稿，不自动重建或安装；应用与试车仍需通过当前检查。");h.review("读取收到的原始参数？",lines,"读为可撤销草稿 B",()->app.loadPortablePartsDraft(share,expected,before));});
        h.smallButton(60,485,244,34,"返回命名库 · 保留预览",()->open=false);h.smallButton(316,485,244,34,"打开另一份文件",()->h.confirm("替换收到方案的预览？","未另存的预览将放弃，已存本地卡与原文件保持。",()->importFile(h,app)));
        h.wrapped("接收不等于采用。先看用途与代价，再决定收录部件、另存方案或读取 B，之后才试车和应用。",72,558,470,18,SOFT,29);
        h.text(review.valid()?"通过当前目录检查，仍需试车":"当前目录下有 "+review.issues().size()+" 项待处理",600,235,17,review.valid()?MINT:AMBER,true);
        h.smallButton(600,263,614,34,"查看全部当前检查与部件修订影响",()->{var lines=new ArrayList<String>();lines.addAll(review.issues());try {var preview=app.partsStore.previewPacks(share.packs());lines.addAll(PartsHud.revisionDetails(app,share.packs(),preview));}catch(IllegalArgumentException error) {lines.add(error.getMessage());}if(lines.isEmpty()) lines.add("当前检查通过；点数不是性能评级。");h.review("收到方案的当前条件",lines,"关闭",()->{});});
        int row=0;for(var slot:PartsCatalog.Slot.values()) {var selection=share.plan().build().slots().get(slot);h.text(slot.title+"："+cut(selection.part(),32),600,330+row++*23,15,MINT);for(var field:slot.fields()) h.text(field.title+"："+selection.values().get(field)+"%",600,330+row++*23,14,INK);}
        h.smallButton(60,610,500,30,"围绕这份原始方案讨论",()->discuss.accept(share));
        h.text("原车型："+cut(share.plan().build().vehicle(),33),60,652,14,SOFT);h.text("原物理："+cut(share.plan().build().physics(),40),600,652,14,SOFT);
        if(!app.partsPlans.warning().isEmpty()) h.text(cut(app.partsPlans.warning(),65),60,671,13,AMBER);else if(!app.partsStore.warning().isEmpty()) h.text(cut(app.partsStore.warning(),65),60,671,13,AMBER);return true;
    }
    private void importFile(HudRenderer h,FormulaGame app) {h.prompt("带说明的命名方案 JSON 完整路径",app.store.directory().resolve("parts-export-shared-plan.json").toAbsolutePath().toString(),512,text->{try {var value=PartsPlanShareCodec.read(Path.of(text.strip()));incoming=value;copy=value.localCopy();open=true;app.notifyUser("已读取只读预览；部件、草稿与车库保持。");}catch(java.io.IOException | RuntimeException error) {app.notifyUser("方案无法读取；请核对文件种类、校验、大小和路径。旧预览与本地资料保留。");}});}
    private void install(HudRenderer h,FormulaGame app,PartsPlanShare share) {if(share.packs().isEmpty()) {app.notifyUser("此方案没有携带玩家部件包；可查看当前检查。");return;}var expected=app.partsStore.catalog();try {var preview=app.partsStore.previewPacks(share.packs());h.review("收录收到方案携带的部件？",PartsHud.revisionDetails(app,share.packs(),preview),"收录这些完整部件包",()->app.installPortableParts(share,expected));}catch(IllegalArgumentException error) {h.review("部件暂不能收录",List.of(error.getMessage(),"已有目录、方案和草稿保留；可先另存收到的方案，调整目录后重试。"),"关闭",()->{});}}
    private void export(HudRenderer h,FormulaGame app,PartsPlan selected) {
        if(selected==null) {app.notifyUser("先选择一份命名方案，再分享说明与来源。");return;}
        var catalog=app.partsStore.catalog();
        var trail=app.partsPlans.trail(selected.id());
        h.editText("本次分享署名",app.store.profile.playerName,"玩家",40,author->{
            if(!PartsCatalog.text(author,40) || author.isBlank()) throw new IllegalArgumentException();
            var share=PartsPlanShare.capture(selected,author,trail,catalog);
            h.review("分享所选方案的完整内容？",details(share),"选择独立导出文件",
                    ()->h.promptExportPath("带说明方案 JSON 完整导出路径",
                            app.store.directory().resolve("parts-export-shared-plan.json"),
                            (path,replace)->app.exportPortablePartsPlan(selected,share,catalog,path,replace)));
        });
    }
    static ArrayList<String> details(PartsPlanShare share) {var lines=new ArrayList<String>();lines.add("方案："+share.plan().name()+"；本次署名："+share.author());lines.add("完整备注："+share.plan().notes());lines.add("源方案身份："+share.plan().id());lines.add("车型："+share.plan().build().vehicle()+"；物理："+share.plan().build().physics()+"；试验预算："+share.plan().build().budget());for(var step:share.trail().steps()) {lines.add(step.title()+"："+step.name()+"；署名："+step.author());lines.add("来源身份："+step.id()+"；内容摘要："+step.digest());}if(share.trail().omitted()>0) lines.add("仅携带最近八条，已有 "+share.trail().omitted()+" 条更早记录未携带。");if(share.trail().steps().isEmpty()) lines.add("没有声明上游来源，不据名称猜测作者。");share.plan().build().slots().forEach((slot,value)->{lines.add(slot.title+"："+value.part()+"；功能指纹："+value.fingerprint());value.values().forEach((field,amount)->lines.add(field.title+"："+amount+"%"));});for(var pack:share.packs()) {lines.add("携带完整部件包："+pack.name()+"；作者："+pack.author()+"；命名空间："+pack.id()+"；修订 "+pack.revision());for(var change:PartsRevision.compare(null,pack).changes()) lines.add(change.description());}lines.add("署名和来源是声明快照，不认证作者；本地编辑后可能已不同。交换不带车库、成绩、资格或强制采用。底部原“导出方案”仍是旧格式，不携带本次说明。");return lines;}
    private static String cut(String value,int max) {return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max-1))+"…";}
}
