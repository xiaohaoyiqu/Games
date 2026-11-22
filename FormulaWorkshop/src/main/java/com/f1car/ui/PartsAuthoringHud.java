package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import java.awt.Color;
import java.util.*;
import java.util.function.UnaryOperator;

/** In-game declaration editor. Publication, exchange and actual vehicle fitting remain explicit operations. */
final class PartsAuthoringHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private boolean open;private int selected,page;private Slot templateSlot=Slot.POWER;
    private PartsAuthoring.State checked;private List<String> issues=List.of();
    boolean showing() {return open;}
    void open(Slot slot) {templateSlot=slot;open=true;}
    void editPack(HudRenderer h,FormulaGame app,Pack pack) {
        var before=app.partsAuthoring();var state=before==null?null:before.state();
        Runnable copy=()->{if(app.partsAuthoring()!=before || before!=null && before.state()!=state) {app.notifyUser("制作草稿已变化，请重新选择包。");return;}if(app.beginPartsAuthoring(PartsAuthoring.Draft.from(pack))) {open=true;selected=page=0;}};
        if(app.partsAuthoring()!=null) h.confirm("改为所选包的制作副本？","替换当前独立制作草稿。已收录目录、试装草稿与已装装备分别保留。",copy);else copy.run();
    }
    void render(HudRenderer h,FormulaGame app) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("PARTS MAKER",60,65,14,MINT,true);h.text("制作装备与能力范围",60,105,30,INK,true);
        h.text("署名与用途由作者声明；只编辑现有车辆模型支持的范围，收录后仍需试装与试车。",60,143,15,SOFT);
        h.smallButton(1030,57,184,38,"返回部件包管理",()->{app.captureAuthoringDrafts();app.partsAuthoringEditing.flush();open=false;});
        h.smallButton(60,755,1154,21,"制作草稿 · "+app.partsAuthoringEditing.status(),()->DraftRecoveryHud.review(h,app,app.partsAuthoringEditing));
        var editor=app.partsAuthoring();
        if(editor==null) {h.wrapped("从已有部件复制出发，定义自己的名称、用途、代价、点数、微调范围及组合规则。一包最多十六件。制作草稿自动保存，目录收录与车辆安装分别确认。",60,250,1100,22,INK,36);h.smallButton(60,390,500,48,"从当前部件创建自己的包",()->fresh(h,app));return;}
        var state=editor.state();var draft=state.draft();selected=Math.max(0,Math.min(selected,draft.parts().size()-1));
        if(checked!=state) {issues=draft.issues();checked=state;}
        h.smallButton(60,165,450,32,"包名："+cut(draft.name(),21),()->text(h,app,editor,state,"部件包名称",draft.name(),60,value->draft.metadata(value,draft.author(),draft.revision())));
        h.smallButton(530,165,360,32,"署名："+cut(draft.author(),15),()->text(h,app,editor,state,"作者署名（声明）",draft.author(),60,value->draft.metadata(draft.name(),value,draft.revision())));
        h.smallButton(910,165,304,32,"修订："+draft.revision(),()->number(h,app,editor,state,"制作包修订",draft.revision(),1,1_000_000,"",value->draft.metadata(draft.name(),draft.author(),value)));
        h.text("命名空间："+cut(draft.id(),18)+" · "+draft.parts().size()+" / 16 件",60,235,17,MINT);
        h.smallButton(600,211,614,30,"另存新命名空间 · 本包依赖一并改名",()->text(h,app,editor,state,"新玩家命名空间：小写字母、数字、连字符",draft.id()+"-copy",32,draft::fork));
        int pages=Math.max(1,(draft.parts().size()+4)/5);page=Math.max(0,Math.min(page,pages-1));
        for(int i=page*5;i<Math.min(draft.parts().size(),page*5+5);i++) {var part=draft.parts().get(i);int index=i,y=263+(i-page*5)*54;h.smallButton(60,y,294,34,(i==selected?"● ":"")+cut(part.name(),14),()->selected=index);h.text(part.slot().title+" · "+cut(part.id().substring(draft.id().length()+1),20),67,y+49,12,SOFT);}
        h.smallButton(60,542,110,30,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,195,565,14,SOFT);h.smallButton(244,542,110,30,"下一页",()->page=Math.min(pages-1,page+1));
        int index=selected;var part=draft.parts().get(index);
        h.smallButton(390,255,524,32,"名称："+cut(part.name(),24),()->text(h,app,editor,state,"部件名称",part.name(),40,value->draft.replace(index,part.text(value,part.purpose(),part.tradeoff()))));
        h.smallButton(930,255,284,32,"方案点数："+part.points(),()->number(h,app,editor,state,"方案预算点数（不是性能评分）",part.points(),0,100," 点",value->draft.replace(index,part.appearance(value,part.color()))));
        h.smallButton(390,297,524,30,"用途："+cut(part.purpose(),25),()->text(h,app,editor,state,"预期用途（需试车验证）",part.purpose(),120,value->draft.replace(index,part.text(part.name(),value,part.tradeoff()))));
        h.smallButton(930,297,284,30,"色标："+String.format(Locale.ROOT,"%06x",part.color()),()->text(h,app,editor,state,"六位十六进制部件色标",String.format(Locale.ROOT,"%06x",part.color()),6,value->{if(!value.matches("[a-fA-F0-9]{6}")) throw new IllegalArgumentException("需要六位十六进制色标。");return draft.replace(index,part.appearance(part.points(),Integer.parseInt(value,16)));}));
        h.smallButton(390,337,824,30,"代价："+cut(part.tradeoff(),41),()->text(h,app,editor,state,"预期代价与限制（声明）",part.tradeoff(),160,value->draft.replace(index,part.text(part.name(),part.purpose(),value))));
        h.smallButton(390,377,824,30,"部件身份："+cut(part.id(),32)+" · 点击改名并更新本包引用",()->text(h,app,editor,state,"部件短身份：小写字母、数字、连字符",part.id().substring(draft.id().length()+1),48,value->draft.rename(index,value)));
        h.text(part.slot().title+" · 参数",390,442,17,INK,true);h.text("最小值",640,442,15,SOFT);h.text("默认值",838,442,15,SOFT);h.text("最大值",1036,442,15,SOFT);
        for(int row=0;row<part.slot().fields().size();row++) {var field=part.slot().fields().get(row);var setting=part.settings().get(field);int y=461+row*36;h.text(field.title,390,y+23,15,INK);int[] values={setting.min(),setting.initial(),setting.max()};for(int column=0;column<3;column++) {int cell=column,value=values[column];h.smallButton(620+column*198,y,182,30,value+"%",()->number(h,app,editor,state,field.title+" · "+List.of("下限","默认值","上限").get(cell),value,field.min,field.max,"%",next->draft.replace(index,part.setting(field,setting.cell(cell,next)))));}}
        h.smallButton(60,582,140,30,"需要 "+part.requires().size()+" 件",()->rules(h,app,editor,state,index,true));h.smallButton(214,582,140,30,"禁配 "+part.excludes().size()+" 件",()->rules(h,app,editor,state,index,false));
        h.smallButton(60,622,140,32,"模板："+templateSlot.title,()->templateSlot=Slot.values()[(templateSlot.ordinal()+1)%Slot.values().length]);
        h.smallButton(214,622,140,32,"追加此槽位",()->{if(change(app,editor,state,d->d.add(template(app,templateSlot)))) {selected=editor.draft().parts().size()-1;page=selected/5;}});
        h.smallButton(60,666,294,30,"移除此部件",()->h.confirm("移除制作草稿中的部件？","其他部件中的依赖原文保留，不自动删除；本次操作可撤销。",()->{if(change(app,editor,state,d->d.remove(index))) selected=Math.min(index,editor.draft().parts().size()-1);}));
        h.smallButton(390,655,824,36,issues.isEmpty()?"范围关系可用 · 下限 ≤ 默认值 ≤ 上限；预览再检查修订与目录":"制作草稿有 "+issues.size()+" 项待处理 · 点击查看",()->h.review("制作检查",issues.isEmpty()?List.of("范围关系通过。收录时还会检查包字节上限、目录容量及当前修订影响。","点数是方案预算，不能证明收益；只有当前模型支持的参数可被定义。"):issues,"返回编辑",()->{}));
        h.smallButton(60,709,140,38,"撤销 "+state.undo().size(),()->{if(current(app,editor,state)) editor.undo();});h.smallButton(214,709,140,38,"重做 "+state.redo().size(),()->{if(current(app,editor,state)) editor.redo();});
        h.smallButton(368,709,210,38,"保存制作草稿",()->{app.captureAuthoringDrafts();app.notifyUser(app.partsAuthoringEditing.flush()?"制作草稿已保存；尚未收录到部件目录。":app.partsAuthoringEditing.warning());});
        h.smallButton(592,709,200,38,"预览并收录目录",()->collect(h,app));h.smallButton(806,709,200,38,"导出制作包",()->export(h,app));h.smallButton(1020,709,194,38,"从模板新建包",()->fresh(h,app));
    }
    private static Part template(FormulaGame app,Slot slot) {var selected=app.partsWorkbench().build().slots().get(slot);var value=app.partsStore.catalog().find(selected.part());return value!=null && value.slot()==slot?value:com.f1car.persistence.PartsPackCodec.builtin().parts().stream().filter(part->part.id().equals(slot.stockId())).findFirst().orElseThrow();}
    private void fresh(HudRenderer h,FormulaGame app) {
        var before=app.partsAuthoring();var state=before==null?null:before.state();
        Runnable create=()->h.editText("新玩家命名空间：小写字母、数字、连字符","my-parts","my-parts",32,value->{if(!open || app.screen!=FormulaGame.Screen.PARTS || app.partsAuthoring()!=before || before!=null && before.state()!=state) {app.notifyUser("制作草稿已变化，请重新开始。");return;}if(!PartsAuthoring.namespace(value)) throw new IllegalArgumentException();if(app.beginPartsAuthoring(PartsAuthoring.Draft.create(value,app.store.profile.playerName,template(app,templateSlot)))) {open=true;selected=page=0;}});
        if(app.partsAuthoring()!=null) h.confirm("创建另一份制作草稿？","替换当前制作副本；已收录部件、试装草稿和车库保持。",create);else create.run();
    }
    private void rules(HudRenderer h,FormulaGame app,PartsAuthoring editor,PartsAuthoring.State state,int index,boolean required) {
        var draft=state.draft();var part=draft.parts().get(index);String value=String.join(", ",new TreeSet<>(required?part.requires():part.excludes()));
        text(h,app,editor,state,required?"需要的完整部件身份，逗号分隔，最多八件":"禁配的完整部件身份，逗号分隔，最多八件",value,664,text->{var values=new LinkedHashSet<String>();if(!text.isBlank()) for(String token:text.split(",",-1)) {String id=token.strip();if(!PartsCatalog.validId(id) || !values.add(id)) throw new IllegalArgumentException("部件身份无效或重复。");}return draft.replace(index,part.rules(required?values:part.requires(),required?part.excludes():values));});
    }
    private boolean current(FormulaGame app,PartsAuthoring editor,PartsAuthoring.State state) {return open && !app.online() && app.screen==FormulaGame.Screen.PARTS && app.partsAuthoring()==editor && editor.state()==state;}
    private boolean change(FormulaGame app,PartsAuthoring editor,PartsAuthoring.State state,UnaryOperator<PartsAuthoring.Draft> action) {if(!current(app,editor,state)) {app.notifyUser("制作草稿已变化，请重新打开输入或预览。");return false;}try {editor.adopt(action.apply(state.draft()));return true;}catch(IllegalArgumentException error) {app.notifyUser(error.getMessage());return false;}}
    private void text(HudRenderer h,FormulaGame app,PartsAuthoring editor,PartsAuthoring.State state,String title,String value,int limit,java.util.function.Function<String,PartsAuthoring.Draft> action) {h.editText(title,value,value,limit,next->{if(!current(app,editor,state)) {app.notifyUser("制作草稿已变化，请重新打开输入。");return;}editor.adopt(action.apply(next));});}
    private void number(HudRenderer h,FormulaGame app,PartsAuthoring editor,PartsAuthoring.State state,String title,int value,int min,int max,String unit,java.util.function.IntFunction<PartsAuthoring.Draft> action) {h.editNumber(title,value,min,max,unit,next->change(app,editor,state,draft->action.apply(next)));}
    private void collect(HudRenderer h,FormulaGame app) {
        var expected=app.reviewPartsAuthoring();if(expected==null) return;var lines=new ArrayList<String>();
        lines.add("只收录部件定义，不替换试装草稿、不安装装备、不采用车库参数。制作副本保留，可继续修改。");
        if(expected.preview()==null) {lines.add(expected.installationIssue());lines.add("制作草稿仍可保存与导出；目录满额时可先导出并移除其他包。");h.review("目录暂不能收录",lines,"返回制作",()->{});return;}
        for(var part:expected.pack().parts()) {for(String id:new TreeSet<>(part.requires())) {var target=expected.preview().find(id);if(target==null) lines.add(part.id()+"：需要的 "+id+" 当前未在目录中；可另行收录，试装仍须齐全。");else if(target.slot()==part.slot()) lines.add(part.id()+"：需要的 "+id+" 占用同一槽位，不能同时试装。");}for(String id:new TreeSet<>(part.excludes())) if(expected.preview().find(id)==null) lines.add(part.id()+"：禁配目标 "+id+" 当前未在目录中；规则原文保留。");}
        lines.addAll(PartsHud.revisionDetails(app,List.of(expected.pack()),expected.preview()));
        h.review("制作包 · 收录与修订预览",lines,"明确收录目录",()->app.collectAuthoredParts(expected));
    }
    private void export(HudRenderer h,FormulaGame app) {
        var expected=app.reviewPartsAuthoring();if(expected==null) return;
        h.promptExportPath("制作包导出完整路径",
                app.store.directory().resolve("parts-export-"+expected.pack().id()+".json"),
                (path,replace)->app.exportAuthoredParts(expected,path,replace));
    }
    private static String cut(String value,int max) {return value.length()<=max?value:value.substring(0,max-1)+"…";}
}
