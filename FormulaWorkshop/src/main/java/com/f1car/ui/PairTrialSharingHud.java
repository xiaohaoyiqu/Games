package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PairTrialShareCodec;
import com.f1car.persistence.ExportDestination;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Explicit offline files. One export draft remains in this process until deliberately ended. */
final class PairTrialSharingHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x63e6bc),AMBER=new Color(0xffbe72),PANEL=new Color(0x13242e);
    private record Local(PairExperiment.Snapshot report,PairTrialRevision history,String title) {}
    private boolean showing,receivedMode;private List<Local> locals=List.of();private List<PairTrialShare> received=List.of();private List<List<String>> receivedDetails=List.of();
    private FormulaGame.PairShareExport exportDraft;private List<String> exportDetails=List.of();private String exportPath="",exportStatus="";
    boolean showing() {return showing;}
    void leave() {showing=false;locals=List.of();received=List.of();receivedDetails=List.of();}
    void open(FormulaGame app) {showing=true;refresh(app);}
    private void refresh(FormulaGame app) {
        var values=new ArrayList<Local>();for(var report:app.pairTrials.reports()) values.add(new Local(report,null,"已存报告 · "+report.id().substring(0,8)+" · 计数 #"+report.lastAttempt()+" · "+report.runs().size()+" / 12"));
        for(var row:app.pairTrials.history()) {var report=row.before();values.add(new Local(report,row,"重测前历史 · "+report.id().substring(0,8)+" · 原计数 #"+report.lastAttempt()+" → #"+row.afterAttempt()));}
        locals=List.copyOf(values);received=app.receivedPairTrials.entries();receivedDetails=received.stream().map(PairTrialSharingHud::details).toList();
    }
    private void prepare(HudRenderer h,FormulaGame app,Local local) {
        if(exportDraft!=null) {app.notifyUser("先继续或明确结束原分享草稿；原声明保持。");return;}
        var lines=new ArrayList<>(PairTrialsHud.source(local.report()));lines.add(0,local.title());lines.add("本次将导出全部冻结参数、装备说明、有效原测量与操作／位置曲线。只写独立文件，不会发送给其他人。");
        h.review("明确选择这份完整报告导出？",lines,"填写本次提供者声明",()->{
            String name=app.store.profile.playerName;if(!PartsCatalog.text(name,40) || name.isBlank()) name="玩家";
            h.prompt("本次提供者署名声明",name,40,author->h.editText("本次分享说明","","",320,notes->{try {
                var share=PairTrialShare.capture(local.report(),local.history(),author,notes);beginExport(app,new FormulaGame.PairShareExport(share,local.history()==null?FormulaGame.PairShareOrigin.CURRENT:FormulaGame.PairShareOrigin.HISTORY));
            }catch(IllegalArgumentException error) {app.notifyUser("署名或说明无效，原报告保持。");}}));
        });
    }
    private void beginExport(FormulaGame app,FormulaGame.PairShareExport value) {
        if(exportDraft!=null) {app.notifyUser("原分享草稿仍在，未替换其内容或身份。");return;}
        exportDraft=value;exportDetails=details(value.share());exportPath=defaultPath(app).toString();exportStatus="分享草稿尚未导出；失败可保留同份声明和身份重试。";
    }
    private void write(HudRenderer h,FormulaGame app) {
        var draft=exportDraft;if(draft==null) return;
        try {
            var destination=ExportDestination.inspect(Path.of(exportPath.strip()));
            var path=destination.path();
            boolean replace=destination.replacing();
            Runnable save=()->{
            if(exportDraft!=draft) {app.notifyUser("分享草稿已变化，请重新预览。");return;}
            if(!h.exportDestinationCurrent(destination)) {
                exportStatus="目标已变化，同份分享草稿保留，请重新确认导出。";
                return;
            }
            boolean saved=app.exportPairTrial(draft,path,replace);exportStatus=saved?"完整四条件文件已导出；可保留同份声明重导出，或明确结束草稿。":"本次未导出；同份声明、分享身份和目标保持，请修复后明确重试。";
        };
        var lines=new ArrayList<String>();lines.add("独立导出目标："+path);lines.add(replace?"此目标已存在，本次明确替换该导出文件。":"仅新建此独立文件；目标随后出现时不自动覆盖。");lines.add("分享身份 "+draft.share().id()+"；原声明、全部参数、测量与曲线随文件提供。不会自动发送。");
        h.review(replace?"明确覆盖此导出文件？":"明确写出完整四条件文件？",lines,replace?"覆盖此独立导出文件":"写出此独立文件",save);
        }catch(java.io.IOException | RuntimeException error) {exportStatus="无法核对导出目标；同份分享草稿保持，请修改路径。";app.notifyUser(exportStatus);}
    }
    private void export(HudRenderer h,FormulaGame app) {
        var draft=exportDraft;var value=draft.share();h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件 · 独立分享草稿",60,107,30,INK,true);
        h.smallButton(930,57,284,38,"返回原入口，保留分享草稿",this::leave);
        h.text("声明 "+label(value.author(),18)+" · 分享 "+value.id().substring(0,8)+" · "+(draft.origin()==FormulaGame.PairShareOrigin.RECEIVED?"收到原文重导出":"本机选定来源"),60,156,17,MINT);
        h.wrapped(exportStatus,60,203,1154,17,AMBER,28);
        h.smallButton(60,279,1154,42,"核对完整提供者声明、参数、原测量与曲线范围",()->h.review("本次完整分享内容",exportDetails,"返回导出草稿",()->{}));
        h.text("独立文件路径：",60,374,16,SOFT);h.wrapped(exportPath,60,408,1154,17,INK,28);
        h.smallButton(60,487,550,42,"修改目标路径，保留同份内容",()->h.editTextChecked("玩家资料目录外的独立导出路径",exportPath,defaultPath(app).toString(),512,text->{try {String next=text.strip();if(next.isBlank()) return false;Path.of(next);exportPath=next;return true;}catch(RuntimeException error) {app.notifyUser("路径无效，原输入与分享草稿保持。");return false;}}));
        h.smallButton(640,487,574,42,"预览目标后明确导出／重试",()->write(h,app));
        h.wrapped("收到原文重导出时，保留原提供者署名、说明、分享身份和历史关系。导出只写文件，不发送、不采用配置，也不认证作者或真实驾驶。",60,583,1154,16,SOFT,27);
        h.smallButton(60,704,550,38,"返回原入口并保留此草稿",this::leave);h.smallButton(640,704,574,38,"明确结束此分享草稿",()->h.review("结束本次分享草稿？",List.of(value.id(),"仅结束当前进程的分享声明及目标草稿；已存报告、收到副本与已导出的外部文件保持。","尚未导出的署名／说明不会另行保存；退出程序也不会自动保存此草稿。"),"明确结束此分享草稿",()->{exportDraft=null;exportDetails=List.of();exportPath=exportStatus="";refresh(app);}));
    }
    void render(HudRenderer h,FormulaGame app,PairTraceHud trace) {
        if(exportDraft!=null) {export(h,app);return;}
        h.clearButtons();h.panel(28,24,1224,752,PANEL);h.text("四条件 · 独立文件分享与收到库",60,107,30,INK,true);
        h.smallButton(630,57,180,38,receivedMode?"查看本机来源":"查看收到副本",()->{receivedMode=!receivedMode;refresh(app);});h.smallButton(830,57,180,38,"明确刷新本页列表",()->refresh(app));h.smallButton(1030,57,184,38,"返回原入口",this::leave);
        h.text(receivedMode?"收到内容只作提供者声明，不成为本机报告、资格或装备。":"明确选择一份已接受报告或完整历史；导出全部原测量和曲线。",60,153,16,SOFT);
        if(!receivedMode) {
            for(int i=0;i<locals.size();i++) {var local=locals.get(i);h.smallButton(60,195+i*53,1154,44,local.title()+" · "+local.report().conditions().exercise().title+" · 选择导出",()->prepare(h,app,local));}
            if(locals.isEmpty()) h.text("没有可导出的已接受报告或成功重测历史；待存候选不冒充已存来源。",60,290,18,SOFT);
        } else {
            for(int i=0;i<received.size();i++) {var value=received.get(i);var lines=receivedDetails.get(i);int y=212+i*92;
                h.text(label(value.author(),12)+" · 分享 "+value.id().substring(0,8)+" · "+value.report().runs().size()+" / 12",60,y+20,16,INK);h.text("报告 "+value.report().id().substring(0,8)+" · 原计数 #"+value.report().lastAttempt()+" · "+(value.history()==null?"提供者报告":"提供者重测前历史"),60,y+49,14,SOFT);
                h.smallButton(620,y,180,38,"原文与只读曲线",()->h.review("收到的完整四条件声明",lines,value.report().runs().isEmpty()?"返回收到库":"只读打开原曲线",()->{if(!value.report().runs().isEmpty()) trace.openReceived(value);}));
                h.smallButton(820,y,180,38,"保留原声明导出",()->beginExport(app,new FormulaGame.PairShareExport(value,FormulaGame.PairShareOrigin.RECEIVED)));
                h.smallButton(1020,y,194,38,"移除此收到副本",()->h.review("只移除此收到副本？",List.of(value.id(),"已存本机报告、历史、装备、成绩及原外部文件保持。","已有收到待存仍须另点重试，不会因移除自动提交。"),"明确移除此收到副本",()->{app.removeReceivedPairTrial(value);refresh(app);}));
            }
            if(received.isEmpty()) h.text("收到库为空。可读取独立 JSON，先核对完整提供者内容，再明确收录。",60,305,18,SOFT);
            h.wrapped(app.receivedPairTrials.warning(),60,604,1154,15,AMBER,23);
        }
        h.smallButton(60,662,1154,31,"读取独立四条件交换文件，先预览再接收",()->h.editTextChecked("独立四条件交换 JSON 路径",defaultPath(app).toString(),defaultPath(app).toString(),512,text->{try {var value=PairTrialShareCodec.read(Path.of(text.strip()));var lines=new ArrayList<>(details(value));lines.add("本次收录的是已预览的冻结内容；即使外部文件随后改变，也不悄悄换成新内容。");h.review("接收此完整提供者声明？",lines,"仅存独立收到库",()->{app.receivePairTrial(value);receivedMode=true;refresh(app);});return true;}catch(java.io.IOException | RuntimeException error) {app.notifyUser("文件无法读取；请核对四条件格式、UTF-8、大小与内容校验，原输入和已有资料保持。");return false;}}));
        if(app.receivedPairTrials.pending()!=null) {h.smallButton(60,704,350,38,"重试同份收到待存",()->{app.receivedPairTrials.retry();receivedMode=true;refresh(app);});h.smallButton(430,704,350,38,"明确取消收到待存",()->h.review("取消尚未收录的收到候选？",List.of("仅取消本进程中的收到待存；已存收到库、本机报告及外部文件保持。","需要时可从原独立文件重新预览接收。"),"明确取消收到待存",()->{app.receivedPairTrials.cancelPending();refresh(app);}));}
        else h.smallButton(60,704,720,38,"分享包括完整装备、参数、原测量和操作／位置曲线",()->h.review("完整四条件文件的范围",List.of("每次导出一份完整已接受报告或历史快照，可未完成十二组。","不把缺少的组补为零，不比较未齐十二次的部分中位数。","提供者署名、说明与历史关系是声明；内容校验不认证作者或实际驾驶。","收到副本不自动采用、合并本机报告、安装地图或迁移留言。"),"返回分享库",()->{}));
        h.smallButton(800,704,414,38,"修复后明确重读收到库",()->{app.receivedPairTrials.reload();receivedMode=true;refresh(app);});
    }
    static List<String> details(PairTrialShare value) {
        var lines=new ArrayList<String>();lines.add("提供者署名声明："+value.author());lines.add("原分享说明："+value.notes());lines.add("分享身份 "+value.id()+"；提供者分享毫秒值 "+value.sharedAt());
        if(value.history()!=null) {var history=value.history();lines.add("提供者声明这是重测前完整快照；历史 "+history.id()+"；原计数 #"+history.before().lastAttempt()+" → #"+history.afterAttempt());for(var row:history.replacements()) lines.add("原第 "+(row.index()+1)+" 组 #"+row.oldAttempt()+" 被 #"+row.newAttempt()+" 替换；本文件不含替换后测量。");}
        lines.addAll(PairTrialsHud.source(value.report()));lines.add("有效 "+value.report().runs().size()+" / 12；失败 "+value.report().failedAttempts()+" 次；原计数 #"+value.report().lastAttempt());
        for(int i=0;i<value.report().runs().size();i++) {var run=value.report().runs().get(i);var measurement=run.measurement();lines.add("原第 "+(i+1)+" 组 · "+run.condition().title+" · #"+run.attempt()+" · 完成毫秒 "+run.recordedAt());lines.add("原测量 "+Double.toString(measurement.value())+" "+value.report().conditions().exercise().unit+"；入口／出口 "+Float.toString(measurement.entry())+" / "+Float.toString(measurement.exit())+" km/h；曲线 "+run.trace().samples().size()+" 点，"+(run.trace().positions()==null?"无原位置":"原位置与对齐标记保持"));}
        if(!value.report().complete()) lines.add("十二次未齐，不比较部分条件中位数。未完成原组不会补造为零。");
        else {lines.add("以下仅按本文件十二次原测量作描述性汇总；不证明驾驶真实性、显著性或收益。");if(!value.report().comparableEntry()) lines.add("各条件入口差异需核对或重测，以下差值不作条件收益判断。");for(var group:value.report().groups()) lines.add(group.condition().title+" · "+group.count()+" 次 · 中位 "+Double.toString(group.median())+" · 极差 "+Double.toString(group.spread())+" "+value.report().conditions().exercise().unit);for(var contrast:value.report().contrasts()) lines.add(contrast.label()+"："+Double.toString(contrast.delta())+" "+value.report().conditions().exercise().unit);}
        lines.add("这是提供者内容，不能认证作者、真实驾驶、改装收益或因果；不写成本机测量、不采用配置。");return List.copyOf(lines);
    }
    private static String label(String value,int maximum) {return value.codePointCount(0,value.length())<=maximum?value:value.substring(0,value.offsetByCodePoints(0,maximum-1))+"…";}
    private static Path defaultPath(FormulaGame app) {return app.store.directory().toAbsolutePath().getParent().resolve("pair-trial-export.json");}
}
