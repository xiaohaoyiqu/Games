package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import java.awt.Color;
import java.util.*;
import java.util.function.Consumer;

/** A bounded authoring view. Adopting rules only changes the unsaved activity draft. */
final class ActivityPartsHud {
    private static final Color INK=new Color(0xebf5f0),MUTED=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private final Map<Slot,List<String>> rows=new EnumMap<>(Slot.class);
    private final Set<String> selected=new LinkedHashSet<>();
    private PartsCatalog catalog;private PartsActivityRule original;private int slot,page,budget;
    void open(FormulaGame app,PartsActivityRule rule) {
        catalog=app.partsStore.catalog();original=rule;slot=page=0;selected.clear();var build=app.activityPartsBuild();budget=rule==null?build.budget():rule.budget();
        if(rule!=null) rule.allowed().forEach(part->selected.add(part.part()));else {for(var type:Slot.values()) selected.add(type.stockId());build.slots().values().stream().filter(part->catalog.find(part.part())!=null).forEach(part->selected.add(part.part()));}
        for(var type:Slot.values()) {var values=new ArrayList<>(catalog.parts(type).stream().map(Part::id).toList());if(rule!=null) rule.allowed().stream().filter(part->part.slot()==type && !values.contains(part.part())).forEach(part->values.add(part.part()));rows.put(type,List.copyOf(values));}
    }
    void render(HudRenderer h,FormulaGame app,Consumer<PartsActivityRule> adopt,Runnable cancel) {
        h.panel(70,230,1136,505,new Color(24,49,60));
        for(var type:Slot.values()) h.smallButton(85+type.ordinal()*279,246,264,38,(slot==type.ordinal()?"● ":"")+type.title,()->{slot=type.ordinal();page=0;});
        var values=rows.get(Slot.values()[slot]);int pages=Math.max(1,(values.size()+4)/5);page=Math.min(page,pages-1);
        h.text("选择允许部件；每槽至少一件，共最多 64 件。",90,318,16,MUTED);
        for(int i=page*5;i<Math.min(values.size(),page*5+5);i++) {String id=values.get(i);var part=catalog.find(id);String label=(selected.contains(id)?"[已允许] ":"[未允许] ")+(part==null?id+"（缺失）":part.name()+" · "+part.points()+" 点");if(label.codePointCount(0,label.length())>38) label=label.substring(0,label.offsetByCodePoints(0,37))+"…";h.smallButton(85,337+(i-page*5)*48,615,40,label,()->{if(!selected.remove(id)) {if(selected.size()>=PartsActivityRule.MAX_ALLOWED) app.notifyUser("最多允许 64 件部件。");else selected.add(id);}});}
        h.smallButton(85,594,300,35,"上一页 "+(page+1)+" / "+pages,()->page=Math.max(0,page-1));h.smallButton(400,594,300,35,"下一页",()->page=Math.min(pages-1,page+1));
        h.smallButton(730,337,456,40,"规则点数上限："+budget,()->h.prompt("活动预算 0–1000；开赛后不能用工作台预算覆盖",Integer.toString(budget),value->{try {int parsed=Integer.parseInt(value.strip());if(parsed<0 || parsed>1000) throw new NumberFormatException();budget=parsed;}catch(NumberFormatException error) {app.notifyUser("请输入 0–1000 的整数。");}}));
        h.text("已允许 "+selected.size()+" / 64 件",730,414,18,MINT,true);
        h.wrapped("规则固定部件功能定义。作者自填点数只是组合约束，不代表公平或必然更快；仍需试车比较。",730,457,450,17,INK,28);
        h.wrapped("采用前检查当前工作台方案是否满足规则。以后参赛可换符合规则的方案，整场与续玩固定开赛快照。",730,559,450,16,MUTED,27);
        h.smallButton(85,663,300,42,"保持原厂规则",()->adopt.accept(null));h.smallButton(400,663,300,42,"取消规则编辑",cancel);h.smallButton(730,663,456,42,"检查并采用到活动草稿",()->{
            if(app.partsStore.catalog()!=catalog) {app.notifyUser("部件目录已变化，请取消并重新编辑规则。");return;}
            try {var rule=PartsActivityRule.capture(catalog,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,budget,selected);var build=app.activityPartsBuild();var review=rule.review(build,catalog,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());if(!review.valid()) {h.review("规则仍需处理",review.issues(),"关闭并继续编辑",()->{});return;}var lines=new ArrayList<String>();lines.add("仅改变未保存的活动草稿；保存时另建新卡，旧卡和进度保留。");if(original!=null) {lines.add("预算："+original.budget()+" → "+rule.budget()+" 点。");for(var before:original.allowed()) if(!rule.allowed().contains(before)) lines.add("移除或更新规则部件："+before.part());for(var after:rule.allowed()) if(!original.allowed().contains(after)) lines.add("新增或采用当前定义："+after.part());}lines.addAll(describe(rule,catalog));h.review("采用限定部件规则？",lines,"采用到草稿",()->{if(app.partsStore.catalog()!=catalog || app.activityPartsBuild()!=build) {app.notifyUser("目录或工作台方案已改变，请重新检查。");return;}adopt.accept(rule);});}
            catch(IllegalArgumentException error) {app.notifyUser("每槽至少保留一件当前存在的部件，再检查预算与参赛方案。");}
        });
    }
    static List<String> describe(PartsActivityRule rule,PartsCatalog catalog) {
        if(rule==null) return List.of("固定原厂性能；不要求工作台部件。列出的作者计时目标仍按原厂条件。");var lines=new ArrayList<String>();lines.add("预算 "+rule.budget()+" 点；允许 "+rule.allowed().size()+" 件部件；对手与玩家采用同一参赛性能。");
        for(var allowed:rule.allowed()) {var part=catalog.find(allowed.part());if(part==null) {lines.add(allowed.slot().title+"：缺少 "+allowed.part()+"，需先导入匹配部件。");continue;}boolean matches=allowed.fingerprint().equals(catalog.fingerprint(part.id()));lines.add(allowed.slot().title+"："+part.name()+"（"+part.id()+"）；"+(matches?part.points()+" 点":"当前定义已变化，旧规则不能开赛"));lines.add("用途："+part.purpose()+"；代价："+part.tradeoff());var ranges=new ArrayList<String>();part.settings().forEach((field,range)->ranges.add(field.title+" "+range.min()+"–"+range.max()+"%（默认 "+range.initial()+"%）"));lines.add((matches?"调节范围：":"当前目录范围，仅供修订参考：")+String.join("；",ranges)+"；需要："+new TreeSet<>(part.requires())+"；禁配："+new TreeSet<>(part.excludes()));}return List.copyOf(lines);
    }
}
