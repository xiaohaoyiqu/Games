package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.*;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Both entry points edit one recoverable working copy; review callbacks reject stale drafts. */
final class DiscussionRevisionsHud {
    private final RevisionDiscussionHud originalMessages=new RevisionDiscussionHud();
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open,library,fromComparison;private int page;
    private String selected="";private DiscussionRevisionDraft shown;
    private PartsDiscussionRevision pending;
    boolean showing() {return open;}
    void open(HudRenderer h,FormulaGame app,PartsDiscussion a,PartsDiscussion b,boolean localChild) {
        if(a==null || b==null) {open=true;library=true;fromComparison=false;page=0;sync(app);return;}
        var old=app.revisionEditing.current();
        if(old!=null && old.parent().equals(a) && old.child().equals(b) && old.localParent() && old.localChild()==localChild) {
            open=true;library=false;fromComparison=true;sync(app);return;
        }
        Runnable begin=()->{
            if(!Objects.equals(old,app.revisionEditing.current())) {app.notifyUser("未提交修订草稿已变化，请重新选择。");return;}
            app.revisionEditing.capture(DiscussionRevisionDraft.begin(a,b,true,localChild,app.store.profile.playerName));
            open=true;library=false;fromComparison=true;page=0;sync(app);
        };
        if(old==null) begin.run();
        else h.confirm("替换未提交的修订草稿？","会替换此前父子选择、原留言和说明；已保存声明保持。可取消后从声明库继续原草稿。",begin);
    }
    void openLibrary(FormulaGame app,String selected) {this.selected=selected;open=true;library=true;fromComparison=false;page=0;sync(app);}
    void dismiss() {open=false;shown=null;pending=null;originalMessages.dismiss();}
    private void close(FormulaGame app) {app.revisionEditing.flush();dismiss();}
    private void sync(FormulaGame app) {var value=app.revisionEditing.current();if(shown!=value) {shown=value;pending=value==null?null:value.declaration();}}
    private boolean edit(FormulaGame app,DiscussionRevisionDraft expected,DiscussionRevisionDraft next) {
        if(!Objects.equals(expected,app.revisionEditing.current())) {app.notifyUser("修订草稿已变化，请重新查看后操作。");return false;}
        app.revisionEditing.capture(next);sync(app);return true;
    }
    void render(HudRenderer h,FormulaGame app,BiConsumer<FormulaGame.RevisionDiscussionReview,Boolean> navigate) {
        if(originalMessages.showing()) {originalMessages.render(h,app,navigate);return;}
        sync(app);h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("方案修订声明",60,99,30,INK,true);
        h.text("明确选择父方案与子方案，关联两条原留言；署名和修订关系均为提供者声明。",60,142,16,SOFT);
        h.smallButton(1030,57,184,38,fromComparison?"返回背景对照":"返回话题库",()->close(app));
        if(library || shown==null) {library=true;library(h,app);return;}
        var draft=shown;
        h.smallButton(60,190,550,37,"查看已保存声明",()->library=true);
        h.smallButton(630,190,584,37,"交换父子方向",()->edit(app,draft,draft.swap()));
        h.text("父方案 A："+cut(draft.parent().plan().plan().name(),42),60,279,20,MINT,true);
        h.text("子方案 B："+cut(draft.child().plan().plan().name(),42),60,319,20,MINT,true);
        h.wrapped("恢复的是未提交草稿；两侧冻结话题保持，创建声明仍需核对本地原文并明确确认。",60,352,1154,16,SOFT,25);
        message(h,app,draft,true);message(h,app,draft,false);
        h.smallButton(60,556,1154,36,"声明者："+cut(draft.author(),55),()->h.prompt("声明者署名",draft.author(),40,text->edit(app,draft,draft.text(text.strip(),draft.notes()))));
        h.smallButton(60,608,1154,36,"修订说明："+(draft.notes().isBlank()?"点击填写这次修改与原留言的关系":cut(draft.notes(),54)),()->h.prompt("修订说明（单行，最多 320 字符）",draft.notes(),320,text->edit(app,draft,draft.text(draft.author(),text.strip()))));
        recovery(h,app,draft);
        h.smallButton(60,705,1154,38,pending==null?"预览 B 修订自 A 的完整声明":"重新预览同一份待保存声明",()->prepare(h,app,draft));
        h.text("同名、时间、相同卡身份都不证明先后；保存关系也不认证作者、收益或装备可安装。",60,764,13,AMBER);
    }
    private void message(HudRenderer h,FormulaGame app,DiscussionRevisionDraft draft,boolean before) {
        var topic=before?draft.parent():draft.child();String id=before?draft.parentMessage():draft.childMessage();var value=topic.message(id);int index=topic.messages().indexOf(value),y=424+(before?0:64);
        h.smallButton(60,y-22,1044,36,(before?"父侧":"子侧")+"原留言 "+(index+1)+" / "+topic.messages().size()+"："+cut(value.text().replace('\n',' '),48),()->h.review("所选原留言",List.of(value.author(),value.scope(),value.text()),"返回声明",()->{}));
        h.smallButton(1120,y-22,94,36,"下一条",()->edit(app,draft,draft.message(before,topic.messages().get((index+1)%topic.messages().size()).id())));
    }
    private void prepare(HudRenderer h,FormulaGame app,DiscussionRevisionDraft draft) {
        try {
            var prepared=draft.prepare();if(!edit(app,draft,prepared)) return;var value=prepared.declaration();
            var review=app.discussionRevisions.review(value,PartsDiscussionRevisionStore.Origin.LOCAL);var lines=new ArrayList<>(details(value,app));lines.addAll(review.issues());
            if(!review.valid()) {h.review("声明不能收录",lines,"返回修改",()->{});return;}
            h.review("确认 B 修订自 A？",lines,"只保存独立修订关系",()->{
                if(!Objects.equals(prepared,app.revisionEditing.current())) {app.notifyUser("修订草稿已变化，请重新预览；旧确认未保存。");return;}
                if(app.acceptDiscussionRevision(review,prepared.localParent(),prepared.localChild())) {
                    selected=value.id();edit(app,prepared,null);app.revisionEditing.flush();library=true;
                }
            });
        }catch(IllegalArgumentException error) {app.notifyUser("请选择不同话题与不同方案，填写署名和修订说明，并核对两侧原留言。");}
    }
    private void recovery(HudRenderer h,FormulaGame app,DiscussionRevisionDraft draft) {
        h.smallButton(60,653,550,33,app.revisionEditing.status(),()->DraftRecoveryHud.review(h,app,app.revisionEditing));
        h.smallButton(630,653,584,33,"明确放弃未提交草稿",()->{
            if(draft==null) {app.notifyUser("当前没有未提交的修订草稿。");return;}
            h.confirm("放弃这份未提交修订草稿？","仅清空恢复草稿；已保存声明、父子话题、方案和报告保持。",()->{if(edit(app,draft,null)) {app.revisionEditing.flush();library=true;}});
        });
    }
    private void library(HudRenderer h,FormulaGame app) {
        h.smallButton(60,190,350,37,"读取独立修订声明",()->receive(h,app));
        h.smallButton(430,190,350,37,"修复后重新读取声明库",app.discussionRevisions::reload);
        h.smallButton(800,190,414,37,"继续未提交的父子草稿",()->{sync(app);if(shown!=null) library=false;else app.notifyUser("先在背景对照页选择 A 与 B。");});
        var values=app.discussionRevisions.entries();int pages=Math.max(1,(values.size()+3)/4);page=Math.min(page,pages-1);
        if(values.stream().noneMatch(row->row.revision().id().equals(selected))) selected=values.isEmpty()?"":values.get(0).revision().id();
        for(int i=page*4;i<Math.min(values.size(),page*4+4);i++) {var row=values.get(i);var value=row.revision();int y=278+(i-page*4)*70;
            h.smallButton(60,y,1154,37,(selected.equals(value.id())?"● ":"")+cut(value.child().plan().plan().name(),24)+" 修订自 "+cut(value.parent().plan().plan().name(),24),()->selected=value.id());h.text(row.origin().title+" · "+cut(value.author(),26),72,y+59,14,SOFT);}
        if(values.isEmpty()) h.wrapped("尚未保存修订声明。可以接收独立文件、继续未提交草稿，或返回背景对照选择方案。",60,315,1100,20,SOFT,30);
        var chosen=app.discussionRevisions.find(selected);
        h.smallButton(60,551,550,30,"父侧原留言 · 查看与回应",()->{if(chosen!=null) originalMessages.open(app,chosen,RevisionDiscussionTarget.Side.PARENT);});
        h.smallButton(630,551,584,30,"子侧原留言 · 查看与回应",()->{if(chosen!=null) originalMessages.open(app,chosen,RevisionDiscussionTarget.Side.CHILD);});
        h.smallButton(60,592,350,37,"查看完整关系与原文",()->{if(chosen!=null) h.review("完整修订声明",details(chosen.revision(),app),"返回声明库",()->{});});
        h.smallButton(430,592,350,37,"导出独立修订声明",()->{if(chosen!=null) export(h,app,chosen);});
        h.smallButton(800,592,414,37,"移除本地声明副本",()->{if(chosen!=null) h.confirm("移除这份修订声明？","两侧话题、方案、报告、留言和外部文件保留。",()->app.removeDiscussionRevision(chosen));});
        if(!app.discussionRevisions.warning().isEmpty()) h.text(cut(app.discussionRevisions.warning(),78),60,648,13,AMBER);
        recovery(h,app,shown);
        h.smallButton(60,705,350,38,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,580,729,17,SOFT);h.smallButton(800,705,414,38,"下一页",()->page=Math.min(pages-1,page+1));
        h.text("收到声明只进入独立关系库；恢复草稿不会发布声明或采用方案。",60,764,13,AMBER);
    }
    private void receive(HudRenderer h,FormulaGame app) {
        h.prompt("独立修订声明 JSON 完整路径","",512,text->{try {
            var value=PartsDiscussionRevisionCodec.read(Path.of(text.strip()));var review=app.discussionRevisions.review(value,PartsDiscussionRevisionStore.Origin.RECEIVED);
            var lines=new ArrayList<>(details(value,app));lines.addAll(review.issues());
            h.review(review.valid()?"收录提供者的修订声明？":"声明存在冲突",lines,review.valid()?"只收录独立声明":"保留已有声明",()->{if(review.valid() && app.acceptDiscussionRevision(review,false,false)) selected=value.id();});
        }catch(java.io.IOException | RuntimeException error) {app.notifyUser("修订声明无法读取，请检查类型、字段、大小和内容校验。");}});
    }
    private void export(HudRenderer h,FormulaGame app,PartsDiscussionRevisionStore.Entry value) {
        var suggested=app.store.directory().toAbsolutePath().getParent().resolve("parts-revision-export.json");
        h.review("导出完整修订声明？",details(value.revision(),app),"填写独立导出路径",()->h.promptExportPath(
            "玩家资料目录外的修订文件路径",suggested,(path,replace)->app.exportDiscussionRevision(value,path,replace)));
    }
    static List<String> details(PartsDiscussionRevision value,FormulaGame app) {
        var lines=new ArrayList<String>();lines.add("子方案 "+value.child().plan().plan().name()+"；父方案 "+value.parent().plan().plan().name());
        lines.add("声明者："+value.author()+"；修订说明："+value.notes());
        lines.add("父侧原留言："+value.parent().message(value.parentMessage()).text());lines.add("子侧原留言："+value.child().message(value.childMessage()).text());
        lines.add("父侧对应："+PartsDiscussionRevision.status(value.parent(),app.partsDiscussions.find(value.parent().id())).title);
        lines.add("子侧对应："+PartsDiscussionRevision.status(value.child(),app.partsDiscussions.find(value.child().id())).title);
        lines.addAll(new PartsDiscussionComparison(value.parent(),value.child()).summary());
        lines.add("父侧完整冻结原文");lines.addAll(PartsDiscussionHud.details(value.parent(),app.vehicleSpec.physicsVersion));
        lines.add("子侧完整冻结原文");lines.addAll(PartsDiscussionHud.details(value.child(),app.vehicleSpec.physicsVersion));
        lines.add("修订只是明确声明，不证明真实先后、作者身份、装备兼容或收益。留言和测量分别保留，不自动迁移或合并。");return List.copyOf(lines);
    }
    private static String cut(String text,int max) {return text.codePointCount(0,text.length())<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
}
