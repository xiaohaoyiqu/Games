package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.PartsCatalog.*;
import com.f1car.persistence.PartsDiscussionCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded conversations and in-process reply drafts, with explicit branch merge previews. */
final class PartsDiscussionHud {
    private final DiscussionTracesHud traceCitations=new DiscussionTracesHud();
    private final DiscussionLapsHud lapCitations=new DiscussionLapsHud();
    private final DiscussionComparisonHud backgroundComparison=new DiscussionComparisonHud();
    private final DiscussionRevisionsHud revisions=new DiscussionRevisionsHud();
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open;private int mode,page,messagePage;private String selected="",selectedMessage="",title="",author="",body="",parent;
    private FormulaGame.RevisionDiscussionReview revisionContext;
    private PartsPlanShare source;private PartsTrialEvidence evidence;private PartsPlan local;private PartsCatalog catalog;private PartsDiscussion before,pending;
    private PartsDiscussion.Kind kind=PartsDiscussion.Kind.QUESTION;private Slot slot;private Field field;
    void restoreDraft(FormulaGame app) {
        var value=app.discussionEditing.current();if(value==null) return;
        source=value.source();evidence=value.evidence();local=value.local();catalog=local==null?null:app.partsStore.catalog();before=value.before();parent=value.parent();
        title=value.title();author=value.author();body=value.body();kind=value.kind();slot=value.slot();field=value.field();pending=null;
    }
    void captureDraft(FormulaGame app) {
        var old=app.discussionEditing.current();if(source==null) {app.discussionEditing.capture(null);return;}
        if(old!=null && old.source()==source && old.evidence()==evidence && old.local()==local && old.before()==before && Objects.equals(old.parent(),parent) && old.title().equals(title) && old.author().equals(author) && old.body().equals(body) && old.kind()==kind && old.slot()==slot && old.field()==field) return;
        app.discussionEditing.capture(new DiscussionDraft(source,evidence,local,before,parent,title,author,body,kind,slot,field));
    }
    boolean showing() {return open;}
    void open() {open=true;mode=0;revisionContext=null;}
    void close() {open=false;}
    void compose(HudRenderer h,PartsPlanShare share,PartsTrialEvidence trial) {replaceDraft(h,()->begin(share,trial,null,null,null,null));}
    private void replaceDraft(HudRenderer h,Runnable action) {if(source!=null && !body.isBlank()) h.confirm("另起一份讨论草稿？","当前未保存文字将替换；已保存话题与原方案保持。",action);else action.run();}
    private void begin(PartsPlanShare share,PartsTrialEvidence trial,PartsPlan expected,PartsCatalog expectedCatalog,PartsDiscussion thread,String replyTo) {revisionContext=null;source=share;evidence=trial;local=expected;catalog=expectedCatalog;before=thread;parent=replyTo;title=thread==null?cut("关于 "+share.plan().name(),60):thread.title();body="";kind=thread==null?PartsDiscussion.Kind.QUESTION:PartsDiscussion.Kind.REPLY;slot=null;field=null;pending=null;mode=1;open=true;}
    void render(HudRenderer h,FormulaGame app,PartsPlan selectedPlan,Consumer<PartsPlanShare> viewPlan) {if(revisions.showing()) {revisions.render(h,app,(request,reply)->navigateRevision(h,app,request,reply));return;}if(backgroundComparison.showing()) {backgroundComparison.render(h,app,value->{selected=value.id();selectedMessage=value.root().id();messagePage=0;mode=2;revisionContext=null;},(request,reply)->navigateRevision(h,app,request,reply));return;}if(lapCitations.showing()) {lapCitations.render(h,app,value->{selected=value.id();selectedMessage=value.root().id();messagePage=0;mode=2;});return;}if(traceCitations.showing()) {traceCitations.render(h,app,value->{selected=value.id();selectedMessage=value.root().id();messagePage=0;mode=2;});return;}h.panel(50,210,1174,477,new Color(25,50,61));h.text(mode==1?before==null?"围绕冻结方案新建话题":"回应指定留言":mode==2?"方案讨论 · 回复关系":"方案讨论库",600,194,23,MINT,true);if(mode==1) draft(h,app);else if(mode==2) thread(h,app,viewPlan);else library(h,app,selectedPlan,viewPlan);if(!app.partsDiscussions.warning().isEmpty()) h.text(cut(app.partsDiscussions.warning(),68),60,680,13,AMBER);}
    private void library(HudRenderer h,FormulaGame app,PartsPlan selectedPlan,Consumer<PartsPlanShare> viewPlan) {
        h.smallButton(60,662,500,25,"留言装备圈引用库",()->lapCitations.open(null,null));h.smallButton(600,662,614,25,"留言曲线引用库 · 独立收录与交换",()->traceCitations.open(null,null));
        h.smallButton(60,214,244,34,"新建所选方案话题",()->{if(selectedPlan==null) {app.notifyUser("先选择或另存一张命名方案卡。");return;}replaceDraft(h,()->{var current=app.partsStore.catalog();begin(PartsPlanShare.capture(selectedPlan,app.store.profile.playerName,app.partsPlans.trail(selectedPlan.id()),current),null,selectedPlan,current,null,null);});});
        h.smallButton(316,214,244,34,"接收讨论并预览合并",()->importFile(h,app));h.smallButton(600,214,294,34,"重新读取讨论库",app::reloadPartsDiscussions);h.smallButton(914,214,300,34,"返回命名方案库",()->open=false);
        var values=app.partsDiscussions.entries();int pages=Math.max(1,(values.size()+4)/5);page=Math.min(page,pages-1);if(values.stream().noneMatch(value->value.id().equals(selected))) selected=values.isEmpty()?"":values.get(0).id();
        for(int i=page*5;i<Math.min(values.size(),page*5+5);i++) {var value=values.get(i);int y=265+(i-page*5)*65;h.smallButton(60,y,500,36,(value.id().equals(selected)?"● ":"")+cut(value.title(),25),()->{selected=value.id();selectedMessage="";messagePage=0;});h.text(value.messages().size()+" 条 · "+app.discussionReading.badge(value)+" · "+cut(value.plan().plan().name(),12),72,y+57,13,SOFT);}
        pager(h,pages,false);var value=app.partsDiscussions.find(selected);if(value==null) {h.wrapped("围绕命名方案讨论用途、驾驶观察和改装建议，也可以从收到的方案或试车记录发起。收到讨论不会安装部件、采用参数或写本机测量。",600,295,590,20,SOFT,32);}else {h.smallButton(60,599,500,25,"对照两份讨论的方案背景",()->backgroundComparison.open(app,value));h.text(cut(value.title(),28),600,286,22,INK,true);h.text("固定方案："+cut(value.plan().plan().name(),26),600,325,16,SOFT);h.text(value.messages().size()+" / 32 条 · "+(value.evidence()==null?"无试车记录附件":"附提供者六组记录"),600,355,16,AMBER);h.smallButton(600,382,614,36,"打开留言与回复",()->mode=2);h.smallButton(600,430,614,36,"查看原方案并分别采用",()->viewPlan.accept(value.plan()));h.smallButton(600,478,294,36,"导出完整讨论",()->export(h,app,value));h.smallButton(914,478,300,36,"移除本地话题副本",()->h.confirm("移除此话题的本地副本？","方案、本机报告、部件和外部文件保持；未导出的本地回复只在此副本中。",()->app.removePartsDiscussion(value)));}
        if(source!=null) h.smallButton(600,538,614,36,"继续未保存的讨论草稿",()->mode=1);h.smallButton(600,591,294,28,app.discussionEditing.status(),()->DraftRecoveryHud.review(h,app,app.discussionEditing));h.smallButton(914,591,300,28,app.discussionReading.status(),()->DiscussionReadingHud.recovery(h,app));h.smallButton(600,627,614,29,"方案修订声明库 · 原话题分别保留",()->revisions.open(h,app,null,null,false));
    }
    private void thread(HudRenderer h,FormulaGame app,Consumer<PartsPlanShare> viewPlan) {
        var value=app.partsDiscussions.find(selected);if(value==null) {mode=0;return;}if(revisionContext!=null && !revisionContext.reviewed().target().frozen().id().equals(value.id())) revisionContext=null;if(value.message(selectedMessage)==null) selectedMessage=value.root().id();var chosen=value.message(selectedMessage);
        DiscussionReadingHud.controls(h,app,value,chosen.id(),662);
        h.smallButton(60,599,500,25,"所选留言的装备圈引用",()->lapCitations.open(value,chosen.id()));h.smallButton(600,578,614,32,"所选留言的曲线引用 · 查看或添加",()->traceCitations.open(value,chosen.id()));
        h.smallButton(60,214,500,34,"返回话题库",()->mode=0);h.smallButton(600,214,294,34,"回应所选留言",()->reply(h,value,chosen));h.smallButton(914,214,300,34,"回应最初问题",()->reply(h,value,value.root()));
        int pages=Math.max(1,(value.messages().size()+4)/5);messagePage=Math.min(messagePage,pages-1);for(int i=messagePage*5;i<Math.min(value.messages().size(),messagePage*5+5);i++) {var message=value.messages().get(i);int y=265+(i-messagePage*5)*65;h.smallButton(60,y,500,36,(message.id().equals(selectedMessage)?"● ":"")+(app.discussionReading.unread(value)<0?"[待确认] ":app.discussionReading.read(value,message.id())?"[已读] ":"[未读] ")+"层 "+value.depth(message)+" · "+cut(message.author(),10)+" · "+message.kind().title,()->selectedMessage=message.id());h.text(cut(message.text().replace('\n',' '),31),72,y+57,13,SOFT);}pager(h,pages,true);
        h.text(cut(chosen.author(),28)+" · "+chosen.kind().title,600,286,21,INK,true);h.text("针对："+chosen.scope(),600,325,16,MINT);h.text(chosen.parent()==null?"最初留言":"回应："+cut(value.message(chosen.parent()).author(),22),600,360,16,SOFT);h.wrapped(cut(chosen.text(),75),600,400,590,17,SOFT,30);
        h.smallButton(600,478,614,36,"查看完整留言与回复背景",()->h.review("留言原文 · 署名为声明",messageDetails(value,chosen),"关闭",()->{}));h.smallButton(600,528,294,36,"查看原始方案",()->viewPlan.accept(value.plan()));h.smallButton(914,528,300,36,"查看试车附件与限制",()->{if(value.evidence()==null) app.notifyUser("本话题没有试车记录附件。");else h.review("提供者试车快照",PartsTrialEvidenceHud.details(value.evidence(),app.vehicleSpec.physicsVersion),"关闭",()->{});});if(revisionContext!=null) revisionControls(h,app);else h.wrapped("列表按回复关系和固定身份排列，不把提供者时钟当作可靠先后。正文与背景可以完整翻页查看。",600,615,590,16,AMBER,32);
    }
    private void reply(HudRenderer h,PartsDiscussion value,PartsDiscussion.Message message) {if(value.messages().size()>=PartsDiscussion.CAPACITY) {h.review("本话题已满",List.of("最多三十二条留言。先导出保留，再围绕需要继续的问题另起话题；不自动截断旧原文。"),"关闭",()->{});return;}var origin=revisionContext;replaceDraft(h,()->{begin(value.plan(),value.evidence(),null,null,value,message.id());if(origin!=null && origin.reviewed().target().frozen().id().equals(value.id()) && origin.reviewed().target().messageId().equals(message.id()) && origin.reviewed().target().review(value).allowed()) revisionContext=origin;});}
    private void navigateRevision(HudRenderer h,FormulaGame app,FormulaGame.RevisionDiscussionReview request,boolean reply) {
        if(app.resolveRevisionDiscussion(request)==null) return;
        var previous=reply?workingDraft():null;
        Runnable enter=()->{
            if(reply && !Objects.equals(previous,workingDraft())) {app.notifyUser("原回复草稿已变化，请重新查看后选择是否替换。");return;}
            var current=app.resolveRevisionDiscussion(request);if(current==null) return;
            if(reply && current.messages().size()>=PartsDiscussion.CAPACITY) {app.notifyUser("原话题已满，不能继续追加；已有草稿保留。");return;}
            var target=request.reviewed().target();
            if(reply) {begin(current.plan(),current.evidence(),null,null,current,target.messageId());captureDraft(app);}else mode=2;
            selected=current.id();selectedMessage=target.messageId();messagePage=request.reviewed().messagePage();revisionContext=request;
            revisions.dismiss();backgroundComparison.close();open=true;
        };
        if(reply) replaceDraft(h,enter);else enter.run();
    }
    private DiscussionDraft workingDraft() {return source==null?null:new DiscussionDraft(source,evidence,local,before,parent,title,author,body,kind,slot,field);}
    private void returnRevision(FormulaGame app) {captureDraft(app);app.discussionEditing.flush();var expected=revisionContext.entry();if(!expected.equals(app.discussionRevisions.find(expected.revision().id()))) {app.notifyUser("原修订声明已移除或变化；当前原留言与草稿保留，可查看进入时的冻结背景。");return;}revisions.openLibrary(app,expected.revision().id());}
    private void revisionControls(HudRenderer h,FormulaGame app) {
        if(revisionContext==null) return;var context=revisionContext;
        h.smallButton(600,615,294,32,"返回修订关系",()->returnRevision(app));
        h.smallButton(914,615,300,32,"查看修订关联背景",()->h.review("进入本话题时的修订背景",DiscussionRevisionsHud.details(context.entry().revision(),app),"返回原留言",()->{}));
    }
    private void draft(HudRenderer h,FormulaGame app) {
        if(source==null) {mode=0;return;}if(author.isEmpty()) author=app.store.profile.playerName;
        h.smallButton(60,214,500,34,"返回讨论库 · 保留本次草稿",()->mode=0);h.smallButton(600,214,614,34,"查看冻结方案与附件",()->h.review("讨论背景",context(source,evidence,app.vehicleSpec.physicsVersion),"关闭",()->{}));
        h.smallButton(60,264,500,34,before==null?"话题："+cut(title,24):"回应："+cut(before.message(parent).author(),24),()->{if(before==null) h.editText("话题标题",title,"方案讨论",80,text->{if(text.isBlank()) throw new IllegalArgumentException();title=text;pending=null;});else h.review("所回应的原文",messageDetails(before,before.message(parent)),"关闭",()->{});});
        h.smallButton(600,264,614,34,"本条署名："+cut(author,28),()->h.editText("本条留言署名",author,"玩家",40,text->{if(text.isBlank()) throw new IllegalArgumentException();author=text;pending=null;}));
        h.smallButton(60,312,244,34,"类别："+kind.title,()->{kind=PartsDiscussion.Kind.values()[(kind.ordinal()+1)%PartsDiscussion.Kind.values().length];pending=null;});h.smallButton(316,312,244,34,"针对："+(slot==null?"整套方案":slot.title),()->{slot=slot==null?Slot.values()[0]:slot.ordinal()+1==Slot.values().length?null:Slot.values()[slot.ordinal()+1];field=null;pending=null;});
        h.smallButton(600,312,614,34,"参数："+(field==null?"未限定":field.title),()->{if(slot!=null) {var fields=slot.fields();field=field==null?fields.get(0):fields.indexOf(field)+1==fields.size()?null:fields.get(fields.indexOf(field)+1);pending=null;}});
        h.smallButton(60,366,1154,38,"编辑留言正文 · "+body.length()+" / 640",()->h.editText("留言正文（观察为声明，可换行）",body,"",640,text->{body=text;pending=null;}));h.wrapped(body.isBlank()?"写清要解决的问题、观察条件，或回应上条的具体意见。未提交草稿会独立保存，下次可继续。":cut(body,180),72,448,1090,19,SOFT,55);
        if(before!=null) h.smallButton(600,552,614,34,"刷新回复背景，保留文字",()->refreshReply(app));
        if(before!=null && revisionContext!=null && revisionContext.reviewed().target().frozen().id().equals(before.id()) && revisionContext.reviewed().target().messageId().equals(parent)) h.smallButton(60,552,500,34,"返回修订关系 · 保留回复草稿",()->returnRevision(app));
        if(local!=null) h.smallButton(60,552,500,34,"更新命名卡背景，保留文字",()->{var current=app.partsPlans.find(local.id());if(current==null) {app.notifyUser("命名卡已移除，草稿文字保留。");return;}catalog=app.partsStore.catalog();source=PartsPlanShare.capture(current,source.author(),app.partsPlans.trail(current.id()),catalog);local=current;pending=null;app.notifyUser("已更新话题的方案背景，文字保留；请重新预览。");});
        h.smallButton(60,600,500,42,"放弃这份未保存草稿",()->h.confirm("放弃未保存文字？","已保存的讨论、方案和记录保持。",()->{source=null;pending=null;body="";mode=0;}));h.smallButton(600,600,614,42,"预览原文与背景再保存",()->{try {if(pending==null) {var message=PartsDiscussion.Message.create(parent,author,kind,slot,field,body);pending=before==null?PartsDiscussion.create(title,source,evidence,message):before.append(message);}var expected=before;var value=pending;var lines=details(value,app.vehicleSpec.physicsVersion);lines.add("只保存本条新留言；原文不编辑、不按署名认证，不采用方案或写测量。");h.review("保存讨论原文？",lines,"保存这一条留言",()->{if(app.savePartsDiscussion(value,expected,local,catalog)) {selected=value.id();selectedMessage=value.messages().stream().filter(message->expected==null || expected.message(message.id())==null).findFirst().orElseThrow().id();messagePage=value.messages().indexOf(value.message(selectedMessage))/5;source=null;pending=null;body="";mode=2;}});}catch(IllegalArgumentException error) {app.notifyUser("请核对标题、署名、正文与回复对应，草稿保留。");}});
    }
    private void refreshReply(FormulaGame app) {
        if(!app.reloadPartsDiscussions()) return;
        var current=app.partsDiscussions.find(before.id());
        var merge=current==null?null:PartsDiscussion.merge(before,current);
        if(merge==null || !merge.valid() || !current.equals(merge.candidate())
                || !Objects.equals(before.message(parent),current.message(parent))) {
            app.notifyUser("原话题或所回应的原文不再对应，草稿保留。");
            return;
        }
        before=current;
        pending=null;
        app.notifyUser("回复背景已刷新，文字保留，请重新预览。");
    }
    private void importFile(HudRenderer h,FormulaGame app) {h.prompt("方案讨论 JSON 完整路径",app.store.directory().resolve("parts-export-discussion.json").toAbsolutePath().toString(),512,text->{try {var incoming=PartsDiscussionCodec.read(Path.of(text.strip()));var merge=app.partsDiscussions.preview(incoming);var lines=new ArrayList<String>();lines.add("新增留言 "+merge.added()+" 条；相同原文 "+merge.shared()+" 条。时间与署名不决定覆盖。");for(var message:incoming.messages()) {var old=merge.before()==null?null:merge.before().message(message.id());lines.add((old==null?"新增":old.equals(message)?"原文相同":"原文冲突")+"："+message.author()+"；"+message.id());}lines.addAll(merge.issues());lines.addAll(details(merge.valid()?merge.candidate():incoming,app.vehicleSpec.physicsVersion));if(!merge.valid()) {h.review("讨论存在冲突，未收录",lines,"保留本地原文",()->{});return;}h.review("查看后收录新增讨论？",lines,"只追加一致的新留言",()->{if(app.receivePartsDiscussion(merge)) {selected=incoming.id();selectedMessage=incoming.messages().stream().filter(message->merge.before()==null || merge.before().message(message.id())==null).findFirst().orElse(incoming.root()).id();messagePage=merge.candidate().messages().indexOf(merge.candidate().message(selectedMessage))/5;mode=2;}});}catch(java.io.IOException | RuntimeException error) {app.notifyUser("讨论无法读取；请核对类型、背景、回复关系和校验，已有资料保留。");}});}
    private void export(HudRenderer h,FormulaGame app,PartsDiscussion value) {
        h.review("导出完整原文与背景？",details(value,app.vehicleSpec.physicsVersion),"填写独立导出路径",
                ()->h.promptExportPath("方案讨论导出路径",app.store.directory().resolve("parts-export-discussion.json"),
                        (path,replace)->app.exportPartsDiscussion(value,path,replace)));
    }
    private void pager(HudRenderer h,int pages,boolean messages) {h.smallButton(60,628,180,32,"上一页",()->{if(messages) messagePage=Math.max(0,messagePage-1);else page=Math.max(0,page-1);});h.text(((messages?messagePage:page)+1)+" / "+pages,270,650,14,SOFT);h.smallButton(380,628,180,32,"下一页",()->{if(messages) messagePage=Math.min(pages-1,messagePage+1);else page=Math.min(pages-1,page+1);});}
    private static List<String> context(PartsPlanShare plan,PartsTrialEvidence evidence,String physics) {var lines=new ArrayList<String>(PartsPlanShareHud.details(plan));if(evidence!=null) lines.addAll(PartsTrialEvidenceHud.details(evidence,physics));else lines.add("本话题没有试车记录附件；留言的观察属于玩家声明。");return lines;}
    static List<String> details(PartsDiscussion value,String physics) {var lines=new ArrayList<String>();lines.add("话题："+value.title()+"；身份："+value.id());lines.addAll(context(value.plan(),value.evidence(),physics));for(var message:value.messages()) lines.addAll(messageDetails(value,message));return lines;}
    private static List<String> messageDetails(PartsDiscussion value,PartsDiscussion.Message message) {return List.of("署名（声明）："+message.author()+"；"+message.kind().title+"；针对："+message.scope(),"留言身份："+message.id()+"；回应："+(message.parent()==null?"最初留言":message.parent())+"；关系层数："+value.depth(message),"记录时间（提供者毫秒值）："+message.createdAt(),message.text());}
    private static String cut(String value,int limit) {return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…";}
}
