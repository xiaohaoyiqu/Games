package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.util.*;

/** Named plans are reusable drafts. A is a frozen reference, B is the live draft, neither auto-applies. */
final class PartsPlansHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72),CARD=new Color(0x19323d);
    private String query="",cachedQuery,selected;
    private List<PartsPlan> cachedEntries,filtered=List.of();
    private int page;
    private final PartsPlanShareHud shareHud=new PartsPlanShareHud();
    private TuningPurpose.Intent intent=TuningPurpose.Intent.defaults();
    private boolean draftsLoaded;
    private void loadDrafts(FormulaGame app) {if(draftsLoaded) return;draftsLoaded=true;if(app.purposeEditing.current()!=null) intent=app.purposeEditing.current();discussionHud.restoreDraft(app);}
    void captureDrafts(FormulaGame app) {if(draftsLoaded) {app.purposeEditing.capture(intent);discussionHud.captureDraft(app);}}
    private final PartsTrialEvidenceHud evidenceHud=new PartsTrialEvidenceHud();
    private final PartsDiscussionHud discussionHud=new PartsDiscussionHud();
    boolean sharedPreviewOpen() {return shareHud.showing() || evidenceHud.showing() || discussionHud.showing();}
    void openEvidence() {evidenceHud.open();}
    void openDiscussion() {discussionHud.open();}
    void render(HudRenderer h,FormulaGame app) {
        loadDrafts(app);
        var entries=app.partsPlans.entries();if(cachedEntries!=entries || !Objects.equals(cachedQuery,query)) {String term=query.toLowerCase(Locale.ROOT);filtered=entries.stream().filter(plan->(plan.name()+" "+plan.notes()+" "+plan.id()).toLowerCase(Locale.ROOT).contains(term)).toList();cachedEntries=entries;cachedQuery=query;}
        if(selected==null || filtered.stream().noneMatch(plan->plan.id().equals(selected))) selected=filtered.isEmpty()?null:filtered.get(0).id();
        if(discussionHud.showing()) {discussionHud.render(h,app,app.partsPlans.find(selected),share->{discussionHud.close();evidenceHud.close();shareHud.show(share);});return;}
        if(evidenceHud.showing()) {evidenceHud.render(h,app,app.partsPlans.find(selected),share->{evidenceHud.close();shareHud.show(share);},evidence->discussionHud.compose(h,evidence.plan(),evidence));return;}
        if(shareHud.render(h,app,app.partsPlans.find(selected),share->discussionHud.compose(h,share,null))) return;
        h.smallButton(60,214,244,34,"另存当前草稿 · "+entries.size()+"/16",()->save(h,app));h.smallButton(316,214,244,34,query.isEmpty()?"搜索命名方案":"搜索："+shorten(query,10),()->h.prompt("搜索名称／备注／身份；留空显示全部",query,80,value->{query=value.strip();page=0;}));
        int pages=Math.max(1,(filtered.size()+4)/5);page=Math.max(0,Math.min(pages-1,page));
        for(int index=page*5;index<Math.min(filtered.size(),page*5+5);index++) {var plan=filtered.get(index);int y=262+(index-page*5)*68;h.panel(60,y,500,61,CARD);h.smallButton(72,y+6,475,30,(plan.id().equals(selected)?"● ":"")+shorten(plan.name(),25),()->selected=plan.id());h.text(shorten(plan.notes().isBlank()?"选择后可读为 B、固定为 A 或编辑。":plan.notes(),34),82,y+53,13,SOFT);}
        if(filtered.isEmpty()) h.wrapped(entries.isEmpty()?"这里保存多份部件方案。先另存当前草稿，再读取为 B 或固定为对照 A。":"没有匹配方案。清空搜索可显示全部。",72,292,470,18,SOFT,28);
        h.smallButton(60,618,130,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,278,642,15,SOFT);h.smallButton(430,618,130,34,"下一页",()->page=Math.min(pages-1,page+1));
        var plan=app.partsPlans.find(selected);h.text(plan==null?"命名方案与对照试车":shorten(plan.name(),23),600,194,24,MINT,true);h.text(plan==null?"选中方案不会改变草稿或车库。":shorten(plan.notes().isBlank()?"选中方案不会自动参与对照；先读为 B 或固定为 A。":plan.notes(),31),600,235,15,SOFT);
        if(plan!=null) {
            h.smallButton(1090,209,124,30,"来源与说明",()->h.review("命名方案详情",details(plan,app),"关闭",()->{}));
            h.smallButton(600,264,144,35,"读为草稿 B",()->app.loadPartsPlan(plan.id()));
            h.smallButton(758,264,144,35,"用草稿覆盖",()->h.review("覆盖命名方案参数？",details(plan,app.partsWorkbench().build()),"覆盖所选方案",()->app.updatePartsPlan(plan,plan.name(),plan.notes(),true)));
            h.smallButton(916,264,144,35,"名称与备注",()->edit(h,app,plan));
            h.smallButton(1074,264,140,35,"移除此方案",()->h.confirm("移除命名方案？",shorten(plan.name(),30)+"\n草稿、对照 A 快照、车库与外部导出文件保留。",()->app.removePartsPlan(plan)));
            h.smallButton(600,310,294,35,"将所选方案固定为对照 A",()->app.referencePartsPlan(plan.id()));
            h.smallButton(600,350,614,24,"按当前目录预览并另存迁移副本",()->migrate(h,app,plan));
        }
        h.smallButton(914,310,300,35,"对照 A 改用当前车库",app::clearPartsReference);
        var comparison=app.partsComparison();h.text("A："+shorten(comparison.baselineName(),14)+(app.partsWorkbench().reference()==null?"":"（快照）"),600,375,14,SOFT);h.text("B：当前部件草稿",1000,375,14,MINT);
        int index=0;for(var value:comparison.values()) {int y=400+index++*20;h.text(value.field().title,600,y,14,INK);h.text(value.a()+"%",850,y,14,SOFT);h.text(value.b()+"%"+(value.changed()?"  *":""),1030,y,14,value.changed()?MINT:SOFT);}
        if(!app.partsPlans.warning().isEmpty()) h.text(shorten(app.partsPlans.warning(),36),60,665,13,AMBER);
    }
    void trials(HudRenderer h,FormulaGame app) {
        loadDrafts(app);
        h.smallButton(600,679,614,25,app.purposeEditing.status(),()->DraftRecoveryHud.review(h,app,app.purposeEditing));
        var comparison=app.partsComparison();h.panel(600,585,614,91,CARD);h.text("A／B 差异 "+comparison.changedSettings()+" 项 · "+(comparison.valid()?"交错各测三次":"请先查看对照问题"),617,610,16,comparison.valid()?MINT:AMBER,true);h.smallButton(1074,589,130,28,"试车问题",()->editIntent(h));
        var exercises=TuningTrial.Exercise.values();for(int i=0;i<exercises.length;i++) {var exercise=exercises[i];h.smallButton(610+i*200,626,190,34,new String[]{"加速 A／B","制动 A／B","连续弯 A／B"}[i],()->trial(h,app,exercise));}
    }
    void save(HudRenderer h,FormulaGame app) {h.editText("新部件方案名称","我的部件方案","我的部件方案",40,name->{if(!PartsCatalog.text(name,40) || name.isBlank()) throw new IllegalArgumentException();h.editText("本地备注（不随格式 1 导出）","","",120,notes->{var plan=app.savePartsPlan(name,notes);if(plan!=null) {selected=plan.id();query="";page=0;}});});}
    private void edit(HudRenderer h,FormulaGame app,PartsPlan expected) {h.editText("部件方案名称",expected.name(),expected.name(),40,name->{if(!PartsCatalog.text(name,40) || name.isBlank()) throw new IllegalArgumentException();h.editText("本地备注（不随格式 1 导出）",expected.notes(),"",120,notes->app.updatePartsPlan(expected,name,notes,false));});}
    void exportSelected(HudRenderer h,FormulaGame app) {
        var plan=app.partsPlans.find(selected);if(plan==null) {app.notifyUser("请先选择一份命名方案；当前草稿可在部件页导出。");return;}
        var reviewed=app.reviewPartsPlanExport(plan);
        if(reviewed==null) return;
        h.promptExportPath("导出所选命名方案完整路径",app.store.directory().resolve("parts-export-plan.json"),
                (path,replace)->app.exportPartsPlan(plan,reviewed,path,replace));
    }
    private void trial(HudRenderer h,FormulaGame app,TuningTrial.Exercise exercise) {
        var comparison=app.partsComparison();if(!comparison.valid()) {h.review("对照问题",comparison.issues(),"关闭",()->{});return;}var track=app.partsTrialTrack();var controls=app.controls.settings();
        try {var purpose=TuningPurpose.Draft.prepare(app.partsStore.catalog(),app.partsWorkbench().reference(),app.partsWorkbench().build(),intent);var lines=new ArrayList<>(TuningInterpretation.preview(comparison,exercise,purpose));lines.add(exercise==TuningTrial.Exercise.FLOW?"赛道："+track.name:"场地：综合训练场；路线条件固定。");lines.add("九项性能差异："+comparison.changedSettings()+"。开始时固定参数、操作、部件说明与试车问题，交错各测三次。");lines.add("车库、快捷槽、命名方案与草稿保留；试车后可返回工作台。用途记录随报告保存，未提交用途会保存为独立草稿。");lines.add("同时改变多项时，不能把结果归因于其中一项。");h.review("开始 "+exercise.title+"？",lines,"开始对照试车",()->app.beginPartsTuning(comparison,exercise,track,purpose,controls));}catch(IllegalArgumentException | NullPointerException error) {app.notifyUser("请先核对部件来源与定义；草稿保留。");}
    }
    private void editIntent(HudRenderer h) {var original=intent;h.editText("本次试车要解决什么问题？",original.question(),TuningPurpose.Intent.defaults().question(),120,question->{if(question.isBlank()) throw new IllegalArgumentException();intent=new TuningPurpose.Intent(question,original.expectation(),original.concern());h.editText("预期变化（等待试车验证）",original.expectation(),"",120,expectation->{intent=new TuningPurpose.Intent(question,expectation,original.concern());h.editText("关心的代价或其它表现",original.concern(),"",120,concern->intent=new TuningPurpose.Intent(question,expectation,concern));});});}
    private static List<String> details(PartsPlan plan) {return details(plan,plan.build());}
    private static List<String> details(PartsPlan plan,FormulaGame app) {var lines=new ArrayList<>(details(plan));var trail=app.partsPlans.trail(plan.id());if(trail.steps().isEmpty()) lines.add("没有记录来源；旧方案不按名称推断作者或来源包。");for(var source:trail.steps()) {lines.add(source.title()+"："+source.name());if(!source.author().isBlank()) lines.add("原始署名："+source.author());lines.add("来源身份："+source.id()+"；摘要："+source.digest());}if(trail.omitted()>0) lines.add("更早的 "+trail.omitted()+" 条来源未携带。");lines.add("来源是声明快照，不跟随源内容变化；本地编辑可能已与来源不同。");lines.add("底部旧格式 1 导出不携带备注／来源；上方“分享说明与来源”入口可携带这些内容。");return List.copyOf(lines);}
    private void migrate(HudRenderer h,FormulaGame app,PartsPlan plan) {
        var catalog=app.partsStore.catalog();var preview=PartsPlanMigration.prepare(plan,catalog,app.vehicleSpec.id,app.vehicleSpec.physicsVersion);var lines=new ArrayList<String>();lines.add("原方案："+plan.name()+"；旧方案和已固定的对照 A 保留。");lines.add("按当前车型、物理和目录另建副本：同槽同身份部件保留参数并限幅，缺失或改槽部件改用原厂默认；逐项变化如下。");if(preview.changes().isEmpty()) lines.add("无需迁移：方案已匹配当前条件。");else lines.addAll(preview.changes());lines.add(Tuning.performanceDescription(preview.candidate().tuning(0).performanceKey()));if(!preview.issues().isEmpty()) {lines.add("迁移后仍有待处理问题；副本仅作草稿，不可直接应用或试车：");lines.addAll(preview.issues());}lines.add("确认只另存独立命名卡及迁移前来源，不读取工作台、改变车库、替换活动或携带成绩／地图资格。");
        lines.add("原方案参数："+Tuning.performanceDescription(plan.build().tuning(0).performanceKey()));
        for(var slot:PartsCatalog.Slot.values()) {var part=catalog.find(preview.candidate().slots().get(slot).part());lines.add("当前"+slot.title+"："+part.name()+"；"+part.points()+" 点；用途："+part.purpose()+"；代价："+part.tradeoff());part.settings().forEach((field,range)->lines.add(field.title+"范围："+range.min()+"%–"+range.max()+"%，默认 "+range.initial()+"%"));lines.add("需要："+String.join(", ",new TreeSet<>(part.requires()))+"；禁配："+String.join(", ",new TreeSet<>(part.excludes())));}
        h.review("迁移命名方案 · 查看副本",lines,preview.changed()?preview.issues().isEmpty()?"另存迁移副本":"另存待处理草稿":"关闭，无需迁移",()->{if(preview.changed()) {var copy=app.migratePartsPlan(preview,catalog);if(copy!=null) {selected=copy.id();query="";page=0;}}});
    }
    private static List<String> details(PartsPlan plan,PartsBuild build) {var lines=new ArrayList<String>();lines.add("方案："+plan.name());lines.add("备注："+plan.notes());lines.add("身份："+plan.id());lines.add("以下参数"+(build.equals(plan.build())?"来自所选方案。":"来自当前草稿，将替换所选方案；名称和备注保留。"));lines.add("车型："+build.vehicle());lines.add("物理："+build.physics());lines.add("试验点数上限："+build.budget());build.slots().forEach((slot,value)->{lines.add(slot.title+"："+value.part());lines.add("功能指纹："+value.fingerprint());value.values().forEach((field,percent)->lines.add(field.title+"："+percent+"%"));});lines.add("保存或覆盖方案不会应用到车库，允许保存尚有待处理问题的草稿。");return List.copyOf(lines);}
    private static String shorten(String value,int max) {return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max-1))+"…";}
}
