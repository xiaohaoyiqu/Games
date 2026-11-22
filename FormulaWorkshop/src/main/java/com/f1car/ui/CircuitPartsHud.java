package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.util.*;

/** Explicit, complete package-part revision and recommendation reviews, built only on a click. */
final class CircuitPartsHud {
    void install(HudRenderer h,FormulaGame app,CircuitEventPack.Entry entry) {
        var bundle=entry.original().partsBundle();if(bundle==null) return;var current=app.partsStore.catalog();
        try {var proposed=app.partsStore.previewPacks(bundle.packs());var lines=new ArrayList<String>();lines.add("收录包内部件会按命名空间替换本地同名包；先查看新增、移除及功能变化。地图、车库、草稿、命名卡和历史不自动改写。");
            for(var pack:bundle.packs()) {var before=current.packs().stream().filter(old->old.id().equals(pack.id())).findFirst().orElse(null);var revision=PartsRevision.compare(before,pack);lines.add(pack.name()+"；作者 "+pack.author()+"；修订 "+revision.fromRevision()+" → "+revision.toRevision());if(revision.changes().isEmpty()) lines.add("与当前同名包一致。");for(var change:revision.changes()) lines.add(change.description());}
            if(bundle.packs().isEmpty()) lines.add("仅用原厂部件，无需收录玩家包。");
            var suggestion=entry.activity().partsRule().review(bundle.suggestion().build(),proposed,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,0);lines.addAll(suggestion.issues());
            var bench=app.partsWorkbench();if(bench!=null) {impact(lines,"当前工作台草稿",bench.build().review(proposed,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,0));if(bench.reference()!=null) impact(lines,"固定对照 A",bench.reference().build().review(proposed,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,0));}
            for(var plan:app.partsPlans.entries()) impact(lines,"命名方案："+plan.name(),plan.build().review(proposed,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,0));
            for(var reference:app.circuitActivityReferences()) if(reference.card().partsRule()!=null) {String reason=reference.card().partsRule().incompatibility(proposed,app.vehicleSpec.id,app.vehicleSpec.physicsVersion);lines.add(reference.source()+"／"+reference.card().name()+"："+(reason.isEmpty()?"部件规则仍匹配。":reason));}
            h.review("包内部件 · 修订与影响",lines,suggestion.valid()?"收录这些部件包":"关闭并检查条件",()->{if(suggestion.valid()) app.installCircuitPackParts(entry,current);});
        }catch(IllegalArgumentException error) {h.review("包内部件暂不能收录",List.of("部件包命名空间、容量或当前内置部件不适用。","最多八个玩家包；可先在工作台导出现有内容并整理目录。","原包、地图和本地目录保留。"),"关闭说明",()->{});}
    }
    void suggestion(HudRenderer h,FormulaGame app,CircuitEventPack.Entry entry,boolean save) {
        var source=entry.original().partsBundle();if(source==null) return;var catalog=app.partsStore.catalog();var plan=source.suggestion();var review=entry.activity().partsRule().review(plan.build(),catalog,app.vehicleSpec.id,app.vehicleSpec.physicsVersion,app.store.profile.tuning.livery());var lines=new ArrayList<String>();lines.add("来自赛事包“"+entry.original().title()+"”；作者："+entry.original().author());lines.add("推荐方案："+plan.name()+"；说明："+plan.notes());lines.add(Tuning.performanceDescription(review.candidate().performanceKey()));lines.add("点数 "+review.points()+" / "+entry.activity().partsRule().budget()+"；这是作者建议，效果与难度仍需试车。");lines.addAll(review.issues());lines.add(save?"另存会创建独立命名方案身份，保留已有方案；不改变草稿与车库。":"读取会替换工作台草稿，可撤销；不应用车库，不覆盖命名方案。");
        h.review(save?"另存包内推荐方案？":"读取包内推荐方案？",lines,review.valid()?save?"另存为独立命名方案":"读为工作台草稿":"关闭并处理部件",()->{if(review.valid()) {if(save) app.saveCircuitPackSuggestion(entry,catalog);else app.loadCircuitPackSuggestion(entry,catalog);}});
    }
    private static void impact(List<String> lines,String name,PartsBuild.Review review) {lines.add(name+"："+(review.valid()?"仍通过目录检查。":"有 "+review.issues().size()+" 项待处理，原方案保留。"));if(!review.valid()) lines.addAll(review.issues());}
}
