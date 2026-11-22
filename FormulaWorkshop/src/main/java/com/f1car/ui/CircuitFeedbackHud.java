package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.model.CircuitFeedback.*;
import com.f1car.persistence.CircuitFeedbackCodec;
import com.f1car.persistence.CircuitFeedbackReplyCodec;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;

/** Offline author/player workflow. Drafts survive page navigation; original comments remain immutable. */
final class CircuitFeedbackHud {
    private final FeedbackDiscussionHud discussions=new FeedbackDiscussionHud();
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private static final class Draft {
        final CircuitDocument source;final String physics,id;final long createdAt;
        String author,title="试玩反馈",summary="";final ArrayList<Note> notes=new ArrayList<>();
        Draft(CircuitDocument source,String physics,String author) {this.source=source;this.physics=physics;this.author=author;id=CircuitFeedback.newId("feedback-");createdAt=System.currentTimeMillis();}
        Draft(CircuitFeedbackDraft saved) {source=saved.source();physics=saved.physics();id=saved.id();createdAt=saved.createdAt();author=saved.author();title=saved.title();summary=saved.summary();notes.addAll(saved.notes());}
        CircuitFeedback build() {return new CircuitFeedback(1,id,source,physics,author,title,summary,createdAt,notes);}
    }
    private boolean open;private int page,inboxPage,noteIndex,draftIndex,draftPage,targetPage,attachmentPage;private String selected="";private State filter;
    private Draft draft;private List<Evidence> attachments=List.of();private Note attachmentNote;
    private boolean draftLoaded;
    private void loadDraft(FormulaGame app) {if(draftLoaded) return;draftLoaded=true;var saved=app.feedbackEditing.current();if(saved!=null) {draft=new Draft(saved);draftIndex=saved.selected();draftPage=draftIndex/3;}}
    void captureDraft(FormulaGame app) {
        discussions.captureDraft(app);
        if(!draftLoaded) return;if(draft==null) {app.feedbackEditing.capture(null);return;}
        var old=app.feedbackEditing.current();int selected=Math.min(draftIndex,Math.max(0,draft.notes.size()-1));
        if(old!=null && old.id().equals(draft.id) && old.source()==draft.source && old.physics().equals(draft.physics) && old.createdAt()==draft.createdAt
            && old.author().equals(draft.author) && old.title().equals(draft.title) && old.summary().equals(draft.summary) && old.notes().equals(draft.notes) && old.selected()==selected) return;
        app.feedbackEditing.capture(new CircuitFeedbackDraft(1,draft.id,draft.source,draft.physics,draft.createdAt,draft.author,draft.title,draft.summary,draft.notes,selected));
    }
    private void checkpoint(FormulaGame app) {captureDraft(app);if(!app.feedbackEditing.flush()) app.notifyUser(app.feedbackEditing.warning());if(!app.feedbackDiscussionEditing.flush()) app.notifyUser(app.feedbackDiscussionEditing.warning());}
    private Entry overlayEntry;private Note overlayNote;private CircuitDocument overlayDocument;private boolean overlayValid;private String overlayLabel="";
    private Entry alignmentEntry;private CircuitDocument alignmentDocument;private Alignment alignment=Alignment.UNBOUND;
    private List<Entry> cachedEntries=List.of(),cachedRows=List.of();private State cachedFilter;
    private int summaryPage,replyPage;private List<Entry> summaryEntries;private List<CircuitFeedbackReply> summaryReplies;private CircuitFeedbackSummary summary;
    private List<CircuitFeedbackReply> replySource,replyRows=List.of();private String replySelection="";private Map<String,Boolean> replyRoads=Map.of();
    boolean showing() {return open;}
    boolean showingDiscussion() {return open && discussions.showing();}
    void showDiscussionMessage(CircuitFeedbackDiscussion thread,CircuitFeedbackDiscussion.Message message) {
        show();
        discussions.showMessage(thread,message);
    }
    void editDiscussionReply(FormulaGame app,CircuitFeedbackDiscussionDraft draft) {
        show();
        discussions.editReply(app,draft);
    }
    void show() {open=true;}
    void showNote(FormulaGame app,Entry entry,Note note) {
        discussions.close();
        show();
        page=0;
        var current=app.circuitFeedback.find(entry.feedback().id());
        if(current==null) return;
        if(!matchesFilter(current)) filter=null;
        selected=current.feedback().id();
        noteIndex=Math.max(0,current.feedback().notes().indexOf(note));
        inboxPage=inboxRows(app).indexOf(current)/5;
    }
    boolean close() {if(discussions.close()) return true;if(!open) return false;open=false;return true;}
    void focus(Entry entry,Note note,CircuitDocument target,CircuitEditorView view) {overlayEntry=entry;overlayNote=note;overlayDocument=target;overlayValid=true;overlayLabel=entry.alignment(target)==Alignment.REFERENCE?"玩家意见 · 旧版参考":"玩家意见";discussions.close();open=false;view.focus(List.of(note.location()));}
    Note overlay(FormulaGame app,CircuitDocument document) {
        if(overlayEntry==null) return null;var current=app.circuitFeedback.find(overlayEntry.feedback().id());
        if(current==null || !Objects.equals(current.binding(),overlayEntry.binding()) || !document.id().equals(overlayEntry.binding().targetId())) {overlayEntry=null;return null;}
        if(overlayDocument!=document) {overlayDocument=document;var state=overlayEntry.alignment(document);overlayValid=state==Alignment.MATCHED || state==Alignment.REFERENCE;}
        if(!overlayValid) {overlayEntry=null;return null;}return overlayNote;
    }
    String overlayLabel() {return overlayLabel;}

