package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.SetupLibraryStore;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;

final class SetupLibraryHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),AMBER=new Color(255,190,114);
    private enum SaveKind { NEW, EDIT, IMPORT }
    private record PendingSetup(SaveKind kind,SetupCard original,SetupCard candidate) {}
    private record ComparisonRow(String label,String current,String saved,String difference,boolean changed) {}
    private String selected,baseline,query="";
    private boolean currentTrack=true;
    private int page;
    private boolean suspensionPage;
    private TuningTrial.Exercise exercise=TuningTrial.Exercise.ACCELERATION;
    private PendingSetup pending;
    private List<SetupCard> cachedEntries,filtered=List.of();
    private TrackLayout cachedTrack;
    private String cachedQuery;
    private Tuning cachedBaseline,cachedComparison;
    private List<ComparisonRow> comparisonRows=List.of();

    void render(HudRenderer h,FormulaGame app) {
        h.panel(28,24,1224,752,new Color(10,29,39,247));h.text("SETUP LIBRARY",60,65,14,MINT,true);h.text("命名调校配置库",60,108,30,INK,true);
        h.smallButton(1055,56,165,42,"返回车库",app::backFromSetupLibrary);
        var entries=app.setupLibrary.entries();
        h.text("当前赛道："+shorten(app.circuit.name,30)+" · "+entries.size()+" / 32 份",60,141,16,SOFT);
        if(pending==null) {
            h.smallButton(60,163,246,39,"保存当前配置为新方案",()->edit(h,app,null));
            h.smallButton(320,163,230,39,"导入配置文件",()->importFile(h,app));
        } else {
            h.smallButton(60,163,246,39,"查看待保存配置",()->reviewPending(h,app));
            h.smallButton(320,163,230,39,"放弃待存配置",()->discardPending(h));
        }
        h.smallButton(60,215,234,36,currentTrack?"范围：当前赛道":"范围：全部赛道",()->{currentTrack=!currentTrack;page=0;});
        h.smallButton(306,215,244,36,query.isEmpty()?"搜索名称、赛道与备注":"搜索："+shorten(query,11),()->h.prompt("搜索配置；留空显示全部",query,80,value->{query=value.strip();page=0;}));
        var values=filtered(entries,currentTrack?app.circuit:null);
        int pages=Math.max(1,(values.size()+3)/4);page=Math.max(0,Math.min(page,pages-1));
        if(selected==null || values.stream().noneMatch(card->card.id().equals(selected))) selected=values.isEmpty()?null:values.get(0).id();
        if(baseline!=null && app.setupLibrary.find(baseline)==null) baseline=null;
        for(int i=page*4;i<Math.min(values.size(),page*4+4);i++) {
            var card=values.get(i);int y=267+(i-page*4)*84;
            h.panel(60,y,490,73,new Color(24,49,60));
            h.smallButton(70,y+8,470,32,(card.id().equals(selected)?"● ":"")+shorten(card.name(),23)+(card.id().equals(baseline)?" · 对比 A":""),()->selected=card.id());
            String incompatibility=card.incompatibility(app.vehicleSpec);
            h.text(shorten(card.trackName(),24)+" · "+(incompatibility.isEmpty()?"可应用":incompatibility),80,y+61,13,SOFT);
        }
        if(values.isEmpty()) h.wrapped("没有匹配配置。可保存当前配置，或切换到全部赛道并清空搜索。",70,306,465,19,SOFT,30);
        h.smallButton(60,623,113,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,218,648,15,SOFT);
        h.smallButton(367,623,183,34,"下一页",()->page=Math.min(pages-1,page+1));
        if(!app.setupLibrary.warning().isEmpty()) h.wrapped(app.setupLibrary.warning(),60,687,485,14,AMBER,22);
        h.smallButton(60,730,170,30,"重新读取配置库",()->{app.setupLibrary.reload();app.notifyUser(app.setupLibrary.writable()?"配置库已重新读取。":app.setupLibrary.warning());});
        h.text("不会自动覆盖原有 A/B 快捷槽",246,752,13,SOFT);
        var card=app.setupLibrary.find(selected);if(card==null) return;
        var a=app.setupLibrary.find(baseline);var tuning=a==null?app.store.profile.tuning:a.tuning();
        h.text(shorten(card.name(),24),585,192,24,MINT,true);
        h.text("保存赛道："+shorten(card.trackName(),30),585,225,15,SOFT);
        h.text(card.sameTrack(app.circuit)?"与当前路线一致":card.trackId().equals(app.circuit.id)?"本图道路已修改：需重新试车":"来自其他赛道：应用前请重新试车",585,251,14,AMBER);
        h.text("A "+(a==null?"当前车库配置":shorten(a.name(),12))+"     B 所选配置",585,287,16,INK,true);
        h.smallButton(1000,272,205,30,suspensionPage?"查看基础参数":"查看前后悬挂",()->suspensionPage=!suspensionPage);
        h.text("项目",585,320,14,SOFT);h.text("A",820,320,14,SOFT);h.text("B",955,320,14,SOFT);h.text("变化",1081,320,14,SOFT);
        int index=0;
        var rows=comparisonRows(card,tuning);
        for(var row:rows.subList(suspensionPage?5:0,suspensionPage?9:5)) {
            int y=343+index++*28;
            h.text(row.label(),585,y,16,SOFT);
            h.text(row.current(),820,y,16,INK);
            h.text(row.saved(),955,y,16,MINT);
            h.text(row.difference(),1081,y,15,row.changed()?MINT:SOFT);
        }
        if(suspensionPage) h.text("轴倍率叠加总配套；100% 为该轴的原始设置。",585,455,14,SOFT);
        h.text("保存时操作："+(ControlIdentity.KEYBOARD.equals(card.controls())?"键盘":"手柄")+" · 应用性能时保留当前操作与涂装",585,482,13,SOFT);
        h.wrapped(card.notes().isBlank()?"尚未填写备注。记录目标、驾驶感受和试车条件，方便下次比较。":shorten(card.notes(),72),585,515,620,15,SOFT,23);
        if(card.canRebase(app.vehicleSpec)) h.smallButton(955,554,250,32,"复制到当前物理版本",()->h.confirm("按当前物理另存一份？",(card.physics().equals("fw-physics-2-speed-limits")?"前后制动从 50/50 开始。":"保留原前后制动比例。")+"\n新增前后弹簧与阻尼倍率均为 100%。\n原配置保留；新副本需重新试车，不携带成绩或地图资格。",()->{var copy=app.rebaseReviewedNamedSetup(card);if(copy!=null) selected=copy.id();}));
        h.smallButton(585,600,190,36,"应用 B 的性能",()->h.confirm("应用所选性能配置？",card.name()+"\n"+(card.sameTrack(app.circuit)?"用于当前赛道。":"保存赛道或道路版本不同，建议先重复试车。")+"\n保留当前涂装、操作和 A/B 快捷槽。",()->app.applyReviewedNamedSetup(card)));
        h.smallButton(790,600,196,36,"将 B 设为对比 A",()->baseline=card.id());h.smallButton(1000,600,205,36,"A 改回当前车库",()->baseline=null);
        if(pending==null) h.smallButton(585,648,190,36,"编辑名称与备注",()->edit(h,app,card));
        h.smallButton(790,648,196,36,"导出 B 的配置",()->export(h,app,card));
        h.smallButton(1000,648,205,36,"删除 B",()->h.confirm("删除这份配置？",card.name()+"\n当前车库、试车报告与 A/B 快捷槽保留。",()->app.removeReviewedNamedSetup(card)));
        h.smallButton(585,698,190,36,exercise.title,()->{var all=TuningTrial.Exercise.values();exercise=all[(exercise.ordinal()+1)%all.length];});
        h.smallButton(790,698,415,36,"用 A / B 建立六次对照试车",()->{
            var planned=exercise;
            h.confirm("建立新的对照试车？","赛道："+app.circuit.name+"\n项目："+planned.title+"。两套配置各三次，按当前操作条件试车。\n将替换当前试车计划；已保存报告与车库 A/B 快捷槽保留。",()->app.beginReviewedLibraryTuning(a,card,tuning,planned));
        });
        h.text("参数差值不代表圈速收益；配置文件不携带有效圈、奖牌或地图资格。",585,757,13,SOFT);
    }

    private List<SetupCard> filtered(List<SetupCard> entries,TrackLayout track) {
        if(entries!=cachedEntries || track!=cachedTrack || !query.equals(cachedQuery)) {
            String term=query.toLowerCase(Locale.ROOT);
            filtered=entries.stream().filter(card->track==null || card.sameTrack(track))
                    .filter(card->term.isEmpty() || (card.name()+" "+card.trackName()+" "+card.notes()).toLowerCase(Locale.ROOT).contains(term))
                    .toList();
            cachedEntries=entries;
            cachedTrack=track;
            cachedQuery=query;
        }
        return filtered;
    }

    private List<ComparisonRow> comparisonRows(SetupCard card,Tuning tuning) {
        if(!tuning.equals(cachedBaseline) || !card.tuning().equals(cachedComparison)) {
            comparisonRows=card.differences(tuning).stream().map(delta->new ComparisonRow(delta.label(),
                    String.format(Locale.ROOT,"%.1f%%",delta.current()*100),String.format(Locale.ROOT,"%.1f%%",delta.saved()*100),
                    String.format(Locale.ROOT,"%+.1f 点",delta.percentagePoints()),Math.abs(delta.percentagePoints())>=.001)).toList();
            cachedBaseline=tuning;
            cachedComparison=card.tuning();
        }
        return comparisonRows;
    }

    private void edit(HudRenderer h,FormulaGame app,SetupCard original) {
        String defaults=original==null?"我的试车配置":original.name();
        h.editText("配置名称",defaults,defaults,40,name->{
            if(name.isBlank() || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            String notes=original==null?"":original.notes();
            h.editText(original==null?"备注：观察目标与驾驶感受":"配置备注",notes,"",240,value->{
                var candidate=original==null?app.captureNamedSetup(name,value):original.withText(name,value);
                submit(app,new PendingSetup(original==null?SaveKind.NEW:SaveKind.EDIT,original,candidate));
            });
        });
    }

    private void submit(FormulaGame app,PendingSetup draft) {
        pending=draft;
        var candidate=draft.candidate();
        var saved=switch(draft.kind()) {
            case NEW->app.saveNamedSetup(candidate);
            case IMPORT->app.importNamedSetup(candidate);
            case EDIT->app.editNamedSetup(draft.original(),candidate)?candidate:null;
        };
        if(saved==null) return;
        pending=null;
        selected=saved.id();
        if(draft.kind()!=SaveKind.EDIT) {query="";page=0;}
        if(draft.kind()==SaveKind.IMPORT || !saved.sameTrack(app.circuit)) currentTrack=false;
    }

    private void reviewPending(HudRenderer h,FormulaGame app) {
        var draft=pending;
        var candidate=draft.candidate();
        var lines=new ArrayList<String>();
        if(!app.setupLibrary.warning().isEmpty()) lines.add(app.setupLibrary.warning());
        addText(lines,"待存",candidate);
        lines.add("待存配置的赛道与物理版本："+candidate.trackName()+" · "+candidate.physics());
        if(!app.setupLibrary.writable()) {
            h.review("待保存配置",lines,"返回配置库",null);
            return;
        }
        if(draft.kind()!=SaveKind.EDIT) {
            h.review("待保存配置",lines,"重试保存",()->submit(app,draft));
            return;
        }
        var original=draft.original();
        var current=app.setupLibrary.find(original.id());
        if(original.equals(current)) {
            h.review("待保存配置",lines,"重试保存",()->submit(app,draft));
            return;
        }

        addText(lines,"编辑时",original);
        if(current!=null) addText(lines,"重读后",current);
        if(current==null || !original.sameConfiguration(current)) {
            lines.add(current==null?"原配置已从库中移除。":"原配置的调校或赛道条件已变化。");
            h.review("核对待存配置",lines,"另存为新配置",()->{
                if(pending==draft && Objects.equals(current,app.setupLibrary.find(original.id())))
                    submit(app,new PendingSetup(SaveKind.NEW,null,candidate.copy()));
                else app.notifyUser("配置已变化，请重新核对待存内容。");
            });
            return;
        }

        var updated=current.mergeEditedText(original,candidate);
        addText(lines,"将保存",updated);
        h.review("核对待存名称与备注",lines,"保存核对后的内容",()->submit(app,new PendingSetup(SaveKind.EDIT,current,updated)));
    }

    private static void addText(List<String> lines,String label,SetupCard card) {
        lines.add(label+"名称："+card.name());
        lines.add(label+"备注："+(card.notes().isEmpty()?"（空）":card.notes()));
    }

    private void discardPending(HudRenderer h) {
        var draft=pending;
        h.confirm("放弃待保存配置？",draft.candidate().name()+"\n确认后丢弃本次输入。",()->{if(pending==draft) pending=null;});
    }

    private void importFile(HudRenderer h,FormulaGame app) {
        h.prompt("导入配置文件完整路径","",512,value->{
            try {
                var incoming=SetupLibraryStore.readShare(Path.of(value.strip()));
                h.confirm("收录这份配置？",incoming.name()+"\n赛道："+incoming.trackName()+"\n"+(incoming.incompatibility(app.vehicleSpec).isEmpty()?"可在当前物理版本试车。":incoming.incompatibility(app.vehicleSpec)+"，可收录查看，不能直接应用。")+"\n仅加入配置库，不会应用到车库。",()->submit(app,new PendingSetup(SaveKind.IMPORT,null,incoming)));
            } catch(java.io.IOException | RuntimeException error) { app.notifyUser("配置文件无法读取：请检查格式、版本、参数范围和文件大小。"); }
        });
    }
    private void export(HudRenderer h,FormulaGame app,SetupCard card) {
        h.promptExportPath("导出配置文件完整路径",app.store.directory().resolve("setup-export-"+card.id()+".json"),
            (path,replace)->app.exportNamedSetup(card,path,replace));
    }
    private static String shorten(String text,int max) { return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…"; }
}
