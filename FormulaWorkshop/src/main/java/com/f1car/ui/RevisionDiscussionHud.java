package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PartsDiscussionRevisionStore;
import java.awt.Color;
import java.util.*;
import java.util.function.BiConsumer;

/** Frozen original text stays visible even when the matching local discussion is absent or conflicting. */
final class RevisionDiscussionHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open;private PartsDiscussionRevisionStore.Entry entry;private RevisionDiscussionTarget target;
    private FormulaGame.RevisionDiscussionReview reviewed;
    private List<String> originalDetails=List.of();
    boolean showing() {return open;}
    void open(FormulaGame app,PartsDiscussionRevisionStore.Entry entry,RevisionDiscussionTarget.Side side) {this.entry=Objects.requireNonNull(entry);open=true;select(app,side);}
    void dismiss() {open=false;entry=null;target=null;reviewed=null;originalDetails=List.of();}
    private void select(FormulaGame app,RevisionDiscussionTarget.Side side) {
        target=new RevisionDiscussionTarget(entry.revision(),side);originalDetails=PartsDiscussionHud.details(target.frozen(),app.vehicleSpec.physicsVersion);refresh(app);
    }
    private void refresh(FormulaGame app) {reviewed=app.reviewRevisionDiscussion(entry,target.side());}
    void render(HudRenderer h,FormulaGame app,BiConsumer<FormulaGame.RevisionDiscussionReview,Boolean> navigate) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("修订关联的原留言",60,99,30,INK,true);
        h.text("父子双方的原话题分别保留；选择在哪条原留言下查看或回应。",60,142,16,SOFT);
        h.smallButton(1030,57,184,38,"返回声明库",this::dismiss);
        h.smallButton(60,190,350,37,"查看父侧原留言",()->select(app,RevisionDiscussionTarget.Side.PARENT));
        h.smallButton(430,190,350,37,"查看子侧原留言",()->select(app,RevisionDiscussionTarget.Side.CHILD));
        h.smallButton(800,190,414,37,"明确刷新本地对应",()->refresh(app));
        var message=target.message();
        h.text(target.side().title+"方案："+cut(target.frozen().plan().plan().name(),45),60,278,21,MINT,true);
        h.text("原话题："+cut(target.frozen().title(),48),60,319,18,INK);
        h.text("原留言署名："+cut(message.author(),36)+" · "+message.kind().title+" · "+message.scope(),60,358,17,SOFT);
        h.wrapped(cut(message.text(),180),60,397,1154,19,INK,30);
        String status=reviewed==null?"声明已移除或变化；冻结背景仍可读":reviewed.reviewed().status().title;
        boolean changed=reviewed!=null && (reviewed.entry()!=app.discussionRevisions.find(entry.revision().id()) || reviewed.reviewed().local()!=app.partsDiscussions.find(target.frozen().id()));
        h.text(changed?"本地对象已变化，请明确刷新后操作":status,60,529,17,AMBER);
        h.smallButton(60,551,550,34,"查看完整冻结话题与方案",()->h.review("完整原文与冻结背景",originalDetails,"返回原留言",()->{}));
        h.smallButton(630,551,584,34,"预览并分别收录这侧原话题",()->receive(h,app));
        h.smallButton(60,608,1154,35,"查看这条原留言的完整文字与身份",()->h.review("所选原留言",List.of(target.side().title,message.author(),message.scope(),message.id(),message.text()),"返回原留言",()->{}));
        h.smallButton(60,705,550,38,"打开本地对应原留言",()->route(h,app,navigate,false));
        h.smallButton(630,705,584,38,"回应这条原留言",()->route(h,app,navigate,true));
        h.text("缺失或冲突时不跳到同名话题；回应写在所选原话题，不复制到另一修订。",60,764,13,AMBER);
    }
    private void route(HudRenderer h,FormulaGame app,BiConsumer<FormulaGame.RevisionDiscussionReview,Boolean> navigate,boolean reply) {
        var request=reviewed;if(request==null || app.resolveRevisionDiscussion(request)==null) {app.notifyUser("请先核对、分别收录或明确刷新本地原话题；冻结原文保持。");return;}
        if(reply && request.reviewed().local().messages().size()>=PartsDiscussion.CAPACITY) {app.notifyUser("原话题已有三十二条留言；可查看或导出，不能继续追加。");return;}
        var chosen=request.reviewed().target();var lines=new ArrayList<String>();lines.add(chosen.side().title+"方案："+chosen.frozen().plan().plan().name());
        lines.add("原话题："+chosen.frozen().title()+"；原留言署名："+chosen.message().author());lines.add(chosen.message().text());lines.add(request.reviewed().status().title);
        lines.add(reply?"仅打开这条原留言的回复草稿；仍需填写、预览并保存。已有草稿替换另行确认。":"打开本地对应原留言，不改变已有回复草稿或已读标记。");
        h.review(reply?"在这条原留言下回应？":"打开这条本地原留言？",lines,reply?"进入原话题回复草稿":"打开原留言",()->navigate.accept(request,reply));
    }
    private void receive(HudRenderer h,FormulaGame app) {
        var incoming=target.frozen();var preview=app.partsDiscussions.preview(incoming);var lines=new ArrayList<>(originalDetails);lines.addAll(preview.issues());
        lines.add("只按原讨论规则收录／追加合并；完成后仍需明确刷新本地对应，不自动跳转或回应。");
        h.review(preview.valid()?"分别收录这侧原话题？":"原话题冲突，未收录",lines,preview.valid()?"只收录原话题":"保留原话题",()->{if(preview.valid()) app.receivePartsDiscussion(preview);});
    }
    private static String cut(String text,int max) {return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
}