    private boolean matchesFilter(Entry entry) {
        return filter==null || entry.feedback().notes().stream().anyMatch(note->entry.resolution(note.id()).state()==filter);
    }

    private List<Entry> inboxRows(FormulaGame app) {
        if(cachedEntries!=app.circuitFeedback.entries() || cachedFilter!=filter) {
            cachedEntries=app.circuitFeedback.entries();
            cachedFilter=filter;
            cachedRows=cachedEntries.stream().filter(this::matchesFilter).toList();
        }
        return cachedRows;
    }
    void render(HudRenderer h,FormulaGame app,Runnable back) {
        loadDraft(app);
        if(discussions.showing()) {discussions.render(h,app);return;}
        h.panel(44,128,1188,594,new Color(18,39,51));h.text(page==1?"编写试玩反馈":page==2?"明确对应本地地图":page==3?"选择一条已存试跑事件":page==4?"按源版本汇总反馈":page==5?"收到的作者答复":"玩家试玩反馈",70,158,22,MINT,true);
        h.smallButton(1030,139,180,29,"返回编辑画布",()->{checkpoint(app);back.run();});
        h.smallButton(700,139,310,29,"多轮反馈讨论",()->{var entry=app.circuitFeedback.find(selected);discussions.open(entry==null?null:entry.feedback());});
        if(page==1) compose(h,app);else if(page==2) binding(h,app);else if(page==3) observations(h,app);else if(page==4) summary(h,app);else if(page==5) replies(h,app);else inbox(h,app);
        h.smallButton(70,723,1140,26,"反馈草稿 · "+app.feedbackEditing.status(),()->{captureDraft(app);DraftRecoveryHud.review(h,app,app.feedbackEditing);});
        if(!app.circuitFeedback.warning().isEmpty()) h.text(cut(app.circuitFeedback.warning(),72),70,710,14,GOLD);
    }
    private void inbox(HudRenderer h,FormulaGame app) {
        h.smallButton(70,174,210,34,"为当前图写反馈",()->start(h,app));h.smallButton(290,174,180,34,"导入玩家原文",()->importFile(h,app));h.smallButton(480,174,180,34,"重新读取反馈库",app.circuitFeedback::reload);
        if(draft!=null) h.smallButton(670,174,200,34,"继续未存反馈",()->page=1);
        h.smallButton(880,174,160,34,"导入作者答复",()->importReply(h,app));h.smallButton(1050,174,160,34,"反馈汇总",()->page=4);
        var rows=inboxRows(app);int pages=Math.max(1,(rows.size()+4)/5);inboxPage=Math.min(inboxPage,pages-1);
        if(rows.stream().noneMatch(row->row.feedback().id().equals(selected))) selected=rows.isEmpty()?"":rows.get(0).feedback().id();
        for(int i=inboxPage*5;i<Math.min(rows.size(),inboxPage*5+5);i++) {var row=rows.get(i);int y=228+(i-inboxPage*5)*65;h.smallButton(70,y,390,45,(row.feedback().id().equals(selected)?"● ":"")+cut(row.feedback().title(),20),()->{selected=row.feedback().id();noteIndex=0;});h.text(cut(row.feedback().author()+" · "+row.feedback().source().name(),28),78,y+60,13,SOFT);}
        if(rows.isEmpty()) h.wrapped(filter==null?"还没有玩家意见。为当前图编写反馈，或导入收到的 JSON 文件。":"这个状态下还没有意见，可切换筛选。",80,282,360,18,SOFT,28);
        h.smallButton(70,600,185,32,"上一页",()->inboxPage=Math.max(0,inboxPage-1));h.smallButton(275,600,185,32,"下一页",()->inboxPage=Math.min(pages-1,inboxPage+1));
        h.smallButton(70,642,390,36,"筛选："+(filter==null?"全部":filter.title)+" · 切换",()->{filter=filter==null?State.OPEN:filter==State.DEFERRED?null:State.values()[filter.ordinal()+1];inboxPage=0;});h.text(app.circuitFeedback.entries().size()+" / 32 份 · "+(inboxPage+1)+" / "+pages+" 页",78,695,13,SOFT);
        var entry=app.circuitFeedback.find(selected);if(entry==null) return;var value=entry.feedback();noteIndex=Math.min(noteIndex,value.notes().size()-1);var note=value.notes().get(noteIndex);var resolution=entry.resolution(note.id());
        h.text(cut(value.title(),30),510,245,21,INK,true);h.text(cut(value.author()+" · 源图："+value.source().name(),43),510,274,15,SOFT);var state=alignment(app,entry);h.text(state.title+(!value.physics().equals(app.vehicleSpec.physicsVersion)?" · 旧物理背景":""),510,300,15,state==Alignment.MATCHED?MINT:GOLD);
        h.smallButton(510,317,170,32,"上一条意见",()->noteIndex=Math.floorMod(noteIndex-1,value.notes().size()));h.text((noteIndex+1)+" / "+value.notes().size(),717,340,16,INK);h.smallButton(1012,317,170,32,"下一条意见",()->noteIndex=(noteIndex+1)%value.notes().size());
        h.text(note.kind().title+" · "+resolution.state().title,510,378,18,MINT,true);h.wrapped(cut(note.text(),100),510,407,672,16,INK,24);h.text(note.evidence()==null?"玩家文字意见":"附试跑事件："+note.evidence().marker().event().title+"；属于观测描述",510,493,14,SOFT);h.text(location(note.location()),510,518,14,SOFT);
        h.smallButton(510,539,326,31,"状态："+resolution.state().title+" · 切换",()->app.resolveCircuitFeedback(entry,new Resolution(note.id(),State.values()[(resolution.state().ordinal()+1)%State.values().length],resolution.response())));
        h.smallButton(852,539,330,31,"编辑本地处理答复",()->h.editText("处理答复 · 只在本地保存",resolution.response(),"",320,text->app.resolveCircuitFeedback(entry,new Resolution(note.id(),resolution.state(),text))));
        h.smallButton(510,579,326,34,"查看全部原文与本地处理",()->h.review("玩家原文与本地处理",details(entry),"关闭",()->{}));
        boolean canInspect=note.location()!=null && (state==Alignment.MATCHED || state==Alignment.REFERENCE);
        h.smallButton(852,579,canInspect?160:330,34,state==Alignment.REFERENCE?"定位旧版位置":"定位这条意见",()->app.focusCircuitFeedback(entry,note.id()));
        if(canInspect) h.smallButton(1022,579,160,34,"查看意见附近",()->app.inspectCircuitFeedbackNearby(entry,note.id()));
        var trial=app.circuitFeedback.trial(value.id(),note.id());
        if(trial!=null) h.smallButton(510,682,672,28,"查看这条意见的已存作者试驾",()->h.reviewFeedbackTrial(trial,true));
        h.smallButton(510,616,326,23,"预览并导出处理答复",()->exportReply(h,app,entry));h.smallButton(852,616,330,23,"查看收到的作者答复",()->{replyPage=0;page=5;});
        h.smallButton(490,642,215,36,"对应／更换本地图",()->{targetPage=0;page=2;});h.smallButton(715,642,215,36,"导出玩家原文",()->exportFile(h,app,entry));h.smallButton(940,642,270,36,"移除本地反馈",()->h.confirm("移除本地反馈？","删除原文的本地副本、对应、处理、收到的答复与已存作者试驾。\n地图、资格与试跑记录保留；外部原文件保留。",()->app.removeCircuitFeedback(entry)));
    }
    private void summary(HudRenderer h,FormulaGame app) {
        h.smallButton(70,174,260,34,"返回反馈库",()->page=0);if(summaryEntries!=app.circuitFeedback.entries() || summaryReplies!=app.circuitFeedback.replies()) {summaryEntries=app.circuitFeedback.entries();summaryReplies=app.circuitFeedback.replies();summary=CircuitFeedbackSummary.summarize(summaryEntries,summaryReplies);}
        h.text(summary.feedbackCount()+" 份原文 / "+summary.noteCount()+" 条意见 / "+summary.replyCount()+" 份答复声明",360,197,17,INK);var groups=summary.groups();int pages=Math.max(1,(groups.size()+3)/4);summaryPage=Math.min(summaryPage,pages-1);
        for(int i=summaryPage*4;i<Math.min(groups.size(),summaryPage*4+4);i++) {var group=groups.get(i);int y=232+(i-summaryPage*4)*105;h.smallButton(70,y,1136,37,cut(group.source().name(),32)+" · "+group.reports()+" 份 / "+group.notes()+" 条 · 查看分类与本地状态",()->h.review("源版本反馈汇总",groupDetails(group),"关闭汇总",()->{}));h.text("源身份 "+group.source().id()+" · 物理 "+group.physics(),80,y+61,13,SOFT);h.text("附位置 "+group.positioned()+" 条 · 附观测 "+group.observations()+" 条 · 收到答复 "+group.replies()+" 份；不同驾驶版本分开",80,y+88,14,SOFT);}
        if(groups.isEmpty()) h.wrapped("尚无可汇总意见。先编写或导入一份反馈；答复可以连同原文一起收录。",90,300,1080,20,SOFT,30);h.smallButton(70,670,260,30,"上一页",()->summaryPage=Math.max(0,summaryPage-1));h.text((summaryPage+1)+" / "+pages+"；份数不是人数或独立问题数，状态不证明问题已解决。",350,691,14,GOLD);h.smallButton(950,670,260,30,"下一页",()->summaryPage=Math.min(pages-1,summaryPage+1));
    }
    private static List<String> groupDetails(CircuitFeedbackSummary.Group group) {var lines=new ArrayList<String>();lines.add("源图："+group.source().name()+"；身份："+group.source().id());lines.add("驾驶内容摘要："+group.source().driveKey()+"；物理："+group.physics());lines.add(group.reports()+" 份原文，"+group.notes()+" 条意见；原文类别为玩家选择，不自动合并相似文字。");for(var kind:Kind.values()) lines.add("意见类别 · "+kind.title+"："+group.kinds().get(kind));for(var state:State.values()) lines.add("本地处理 · "+state.title+"："+group.states().get(state));lines.add("附位置 "+group.positioned()+" 条；附试跑观测 "+group.observations()+" 条；收到的独立答复 "+group.replies()+" 份。");lines.add("这些数量不是唯一玩家、独立缺陷、投票或质量评分。答复状态不并入本地状态，作者说已处理也不授予地图资格。");return lines;}
    private void replies(HudRenderer h,FormulaGame app) {
        var entry=app.circuitFeedback.find(selected);if(entry==null) {page=0;return;}h.smallButton(70,174,260,34,"返回原始意见",()->page=0);h.text(cut(entry.feedback().title(),40)+" · 答复与本地处理分开",360,197,17,INK);if(replySource!=app.circuitFeedback.replies() || !replySelection.equals(selected)) {replySource=app.circuitFeedback.replies();replySelection=selected;replyRows=app.circuitFeedback.replies(selected);var roads=new HashMap<String,Boolean>();for(var reply:replyRows) roads.put(reply.id(),reply.sameRoad());replyRoads=Map.copyOf(roads);}var values=replyRows;int pages=Math.max(1,(values.size()+4)/5);replyPage=Math.min(replyPage,pages-1);
        for(int i=replyPage*5;i<Math.min(values.size(),replyPage*5+5);i++) {var reply=values.get(i);int y=244+(i-replyPage*5)*69;h.smallButton(70,y,905,38,cut(reply.author()+" · "+reply.target().name()+" · "+reply.summary().replace('\n',' '),58),()->h.review("收到的作者答复 · 内容声明",replyDetails(reply),"关闭答复",()->{}));h.smallButton(990,y,220,38,"移除此答复副本",()->h.confirm("移除此答复副本？","原始意见、本地对应与处理记录保留。",()->app.removeCircuitFeedbackReply(reply)));h.text(replyRoads.get(reply.id())?"答复针对相同道路与布景；不代表已修复或获得资格。":"答复针对不同道路或布景，需结合版本差异人工判断。",80,y+60,14,SOFT);}
        if(values.isEmpty()) h.wrapped("尚未收到这份原文的作者答复。作者可在明确对应地图并记录处理后导出；收到文件后用“导入作者答复”收录。",90,300,1080,20,SOFT,30);h.smallButton(70,642,260,34,"上一页答复",()->replyPage=Math.max(0,replyPage-1));h.text((replyPage+1)+" / "+pages+" 页 · 不按姓名或时间自动判断哪个答复较新",360,666,15,GOLD);h.smallButton(950,642,260,34,"下一页答复",()->replyPage=Math.min(pages-1,replyPage+1));
    }
    private void importReply(HudRenderer h,FormulaGame app) {h.prompt("收到的作者答复 JSON 完整路径",app.store.directory().resolve("circuit-export-reply.json").toAbsolutePath().toString(),512,text->{try {var reply=CircuitFeedbackReplyCodec.read(Path.of(text.strip()));var lines=replyDetails(reply);lines.add("只收录原文及独立答复；没有原文时一同收录，不安装地图、不对应本地图、不改变本地处理或资格。");h.review("收录这份作者答复？",lines,"保存原文与独立答复",()->{if(app.receiveCircuitFeedbackReply(reply)) {selected=reply.feedback().id();page=5;replyPage=0;}});}catch(java.io.IOException | RuntimeException error) {app.notifyUser("答复无法读取，请核对格式、大小、原文对应与独立路径。");}});}
    private void exportReply(HudRenderer h,FormulaGame app,Entry entry) {
        var target=entry.binding()==null?null:app.packCircuit(entry.binding().targetId());var alignment=entry.alignment(target);if(alignment!=Alignment.MATCHED && alignment!=Alignment.REFERENCE) {app.notifyUser("先明确对应当前地图，再记录并导出处理答复。");return;}
        h.editText("答复署名",app.store.profile.playerName,"作者",40,author->h.editText("答复整体说明（可空）","","",240,summary->{
            try {
                var reply=CircuitFeedbackReply.capture(entry,target,app.vehicleSpec.physicsVersion,author,summary);
                h.review("导出处理答复 · 完整内容",replyDetails(reply),"填写答复导出路径",()->h.promptExportPath(
                    "答复导出 JSON 完整路径",app.store.directory().resolve("circuit-export-reply.json"),
                    (path,replace)->app.exportCircuitFeedbackReply(entry,reply,path,replace)));
            } catch(IllegalArgumentException error) {
                app.notifyUser("请至少记录一条处理状态或答复，并核对署名与说明。");
            }
        }));
    }
    private static ArrayList<String> replyDetails(CircuitFeedbackReply reply) {var lines=original(reply.feedback());lines.add("答复署名（声明）："+reply.author()+"；说明："+reply.summary());lines.add("答复针对地图："+reply.target().name()+"；身份："+reply.target().id()+"；物理："+reply.physics());lines.add(reply.sameRoad()?"原文与答复目标道路及布景相同。":"原文与答复目标道路或布景不同；以下为版本差异，不自动迁移坐标。");for(var change:CircuitRevisionDiff.compare(reply.feedback().source(),reply.target()).changes()) if(change.kind()!=CircuitRevisionDiff.Kind.IDENTITY) lines.add(change.description());for(int i=0;i<reply.feedback().notes().size();i++) {var note=reply.feedback().notes().get(i);var resolution=reply.resolution(note.id());lines.add("作者答复 "+(i+1)+" · "+resolution.state().title+"："+(resolution.response().isBlank()?"未填写文字答复":resolution.response()));}lines.add("答复是提供者的处理声明，不认证身份、不证明修复，不附地图资格、活动或成绩；不会覆盖接收者自己的处理记录。");return lines;}
    private Alignment alignment(FormulaGame app,Entry entry) {var target=entry.binding()==null?null:app.packCircuit(entry.binding().targetId());if(entry!=alignmentEntry || target!=alignmentDocument) {alignmentEntry=entry;alignmentDocument=target;alignment=entry.alignment(target);}return alignment;}
    private void start(HudRenderer h,FormulaGame app) {var editor=app.circuitEditor();if(editor==null) {app.notifyUser("先新建、载入或导入一张地图，再写反馈。");return;}Runnable action=()->{draft=new Draft(editor.document(),app.vehicleSpec.physicsVersion,app.store.profile.playerName);draftIndex=draftPage=0;page=1;};if(draft!=null) h.confirm("放弃未保存的反馈，另写一份？","当前意见草稿尚未保存。返回反馈库也可继续编辑。",action);else action.run();}
    private void compose(HudRenderer h,FormulaGame app) {
        if(draft==null) {page=0;return;}var current=draft;h.text(cut("源版本："+current.source.name(),50),70,197,15,SOFT);h.text("记录打开时的源版本；地图后续修改不改变此快照。",70,221,14,SOFT);
        h.smallButton(70,239,390,35,"玩家："+cut(current.author,21),()->h.editText("反馈署名",current.author,"玩家",40,text->current.author=text));h.smallButton(70,284,390,35,"标题："+cut(current.title,21),()->h.editText("反馈标题",current.title,"试玩反馈",60,text->current.title=text));h.smallButton(70,329,390,35,"整体说明 · 编辑",()->h.editText("整体说明（可空）",current.summary,"",240,text->current.summary=text));
        h.smallButton(70,377,390,35,"新增意见 · "+current.notes.size()+" / 16",()->{if(current.notes.size()>=16) {app.notifyUser("每份最多十六条意见。");return;}h.editText("描述发生了什么、期望怎样改进","","",320,text->{try {current.notes.add(Note.create(Kind.DRIVING,text,null));draftIndex=current.notes.size()-1;draftPage=draftIndex/3;}catch(IllegalArgumentException error) {app.notifyUser("意见需非空，最多三百二十字符。");}});});
        int pages=Math.max(1,(current.notes.size()+2)/3);draftPage=Math.min(draftPage,pages-1);for(int i=draftPage*3;i<Math.min(current.notes.size(),draftPage*3+3);i++) {int index=i;var row=current.notes.get(i);h.smallButton(70,430+(i-draftPage*3)*50,390,38,(i==draftIndex?"● ":"")+(i+1)+" · "+cut(row.text().replace('\n',' '),20),()->draftIndex=index);}
        h.smallButton(70,595,185,32,"上一页",()->draftPage=Math.max(0,draftPage-1));h.smallButton(275,595,185,32,"下一页",()->draftPage=Math.min(pages-1,draftPage+1));
        if(!current.notes.isEmpty()) {draftIndex=Math.min(draftIndex,current.notes.size()-1);int index=draftIndex;var note=current.notes.get(index);h.text("意见 "+(index+1)+" · "+note.kind().title,510,263,20,MINT,true);h.wrapped(cut(note.text(),160),510,297,670,17,INK,25);
            h.smallButton(510,421,326,34,"修改意见全文",()->h.editText("意见全文",note.text(),"",320,text->{try {replace(current,index,note,new Note(note.id(),note.kind(),text,note.location(),note.evidence()));}catch(IllegalArgumentException error) {app.notifyUser("意见需非空且最多三百二十字符。");}}));
            h.smallButton(852,421,330,34,"类别："+note.kind().title,()->replace(current,index,note,new Note(note.id(),Kind.values()[(note.kind().ordinal()+1)%Kind.values().length],note.text(),note.location(),note.evidence())));
            h.text(location(note.location())+(note.evidence()==null?"":" · 已附观测"),510,486,15,SOFT);h.smallButton(510,505,326,34,"使用编辑器当前选中位置",()->selectedLocation(app,current,index,note));h.smallButton(852,505,330,34,"输入 X,Z 坐标",()->h.prompt("位置 X,Z（米）；修改会移除事件附件",note.location()==null?"0,0":note.location().x()+","+note.location().z(),64,text->{try {String[] values=text.strip().split("[,，]",-1);if(values.length!=2) throw new IllegalArgumentException();replace(current,index,note,note.located(new CircuitInspector.Location(Float.parseFloat(values[0].strip()),Float.parseFloat(values[1].strip()))));}catch(IllegalArgumentException error) {app.notifyUser("输入两个有限坐标，范围为正负一千米。");}}));
            h.smallButton(510,550,326,34,"附一条已保存试跑事件",()->chooseObservation(app,current,note));h.smallButton(852,550,330,34,"清除位置与事件",()->replace(current,index,note,note.located(null)));h.smallButton(510,595,326,32,"移除这条未存意见",()->h.confirm("移除这条未保存意见？",note.text(),()->{if(index<current.notes.size() && current.notes.get(index).equals(note)) current.notes.remove(index);}));
        }else h.wrapped("先新增一条具体意见；位置和试跑事件都是可选项。",510,294,670,20,SOFT,30);
        h.smallButton(852,595,330,32,"放弃这份反馈草稿",()->{captureDraft(app);var reviewed=app.feedbackEditing.current();h.confirm("放弃未提交的反馈草稿？","源图与反馈库原文保持；尚未入库的文字和意见将移除。",()->{captureDraft(app);if(draft!=current || !Objects.equals(reviewed,app.feedbackEditing.current())) {app.notifyUser("反馈草稿已变化，请重新查看。");return;}draft=null;page=0;checkpoint(app);});});
        h.smallButton(70,651,390,36,"返回反馈库 · 保留草稿",()->{checkpoint(app);page=0;});h.smallButton(490,651,720,36,"预览完整反馈并保存",()->{try {var value=current.build();h.review("保存这份原始反馈？",original(value),"保存到本地反馈库",()->{if(draft!=current || !reviewed(current,value)) {app.notifyUser("反馈草稿已变化，请重新预览；本次文字保留。");return;}if(app.receiveCircuitFeedback(value)) {selected=value.id();noteIndex=0;draft=null;page=0;checkpoint(app);}});}catch(IllegalArgumentException error) {app.notifyUser("请填写署名、标题及至少一条有效意见，并核对长度。");}});
    }
    private static boolean reviewed(Draft current,CircuitFeedback value) {try {return value.equals(current.build());}catch(IllegalArgumentException error) {return false;}}
    private static void replace(Draft current,int index,Note expected,Note next) {if(index<current.notes.size() && expected.equals(current.notes.get(index))) current.notes.set(index,next);}
    private void selectedLocation(FormulaGame app,Draft current,int index,Note note) {var editor=app.circuitEditor();if(editor==null || !editor.document().driveKey().equals(current.source.driveKey())) {app.notifyUser("编辑器已切换或修改；可输入源版本坐标，或另写当前版本反馈。");return;}int anchor=editor.selectedAnchor(),item=editor.selectedItem();if(anchor>=0) {var point=editor.document().anchors().get(anchor);replace(current,index,note,note.located(new CircuitInspector.Location(point.x(),point.z())));}else if(item>=0) {var value=editor.document().scene().items().get(item);replace(current,index,note,note.located(new CircuitInspector.Location(value.x(),value.z())));}else app.notifyUser("先在编辑画布选中一个路线点或物件，再继续反馈草稿。");}
    private void chooseObservation(FormulaGame app,Draft current,Note note) {var rows=new ArrayList<Evidence>();for(var saved:app.circuits.find(current.source.id()).map(com.f1car.persistence.CircuitStore.Saved::reviews).orElse(List.of())) if(saved.physicsVersion().equals(current.physics) && saved.snapshot().matches(current.source)) for(var marker:saved.snapshot().markers()) rows.add(Evidence.from(current.source,current.physics,saved.snapshot(),marker));attachments=List.copyOf(rows);attachmentPage=0;attachmentNote=note;page=3;}
    private void observations(HudRenderer h,FormulaGame app) {h.smallButton(70,180,390,34,"返回反馈草稿",()->page=1);int pages=Math.max(1,(attachments.size()+4)/5);attachmentPage=Math.min(attachmentPage,pages-1);for(int i=attachmentPage*5;i<Math.min(attachments.size(),attachmentPage*5+5);i++) {var evidence=attachments.get(i);var marker=evidence.marker();h.smallButton(70,245+(i-attachmentPage*5)*66,1136,46,marker.event().title+" · 车 "+(marker.car()+1)+" · "+String.format(Locale.ROOT,"%.1fs",marker.time())+" · "+cut(marker.message(),42),()->h.review("把这条观测附到意见？",List.of(marker.message(),location(marker.location()),"这是玩家携带的观测描述，不是地图资格、成绩或作者认证。"),"附到当前意见",()->{if(draft!=null) {int index=draft.notes.indexOf(attachmentNote);if(index>=0) replace(draft,index,attachmentNote,new Note(attachmentNote.id(),attachmentNote.kind(),attachmentNote.text(),marker.location(),evidence));}page=1;}));}if(attachments.isEmpty()) h.wrapped("源地图与物理版本下没有可附的位置事件。先保存试跑记录，或直接写文字与位置意见。",90,310,1080,20,SOFT,30);h.smallButton(70,642,390,36,"上一页",()->attachmentPage=Math.max(0,attachmentPage-1));h.smallButton(480,642,390,36,"下一页",()->attachmentPage=Math.min(pages-1,attachmentPage+1));}
    private void binding(HudRenderer h,FormulaGame app) {var entry=app.circuitFeedback.find(selected);if(entry==null) {page=0;return;}h.smallButton(70,180,390,34,"返回反馈库",()->page=0);h.smallButton(480,180,390,34,"解除本地对应",()->h.confirm("解除本地地图对应？","原始反馈、位置和本地处理记录保留。",()->{if(app.bindCircuitFeedback(entry,null)) page=0;}));var maps=new LinkedHashMap<String,CircuitDocument>();if(app.circuitEditor()!=null) maps.put(app.circuitEditor().document().id(),app.circuitEditor().document());for(var saved:app.circuits.all()) maps.putIfAbsent(saved.document().id(),saved.document());var targets=List.copyOf(maps.values());int pages=Math.max(1,(targets.size()+4)/5);targetPage=Math.min(targetPage,pages-1);
        for(int i=targetPage*5;i<Math.min(targets.size(),targetPage*5+5);i++) {var target=targets.get(i);h.smallButton(70,245+(i-targetPage*5)*66,1136,46,cut(target.name(),48)+" · 查看源版本差异后对应",()->{var lines=new ArrayList<String>();boolean same=CircuitFeedback.roadKey(target).equals(CircuitFeedback.roadKey(entry.feedback().source()));lines.add("源图："+entry.feedback().source().name()+"；目标："+target.name());lines.add(same?"道路与全部布景相同；地图身份可以不同。":"道路或布景不同；旧坐标仅作参考，需人工判断。不会投射到新路线。");CircuitRevisionDiff.compare(entry.feedback().source(),target).changes().stream().filter(change->change.kind()!=CircuitRevisionDiff.Kind.IDENTITY).forEach(change->lines.add(change.description()));lines.add("只保存本地对应关系，不修改或导入地图；源快照、原文、资格和成绩保持。");h.review("明确对应这张本地图？",lines,same?"保存本地对应":"作为旧版参考对应",()->{if(app.bindCircuitFeedback(entry,target)) page=0;});});}
        if(targets.isEmpty()) h.text("本机还没有地图；先返回编辑器新建或导入。",90,310,20,SOFT);h.smallButton(70,642,390,36,"上一页",()->targetPage=Math.max(0,targetPage-1));h.smallButton(480,642,390,36,"下一页",()->targetPage=Math.min(pages-1,targetPage+1));
    }
    private void importFile(HudRenderer h,FormulaGame app) {h.prompt("收到的玩家反馈 JSON 完整路径",app.store.directory().resolve("circuit-export-feedback.json").toAbsolutePath().toString(),512,text->{try {var value=CircuitFeedbackCodec.read(Path.of(text.strip()));h.review("收录玩家原始反馈？",original(value),"保存到本地反馈库",()->{if(app.receiveCircuitFeedback(value)) {selected=value.id();noteIndex=0;}});}catch(java.io.IOException | RuntimeException error) {app.notifyUser("反馈无法读取；请核对格式、大小、校验与文件路径。");}});}
    private void exportFile(HudRenderer h,FormulaGame app,Entry entry) {
        h.promptExportPath("导出玩家原文 JSON 完整路径",app.store.directory().resolve("circuit-export-feedback.json"),
            (path,replace)->app.exportCircuitFeedback(entry,path,replace));
    }
    private static ArrayList<String> original(CircuitFeedback value) {var lines=new ArrayList<String>();lines.add(value.title()+"；玩家："+value.author());lines.add("源地图："+value.source().name()+"；身份："+value.source().id()+"；物理："+value.physics());lines.add("源地图作者："+value.source().metadata().author()+"；修订："+value.source().metadata().revision());if(!value.summary().isBlank()) lines.add("整体说明："+value.summary());for(int i=0;i<value.notes().size();i++) {var note=value.notes().get(i);lines.add("意见 "+(i+1)+" · "+note.kind().title+"："+note.text());lines.add(location(note.location()));if(note.evidence()!=null) {var evidence=note.evidence();lines.add("附带观测："+evidence.kind().title+"／"+evidence.status()+"；车 "+(evidence.marker().car()+1)+"；"+String.format(Locale.ROOT,"%.1fs",evidence.marker().time())+"；"+evidence.marker().message());}}lines.add("这些是玩家意见和携带的观测，不能认证作者或地图资格；收录不会安装源地图。玩家原文交换不含本地处理；处理答复可另行明确导出。");return lines;}
    private static List<String> details(Entry entry) {var lines=original(entry.feedback());for(int i=0;i<entry.feedback().notes().size();i++) {var note=entry.feedback().notes().get(i);var state=entry.resolution(note.id());lines.add("本地处理 "+(i+1)+" · "+state.state().title+"："+(state.response().isEmpty()?"尚无答复":state.response()));}return lines;}
    private static String location(CircuitInspector.Location value) {return value==null?"未标记位置":String.format(Locale.ROOT,"源位置 X %.1f m，Z %.1f m",value.x(),value.z());}
    private static String cut(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";}
}
