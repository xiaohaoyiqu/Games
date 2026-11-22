package com.f1car.ui;

import com.f1car.model.CircuitDocument;
import com.f1car.model.CircuitRevisionDiff;
import com.f1car.model.CircuitTrial;
import com.f1car.persistence.CircuitStore;
import com.f1car.persistence.CircuitStore.PracticeSlot;
import com.f1car.persistence.CircuitStore.Saved;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class CircuitConflictReview {
    private CircuitConflictReview() {}

    static List<String> lines(CircuitStore.DraftComparison comparison,CircuitDocument draft,
                              CircuitTrial.Proof draftProof,PracticeSlot preview) {
        var lines=new ArrayList<String>();
        var base=comparison.accepted();
        var disk=comparison.disk();
        lines.add("只读对照 · 差异的左值来自已读基准，右值来自行首所示版本。");
        lines.add(base==null?"已读基准中没有这张地图。":"已读基准 · "+base.document().name());
        lines.add("编辑中的草稿 · "+draft.name());
        if(disk!=null) lines.add("打开对照时的磁盘版本 · "+disk.document().name());
        else lines.add(comparison.libraryMissing()?"磁盘资料库文件缺失。":"磁盘资料库中没有这张地图。");
        if(preview!=null) lines.add("当前区间预览（未保存） · 第 "+preview.number()+" 槽 · "+slotValue(preview,draft));

        int start=lines.size();
        if(base==null) appendNewDraft(lines,"草稿",draft,draftProof,null);
        else {
            appendDraftChanges(lines,"草稿",base,draft,draftProof);
            for(var slot:base.slots()) {
                if(slot.preset().matches(base.document())!=slot.preset().matches(draft))
                    appendSlotChange(lines,"草稿",slot.number(),slot,base.document(),slot,draft);
            }
        }
        if(lines.size()==start) lines.add("草稿｜与已读基准一致。");

        if(disk!=null) {
            start=lines.size();
            if(base==null) appendNewDraft(lines,"磁盘",disk.document(),disk.proof(),disk);
            else {
                appendDraftChanges(lines,"磁盘",base,disk.document(),disk.proof());
                for(int number=1;number<=CircuitStore.PRACTICE_SLOT_LIMIT;number++)
                    appendSlotChange(lines,"磁盘",number,base.practiceSlot(number),base.document(),disk.practiceSlot(number),disk.document());
                appendRecordChange(lines,"地图修订",base.history(),disk.history());
                appendRecordChange(lines,"试驾记录",base.reviews(),disk.reviews());
                if(base.reviewCapacity()!=disk.reviewCapacity())
                    lines.add("磁盘｜试驾记录上限 "+base.reviewCapacity()+" → "+disk.reviewCapacity());
            }
            if(lines.size()==start) lines.add("磁盘｜这张地图与已读基准一致。");
        }
        lines.add("返回原页面可重读资料库或另存草稿。再次打开对照可查看最新磁盘内容。");
        return List.copyOf(lines);
    }

    private static void appendDraftChanges(List<String> lines,String side,Saved base,CircuitDocument document,
                                           CircuitTrial.Proof proof) {
        for(var change:CircuitRevisionDiff.compare(base.document(),document).changes())
            lines.add(side+"｜"+change.description());
        if(!Objects.equals(base.proof(),proof))
            lines.add(side+"｜四车验证记录变化 · "+proofValue(base.proof())+" → "+proofValue(proof));
    }

    private static void appendNewDraft(List<String> lines,String side,CircuitDocument document,
                                       CircuitTrial.Proof proof,Saved saved) {
        lines.add(side+"｜新增地图 · "+document.anchors().size()+" 个路线点 · 路宽 "+document.width()+" m · "+document.scene().items().size()+" 个物件");
        lines.add(side+"｜四车验证记录 · "+proofValue(proof));
        if(saved!=null) for(var slot:saved.slots())
            lines.add(side+"｜练习预设第 "+slot.number()+" 槽 · "+slotValue(slot,document));
    }

    private static void appendSlotChange(List<String> lines,String side,int number,PracticeSlot before,
                                         CircuitDocument base,PracticeSlot after,CircuitDocument document) {
        String previous=slotValue(before,base),current=slotValue(after,document);
        if(Objects.equals(before,after) && previous.equals(current)) return;
        String binding=before!=null && after!=null && !before.preset().driveKey().equals(after.preset().driveKey())
                ?"（路线绑定变化）":"";
        lines.add(side+"｜练习预设第 "+number+" 槽"+binding+" · "+previous+" → "+current);
    }

    private static String slotValue(PracticeSlot slot,CircuitDocument document) {
        if(slot==null) return "空槽";
        var preset=slot.preset();
        return slot.name()+" · "+(preset.matches(document)?"路线匹配":"旧路线")+" · "
                +CircuitSectionHud.presetRangeLabel(preset)+" · "+CircuitSectionHud.startLabel(preset.start());
    }

    private static String proofValue(CircuitTrial.Proof proof) {
        return proof==null?"无记录":"物理版本 "+proof.physicsVersion()+" · 四圈用时 "+proof.laps()+" s";
    }

    private static void appendRecordChange(List<String> lines,String label,List<?> before,List<?> after) {
        if(!before.equals(after)) lines.add("磁盘｜"+label+"内容变化 · "+before.size()+" 份 → "+after.size()+" 份");
    }
}
