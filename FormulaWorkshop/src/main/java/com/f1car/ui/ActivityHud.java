package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.ContentTextStore.Text;
import com.f1car.persistence.ActivityCardCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Offline card library, itinerary authoring and explicit continuation of accepted stages. */
final class ActivityHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114),PANEL=new Color(10,29,39,245);
    private boolean editing,history;
    private int selected,page,templateIndex,step;
    private String name="我的驾驶组合";
    private String notes="",author="",defaultName="我的驾驶组合",baseline="";
    private String pendingCardId;
    private long seed=20261112;
    private final java.util.List<ActivityCard.Stage> draft=new ArrayList<>();
    private java.util.List<ActivityCard.Template> templates;
    private PartsActivityRule partsRule;private boolean rulesEditing;private final ActivityPartsHud partsHud=new ActivityPartsHud();
    private final List<ActivityObjective> objectives=new ArrayList<>();private boolean objectivesEditing;private final ActivityObjectivesHud objectivesHud=new ActivityObjectivesHud();
    private ActivityCard keyedCard;private PartsBuild keyedBuild;private String keyedControls,keyedResult;
    private ActivityRun resultCheckpoint;
    private String resultTotal,resultBestKey;
    void board(HudRenderer h,FormulaGame app) {
        h.panel(38,32,1204,732,PANEL);h.text("LOCAL ACTIVITY CARDS",70,74,14,MINT,true);h.text("活动卡与自选组合",70,119,34,INK,true);
        h.text("原厂或限定部件 · 按项保存 · 离线可玩 · 保存的组合长期保留",72,144,16,MUTED);h.smallButton(1060,68,146,42,"返回主菜单",app::mainMenu);
        if(rulesEditing) {partsHud.render(h,app,rule->{if(rule!=null && draft.stream().anyMatch(s->s.target()!=null)) {app.notifyUser("作者计时目标固定原厂条件，请先另建无目标的活动草稿。");return;}partsRule=rule;rulesEditing=false;},()->rulesEditing=false);return;}
        if(objectivesEditing) {objectivesHud.render(h,app,value->{objectives.removeIf(row->row.stage()==step);if(value!=null) objectives.add(value);objectives.sort(Comparator.comparingInt(ActivityObjective::stage));objectivesEditing=false;},()->objectivesEditing=false);return;}
        h.smallButton(70,171,210,40,"新建自选组合",()->replaceDraft(h,app,ActivityCard.create("我的驾驶组合",20261112,app.vehicleSpec.physicsVersion,java.util.List.of(ActivityCard.Stage.create(ActivityCard.Kind.LAP,app.circuit,1,1,0)))));
        if(templates==null) templates=ActivityCard.templateLibrary(app.vehicleSpec.physicsVersion);
        h.smallButton(296,171,280,40,"模板 "+(templateIndex+1)+"/"+templates.size()+"："+templates.get(templateIndex).card().name(),()->templateIndex=(templateIndex+1)%templates.size());
        h.smallButton(585,171,28,40,"‹",()->templateIndex=Math.floorMod(templateIndex-1,templates.size()));
        h.smallButton(619,171,28,40,"›",()->templateIndex=(templateIndex+1)%templates.size());
        h.smallButton(662,171,180,40,"使用所选模板",()->replaceDraft(h,app,templates.get(templateIndex).card()));
        h.smallButton(858,171,348,40,history?"查看我的活动卡":"查看最近八次完成",()->leaveDraft(h,()->{history=!history;editing=false;page=0;selected=0;}));
        if(editing) editor(h,app);else library(h,app);
        h.text(templates.get(templateIndex).description(),72,227,12,MUTED);
    }
    private Text text(FormulaGame app,ActivityCard card) { return app.contentText.get("activity:"+card.id(),new Text(card.name(),"","")); }
    private void load(FormulaGame app,ActivityCard card) { if(pendingCardId!=null) { app.notifyUser("请先完成上一张活动卡的保存。");return; }var text=text(app,card);defaultName=app.contentText.defaults("activity:"+card.id(),new Text(card.name(),"","")).name();name=text.name();notes=text.notes();author=text.author();seed=card.seed();partsRule=card.partsRule();objectives.clear();if(card.objectives()!=null) objectives.addAll(card.objectives());draft.clear();draft.addAll(card.stages());step=0;editing=true;baseline=snapshot(); }
    private String snapshot() { return name+"\n"+notes+"\n"+author+"\n"+seed+"\n"+draft+"\n"+partsRule+"\n"+objectives; }
    private void leaveDraft(HudRenderer h,Runnable action) { if(pendingCardId!=null) return;if(editing && !baseline.equals(snapshot())) h.confirm("放弃未保存的活动草稿？","本次编辑尚未保存。\n已保存活动卡、进行中的组合和历史保留。",action);else action.run(); }
    private void replaceDraft(HudRenderer h,FormulaGame app,ActivityCard card) { leaveDraft(h,()->load(app,card)); }
    private void retrySave(HudRenderer h,FormulaGame app) {
        if(!app.activityStore.conflictReviewAvailable()) app.saveActivities();
        var review=app.activityStore.reviewConflict();
        if(review!=null && review.canKeepDisk()) {
            h.review("核对两份活动档案",review.details(),"采用磁盘进度…",()->
                h.confirm("采用磁盘进度？","本窗口这一次待存结果将被放弃。\n磁盘上的成绩和活动卡保留。",()->{
                    if(app.activityStore.keepDiskAfterResultConflict(review)) app.notifyUser("已采用磁盘进度，可返回活动库继续。");
                    else app.notifyUser(app.activityStore.warning);
                }));
        } else if(review!=null) {
            h.review("核对两份活动档案",review.details(),review.valid()?"确认并保存这份合并":"返回活动",()->{
                if(review.valid()) {
                    if(app.activityStore.applyConflict(review)) {
                        app.saveActivities();
                        finishEditingSave(app);
                    } else app.notifyUser(app.activityStore.warning);
                }
            });
        } else finishEditingSave(app);
    }

    private void finishEditingSave(FormulaGame app) {
        if(app.activityStore.pending() || pendingCardId==null) return;
        if(!app.contentText.put("activity:"+pendingCardId,new Text(defaultName,author,""),new Text(name,author,notes))) {
            app.notifyUser(app.contentText.warning);
            return;
        }
        editing=false;history=false;
        for(int i=0;i<app.activityStore.cards().size();i++) {
            if(app.activityStore.cards().get(i).id().equals(pendingCardId)) selected=i;
        }
        page=selected/6;pendingCardId=null;
    }
    private void editLibraryText(HudRenderer h,FormulaGame app,ActivityCard card,boolean note) {
        String key="activity:"+card.id();var value=text(app,card);var defaults=app.contentText.defaults(key,new Text(card.name(),"",""));
        h.editTextChecked(note?"活动备注（单行）":"活动卡名称",note?value.notes():value.name(),note?defaults.notes():defaults.name(),note?240:40,newValue->{
            if(!value.equals(text(app,card))) {app.notifyUser("原名称或备注已变化；输入保留，请取消后重新打开核对。");return false;}
            var next=note?value.notes(newValue):value.name(newValue);boolean saved=app.contentText.put(key,defaults,next);app.notifyUser(saved?"名称与备注已保存；活动规则与进度保留。":app.contentText.warning);return saved;
        });
    }
    private void library(HudRenderer h,FormulaGame app) {
        var cards=history?app.activityStore.recent().stream().map(ActivityRun::card).toList():app.activityStore.cards();
        selected=Math.max(0,Math.min(selected,cards.size()-1));page=Math.max(0,Math.min(page,Math.max(0,(cards.size()-1)/6)));
        h.panel(70,230,365,396,new Color(24,49,60));h.text(history?"最近完成的组合":"我的活动卡（最多十二张）",88,260,19,INK,true);
        for(int i=page*6;i<Math.min(cards.size(),page*6+6);i++) { int index=i,y=276+(i-page*6)*48;var card=cards.get(i);String label=(selected==i?"● ":"")+shorten(text(app,card).name(),16);if(history) label+=" · "+PracticeSession.format(app.activityStore.recent().get(i).total());h.smallButton(85,y,335,41,label,()->selected=index); }
        if(cards.size()>6) { h.smallButton(85,576,156,35,"上一页",()->page=Math.max(0,page-1));h.smallButton(255,576,165,35,"下一页",()->page=Math.min((cards.size()-1)/6,page+1)); }
        h.panel(455,230,751,414,new Color(24,49,60));
        h.smallButton(85,618,335,26,"导入活动卡 JSON",()->leaveDraft(h,()->importCard(h,app)));
        if(cards.isEmpty()) { h.text("从模板或自选组合开始",480,281,25,MINT,true);h.wrapped("选择已安装地图并排列活动。每项成功后保存进度；中途退出，下次从未完成项继续。活动卡不覆盖普通圈速、挑战册或杯赛。",480,332,690,18,INK,31); }
        else {
            var card=cards.get(selected);h.smallButton(480,239,520,38,"名称："+shorten(text(app,card).name(),24),()->editLibraryText(h,app,card,false));h.smallButton(1014,239,170,38,"编辑备注",()->editLibraryText(h,app,card,true));h.text("种子 "+card.seed()+" · "+card.stages().size()+" 项 · "+card.rulesTitle(),480,298,15,MUTED);
            for(int i=0;i<card.stages().size();i++) { var s=card.stages().get(i);h.text((i+1)+"  "+card.stageTitle(s)+" · "+shorten(s.trackName(),20)+" · "+s.laps()+" 圈",480,330+i*27,16,INK); }
            String reason=app.activityCompatibility(card);if(reason.isEmpty() && card.partsRule()!=null) {var review=app.activityPartsReview(card);if(!review.valid()) reason=review.issues().get(0);}String resultKey=resultKey(app,card);var best=resultKey==null?null:app.activityStore.best(resultKey);
            if(!text(app,card).notes().isBlank()) h.text("备注："+shorten(text(app,card).notes(),48),480,540,12,MUTED);
            h.text(reason.isEmpty()?best==null?"当前操作条件尚无完整组合记录":"同条件最佳总用时 "+PracticeSession.format(best.total()):shorten(reason,45),480,561,15,reason.isEmpty()?MUTED:ORANGE);
            h.smallButton(480,577,220,35,history?"存回活动库":"从第一项重新开始",()->{if(history) { app.saveActivityCard(card);history=false;selected=0; }else start(h,app,card);});
            h.smallButton(713,577,220,35,"复制并编辑新卡",()->load(app,card));
            if(!history) h.smallButton(946,577,238,35,"删除活动卡",()->h.confirm("删除这张活动卡？",text(app,card).name()+"\n已接受进度、历史和最佳成绩保留。",()->{if(!app.activityStore.remove(card.id())) app.notifyUser(app.activityStore.warning);selected=Math.max(0,selected-1);}));
            else h.smallButton(946,577,238,35,"查看逐项观测",()->{var run=app.activityStore.recent().get(selected);var lines=new ArrayList<String>();for(var result:run.results()) {lines.add("第 "+(result.stage()+1)+" 项："+result.detail());var objective=run.card().objective(result.stage());if(objective!=null) {lines.addAll(objective.describe());for(var row:objective.assess(result.elapsed(),result.laps(),result.objectiveEvidence(),true)) lines.add(row.name()+" · "+row.status().title+" · "+row.detail());}lines.addAll(sectionResults(run.card(),result));}h.review("完成组合的逐项观测",lines,"关闭",()->{});});
            h.smallButton(480,618,220,26,"查看规则与方案",()->ruleDetails(h,app,card));h.smallButton(713,618,220,26,"工作台准备参赛",app::openActivityPartsWorkbench);h.smallButton(946,618,238,26,"导出这张活动卡",()->exportCard(h,app,card));
        }
        progress(h,app);
    }
    private void start(HudRenderer h,FormulaGame app,ActivityCard card) {
        var active=app.activityStore.active();
        var rule=card.partsRule();
        var entry=rule==null?null:app.activityPartsBuild();
        var catalog=app.partsStore.catalog();
        var review=rule==null?null:app.activityPartsReview(card);
        Runnable begin=()->app.beginActivity(card,active,entry,rule==null?null:catalog);
        if(card.objectives()!=null) {
            var lines=conditions(card);
            lines.addAll(ActivityPartsHud.describe(rule,catalog));
            if(review!=null) {
                lines.addAll(review.issues());
                if(!review.valid()) {
                    h.review("复合活动参赛条件待处理",lines,"关闭",()->{});
                    return;
                }
            }
            lines.add("开始会替换当前未完成组合，已完成历史与最佳成绩保留；整场条件固定。");
            h.review("复合活动完整目标",lines,"确认条件并开始",begin);
        } else if(review!=null) {
            var lines=new ArrayList<>(ActivityPartsHud.describe(rule,catalog));
            lines.add("参赛工作台方案："+review.points()+" 点。预算使用活动上限；不会应用到车库。整场固定此方案，对手采用相同性能。");
            lines.add(Tuning.performanceDescription(review.candidate().performanceKey()));
            lines.add("开始会替换当前未完成组合；完整历史、最佳成绩和车库保留。");
            lines.addAll(review.issues());
            h.review("限定部件参赛预览",lines,review.valid()?"固定此方案并开始":"关闭并修改方案",()->{if(review.valid()) begin.run();});
        } else if(active!=null && !active.complete()) {
            h.confirm("重新开始这张活动卡？","当前组合的未完成进度会被替换。\n完整历史与最佳成绩保留。",begin);
        } else begin.run();
    }
    private String resultKey(FormulaGame app,ActivityCard card) {
        var build=card.partsRule()==null?null:app.activityPartsBuild();String controls=app.controls.settings().identity();
        if(keyedCard!=card || keyedBuild!=build || !Objects.equals(keyedControls,controls)) {keyedCard=card;keyedBuild=build;keyedControls=controls;var entry=card.partsRule()==null?null:card.partsRule().normalize(build);keyedResult=card.partsRule()!=null && !card.partsRule().acceptsIdentity(entry)?null:ActivityRun.key(card,controls,entry);}return keyedResult;
    }
    private void ruleDetails(HudRenderer h,FormulaGame app,ActivityCard card) {
        var lines=new ArrayList<>(ActivityPartsHud.describe(card.partsRule(),app.partsStore.catalog()));lines.add("成绩按相同地图、规则、参赛方案和操作条件比较。");var text=text(app,card);if(!text.author().isBlank()) lines.add("作者："+text.author());if(!text.notes().isBlank()) lines.add("说明："+text.notes());
        lines.addAll(conditions(card));if(card.partsRule()!=null) {var review=app.activityPartsReview(card);lines.add("当前工作台："+review.points()+" 点；"+Tuning.performanceDescription(review.candidate().performanceKey()));lines.addAll(review.issues());}h.review("活动规则与当前参赛方案",lines,"关闭说明",()->{});
    }
    private void importCard(HudRenderer h,FormulaGame app) {h.prompt("导入活动卡 JSON 完整路径","",512,value->{try {var shared=ActivityCardCodec.read(Path.of(value.strip()));var lines=new ArrayList<String>();lines.add("名称："+shared.card().name()+"；作者："+shared.author()+"；说明："+shared.notes());lines.add("收录为独立本地卡；相同条件不重复占槽。地图和部件文件需另行导入并匹配，不带成绩、进度或资格。");String reason=app.activityCompatibility(shared.card());if(!reason.isEmpty()) lines.add("当前待处理："+reason);lines.addAll(conditions(shared.card()));lines.addAll(ActivityPartsHud.describe(shared.card().partsRule(),app.partsStore.catalog()));h.review("收录活动卡？",lines,"收录到活动卡库",()->{if(app.importActivityCard(shared)) {editing=false;history=false;selected=app.activityStore.cards().size()-1;page=selected/6;}});}catch(Exception error) {h.review("活动卡未导入",java.util.List.of("请检查文件路径、256000 字节上限、格式字段和校验内容。",Objects.toString(error.getMessage(),"文件无法读取。"),"已有活动、方案、地图和成绩保留。"),"关闭",()->{});}});}
    private void exportCard(HudRenderer h,FormulaGame app,ActivityCard card) {
        boolean fromHistory=history;
        var text=text(app,card);if(text.notes().length()>240 || text.notes().chars().anyMatch(Character::isISOControl)) {h.review("保留完整赛事包说明",List.of("这张活动保留了赛事包的完整说明。独立活动卡交换限 240 单元单行说明，不会自动截断原文。","请回赛事包库导出完整赛事包；也可复制活动，明确编辑一份简短说明后再分享独立卡。"),"关闭说明",()->{});return;}
        h.editText("导出作者署名",text.author(),"",40,author->{
            var lines=new ArrayList<String>();
            lines.add("将分享名称："+text.name()+"；作者："+author+"；说明："+text.notes());
            lines.add("包含规则、地图身份与可选作者目标；不含部件文件、场次进度或本机资格。");
            lines.add(card.stages().stream().anyMatch(stage->stage.target()!=null)?"作者目标携带参考圈时间及来源地图快照，仅供条件核对，不自动安装地图。":"这张卡不包含地图内容或成绩。");
            lines.addAll(conditions(card));
            h.review("活动卡分享内容",lines,"填写导出路径",()->h.promptExportPath(
                "导出活动卡完整路径",app.store.directory().resolve("activity-export-card.json"),(path,replace)->{
                    boolean available=fromHistory?app.activityStore.recent().stream().anyMatch(run->run.card().equals(card))
                        :app.activityStore.cards().contains(card);
                    if(!available || !text.equals(text(app,card))) {
                        app.notifyUser("活动卡或说明已变化，请重新预览导出。");
                        return;
                    }
                    app.exportActivityCard(card.rename(text.name()),author,text.notes(),path,replace);
                }));
        });
    }
    private void progress(HudRenderer h,FormulaGame app) {
        var run=app.activityStore.active();
        if(run==null) h.text("当前没有进行中的组合。",72,671,17,MUTED);
        else {
            h.text(shorten(text(app,run.card()).name(),25)+" · 已接受 "+run.stage()+" / "+run.card().stages().size()+" 项 · 合计 "+PracticeSession.format(run.total()),72,661,17,INK);
            String line=run.complete()?"组合已完成，可从活动库重新开始。":run.last()!=null && !run.last().success()?"上次："+run.last().detail():"下一项："+run.card().stageTitle(run.card().stages().get(run.stage()));
            h.text(shorten(line,64),72,694,15,MUTED);
            if(!run.complete() && !app.activityStore.pending() && !app.activityImportPending())
                h.smallButton(860,650,346,47,"继续未完成项 "+(run.stage()+1),()->app.continueActivity(run));
        }
        if(app.activityStore.pending() || app.activityImportPending()) h.smallButton(860,650,346,47,
            app.activityStore.conflictReviewAvailable()?"核对磁盘活动档案":"重试保存活动卡／阶段进度",()->retrySave(h,app));
        if(!app.activityStore.warning.isEmpty()) h.text(shorten(app.activityStore.warning,74),72,738,15,ORANGE);
        else h.text("修改地图或物理版本后旧卡保留；需复制为新条件。暂停／退出不记录半项成绩。",72,738,14,MUTED);
    }
    private void editor(HudRenderer h,FormulaGame app) {
        if(pendingCardId!=null) {
            h.panel(70,230,1136,396,new Color(24,49,60));
            h.text("活动卡尚未完成保存",90,282,26,ORANGE,true);
            h.wrapped("组合与名称备注保留在本次运行中。重试会继续保存同一张卡，不会创建重复副本。",90,335,1040,19,MUTED,30);
            h.smallButton(932,430,274,42,"重试保存",()->retrySave(h,app));
            h.smallButton(860,704,346,30,"重试保存活动资料",()->retrySave(h,app));
            return;
        }
        h.smallButton(70,230,545,42,"名称："+shorten(name,25),()->h.editText("活动卡名称",name,defaultName,40,value->{if(value.isBlank() || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();name=value;}));
        h.smallButton(631,230,285,42,"种子："+seed,()->h.prompt("固定整数种子；同卡重玩使用相同对手参数",Long.toString(seed),value->{try { seed=Long.parseLong(value.strip()); }catch(NumberFormatException error) { app.notifyUser("请输入有效整数种子。"); }}));
        h.smallButton(932,230,274,42,pendingCardId==null?"保存为新活动卡":"继续保存本张活动卡",()->{
            if(app.activityStore.pending() || pendingCardId!=null) {retrySave(h,app);return;}
            try {
                var card=ActivityCard.create(name,seed,app.vehicleSpec.physicsVersion,draft,partsRule,objectives);
                String reason=app.activityCompatibility(card);
                if(!reason.isEmpty()) {app.notifyUser(reason);return;}
                app.saveActivityCard(card);
                if(app.activityStore.cards().contains(card)) pendingCardId=card.id();
                finishEditingSave(app);
            } catch(IllegalArgumentException error) {app.notifyUser("请检查名称和一至八项活动设置。");}
        });
        h.panel(70,290,480,340,new Color(24,49,60));h.panel(570,290,636,340,new Color(24,49,60));
        step=Math.max(0,Math.min(step,draft.size()-1));
        for(int i=0;i<draft.size();i++) { int index=i;var s=draft.get(i);h.smallButton(85,303+i*39,450,34,(i==step?"● ":"")+(i+1)+" "+draftTitle(s)+" · "+shorten(s.trackName(),17),()->step=index); }
        var stage=draft.get(step);
        h.smallButton(590,310,596,42,"活动："+draftTitle(stage),()->{var kind=ActivityCard.Kind.values()[(stage.kind().ordinal()+1)%ActivityCard.Kind.values().length];change(app,s->new ActivityCard.Stage(kind,s.trackId(),s.trackName(),s.geometry(),s.difficulty(),kind.laps,kind==ActivityCard.Kind.RACE?2:0));});
        h.smallButton(590,366,596,42,"地图："+shorten(stage.trackName(),28),()->{var tracks=app.availableTracks();int index=-1;for(int i=0;i<tracks.size();i++) if(tracks.get(i).id().equals(stage.trackId())) index=i;var track=tracks.get((index+1)%tracks.size()).layout();change(app,s->ActivityCard.Stage.create(s.kind(),track,s.difficulty(),s.laps(),s.targetPlace()));});
        if(stage.kind()==ActivityCard.Kind.RACE || stage.kind()==ActivityCard.Kind.CHASE) h.smallButton(590,422,285,42,"对手："+new String[]{"轻松","标准","挑战"}[stage.difficulty()],()->change(app,s->new ActivityCard.Stage(s.kind(),s.trackId(),s.trackName(),s.geometry(),(s.difficulty()+1)%3,s.laps(),s.targetPlace())));
        if(stage.kind()==ActivityCard.Kind.RACE) {
            h.smallButton(891,422,295,42,"正赛圈数："+stage.laps(),()->change(app,s->new ActivityCard.Stage(s.kind(),s.trackId(),s.trackName(),s.geometry(),s.difficulty(),s.laps()==1?3:s.laps()==3?5:1,s.targetPlace())));
            h.smallButton(590,478,596,42,"目标：前 "+stage.targetPlace()+" 名",()->change(app,s->new ActivityCard.Stage(s.kind(),s.trackId(),s.trackName(),s.geometry(),s.difficulty(),s.laps(),s.targetPlace()%4+1)));
        }
        h.wrapped(stage.goal(),590,557,580,17,INK,28);h.smallButton(590,594,596,29,partsRule==null?"规则：固定原厂（点击设置限定部件）":"规则：限定部件 ≤ "+partsRule.budget()+" 点（点击编辑）",()->{partsHud.open(app,partsRule);rulesEditing=true;});
        h.smallButton(70,653,145,42,"添加一项",()->{if(draft.size()<8) { var extra=objective(step);draft.add(draft.get(step));step=draft.size()-1;if(extra!=null) objectives.add(extra.at(step)); }else app.notifyUser("每张卡最多八项。");});
        h.smallButton(228,653,145,42,"删除所选",()->{if(draft.size()>1) { int removed=step;draft.remove(step);var shifted=objectives.stream().filter(value->value.stage()!=removed).map(value->value.stage()>removed?value.at(value.stage()-1):value).toList();objectives.clear();objectives.addAll(shifted);step=Math.min(step,draft.size()-1); }});
        h.smallButton(386,653,145,42,"向上移动",()->move(-1));h.smallButton(544,653,145,42,"向下移动",()->move(1));
        h.smallButton(860,653,346,42,"返回活动库",()->leaveDraft(h,()->editing=false));
        h.smallButton(70,704,760,30,"备注："+shorten(notes.isBlank()?"点击填写":notes,40),()->h.editText("活动备注（单行）",notes,"",240,value->notes=value));
        if(app.activityStore.pending() || pendingCardId!=null)
            h.smallButton(860,704,346,30,app.activityStore.conflictReviewAvailable()?"核对磁盘活动档案":"重试保存活动资料",()->retrySave(h,app));
        else h.smallButton(860,704,346,30,objective(step)==null?"本项复合条件：未设置":"本项复合条件：编辑／查看",()->{objectivesHud.open(step,stage,objective(step));objectivesEditing=true;});
        h.text("保存会创建独立新卡，不改旧组合进度。地图列表包括已通过当前版本四车验证的自制图。",72,754,12,MUTED);
    }
    private void change(FormulaGame app,UnaryOperator<ActivityCard.Stage> operation) {var next=operation.apply(draft.get(step));var objective=objective(step);if(objective!=null && !objective.applicable(next)) {app.notifyUser("附加条件不适用于新圈数或对手；请先编辑或清除本项复合条件。");return;}draft.set(step,next);}
    private ActivityObjective objective(int index) {return objectives.stream().filter(value->value.stage()==index).findFirst().orElse(null);}
    private static ArrayList<String> conditions(ActivityCard card) {var lines=new ArrayList<String>();for(int i=0;i<card.stages().size();i++) {lines.add("第 "+(i+1)+" 项 · "+card.stages().get(i).goal());var value=card.objective(i);if(value!=null) lines.addAll(value.describe());}return lines;}
    private String draftTitle(ActivityCard.Stage stage) {return partsRule!=null && stage.kind()==ActivityCard.Kind.LAP?"限定部件有效圈":stage.kind().title;}
    private void move(int direction) { int next=step+direction;if(next>=0 && next<draft.size()) {int previous=step;Collections.swap(draft,step,next);var changed=objectives.stream().map(value->value.stage()==previous?value.at(next):value.stage()==next?value.at(previous):value).toList();objectives.clear();objectives.addAll(changed);objectives.sort(Comparator.comparingInt(ActivityObjective::stage));step=next; } }
    private static String shorten(String value,int length) { return value.length()<=length?value:value.substring(0,length-1)+"…"; }
    void driving(HudRenderer h,FormulaGame app) {
        var trial=app.activityTrial();var run=trial.run();var stage=trial.stage();h.panel(25,100,500,205,PANEL);
        h.text(shorten(text(app,run.card()).name(),22)+" · "+(run.stage()+1)+" / "+run.card().stages().size(),45,135,23,MINT,true);
        h.text(run.card().stageTitle(stage)+" · 第 "+Math.min(stage.laps(),app.racers.get(0).progress.completedLaps()+1)+" / "+stage.laps()+" 圈",45,170,17,INK);
        h.wrapped(trial.feedback(),45,205,460,16,MUTED,26);h.text(app.drivingHint("R 重开本项 · ESC 暂停 · 已接受项保留"),45,285,15,ORANGE);
        if(stage.kind().drivers>1) { h.panel(25,321,340,66,PANEL);h.text("P"+app.position()+" / "+app.racers.size(),45,362,25,INK,true); }
        if(run.card().objective(run.stage())!=null) {h.panel(25,401,500,run.card().objective(run.stage()).section()==null?112:146,PANEL);int line=0;for(var value:trial.objectiveStatus()) h.text(value.name()+" · "+value.status().title+" · "+value.detail(),45,430+line++*29,15,value.status()==ActivityObjective.Status.FAILED?ORANGE:INK);}
    }
    void result(HudRenderer h,FormulaGame app) {
        var trial=app.activityTrial();var result=trial.result();
        var run=app.activityCheckpoint();
        if(resultCheckpoint!=run) {
            resultCheckpoint=run;
            resultTotal=PracticeSession.format(run.total());
            resultBestKey=run.complete()?run.key():null;
        }
        boolean current=run.equals(app.activityStore.active());
        boolean pending=app.activityStore.pending();
        h.text("本项用时 "+PracticeSession.format(result.elapsed()),316,348,25,MINT,true);h.text("组合进度 "+run.stage()+" / "+run.card().stages().size()+" · 已接受项合计 "+resultTotal,316,389,18,INK);
        if(!result.laps().isEmpty()) h.text("有效圈："+String.join(" / ",result.laps().stream().map(PracticeSession::format).toList()),316,431,15,MUTED);
        String status=pending?"活动档案有待存内容，请先重试保存。"
            :!current?app.activityStore.savedRecent().contains(run)?"此成绩已收入最近完成；可返回活动库查看当前进度。"
                :"此处显示原试跑结果；可返回活动库查看当前进度。"
            :run.complete()?run.equals(app.activityStore.best(resultBestKey))?"完整组合已保存，同条件总用时最佳。":"完整组合已保存，可在最近完成中查看。"
            :result.success()?"本项已保存，下一项可稍后继续。":"失败不推进组合；重试当前项保留前面进度。";
        h.wrapped(status,316,474,640,16,pending?ORANGE:MUTED,26);
        if(run.card().objective(result.stage())!=null) h.smallButton(316,522,642,29,"查看原目标与各项复合条件结果",()->{var lines=new ArrayList<String>();lines.add("本项总结果："+result.detail());lines.add("原目标："+run.card().stages().get(result.stage()).goal());for(var value:trial.objectiveStatus()) lines.add(value.name()+" · "+value.status().title+" · "+value.detail());lines.addAll(sectionResults(run.card(),result));h.review("逐项条件与实际观测",lines,"关闭",()->{});});
        if(pending) h.smallButton(316,570,305,49,app.activityStore.conflictReviewAvailable()?"核对磁盘活动档案":"重试保存",()->retrySave(h,app));
        else if(current) h.smallButton(316,570,305,49,result.success()?run.complete()?"查看完整组合":"开始下一项":"重试当前项",
            run.complete()?app::openActivities:()->app.continueActivity(run));
        h.smallButton(642,570,316,49,"返回活动库",app::openActivities);
    }
    private static List<String> sectionResults(ActivityCard card,ActivityRun.Result result) {var objective=card.objective(result.stage());if(objective==null || objective.section()==null) return List.of();var lines=new ArrayList<String>();var observed=result.objectiveEvidence().sections();lines.add(objective.section().describe());if(observed.isEmpty()) lines.add("没有完成的分段观测；缺失不补零。");for(int i=0;i<observed.size();i++) lines.add(String.format(Locale.ROOT,"第 %d 圈分段 %.3f / %.3f 秒%s",i+1,observed.get(i),objective.section().limit(),i>=result.laps().size()?"；本圈尚未有效完赛":""));lines.add("分段时间来自有序路门；单个分段达标不等于整项成功。");return lines;}
}
