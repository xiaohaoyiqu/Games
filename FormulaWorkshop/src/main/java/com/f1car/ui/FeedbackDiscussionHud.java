package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.CircuitFeedbackDiscussionCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;

/** Local author/player conversations with independent drafts, immutable originals and explicit merge. */
final class FeedbackDiscussionHud {
    private static final int MESSAGES_PER_PAGE=5;
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private boolean open,loaded;private int mode,page,messagePage;private String selected="",selectedMessage="";
    private CircuitFeedback source;private CircuitFeedbackDiscussionDraft draft;private CircuitFeedbackDiscussion pending;
    boolean showing() {return open;}
    boolean close() {if(!open) return false;open=false;return true;}
    void open(CircuitFeedback source) {this.source=source;open=true;mode=0;}
    void showMessage(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        source=thread.feedback();
        selected=thread.id();
        selectedMessage=message.id();
        messagePage=thread.messages().indexOf(message)/MESSAGES_PER_PAGE;
        open=true;
        mode=1;
    }
    void editReply(FormulaGame app,CircuitFeedbackDiscussionDraft next) {
        restore(app);
        showMessage(next.before(),next.before().message(next.parent()));
        change(next);
        mode=2;
        captureDraft(app);
        if(!app.feedbackDiscussionEditing.flush()) app.notifyUser(app.feedbackDiscussionEditing.warning());
    }
    private void restore(FormulaGame app) {if(loaded) return;loaded=true;draft=app.feedbackDiscussionEditing.current();}
    void captureDraft(FormulaGame app) {if(loaded) app.feedbackDiscussionEditing.capture(draft);}
    private void change(CircuitFeedbackDiscussionDraft value) {draft=value;pending=null;}
    private void clear(FormulaGame app) {draft=null;pending=null;app.feedbackDiscussionEditing.capture(null);if(!app.feedbackDiscussionEditing.flush()) app.notifyUser("留言原文已保持，恢复草稿尚未清空；可重试草稿保存。");}
    void render(HudRenderer h,FormulaGame app) {
        restore(app);h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text(mode==2?"编写反馈讨论留言":mode==1?"地图反馈 · 多轮回应":"反馈讨论库",60,105,30,INK,true);h.text("固定原反馈与回复关系；署名、观察和处理答复均为提供者声明。",60,143,16,SOFT);
        if(mode==1) h.text("回应不自动修改原反馈状态、采用地图或推断版本先后。",60,164,13,GOLD);
        h.smallButton(1030,57,180,38,"返回反馈库",()->{captureDraft(app);if(!app.feedbackDiscussionEditing.flush()) app.notifyUser(app.feedbackDiscussionEditing.warning());close();});
        if(mode==2) compose(h,app);else if(mode==1) thread(h,app);else library(h,app);
        h.smallButton(60,723,mode==2?1154:500,26,"讨论草稿 · "+app.feedbackDiscussionEditing.status(),()->{captureDraft(app);DraftRecoveryHud.review(h,app,app.feedbackDiscussionEditing);});
        if(mode!=2) h.smallButton(600,723,614,26,app.discussionReading.status(),()->DiscussionReadingHud.recovery(h,app));
        if(!app.feedbackDiscussions.warning().isEmpty()) h.text(cut(app.feedbackDiscussions.warning(),76),60,710,13,GOLD);
    }
    private void library(HudRenderer h,FormulaGame app) {
        h.smallButton(60,174,244,36,"围绕所选反馈新建",()->{if(source==null) {app.notifyUser("先返回反馈库选择一份原文，或打开收到的话题。");return;}begin(h,app,source,null,null);});h.smallButton(316,174,244,36,"接收并预览合并",()->receive(h,app));h.smallButton(600,174,294,36,"重新读取讨论库",app.feedbackDiscussions::reload);h.smallButton(914,174,300,36,"继续未提交草稿",()->{if(draft==null) app.notifyUser("当前没有未提交的讨论草稿。");else mode=2;});
        var entries=app.feedbackDiscussions.entries();if(entries.stream().noneMatch(value->value.id().equals(selected))) selected=entries.isEmpty()?"":entries.get(0).id();int pages=Math.max(1,(entries.size()+4)/5);page=Math.max(0,Math.min(page,pages-1));
        for(int i=page*5;i<Math.min(entries.size(),page*5+5);i++) {var value=entries.get(i);int y=244+(i-page*5)*68;h.smallButton(60,y,500,38,(value.id().equals(selected)?"● ":"")+cut(value.title(),28),()->selected=value.id());h.text(value.messages().size()+" 条 · "+app.discussionReading.badge(value)+" · "+cut(value.feedback().title(),12),72,y+59,13,SOFT);}
        pager(h,pages,false);var value=app.feedbackDiscussions.find(selected);
        if(value==null) h.wrapped("围绕同一份玩家反馈发起讨论。留言可附作者答复，或记录所指意见的试驾成绩和条件。",600,293,590,20,SOFT,32);
        else {h.text(cut(value.title(),26),600,279,23,INK,true);h.text("源图："+cut(value.feedback().source().name(),28),600,322,17,SOFT);h.text("物理："+value.feedback().physics(),600,357,14,GOLD);h.smallButton(600,382,614,38,"打开留言与回复",()->{mode=1;messagePage=0;selectedMessage=value.root().id();});h.smallButton(600,434,614,38,"查看冻结原反馈",()->h.review("原反馈 · 独立背景",sourceDetails(value.feedback()),"关闭",()->{}));h.smallButton(600,488,294,36,"导出完整话题",()->export(h,app,value));h.smallButton(914,488,300,36,"移除本地话题",()->h.confirm("移除此讨论副本？","未导出的本地留言只在此副本中。\n原反馈、答复库、地图、资格与外部文件保持。",()->app.removeFeedbackDiscussion(value)));h.smallButton(600,544,614,36,"另起同原反馈的话题",()->begin(h,app,value.feedback(),null,null));}
    }
    private void thread(HudRenderer h,FormulaGame app) {
        var value=app.feedbackDiscussions.find(selected);if(value==null) {mode=0;return;}if(value.message(selectedMessage)==null) selectedMessage=value.root().id();var chosen=value.message(selectedMessage);
        DiscussionReadingHud.controls(h,app,value,chosen.id(),650);
        h.smallButton(60,174,500,36,"返回讨论库",()->mode=0);h.smallButton(600,174,294,36,"回应所选留言",()->begin(h,app,value.feedback(),value,chosen.id()));h.smallButton(914,174,300,36,"回应最初问题",()->begin(h,app,value.feedback(),value,value.root().id()));
        int pages=Math.max(1,(value.messages().size()+MESSAGES_PER_PAGE-1)/MESSAGES_PER_PAGE);
        messagePage=Math.max(0,Math.min(messagePage,pages-1));
        int first=messagePage*MESSAGES_PER_PAGE;
        for(int i=first;i<Math.min(value.messages().size(),first+MESSAGES_PER_PAGE);i++) {var message=value.messages().get(i);int y=244+(i-first)*68;h.smallButton(60,y,500,38,(message.id().equals(selectedMessage)?"● ":"")+(app.discussionReading.unread(value)<0?"[待确认] ":app.discussionReading.read(value,message.id())?"[已读] ":"[未读] ")+"层 "+value.depth(message)+" · "+cut(message.author(),10)+" · "+message.kind().title,()->selectedMessage=message.id());h.text(cut(message.text().replace('\n',' '),32),72,y+59,13,SOFT);}pager(h,pages,true);
        h.text(cut(chosen.author(),24)+" · "+chosen.kind().title,600,279,22,INK,true);h.text(chosen.note()==null?"针对整份原反馈":"针对原意见："+cut(note(value.feedback(),chosen.note()).text(),28),600,322,16,MINT);h.wrapped(cut(chosen.text(),130),600,368,590,18,SOFT,28);
        h.smallButton(600,508,614,36,chosen.trial()==null?"查看留言全文、父关系与答复快照":"查看留言全文与所附试驾",()->reviewMessage(h,app,value,chosen));h.smallButton(600,560,614,36,"查看冻结原反馈与地图版本",()->h.review("讨论原背景",sourceDetails(value.feedback()),"关闭",()->{}));
        h.smallButton(600,608,614,32,chosen.reply()==null?"单独收录原反馈":"分别收录此作者答复及其原反馈",()->{var lines=new ArrayList<>(chosen.reply()==null?sourceDetails(value.feedback()):replyDetails(chosen.reply()));lines.add("原反馈若不存在，将作为未对应原文收录；本地处理、地图和资格保持。");h.review("分别收录所附内容",lines,"明确收录",()->{if(chosen.reply()==null) app.receiveCircuitFeedback(value.feedback());else app.receiveCircuitFeedbackReply(chosen.reply());});});
    }
    private void reviewMessage(HudRenderer h,FormulaGame app,CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        var lines=messageDetails(thread,message);
        var trial=message.trial();
        if(trial==null) {
            h.reviewFeedbackDiscussionMessage(thread,message,lines,"关闭",null);
            return;
        }
        var local=app.circuitFeedback.trial(trial.feedbackId(),trial.noteId());
        var feedback=thread.feedback();
        boolean sameLocalTrial=matchesLocalFeedback(app,feedback) && trial.equals(local);
        if(!sameLocalTrial || app.savedCircuitFeedbackTrialSource(trial)==null) {
            lines.add(0,sameLocalTrial?"请打开与这次本机试驾对应的草稿后沿用设定。":"沿用设定需要本机保存一致的原反馈及同一次试驾。");
            h.reviewFeedbackDiscussionMessage(thread,message,lines,"关闭",null);
            return;
        }
        lines.add(0,"载入的本机试驾与附件一致。可沿用区间和发车条件预览，开始试驾时使用当前物理和控制配置。");
        var destination=new FormulaGame.FeedbackDiscussionReturn(thread,message);
        h.reviewFeedbackDiscussionMessage(thread,message,lines,"预览本机保存的试驾",()->{
            if(app.checkFeedbackDiscussionAttachment(destination)) {
                if(!matchesLocalFeedback(app,feedback)) app.notifyUser("本机原反馈已变化，请重新核对附件来源。");
                else if(h.previewDiscussionFeedbackTrial(destination)) return;
            }
            h.reviewFeedbackDiscussionMessage(thread,message,messageDetails(thread,message),"关闭",null);
        });
    }
    private static boolean matchesLocalFeedback(FormulaGame app,CircuitFeedback feedback) {
        var entry=app.circuitFeedback.find(feedback.id());
        return entry!=null && entry.feedback().equals(feedback);
    }
    private void begin(HudRenderer h,FormulaGame app,CircuitFeedback feedback,CircuitFeedbackDiscussion before,String parent) {
        if(before!=null && before.messages().size()>=CircuitFeedbackDiscussion.CAPACITY) {h.review("此话题已满",List.of("原话题已有三十二条留言，原文保持。","可先导出完整讨论，再围绕同一份原反馈另起话题；不自动截断旧留言。"),"返回查看",()->{});return;}
        Runnable action=()->{change(CircuitFeedbackDiscussionDraft.begin(feedback,before,parent,app.store.profile.playerName));mode=2;};
        if(draft!=null && (!draft.body().isBlank() || draft.trial()!=null)) h.confirm("改为另一份讨论草稿？","当前未提交内容将替换；已保存原文保持。\n也可先返回继续或保存已有草稿。",action);else action.run();
    }
    private void compose(HudRenderer h,FormulaGame app) {
        if(draft==null) {mode=0;return;}var current=draft;boolean published=current.published(app.feedbackDiscussions.find(current.threadId()));
        h.smallButton(60,174,500,36,"返回讨论库 · 保留草稿",()->mode=0);h.smallButton(600,174,614,36,published?"此留言已入库 · 重试清空恢复草稿":"查看冻结原反馈与回应背景",()->{if(published) clear(app);else {var lines=new ArrayList<>(sourceDetails(current.feedback()));if(current.before()!=null) lines.addAll(messageDetails(current.before(),current.before().message(current.parent())));h.review("留言背景",lines,"关闭",()->{});}});
        h.smallButton(60,226,500,34,current.before()==null?"标题："+cut(current.title(),24):"回应："+cut(current.before().message(current.parent()).author(),24),()->{if(current.before()==null) h.editText("反馈讨论标题",current.title(),"反馈讨论",80,text->change(current.text(text,current.author(),current.body())));});
        h.smallButton(600,226,614,34,"署名（声明）："+cut(current.author(),28),()->h.editText("留言署名",current.author(),"玩家",40,text->change(current.text(current.title(),text,current.body()))));
        h.smallButton(60,278,244,34,"类型："+current.kind().title,()->change(current.scope(CircuitFeedbackDiscussion.Kind.values()[(current.kind().ordinal()+1)%CircuitFeedbackDiscussion.Kind.values().length],current.note(),current.reply())));
        h.smallButton(316,278,244,34,current.note()==null?"针对整份原反馈":"针对意见 "+(current.feedback().notes().indexOf(note(current.feedback(),current.note()))+1),()->{var notes=current.feedback().notes();int index=current.note()==null?-1:notes.indexOf(note(current.feedback(),current.note()));change(current.scope(current.kind(),index+1<notes.size()?notes.get(index+1).id():null,current.reply()));});
        h.smallButton(600,278,614,34,current.reply()==null?"答复快照：未附加 · 点击选择":"答复快照："+cut(current.reply().author()+" · "+current.reply().target().name(),30),()->{var replies=app.circuitFeedback.replies().stream().filter(reply->reply.feedback().equals(current.feedback())).toList();if(replies.isEmpty()) {app.notifyUser("没有对应原文的作者答复；可先在反馈库收录。");return;}int index=current.reply()==null?-1:replies.indexOf(current.reply());change(current.scope(current.kind(),current.note(),index+1<replies.size()?replies.get(index+1):null));});
        h.smallButton(60,332,1154,38,"编辑留言 · "+current.body().length()+" / 640",()->h.editText("留言原文，可换行",current.body(),"",640,text->change(current.text(current.title(),current.author(),text))));h.wrapped(current.body().isBlank()?"写下问题、观察或回应。草稿自动保存，尚未追加任何留言。":cut(current.body(),180),72,421,1090,19,SOFT,35);
        h.smallButton(60,559,1154,38,current.trial()==null?"附加所指意见的已存试驾":"查看或移除所附试驾",
                ()->editTrialAttachment(h,app,current));
        h.smallButton(60,650,360,42,"预览并保存留言",()->publish(h,app));h.smallButton(434,650,350,42,"明确放弃草稿",()->h.confirm("放弃这份讨论草稿？","已保存话题保持；本次未提交文字将清空。",()->clear(app)));
        h.smallButton(798,650,416,42,current.before()==null?"查看所附答复":"查看并刷新回复背景",()->{if(current.before()==null) {if(current.reply()==null) app.notifyUser("当前没有作者答复快照。");else h.review("冻结作者答复",replyDetails(current.reply()),"关闭",()->{});}else {var latest=app.feedbackDiscussions.find(current.threadId());if(latest==null) {app.notifyUser("本地话题已移除；当前草稿仍保留。");return;}h.review("刷新回复背景",List.of("本地话题现有 "+latest.messages().size()+" 条留言。","只在原反馈、父留言原文和话题身份仍一致时刷新；保留本次文字及未发布身份。"),"明确刷新背景",()->{if(draft!=current || !latest.equals(app.feedbackDiscussions.find(latest.id()))) {app.notifyUser("草稿或话题已变化，请重新查看。");return;}try {change(current.refresh(latest));}catch(IllegalArgumentException error) {app.notifyUser("父留言或背景不同，不能刷新；本次草稿保持。");}});}});
    }
    private void editTrialAttachment(HudRenderer h,FormulaGame app,CircuitFeedbackDiscussionDraft current) {
        if(current.trial()!=null) {
            var lines=new ArrayList<>(trialDetails(current.trial()));
            lines.add("将留言范围改为其他意见时，也会清除此附件。");
            h.review("本条留言所附试驾",lines,"移除这份附件",()->{
                if(draft==current) change(current.trial(null));
            });
            return;
        }
        if(current.note()==null) {
            app.notifyUser("先选择这条留言所针对的意见。");
            return;
        }
        var entry=app.circuitFeedback.find(current.feedback().id());
        var trial=app.circuitFeedback.trial(current.feedback().id(),current.note());
        if(entry==null || !entry.feedback().equals(current.feedback()) || trial==null) {
            app.notifyUser("这条意见没有可附加的本地试驾。");
            return;
        }
        if(!app.circuitFeedback.currentTrial(trial)) {
            app.notifyUser("反馈库已变化，请重新读取后再选试驾。");
            return;
        }
        var lines=new ArrayList<>(trialDetails(trial));
        lines.add(0,"针对这条原意见\n"+note(current.feedback(),current.note()).text());
        h.review("核对要附加的作者试驾",lines,"附加这次试驾",()->{
            if(draft!=current || !app.circuitFeedback.currentTrial(trial)) {
                app.notifyUser("草稿或已存试驾已变化，请重新选择附件。");
                return;
            }
            change(current.trial(trial));
        });
    }
    private void publish(HudRenderer h,FormulaGame app) {
        if(draft==null) return;var current=draft;var local=app.feedbackDiscussions.find(current.threadId());if(current.published(local)) {selected=local.id();selectedMessage=current.messageId();clear(app);mode=1;app.notifyUser("同一留言已保存，未重复追加；恢复草稿已请求清空。");return;}
        try {if(pending==null) pending=current.build();var candidate=pending;var lines=new ArrayList<>(sourceDetails(current.feedback()));lines.addAll(messageDetails(candidate,current.message()));h.review("保存这条反馈留言？",lines,"追加这一条留言",()->{if(draft!=current) {app.notifyUser("草稿已变化，请重新预览。");return;}if(app.saveFeedbackDiscussion(candidate,current.before())) {selected=candidate.id();selectedMessage=current.messageId();messagePage=candidate.messages().indexOf(candidate.message(selectedMessage))/5;clear(app);mode=1;}else publish(h,app);});}
        catch(IllegalArgumentException error) {app.notifyUser("请填写非空标题、署名与正文，并检查回复关系、所附试驾及最多四份答复快照。");}
    }
    private void receive(HudRenderer h,FormulaGame app) {h.prompt("反馈讨论独立文件完整路径","",512,text->{try {receivePreview(h,app,CircuitFeedbackDiscussionCodec.read(Path.of(text.strip())));}catch(java.io.IOException | RuntimeException error) {app.notifyUser("讨论无法读取，请核对独立格式、原背景和校验。");}});}
    private void receivePreview(HudRenderer h,FormulaGame app,CircuitFeedbackDiscussion value) {var merge=app.feedbackDiscussions.preview(value);var lines=new ArrayList<>(sourceDetails(value.feedback()));lines.add("新增 "+merge.added()+" 条，相同 "+merge.shared()+" 条。署名、时间及答复不认证身份，不按时间推断版本先后。");for(var message:value.messages()) lines.addAll(messageDetails(value,message));lines.addAll(merge.issues());h.review("查看反馈讨论合并",lines,merge.valid()?"只追加一致的新留言":"保留本地原文",()->{if(merge.valid()) {if(app.receiveFeedbackDiscussion(merge)) {selected=value.id();selectedMessage=value.root().id();messagePage=0;mode=1;}else receivePreview(h,app,value);}});}
    private void export(HudRenderer h,FormulaGame app,CircuitFeedbackDiscussion value) {
        h.promptExportPath("反馈讨论导出文件完整路径",app.store.directory().resolve("circuit-export-feedback-discussion.json"),
            (path,replace)->app.exportFeedbackDiscussion(value,path,replace));
    }
    private void pager(HudRenderer h,int pages,boolean messages) {h.smallButton(60,650,180,32,"上一页",()->{if(messages) messagePage=Math.max(0,messagePage-1);else page=Math.max(0,page-1);});h.text(((messages?messagePage:page)+1)+" / "+pages,270,672,14,SOFT);h.smallButton(380,650,180,32,"下一页",()->{if(messages) messagePage=Math.min(pages-1,messagePage+1);else page=Math.min(pages-1,page+1);});}
    private static CircuitFeedback.Note note(CircuitFeedback feedback,String id) {return feedback.notes().stream().filter(note->note.id().equals(id)).findFirst().orElseThrow();}
    private static List<String> sourceDetails(CircuitFeedback value) {var lines=new ArrayList<String>();lines.add(value.title()+" · 原反馈 "+value.id());lines.add("源图："+value.source().name()+" · "+value.source().id()+"；道路与布景摘要 "+CircuitFeedback.roadKey(value.source()));lines.add("原物理 "+value.physics()+"；署名（声明）"+value.author()+"；提供时间 "+value.createdAt());lines.add(value.summary());for(var note:value.notes()) lines.add(note.id()+" · "+note.kind().title+" · "+note.text());return lines;}
    private static List<String> replyDetails(CircuitFeedbackReply value) {var lines=new ArrayList<String>();lines.add("答复快照："+value.id()+"；署名（声明）"+value.author()+"；提供时间 "+value.createdAt());lines.add(value.summary());lines.add("答复目标图："+value.target().name()+" · "+value.target().id()+"；摘要 "+CircuitFeedback.roadKey(value.target())+"；答复物理 "+value.physics());for(var row:value.resolutions()) lines.add(row.noteId()+" · "+row.state().title+" · "+row.response());lines.add("答复不自动设置本地处理，不安装目标地图，也不提供资格或可达性证明。");return lines;}
    private static List<String> trialDetails(CircuitFeedbackTrial trial) {
        var lines=HudRenderer.feedbackTrialLines(trial);
        lines.add(0,"作者试驾 "+trial.id()+" · 留言提供者附加");
        return lines;
    }
    private static List<String> messageDetails(CircuitFeedbackDiscussion value,CircuitFeedbackDiscussion.Message message) {var lines=new ArrayList<String>();lines.add("留言 "+message.id()+"；父留言 "+(message.parent()==null?"最初留言":message.parent())+"；关系层数 "+value.depth(message));lines.add("署名（声明）"+message.author()+"；"+message.kind().title+"；提供时间 "+message.createdAt());lines.add(message.note()==null?"针对整份原反馈":"针对意见 "+message.note()+"："+note(value.feedback(),message.note()).text());lines.add(message.text());if(message.reply()!=null) lines.addAll(replyDetails(message.reply()));if(message.trial()!=null) lines.addAll(trialDetails(message.trial()));return lines;}
    private static String cut(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";}
}
