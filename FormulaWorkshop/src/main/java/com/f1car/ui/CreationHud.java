package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.CircuitPackDraft.StageOptions;
import com.f1car.persistence.CircuitStore;
import com.f1car.persistence.ContentTextStore.Text;
import java.awt.Color;
import java.util.*;

/** Creation tasks and reviewable, local file packages alongside the existing circuit editor. */
final class CreationHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),ORANGE=new Color(255,169,114),PANEL=new Color(10,29,39,245);
    private static final int PACKS_PER_PAGE=6;
    private int tab,reportIndex,page,stage,packMapIndex,commissionPage,difficulty=1,laps=1,place=2;
    private String selectedPack="";
    private static final String DEFAULT_TITLE="我的赛道活动",DEFAULT_AUTHOR="本地作者",DEFAULT_NOTES="原创平面环线与原厂驾驶活动。";
    private String title=DEFAULT_TITLE,author=DEFAULT_AUTHOR,notes=DEFAULT_NOTES;
    private long seed=20261112;
    private boolean timed;
    private boolean editStageSettings;
    private static final List<String> DIFFICULTIES=List.of("轻松","标准","挑战");
    private PartsActivityRule partsRule;private boolean rulesEditing;private String suggestionNotes="作者推荐，按相同条件试车后再决定采用。";
    private CircuitPackDraft.Origin draftOrigin;
    private final ActivityPartsHud ruleHud=new ActivityPartsHud();private final CircuitPartsHud partsHud=new CircuitPartsHud();
    private final CircuitPackageHud packageHud=new CircuitPackageHud();
    private final CircuitMigrationHud migrationHud=new CircuitMigrationHud();
    private final java.util.List<ActivityCard.Kind> kinds=new ArrayList<>(java.util.List.of(ActivityCard.Kind.values()));
    private final java.util.List<String> stageMaps=new ArrayList<>();
    private final java.util.List<ActivityObjective> stageObjectives=new ArrayList<>(Collections.nCopies(kinds.size(),null));
    private final List<StageOptions> stageOptions=new ArrayList<>(Collections.nCopies(kinds.size(),StageOptions.INHERIT));
    private boolean draftLoaded;
    private record AuthorLapSelection(int stage,String map,StageOptions options,List<CircuitBenchmark.Source> readings) {}
    private AuthorLapSelection authorLaps;
    private int authorLapPage;
    private final ActivityObjectivesHud objectivesHud=new ActivityObjectivesHud();private boolean objectivesEditing;
    private CircuitEventPack.Entry statusEntry;
    private java.util.List<CircuitStore.Saved> statusMaps;
    private PartsCatalog statusCatalog;
    private record PackStatus(FormulaGame.PackReadiness readiness,String detail) { }
    private PackStatus cachedStatus;
    void render(HudRenderer h,FormulaGame app) {
        if(authorLaps!=null) {renderAuthorLaps(h,app);return;}
        h.panel(38,32,1204,732,PANEL);h.text("CREATE / DRIVE / SHARE",70,74,14,MINT,true);h.text("创作任务与赛事包",70,119,34,INK,true);h.text("修改地图 → 本机四车验证 → 标定可选计时目标 → 保存或分享活动",72,151,16,MUTED);
        h.smallButton(848,68,170,42,"退到主界面",app::closeCreationToMain);
        h.smallButton(1036,68,170,42,"进入编辑器",app::openCircuitEditor);
        h.smallButton(848,124,358,30,app.creationStore.storageLabel(),()->storage(h,app));
        if(rulesEditing) {ruleHud.render(h,app,rule->{if(rule!=null && (timed || stageOptions.stream().anyMatch(options->Boolean.TRUE.equals(options.timed())))) {app.notifyUser("请先关闭全包与单项的原厂作者计时参考，再设置限定部件。");return;}partsRule=rule;captureDraft(app);rulesEditing=false;},()->rulesEditing=false);return;}
        if(objectivesEditing) {objectivesHud.render(h,app,value->{stageObjectives.set(stage,value);captureDraft(app);objectivesEditing=false;},()->objectivesEditing=false);return;}
        h.smallButton(70,173,356,40,"制图委托",()->selectTab(h,0));h.smallButton(450,173,356,40,"制作赛事包",()->selectTab(h,1));h.smallButton(830,173,376,40,"赛事包库与导入",()->selectTab(h,2));
        if(tab==0) commissions(h,app);else if(tab==1) author(h,app);else if(tab==3) migrationHud.render(h,app,()->tab=2);else library(h,app);
        if(app.creationStore.pending()) h.smallButton(870,719,336,35,"重试保存创作记录与赛事包",app::saveCreation);
        if(!app.creationStore.warning.isEmpty()) h.text(shorten(app.creationStore.warning,64),72,752,14,ORANGE);
    }
    private void storage(HudRenderer h,FormulaGame app) {
        if (app.creationStore.conflictReviewAvailable()) {
            reviewStorageConflict(h, app);
            return;
        }
        var status=app.creationStore.status();var lines=new ArrayList<String>();lines.add(app.creationStore.storageLabel());
        lines.add("当前可用内容："+status.briefs()+" 份委托、"+status.reports()+" 份报告、"+status.packs()+" 份赛事包。");
        if(status.failure()!=null) lines.add("上次问题发生在“"+status.failure().stage().title+"”阶段。"+app.creationStore.warning);
        if(status.pending()) lines.add("上述内容包含本次内存中的待保存记录，磁盘上的旧记录可能仍没有这些结果。请先重试保存。");
        else if(app.creationStore.canRetryRead()) lines.add("原文件未能载入，当前空列表不能证明文件没有内容。可重新读取；原文件与地图草稿保留。");
        else lines.add("创作记录与赛事包单独保存；地图与资格仍按各自流程处理。重新读取不会自动安装包或授予资格。");
        String action=status.pending()?"重试保存":app.creationStore.canRetryRead()?"重新读取原文件":"关闭";
        h.review("创作记录保存状态",lines,action,()->{if(status.pending()) app.saveCreation();else if(status.readState()==com.f1car.persistence.CreationStore.ReadState.FAILED) app.retryCreationRead(status);});
    }
    private void reviewStorageConflict(HudRenderer h, FormulaGame app) {
        var review = app.creationStore.reviewConflict();
        if (review == null) {
            h.review("两份创作记录", List.of(app.creationStore.warning), "关闭", () -> {});
            return;
        }
        var lines = new ArrayList<String>();
        lines.add("磁盘上有 " + review.savedBriefs() + " 份委托、" + review.savedReports() + " 份报告和 " + review.savedPacks() + " 份赛事包。");
        if (!review.reusedImports().isEmpty()) {
            lines.add("以下赛事包已收录同一份原包。确认后沿用已存地图编号，只补齐缺失的草稿。");
            for (var entry : review.reusedImports()) {
                lines.add(entry.original().title() + " · " + entry.localCircuits().size() + " 张地图");
            }
        }
        if (!review.additions().isEmpty()) {
            lines.add("确认后将保留磁盘记录，并收录当前窗口新增的以下内容。");
            lines.addAll(review.additions());
        } else if (review.reusedImports().isEmpty()) {
            lines.add("磁盘中已有本次待存的内容。");
        }
        h.review("两份创作记录", lines, review.additions().isEmpty() ? "采用已保存记录" : "合并并保存", () -> {
            if (app.screen != FormulaGame.Screen.CREATION) return;
            if (app.creationStore.saveConflict(review)) app.saveCreation();
            else app.notifyUser(app.creationStore.warning);
        });
    }
    private void commissions(HudRenderer h,FormulaGame app) {
        var kinds=CircuitCommission.Kind.values();int pages=(kinds.length+1)/2;commissionPage=Math.max(0,Math.min(pages-1,commissionPage));
        for(int index=commissionPage*2;index<Math.min(kinds.length,commissionPage*2+2);index++) {var kind=kinds[index];int x=70+(index-commissionPage*2)*566;h.panel(x,236,540,195,new Color(24,49,60));h.text(kind.title,x+20,276,25,MINT,true);h.wrapped(kind.description,x+20,316,500,17,INK,27);h.smallButton(x+20,390,500,40,"领取并创建新草稿",()->app.startCommission(kind));}
        h.smallButton(70,435,180,24,"上一页委托",()->commissionPage=Math.max(0,commissionPage-1));h.text("委托 "+(commissionPage+1)+" / "+pages,550,453,14,MUTED);h.smallButton(1026,435,180,24,"下一页委托",()->commissionPage=Math.min(pages-1,commissionPage+1));
        var brief=app.circuitCommission();h.panel(70,464,1136,173,new Color(24,49,60));
        if(brief==null) { h.text("当前草稿没有委托限制",90,505,23,INK,true);h.wrapped("自由制作仍可继续。委托只约束对应草稿，不作为正常比赛或地图分享的解锁条件。",90,546,1068,18,MUTED,29); }
        else {
            var editor=app.circuitEditor();var review=editor.commissionReview(app.vehicleSpec.physicsVersion);if(review==null) review=brief.review(editor.document(),editor.proof(),app.vehicleSpec.physicsVersion,editor.inspection());var status=review.assessment();h.text("当前："+brief.kind().title+(status.complete()?" · 条件已完成":""),90,505,23,MINT,true);h.wrapped(status.detail(),90,546,1060,17,INK,28);
            var checklist=review;h.smallButton(1036,483,150,32,"逐项条件",()->{var lines=new ArrayList<String>();for(var check:checklist.checks()) lines.add((check.passed()?"已满足：":"待处理：")+check.title()+"。"+check.detail());lines.add("这是可计算的委托条件；标牌可读性、视线与驾驶体验仍需人工评审。");h.review("委托条件 · "+brief.kind().title,lines,"刷新收录检查",app::openCreation);});
            h.smallButton(90,584,335,35,"继续编辑与定位问题",app::openCircuitEditor);h.smallButton(449,584,335,35,"刷新并收录完成报告",app::openCreation);h.smallButton(808,584,378,35,"解除本张委托限制",()->h.prompt("输入 LEAVE 解除限制；地图和已收录报告保留","",value->{if(value.strip().equalsIgnoreCase("LEAVE")) app.abandonCommission();}));
        }
        var reports=app.creationStore.reports();h.text("已收录最近 "+reports.size()+" / 24 份完成报告",72,675,17,INK);
        if(!reports.isEmpty()) { reportIndex=Math.min(reportIndex,reports.size()-1);var report=reports.get(reportIndex);h.text((reportIndex+1)+" · "+report.kind().title+" · 作者四车验证圈 "+PracticeSession.format(report.laps().get(0)),72,711,16,MUTED);h.smallButton(640,687,95,32,"上一份",()->reportIndex=Math.max(0,reportIndex-1));h.smallButton(748,687,95,32,"下一份",()->reportIndex=Math.min(reports.size()-1,reportIndex+1)); }
    }
    private void author(HudRenderer h,FormulaGame app) {
        loadDraft(app);
        h.smallButton(600,124,230,30,app.circuitPackEditing.status(),()->DraftRecoveryHud.review(h,app,app.circuitPackEditing));
        if(app.circuitEditor()==null && stageMaps.isEmpty()) { h.text("先准备一张地图",70,280,28,MINT,true);h.wrapped("新建或导入地图，完成四车验证后制作赛事包。也可在包库导入现有赛事包。",70,335,1040,19,MUTED,30);h.smallButton(70,410,540,44,"新建草稿并进入编辑器",()->{app.openCircuitEditor();app.newCircuit(null);});return; }
        if(stageMaps.isEmpty()) {var document=app.circuitEditor().document();title=shorten(document.name(),36);stage=0;stageMaps.addAll(Collections.nCopies(kinds.size(),document.id()));}
        h.smallButton(70,236,546,38,"标题："+shorten(title,24),()->h.editText("赛事包标题",title,DEFAULT_TITLE,40,value->{if(!text(value,40)) throw new IllegalArgumentException();title=value;captureDraft(app);}));
        h.smallButton(636,236,570,38,"作者："+shorten(author,25),()->h.editText("作者署名",author,DEFAULT_AUTHOR,40,value->{if(!text(value,40)) throw new IllegalArgumentException();author=value;captureDraft(app);}));
        h.smallButton(70,286,1136,38,"说明："+shorten(notes.replace('\n',' '),62),()->h.editText("玩法说明",notes,DEFAULT_NOTES,320,value->{if(!CircuitPackDraft.validNotes(value)) throw new IllegalArgumentException();notes=value;captureDraft(app);}));
        h.text(stageMaps.stream().distinct().count()+" 张地图 · 每张需本机四车验证",72,360,18,MINT);
        if(draftOrigin!=null) h.smallButton(402,333,318,35,"草稿来自 "+shorten(draftOrigin.title(),12),()->{
            var source=draftOrigin;
            h.review("草稿的原赛事包",List.of(source.title()+" · 原作者署名 "+source.author(),
                    "原包编号 "+source.id(),"原内容摘要 "+source.digest()),
                    "返回草稿",()->{});
        });
        h.smallButton(736,333,194,35,"逐项核对赛事包",()->reviewPackage(h,app));
        h.smallButton(946,333,260,35,editStageSettings?"设置范围 · 所选活动":"设置范围 · 全包默认",()->editStageSettings=!editStageSettings);
        h.panel(70,376,540,270,new Color(24,49,60));h.panel(636,376,570,270,new Color(24,49,60));stage=Math.max(0,Math.min(stage,kinds.size()-1));
        for(int i=0;i<kinds.size();i++) {int index=i;h.smallButton(85,389+i*31,510,27,(stage==i?"● ":"")+(i+1)+" · "+kindTitle(kinds.get(i))+" · "+shorten(mapName(app,stageMaps.get(i)),12),()->{stage=index;captureDraft(app);});}
        h.smallButton(656,391,250,38,"活动："+kindTitle(kinds.get(stage)),()->changeKind(app));
        h.smallButton(926,391,260,38,"地图："+shorten(mapName(app,stageMaps.get(stage)),10),()->nextMap(app));
        if(editStageSettings) stageControls(h,app);
        else packageDefaults(h,app);
        if(kinds.get(stage)==ActivityCard.Kind.LAP && partsRule==null) {
            var options=stageOptions.get(stage);
            var reference=options.reference();
            var benchmark=app.circuitBenchmark(stageMaps.get(stage),reference);
            String label=benchmark==null?reference==null?"选择作者圈 · 暂无匹配记录":"重新选择 · 原作者圈已不匹配"
                :(reference==null?"选择作者圈 · 当前最快 ":"已选作者圈 · ")+PracticeSession.format(benchmark.lap());
            h.smallButton(656,583,530,28,label,()->openAuthorLaps(app));
        }
        h.smallButton(656,615,530,27,"标定所选地图（原厂一圈）",()->{
            var map=app.packCircuit(stageMaps.get(stage));
            var run=app.activityStore.active();
            if(run!=null && !run.complete()) h.prompt("输入 NEW 替换当前活动组合，开始作者标定","",value->{
                if(value.strip().equalsIgnoreCase("NEW")) app.startAuthorBenchmark(map,run);
            });
            else app.startAuthorBenchmark(map,run);
        });
        h.smallButton(70,661,125,38,"添加活动",()->{if(kinds.size()<8) {stageMaps.add(stageMaps.get(stage));stageObjectives.add(stageObjectives.get(stage));stageOptions.add(stageOptions.get(stage));kinds.add(kinds.get(stage));stage=kinds.size()-1;captureDraft(app);}});
        h.smallButton(210,661,125,38,"删除活动",()->{if(kinds.size()>1) {kinds.remove(stage);stageMaps.remove(stage);stageObjectives.remove(stage);stageOptions.remove(stage);stage=Math.min(stage,kinds.size()-1);captureDraft(app);}});
        h.smallButton(350,661,125,38,"向前移动",()->move(app,-1));h.smallButton(490,661,125,38,"向后移动",()->move(app,1));
        h.smallButton(636,661,198,38,stageObjectives.get(stage)==null?"本项附加条件":"本项条件 · 已设置",()->{var document=app.packCircuit(stageMaps.get(stage));if(document==null) {app.notifyUser("请先补齐所选地图。");return;}objectivesHud.open(stage,workingDraft().plan(stage).on(document.track(),null),stageObjectives.get(stage));objectivesEditing=true;});
        h.smallButton(850,661,356,42,"保存 "+stageMaps.stream().distinct().count()+" 图赛事包",()->savePack(h,app));
        h.smallButton(70,711,546,28,partsRule==null?"规则：固定原厂（编辑限定部件）":"规则：限定部件 ≤ "+partsRule.budget()+" 点",()->{ruleHud.open(app,partsRule);rulesEditing=true;});
        if(partsRule!=null) h.smallButton(636,711,570,28,"推荐方案说明",()->h.editText("推荐方案说明",suggestionNotes,"作者推荐，按相同条件试车后再决定采用。",120,value->{if(!PartsCatalog.text(value,120)) throw new IllegalArgumentException();suggestionNotes=value;captureDraft(app);}));
        h.text(partsRule==null?"金＝作者实测圈，银／铜按 105%／110% 放宽；难度仍待试驾标定。":"保存会带相关命名空间的完整玩家部件包与推荐方案，不携带本机驾驶资格。",72,757,12,MUTED);
    }
    private void packageDefaults(HudRenderer h,FormulaGame app) {
        h.smallButton(656,440,250,38,"默认对手："+DIFFICULTIES.get(difficulty),()->{difficulty=(difficulty+1)%3;captureDraft(app);});
        h.smallButton(926,440,260,38,"默认短赛圈数："+laps,()->{
            int next=nextLaps(laps);
            for(int i=0;i<kinds.size();i++) {
                var options=stageOptions.get(i);
                if(options.laps()==null && !compatible(app,i,kinds.get(i),options,next)) return;
            }
            laps=next;
            captureDraft(app);
        });
        h.smallButton(656,489,250,38,"默认短赛目标：前 "+place+" 名",()->{place=place%4+1;captureDraft(app);});
        h.smallButton(926,489,260,38,"种子："+seed,()->h.prompt("固定整数种子",Long.toString(seed),value->{
            try {seed=Long.parseLong(value.strip());captureDraft(app);}
            catch(NumberFormatException error) {app.notifyUser("请输入有效整数种子。");}
        }));
        h.smallButton(656,538,530,38,(timed?"● ":"")+"计时项默认使用有效作者圈作为奖牌参考",()->{
            if(partsRule!=null) app.notifyUser("限定部件活动使用本项的有效圈或附加条件。");
            else {timed=!timed;captureDraft(app);}
        });
    }
    private void stageControls(HudRenderer h,FormulaGame app) {
        var options=stageOptions.get(stage);
        var kind=kinds.get(stage);
        int opponents=options.difficulty()==null?difficulty:options.difficulty();
        h.smallButton(656,440,250,38,scope(options.difficulty())+"对手："+DIFFICULTIES.get(opponents),
                ()->setStageOptions(app,options.withDifficulty((opponents+1)%3)));
        if(kind==ActivityCard.Kind.RACE) {
            int count=options.laps()==null?laps:options.laps();
            int target=options.place()==null?place:options.place();
            h.smallButton(926,440,260,38,scope(options.laps())+"短赛圈数："+count,
                    ()->setStageOptions(app,options.withLaps(nextLaps(count))));
            h.smallButton(656,489,250,38,scope(options.place())+"争取前 "+target+" 名",
                    ()->setStageOptions(app,options.withPlace(target%4+1)));
        }
        if(kind==ActivityCard.Kind.LAP && partsRule==null) {
            boolean useTarget=options.timed()==null?timed:options.timed();
            h.smallButton(656,538,530,38,scope(options.timed())+(useTarget?"启用作者圈奖牌参考":"完成有效圈"),
                    ()->setStageOptions(app,options.withTiming(!useTarget)));
        }
        if(!options.empty()) h.smallButton(926,489,260,38,"本项恢复全包默认",()->setStageOptions(app,StageOptions.INHERIT));
    }
    private static String scope(Object value) {return value==null?"继承 · ":"本项 · ";}
    private static int nextLaps(int value) {return value==1?3:value==3?5:1;}

    private void openAuthorLaps(FormulaGame app) {
        if(!app.refreshCircuitAuthoring(true,false)) return;
        String map=stageMaps.get(stage);
        authorLaps=new AuthorLapSelection(stage,map,stageOptions.get(stage),app.circuitBenchmarkSources(map));
        authorLapPage=0;
    }
    boolean backOverlay() {
        if(authorLaps==null) return false;
        authorLaps=null;
        return true;
    }
    boolean editingOverlay() { return authorLaps!=null || rulesEditing || objectivesEditing || tab==3; }
    private void selectTab(HudRenderer hud,int next) {
        if(next!=tab) hud.cancelCreationImport();
        tab=next;
    }

    private void renderAuthorLaps(HudRenderer h,FormulaGame app) {
        var selection=authorLaps;
        h.panel(38,32,1204,732,PANEL);
        h.text("选择本项的作者参考圈",70,116,31,INK,true);
        h.text("第 "+(selection.stage()+1)+" 项 · "+mapName(app,selection.map()),70,158,18,MINT);
        h.smallButton(1010,72,196,40,"返回赛事包草稿",()->authorLaps=null);
        h.text("已保存的原厂有效圈 · 按用时排序 · 保留每圈的原始操作条件",70,198,17,MUTED);
        var readings=selection.readings();
        if(readings.isEmpty())
            h.wrapped("此地图暂无可用作者圈。返回草稿可运行原厂一圈标定，再回来选择。",70,266,1000,18,INK,28);
        int pages=Math.max(1,(readings.size()+5)/6);
        for(int i=authorLapPage*6;i<Math.min(readings.size(),authorLapPage*6+6);i++) {
            var row=readings.get(i);
            String control=ControlIdentity.KEYBOARD.equals(row.controls())?"键盘":"手柄";
            String picked=row.reference().equals(selection.options().reference())?"已选 · ":"";
            h.smallButton(70,224+(i-authorLapPage*6)*57,1136,47,
                picked+PracticeSession.format(row.lap())+" · "+control+" · "+row.reference().resultId().substring(0,8),
                ()->reviewAuthorLap(h,app,selection,row));
        }
        if(pages>1) {
            h.smallButton(70,595,186,35,"上一页",()->authorLapPage=Math.max(0,authorLapPage-1));
            h.text((authorLapPage+1)+" / "+pages,278,620,15,MUTED);
            h.smallButton(410,595,186,35,"下一页",()->authorLapPage=Math.min(pages-1,authorLapPage+1));
        }
        if(selection.options().reference()!=null) {
            h.smallButton(70,665,430,42,"取消指定，改用当前最快的已保存作者圈",()->{
                if(authorSelectionCurrent(selection,app)) {
                    stageOptions.set(stage,selection.options().withReference(null));
                    captureDraft(app);
                    authorLaps=null;
                } else app.notifyUser("草稿设置已变化，请重新选择作者圈。");
            });
        }
    }

    private void reviewAuthorLap(HudRenderer h,FormulaGame app,AuthorLapSelection selection,CircuitBenchmark.Source source) {
        if(!authorSelectionCurrent(selection,app) || !app.circuitBenchmarkSources(selection.map()).contains(source)) {
            app.notifyUser("原作者圈或草稿设置已变化，请返回后重新选择。");
            return;
        }
        var benchmark=app.circuitBenchmark(selection.map(),source.reference());
        if(benchmark==null) {app.notifyUser("这条原圈与地图条件不再匹配。");return;}
        h.review("使用这次已保存的作者圈？",List.of(
            benchmark.source().name()+" · "+benchmark.source().id(),
            "原圈 "+PracticeSession.format(benchmark.lap())+" · 操作 "+benchmark.controls(),
            "组合 "+benchmark.runId()+" · 试次 "+benchmark.resultId(),
            "金 "+PracticeSession.format(benchmark.gold())+" · 银 "+PracticeSession.format(benchmark.silver())
                +" · 铜 "+PracticeSession.format(benchmark.bronze())),
            "设为本项的作者圈",()->{
                if(!app.refreshCircuitAuthoring(true,false)) return;
                if(!authorSelectionCurrent(selection,app)
                        || !benchmark.equals(app.circuitBenchmark(selection.map(),source.reference()))) {
                    app.notifyUser("原作者圈或地图已变化，请重新选择。");
                    return;
                }
                stageOptions.set(stage,selection.options().withReference(source.reference()));
                captureDraft(app);
                authorLaps=null;
            });
    }

    private boolean authorSelectionCurrent(AuthorLapSelection selection,FormulaGame app) {
        return authorLaps==selection && app.screen==FormulaGame.Screen.CREATION && partsRule==null
            && selection.stage()==stage && stage<stageMaps.size() && kinds.get(stage)==ActivityCard.Kind.LAP
            && stageMaps.get(stage).equals(selection.map()) && stageOptions.get(stage).equals(selection.options());
    }
    private void setStageOptions(FormulaGame app,StageOptions options) {
        if(compatible(app,stage,kinds.get(stage),options,laps)) {
            stageOptions.set(stage,options);
            captureDraft(app);
        }
    }
    private void changeKind(FormulaGame app) {
        var kindsAvailable=ActivityCard.Kind.values();
        var next=kindsAvailable[(kinds.get(stage).ordinal()+1)%kindsAvailable.length];
        var options=stageOptions.get(stage).forKind(next);
        if(compatible(app,stage,next,options,laps)) {
            kinds.set(stage,next);
            stageOptions.set(stage,options);
            captureDraft(app);
        }
    }
    private void library(HudRenderer h,FormulaGame app) {
        var packs=app.creationStore.packs();
        int lastPage=Math.max(0,(packs.size()-1)/PACKS_PER_PAGE);
        page=Math.max(0,Math.min(page,lastPage));
        int first=page*PACKS_PER_PAGE;
        var visible=packs.subList(first,Math.min(packs.size(),first+PACKS_PER_PAGE));
        if(visible.stream().noneMatch(entry->entry.id().equals(selectedPack)))
            selectLibraryPack(visible.isEmpty()?"":visible.get(0).id());
        if(h.creationImportReading()) {
            h.smallButton(70,236,215,40,"读取中 · 改选文件",()->h.chooseCreationFile(null,false));
            h.smallButton(293,236,142,40,"取消读取",h::cancelCreationImport);
        } else h.smallButton(70,236,365,40,"导入赛事包 JSON",()->h.chooseCreationFile(null,false));
        h.panel(70,290,365,371,new Color(24,49,60));
        for(int i=0;i<visible.size();i++) {
            var entry=visible.get(i);
            h.smallButton(85,305+i*48,335,39,(selectedPack.equals(entry.id())?"● ":"")+shorten(packText(app,entry).name(),17),()->selectLibraryPack(entry.id()));
        }
        if(lastPage>0) {h.smallButton(85,610,155,34,"上一页",()->page=Math.max(0,page-1));h.smallButton(254,610,166,34,"下一页",()->page=Math.min(lastPage,page+1));}
        h.panel(455,236,751,425,new Color(24,49,60));
        if(packs.isEmpty()) { h.text("地图与活动规则一起分享",480,282,27,MINT,true);h.wrapped("赛事包支持一至八张地图、最多八项活动。每项可选择地图，导入后逐图完成本机四车验证，再加入活动库；中断安装可补齐。",480,335,690,19,INK,32); }
        else {
            var entry=visible.stream().filter(row->row.id().equals(selectedPack)).findFirst().orElseThrow();
            var pack=entry.original();var state=status(app,entry);
            var readiness=state.readiness();var maps=readiness.maps();
            packMapIndex=Math.min(packMapIndex,maps.size()-1);
            var metadata=packText(app,entry);h.smallButton(480,243,706,38,"名称："+shorten(metadata.name(),32),()->editPack(h,app,entry,0));h.smallButton(480,293,520,32,"作者："+shorten(metadata.author(),25),()->editPack(h,app,entry,1));h.smallButton(1014,293,172,32,"编辑说明",()->editPack(h,app,entry,2));h.wrapped(shorten(metadata.notes().replace('\n',' '),90),480,359,690,17,INK,28);
            h.text(entry.activity().rulesTitle()+" · "+maps.size()+" 图／"+entry.activity().stages().size()+" 项 · 种子 "+entry.activity().seed(),480,444,17,INK);h.text("物理："+shorten(entry.activity().physics(),45),480,476,14,MUTED);
            h.smallButton(1014,459,172,28,"完整活动条件",()->{var lines=activityDetails(entry.activity());lines.addAll(ActivityPartsHud.describe(entry.activity().partsRule(),app.partsStore.catalog()));lines.add("收录活动前仍需每张地图的本机四车资格；作者附加条件是否可达须另外试车确认。");h.review("赛事包完整目标",lines,"关闭说明",()->{});});
            String detail=state.detail();
            h.text(detail.isEmpty()?"全部地图资格与包内规则对应。":shorten(detail,32),480,512,16,detail.isEmpty()?MINT:ORANGE);
            h.smallButton(1014,493,172,31,"查看逐图状态",()->reviewPackMaps(h,app,entry));
            int mapIndex=packMapIndex;
            var map=maps.get(mapIndex);
            h.smallButton(480,530,158,28,"上一张地图",()->packMapIndex=Math.floorMod(packMapIndex-1,maps.size()));
            h.text((mapIndex+1)+" / "+maps.size()+" · "+shorten(map.name(),12)+" · "+map.state().label,655,550,15,
                    map.state()==FormulaGame.PackMapState.READY?MINT:INK);
            h.smallButton(1028,530,158,28,"下一张地图",()->packMapIndex=(packMapIndex+1)%maps.size());
            boolean missing=map.state()==FormulaGame.PackMapState.MISSING;
            h.smallButton(480,568,335,36,missing?"补齐缺失地图":"编辑／验证所选地图",()->{
                if(missing) app.installCircuitPackMaps(entry.id());
                else app.openPackMap(entry.id(),mapIndex);
            });
            h.smallButton(835,568,351,36,readiness.issue().isEmpty()?"加入活动卡库":"检查收录条件",()->{
                if(readiness.issue().isEmpty()) app.activateCircuitPack(entry);
                else reviewPackMaps(h,app,entry);
            });
            h.smallButton(480,615,335,36,"导出当前名称与说明",()->h.chooseCreationFile(entry.id(),true));h.smallButton(835,615,351,36,"删除包（保留地图）",()->h.confirm("删除这份赛事包？",metadata.name()+"\n已安装地图、活动进度和历史保留。",()->{if(!app.creationStore.remove(entry.id())) app.notifyUser(app.creationStore.warning);}));
            if(pack.partsBundle()!=null) {h.smallButton(480,674,220,30,"查看／收录包内部件",()->partsHud.install(h,app,entry));h.smallButton(713,674,220,30,"推荐方案读为草稿",()->partsHud.suggestion(h,app,entry,false));h.smallButton(946,674,238,30,"另存推荐方案",()->partsHud.suggestion(h,app,entry,true));}
            h.smallButton(70,674,365,30,"固定当前原始包为对照 A",()->packageHud.pin(app,entry));h.smallButton(70,709,365,24,packageHud.label(),()->packageHud.compare(h,app,entry));
            if(!app.creationStore.pending()) {
                h.smallButton(480,709,330,24,"复制原活动为新草稿",()->copyPackage(h,app,entry));
                h.smallButton(835,709,349,24,"修订关系与所选地图迁移",()->{migrationHud.open(entry.id(),mapIndex);selectTab(h,3);});
            }
        }
        if(app.creationStore.warning.isEmpty()) h.text("最多十二份包；地图需本机验证，部件与推荐草稿分别确认；重复包不反复占地图槽。",72,757,12,MUTED);
    }
    private void selectLibraryPack(String id) {
        if(selectedPack.equals(id)) return;
        selectedPack=id;
        packMapIndex=0;
    }
    private Text packText(FormulaGame app,CircuitEventPack.Entry entry) { var p=entry.original();return app.contentText.get("pack:"+entry.id(),new Text(p.title(),p.author(),p.notes())); }
    private void editPack(HudRenderer h,FormulaGame app,CircuitEventPack.Entry entry,int field) {
        var p=entry.original();String key="pack:"+entry.id();var value=packText(app,entry);var defaults=app.contentText.defaults(key,new Text(p.title(),p.author(),p.notes()));
        h.editTextChecked(new String[]{"赛事包名称","赛事包作者","赛事包说明（单行）"}[field],field==0?value.name():field==1?value.author():value.notes(),field==0?defaults.name():field==1?defaults.author():defaults.notes(),field==2?320:40,text->{
            if(!entry.equals(app.creationStore.pack(entry.id())) || !value.equals(packText(app,entry))) {app.notifyUser("赛事包或原文字已变化；输入保留，请取消后重新打开核对。");return false;}
            if(field==1 && text.isBlank()) throw new IllegalArgumentException();var next=field==0?value.name(text):field==1?value.author(text):value.notes(text);
            boolean saved=app.contentText.put(key,defaults,next);app.notifyUser(saved?"名称与说明已保存；导出时生成独立修订，不改变地图与活动条件。":app.contentText.warning);return saved;
        });
    }
    private void loadDraft(FormulaGame app) {
        if (draftLoaded) return;
        draftLoaded = true;
        var saved = app.circuitPackEditing.current();
        if (saved == null) return;
        loadDraft(saved);
    }
    private void loadDraft(CircuitPackDraft saved) {
        draftLoaded=true;
        editStageSettings=false;
        rulesEditing=false;
        objectivesEditing=false;
        title = saved.title();
        author = saved.author();
        notes = saved.notes();
        stage = saved.selectedStage();
        seed = saved.seed();
        difficulty = saved.difficulty();
        laps = saved.laps();
        place = saved.place();
        timed = saved.timed();
        partsRule = saved.rule();
        suggestionNotes = saved.suggestionNotes();
        draftOrigin=saved.origin();
        kinds.clear();
        stageMaps.clear();
        stageObjectives.clear();
        stageOptions.clear();
        for (var row : saved.stages()) {
            kinds.add(row.kind());
            stageMaps.add(row.map());
            stageObjectives.add(row.objective());
            stageOptions.add(row.options()==null?StageOptions.INHERIT:row.options());
        }
    }

    private void copyPackage(HudRenderer h,FormulaGame app,CircuitEventPack.Entry entry) {
        var previous=app.circuitPackEditing.current();
        var local=entry.localCircuits();
        var currentMaps=local.stream().map(document->app.packCircuit(document.id())).toList();
        String name=app.store.profile.playerName;
        if(!PartsCatalog.text(name,40) || name.isBlank()) name=DEFAULT_AUTHOR;
        CircuitPackDraft copy;
        try {
            copy=CircuitPackDraft.fromPackage(entry,name);
        } catch(IllegalArgumentException error) {
            app.notifyUser("原包格式或文字无法建立编辑草稿，原包保留。");
            return;
        }
        var lines=activityDetails(entry.activity());
        lines.add("原包《"+copy.origin().title()+"》 · 署名 "+copy.origin().author()+"。");
        lines.add("草稿使用已对应的本机地图编号。导入地图仍需本机四车验证。");
        for(int i=0;i<local.size();i++) {
            var current=currentMaps.get(i);
            if(current==null) lines.add("本机缺少《"+local.get(i).name()+"》，需在包库补齐或为活动换图。");
            else if(!current.driveKey().equals(local.get(i).driveKey()))
                lines.add("本机《"+current.name()+"》的道路或布景已有修改，请按现有版本核对。");
        }
        if(copy.plans().stream().anyMatch(CircuitPackDraft.StagePlan::timed))
            lines.add("启用作者圈目标的活动需重新选择本机已保存的原厂成绩。");
        if(copy.rule()!=null) lines.add("规则会带入草稿，部件收录和推荐方案需分别确认。");
        if(previous!=null) lines.add("将以这份草稿替换正在编辑的《"+previous.title()+"》。");
        h.review("复制赛事包活动为新草稿",lines,"打开新草稿",()->{
            if(app.screen!=FormulaGame.Screen.CREATION || app.creationStore.pending()
                    || !entry.equals(app.creationStore.pack(entry.id()))
                    || !currentMaps.equals(local.stream().map(document->app.packCircuit(document.id())).toList())
                    || !Objects.equals(previous,app.circuitPackEditing.current())) {
                app.notifyUser("赛事包、地图或当前草稿已变化，请重新预览。");
                return;
            }
            loadDraft(copy);
            app.circuitPackEditing.capture(copy);
            selectTab(h,1);
        });
    }

    private void captureDraft(FormulaGame app) {
        app.circuitPackEditing.capture(workingDraft());
    }
    private CircuitPackDraft workingDraft() {
        var stages = new ArrayList<CircuitPackDraft.Stage>(kinds.size());
        for (int i = 0; i < kinds.size(); i++) {
            var goal = stageObjectives.get(i);
            stages.add(new CircuitPackDraft.Stage(kinds.get(i),stageMaps.get(i),goal==null?null:goal.at(i),stageOptions.get(i)));
        }
        return new CircuitPackDraft(1,title,author,notes,stages,stage,seed,difficulty,laps,place,timed,partsRule,suggestionNotes,draftOrigin);
    }

    private void move(FormulaGame app, int amount) {
        int next = stage + amount;
        if (next < 0 || next >= kinds.size()) return;
        Collections.swap(kinds, stage, next);
        Collections.swap(stageMaps, stage, next);
        Collections.swap(stageObjectives, stage, next);
        Collections.swap(stageOptions, stage, next);
        stage = next;
        captureDraft(app);
    }
    private String kindTitle(ActivityCard.Kind kind) {return partsRule!=null && kind==ActivityCard.Kind.LAP?"限定有效圈":kind.title;}
    private void reviewPackage(HudRenderer h,FormulaGame app) {
        var draft=workingDraft();
        var sources=readStageSources(app,draft);
        var lines=new ArrayList<String>();
        lines.add(draft.title()+" · "+draft.stages().size()+" 项活动 · 固定种子 "+draft.seed());
        int firstIssue=-1;
        for(int i=0;i<sources.size();i++) {
            var plan=draft.plan(i);
            var source=sources.get(i);
            String row="第 "+(i+1)+" 项 · "+kindTitle(plan.kind());
            String issue=source.issue(plan,app.vehicleSpec.physicsVersion);
            var document=source.document();
            if(document!=null) {
                row+=" · "+document.name();
                var objective=draft.stages().get(i).objective();
                try {
                    var stage=plan.on(document.track(),source.benchmark());
                    row+=" · "+stage.goal();
                    if(objective!=null && !objective.applicable(stage))
                        issue="附加条件与本项模式或圈数不相容。";
                } catch(IllegalArgumentException | IllegalStateException error) {
                    issue="当前路线无法生成，请回到地图编辑器核对锚点与道宽。";
                }
                lines.add(row);
                if(source.benchmark()!=null) lines.add((plan.reference()==null?"自动最快":"指定作者圈")+" · 试次 "+source.benchmark().resultId());
                if(objective!=null) lines.addAll(objective.describe());
            } else {
                lines.add(row);
            }
            if(issue.isEmpty()) {
                lines.add("路线和本机四车验证对应。");
            } else {
                if(firstIssue<0) firstIssue=i;
                lines.add(issue);
            }
        }
        if(draft.rule()!=null) {
            var report=draft.rule().review(app.activityPartsBuild(),app.partsStore.catalog(),
                    app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());
            lines.addAll(ActivityPartsHud.describe(draft.rule(),app.partsStore.catalog()));
            lines.add(report.valid()?"推荐方案符合当前限定部件规则。":"请先在组装方案里准备符合规则的推荐方案。");
        }
        int selectedIssue=firstIssue;
        h.review("赛事包检查",lines,selectedIssue<0?"返回草稿":"定位第 "+(selectedIssue+1)+" 项",()->{
            if(selectedIssue>=0 && draft.equals(workingDraft())) stage=selectedIssue;
        });
    }

    private void savePack(HudRenderer h,FormulaGame app) {
        var draft=workingDraft();
        boolean authorTimes=draft.plans().stream().anyMatch(CircuitPackDraft.StagePlan::timed);
        if(!authorTimes && draft.rule()==null && draft.objectives().isEmpty() && stageOptions.stream().allMatch(StageOptions::empty)) {
            app.createCircuitPack(draft);
            return;
        }
        if(!app.refreshCircuitAuthoring(authorTimes,draft.rule()!=null)) return;
        var build=draft.rule()==null?null:app.activityPartsBuild();
        var catalog=app.partsStore.catalog();
        var sources=readStageSources(app,draft);
        try {
            var stages=new ArrayList<ActivityCard.Stage>();
            var layouts=new HashMap<String,TrackLayout>();
            for(int i=0;i<sources.size();i++) {
                var plan=draft.plan(i);
                var source=sources.get(i);
                var issue=source.issue(plan,app.vehicleSpec.physicsVersion);
                if(!issue.isEmpty()) {
                    app.notifyUser("第 "+(i+1)+" 项 "+issue);
                    return;
                }
                var track=layouts.computeIfAbsent(plan.map(),id->source.document().track());
                stages.add(plan.on(track,source.benchmark()));
            }
            var card=ActivityCard.create(draft.title(),draft.seed(),app.vehicleSpec.physicsVersion,stages,draft.rule(),draft.objectives());
            var lines=activityDetails(card);
            for(int i=0;i<stages.size();i++) {
                var target=stages.get(i).target();
                if(target!=null) lines.add("第 "+(i+1)+" 项作者圈 · "+PracticeSession.format(target.lap())
                        +" · "+(ControlIdentity.KEYBOARD.equals(target.controls())?"键盘":"手柄")+" · 试次 "+target.resultId());
            }
            lines.addAll(ActivityPartsHud.describe(draft.rule(),catalog));
            lines.add("名称："+draft.title()+"；作者："+draft.author()+"；说明："+draft.notes());
            if(draft.rule()!=null) lines.add("推荐方案说明："+draft.suggestionNotes());
            h.review("保存地图赛事包 · 完整活动条件",lines,"保存为独立赛事包",()->app.createCircuitPack(draft,()->
                draft.equals(workingDraft()) && sources.equals(readStageSources(app,draft))
                    && (draft.rule()==null || app.activityPartsBuild()==build && app.partsStore.catalog()==catalog)));
        } catch(IllegalArgumentException error) {
            app.notifyUser("请补齐地图或作者圈，并检查活动条件，草稿保留。");
        }
    }
    private record StageSource(CircuitDocument document,CircuitTrial.Proof proof,CircuitBenchmark benchmark) {
        String issue(CircuitPackDraft.StagePlan plan,String physics) {
            if(document==null) return "地图缺失，请添加地图或重新选择。";
            if(proof==null || !proof.matches(document,physics)) return "地图需要完成并保存本机四车验证。";
            if(plan.timed() && benchmark==null) return plan.reference()==null
                ?"本项需要已保存的原厂作者圈，可用所选地图的作者标定。"
                :"选定的作者圈已移出记录或条件不同，请在本项重新选择。";
            return "";
        }
    }
    private static List<StageSource> readStageSources(FormulaGame app,CircuitPackDraft draft) {
        return draft.plans().stream().map(plan->new StageSource(app.packCircuit(plan.map()),
                app.circuitShowcaseProof(plan.map()),plan.timed()?app.circuitBenchmark(plan.map(),plan.reference()):null)).toList();
    }
    private boolean compatible(FormulaGame app,int index,ActivityCard.Kind kind,StageOptions options,int defaultLaps) {
        var objective=stageObjectives.get(index);
        if(objective==null) return true;
        String map=stageMaps.get(index);
        var document=app.packCircuit(map);
        int count=options.laps()==null?defaultLaps:options.laps();
        int opponents=options.difficulty()==null?difficulty:options.difficulty();
        int placing=options.place()==null?place:options.place();
        var plan=new CircuitPackDraft.StagePlan(kind,map,opponents,count,placing,false);
        if(document!=null && objective.applicable(plan.on(document.track(),null))) return true;
        app.notifyUser("第 "+(index+1)+" 项条件不适用于新模式或圈数，请先修改该项条件。");
        return false;
    }
    static ArrayList<String> activityDetails(ActivityCard card) {var lines=new ArrayList<String>();lines.add(card.rulesTitle()+"；固定种子 "+card.seed()+"；物理 "+card.physics());for(int i=0;i<card.stages().size();i++) {var stage=card.stages().get(i);lines.add("第 "+(i+1)+" 项 · "+stage.trackName()+" · "+card.stageTitle(stage)+" · 难度 "+stage.difficulty()+" · "+stage.goal());var objective=card.objective(i);if(objective==null) lines.add("无附加条件。");else lines.addAll(objective.describe());}return lines;}
    private String mapName(FormulaGame app,String id) { var document=app.packCircuit(id);return document==null?"缺失地图":document.name(); }
    private PackStatus status(FormulaGame app,CircuitEventPack.Entry entry) {
        if(statusEntry!=entry || statusMaps!=app.circuits.all() || statusCatalog!=app.partsStore.catalog()) {
            var readiness=app.packReadiness(entry);
            int missing=0,waiting=0,different=0;
            for(var map:readiness.maps()) switch(map.state()) {
                case MISSING -> missing++;
                case NEEDS_VALIDATION -> waiting++;
                case DIFFERENT -> different++;
                case READY -> {}
            }
            var issues=new ArrayList<String>(3);
            if(missing>0) issues.add("缺图 "+missing);
            if(waiting>0) issues.add("待验证 "+waiting);
            if(different>0) issues.add("条件不符 "+different);
            String detail=issues.isEmpty()?readiness.issue():String.join(" · ",issues);
            statusEntry=entry;statusMaps=app.circuits.all();statusCatalog=app.partsStore.catalog();
            cachedStatus=new PackStatus(readiness,detail);
        }
        return cachedStatus;
    }
    private void reviewPackMaps(HudRenderer h,FormulaGame app,CircuitEventPack.Entry entry) {
        if(!entry.equals(app.creationStore.pack(entry.id()))) {
            app.notifyUser("赛事包已变化，请重新选择。");
            return;
        }
        if(!app.creationStore.pending() && !app.refreshCircuitPackInputs(entry)) return;
        var readiness=status(app,entry).readiness();
        var lines=new ArrayList<String>();
        for(int index=0;index<readiness.maps().size();index++) {
            var map=readiness.maps().get(index);
            lines.add("第 "+(index+1)+" 张 · "+map.name()+" · "+map.state().label);
            if(!map.issue().isEmpty()) lines.add(map.issue());
        }
        boolean hasPending=readiness.firstPendingMap()>=0;
        if(!hasPending && !readiness.issue().isEmpty()) lines.add(readiness.issue());
        h.review("赛事包逐图状态",lines,hasPending?"定位首张待处理地图":"返回包库",hasPending?()->{
            var current=app.creationStore.pack(entry.id());
            if(app.screen!=FormulaGame.Screen.CREATION || !entry.equals(current) || !entry.id().equals(selectedPack)) {
                app.notifyUser("所选赛事包已变化，请重新查看逐图状态。");
                return;
            }
            int index=status(app,current).readiness().firstPendingMap();
            if(index>=0) packMapIndex=index;
            else app.notifyUser("当前包内地图资格均匹配。");
        }:null);
    }
    private void nextMap(FormulaGame app) {
        var options = app.packMapOptions();
        if (options.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < options.size(); i++) if (options.get(i).id().equals(stageMaps.get(stage))) index = i;
        stageMaps.set(stage, options.get((index + 1) % options.size()).id());
        captureDraft(app);
    }
    private String shorten(String value,int maximum) {
        if (value.length() <= maximum) return value;
        int end = maximum - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
    private boolean text(String value,int maximum) { return PartsCatalog.text(value,maximum) && !value.isBlank(); }
}
