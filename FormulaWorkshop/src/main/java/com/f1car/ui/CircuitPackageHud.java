package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.util.*;

/** Only builds a bounded comparison on request. A remains an immutable source snapshot. */
final class CircuitPackageHud {
    private CircuitEventPack.Entry baseline;
    void pin(FormulaGame app,CircuitEventPack.Entry entry) {baseline=entry;app.notifyUser("已将原始包固定为对照 A；名称不用于推断版本先后。");}
    String label() {if(baseline==null) return "先固定一份包为对照 A";String name=baseline.original().title();return "比较 A 与当前包："+(name.length()>9?name.substring(0,8)+"…":name);}
    void compare(HudRenderer h,FormulaGame app,CircuitEventPack.Entry current) {
        if(baseline==null) {h.review("先选择包对照",List.of("选中一份包，点击“固定当前包为对照 A”，再选另一份比较。","同名包不自动视为同一作品；比较不会覆盖地图、部件、方案或活动。"),"关闭",()->{});return;}
        var a=baseline;
        var lines=new ArrayList<String>();
        describePackage(lines,"A 固定原始包",a.original());
        describePackage(lines,"B 选中的原始包",current.original());
        boolean lineageRead=app.circuitLineages.refreshIfChanged();
        var diff=CircuitPackageDiff.compare(a.original(),current.original(),lineageRead?app.circuitLineages.entries():List.of());
        lines.add(diff.usesDeclaredMaps()?"源地图按匹配这两份原包的直接作者声明配对。"
                :"源地图按相同源编号配对。活动按位置比较。");
        if(diff.usesDeclaredMaps()) lines.add("活动按位置比较，计时与资格各自对应原来的地图条件。");
        if(!lineageRead) lines.add(app.circuitLineages.warning());
        if(diff.changes().isEmpty()) lines.add("原始包的内容一致。包、活动和推荐方案各有自己的编号。");
        else lines.addAll(diff.changes());
        if(app.refreshCircuitAuthoring(false,false)) {
            installed(lines,app,"A",a);
            installed(lines,app,"B",current);
        } else lines.add("当前无法核对本地地图，以上为原始包对照。");
        h.review("赛事包 A／B · 内容与本地修改",lines,"关闭比较",()->{});
    }
    private static void describePackage(List<String> lines,String side,CircuitEventPack pack) {
        lines.add(side+"："+pack.title()+" · "+pack.author());
        lines.add("编号 "+pack.id());
        lines.add("摘要 "+pack.digest());
    }
    private static void installed(List<String> lines,FormulaGame app,String label,CircuitEventPack.Entry entry) {
        var sources=entry.original().circuits();
        var locals=entry.localCircuits();
        for(int i=0;i<locals.size();i++) {var source=sources.get(i);var local=locals.get(i);var saved=app.circuits.find(local.id()).orElse(null);lines.add(label+" 已存本地地图 #"+(i+1)+"："+(saved==null?local.name():saved.document().name()));if(saved==null) {lines.add("本地草稿缺失。");continue;}
            var changes=CircuitRevisionDiff.compare(source,saved.document()).changes().stream().filter(change->change.kind()!=CircuitRevisionDiff.Kind.IDENTITY).toList();if(changes.isEmpty()) lines.add("本地内容与原包一致（本地身份单独保留）。");else changes.forEach(change->lines.add("本地编辑 · "+change.description()));lines.add(saved.proof()!=null && saved.proof().matches(saved.document(),app.vehicleSpec.physicsVersion)?"本机四车资格当前匹配。":"本机四车资格待验证。");
        }
    }
}
