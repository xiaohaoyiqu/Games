package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.*;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Full laps attach to explicit original messages, independently of old short-trial citations. */
final class DiscussionLapsHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private final EquipmentLapsHud comparison=new EquipmentLapsHud();
    private boolean open,choosing;private int page;private String selected="";private PartsDiscussion target;private String message;
    private List<EquipmentLap> local=List.of();private List<EquipmentLapShare> received=List.of();private List<Choice> choices=List.of();
    private record Choice(EquipmentLap lap,EquipmentLapShare original,String relation,boolean eligible) {}
    boolean showing() {return open;}
    void open(PartsDiscussion target,String message) {this.target=target;this.message=message;open=true;choosing=false;page=0;}
    void render(HudRenderer h,FormulaGame app,Consumer<PartsDiscussion> openThread) {
        if(comparison.showing()) {h.discussionLapView(comparison);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("留言装备圈引用",60,99,30,INK,true);h.text("原留言、完整圈与实际微调分别保留；关联不认证作者、驾驶或收益。",60,141,16,SOFT);
        h.smallButton(914,57,300,38,"返回方案讨论",()->{open=false;choosing=false;});
        if(choosing) {chooser(h,app);return;}
        h.smallButton(60,190,350,38,"为所选留言选择装备圈",()->choose(app));h.smallButton(430,190,350,38,"读取独立装备圈引用",()->receive(h,app));h.smallButton(800,190,414,38,"修复后重新读取引用库",app.discussionLaps::reload);
        var entries=app.discussionLaps.entries();if(entries.stream().noneMatch(e->e.citation().id().equals(selected))) selected=entries.isEmpty()?"":entries.get(0).citation().id();var entry=app.discussionLaps.find(selected);
        int pages=Math.max(1,(entries.size()+3)/4);page=Math.min(page,pages-1);
        for(int i=page*4;i<Math.min(entries.size(),page*4+4);i++) {var row=entries.get(i);var value=row.citation();int y=264+(i-page*4)*76;h.smallButton(60,y,500,37,(selected.equals(value.id())?"● ":"")+cut(value.discussion().title(),25),()->selected=value.id());h.text(row.origin().title+" · "+value.status(app.partsDiscussions.find(value.discussion().id())).title,72,y+59,13,SOFT);}
        h.smallButton(60,593,180,32,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages+" · "+entries.size()+"/8",260,616,14,SOFT);h.smallButton(380,593,180,32,"下一页",()->page=Math.min(pages-1,page+1));
        if(entry==null) h.wrapped("返回话题选择原留言，再选择本机／收到的完整装备圈。引用独立保存；收到引用可先阅读，所附话题另行决定是否收录。",600,290,590,20,SOFT,32);
        else {var value=entry.citation();h.text(cut(value.author(),22)+" · 引用者声明",600,281,21,INK,true);h.wrapped("完整圈 "+value.share().id().substring(0,8)+" · 原留言与实际参数分别保留",600,320,590,17,AMBER,26);h.wrapped(cut(value.notes().isBlank()?value.discussion().message(value.message()).text():value.notes(),74),600,369,590,17,SOFT,27);
            h.smallButton(600,451,614,36,"查看完整原文、参数与来源",()->h.review("装备圈引用原文",details(value,app),"返回引用库",()->{}));h.smallButton(600,499,614,36,"只读比较所附完整圈",()->comparison.openReference(app,value.share()));
            h.smallButton(600,547,294,36,"分别收录所附话题",()->{var merge=app.partsDiscussions.preview(value.discussion());var lines=new ArrayList<>(details(value,app));lines.addAll(merge.issues());h.review("查看原话题合并",lines,merge.valid()?"只追加一致的原留言":"保留本地原文",()->{if(merge.valid() && app.receivePartsDiscussion(merge)) {open=false;openThread.accept(app.partsDiscussions.find(value.discussion().id()));}});});
            h.smallButton(914,547,300,36,"导出独立完整引用",()->export(h,app,entry));h.smallButton(600,595,614,32,"移除这份引用副本",()->h.confirm("移除此引用？","原话题、完整圈库与外部文件保持。",()->app.discussionLaps.remove(entry)));
        }
        if(app.discussionLaps.pending()!=null) {
            var pending=app.discussionLaps.pending();
            h.smallButton(60,664,350,38,"重试同份待存引用",()->{
                if(app.discussionLaps.pending()!=pending) {app.notifyUser("待存引用已变化，请重新选择。");return;}
                if(app.discussionLaps.retry()) {selected=pending.citation().id();app.notifyUser("同份引用已保存；原话题与记录库保持。");}
                else app.notifyUser(app.discussionLaps.warning());
            });
            h.smallButton(430,664,350,38,"明确取消待存引用",app.discussionLaps::cancelPending);
        }
        h.smallButton(800,664,414,38,"查看所选留言背景",()->h.review("引用目标",target==null?List.of("先返回话题并选择原留言。收到引用也可独立阅读，不会自动收录所附话题。") :List.of(target.title(),target.message(message)==null?"原留言暂不可用":target.message(message).text()),"关闭",()->{}));
        h.wrapped(app.discussionLaps.warning().isEmpty()?"引用库独立保存；不会安装装备、采用微调或写入本机圈／成绩。":app.discussionLaps.warning(),60,739,1154,14,AMBER,24);
    }
    private boolean targetCurrent(FormulaGame app) {return target!=null && target.message(message)!=null && target.equals(app.partsDiscussions.find(target.id()));}
    private void choose(FormulaGame app) {
        if(!targetCurrent(app)) {app.notifyUser("请返回话题，重新选择原留言后再引用。");return;}
        local=app.equipmentLaps.entries();received=app.receivedEquipmentLaps.entries();var next=new ArrayList<Choice>();for(var lap:local) next.add(choice(lap,null));for(var share:received) next.add(choice(share.recording(),share));choices=List.copyOf(next);choosing=true;page=0;
    }
    private Choice choice(EquipmentLap lap,EquipmentLapShare original) {try {return new Choice(lap,original,PartsDiscussionLap.correspondence(target,lap).title,true);}catch(IllegalArgumentException error) {return new Choice(lap,original,"装备功能、车型、物理或实际参数不对应",false);}}
    private boolean choicesCurrent(FormulaGame app) {return local==app.equipmentLaps.entries() && received==app.receivedEquipmentLaps.entries() && targetCurrent(app);}
    private void chooser(HudRenderer h,FormulaGame app) {
        h.smallButton(60,190,350,38,"返回引用库",()->{choosing=false;page=0;});h.smallButton(430,190,350,38,"明确刷新圈与留言背景",()->choose(app));h.smallButton(800,190,414,38,"读取独立完整装备圈文件",()->{if(!targetCurrent(app)) {app.notifyUser("原话题已变化，请返回重选留言。");return;}var frozen=target;var originalMessage=message;h.prompt("完整装备圈交换文件路径","",512,text->{try {select(h,app,frozen,originalMessage,EquipmentLapShareCodec.read(Path.of(text.strip())));}catch(java.io.IOException | RuntimeException error) {app.notifyUser("完整圈文件无效或不能对应此方案；原文保持。");}});});
        h.wrapped(choicesCurrent(app)?"先核对对应关系再选择；微调不同会保留原数值，不能当作同参数证据。":"圈库或话题已变化；原读数保留，请返回话题重选或明确刷新。",60,256,1154,16,AMBER,25);
        int pages=Math.max(1,(choices.size()+3)/4);page=Math.min(page,pages-1);
        for(int i=page*4;i<Math.min(choices.size(),page*4+4);i++) {var row=choices.get(i);int y=310+(i-page*4)*75;String relation=row.relation();boolean eligible=row.eligible();
            h.text((row.original()==null?"本机 ":"收到 ")+row.lap().id().substring(0,8)+" · "+String.format(Locale.ROOT,"%.3f s",row.lap().lap().duration()),60,y+15,18,INK,true);h.text(relation,60,y+44,15,eligible?SOFT:AMBER);
            boolean valid=eligible;h.smallButton(930,y-5,284,38,valid?"选择并预览完整背景":"不能作为本方案引用",()->{if(!valid) {app.notifyUser("不能把不同装备或未知微调来源宣称为本方案记录。");return;}if(!choicesCurrent(app)) {app.notifyUser("圈库或话题已变化，请重新选择。");return;}var frozen=target;var originalMessage=message;if(row.original()!=null) select(h,app,frozen,originalMessage,row.original());else {String author=app.store.profile.playerName;if(!PartsCatalog.text(author,40) || author.isBlank()) author="玩家";h.prompt("本机圈提供者署名声明",author,40,name->{try {select(h,app,frozen,originalMessage,new EquipmentLapShare(1,name,"",row.lap()));}catch(IllegalArgumentException error) {app.notifyUser("署名无效，原圈保持。");}});}});
        }
        if(choices.isEmpty()) h.wrapped("本机与收到库尚无完整圈。可以在计时挑战完成后明确收录，或读取玩家提供的完整圈文件。",60,354,1100,20,SOFT,32);
        h.smallButton(60,664,350,38,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,465,690,17,SOFT);h.smallButton(800,664,414,38,"下一页",()->page=Math.min(pages-1,page+1));
    }
    private void select(HudRenderer h,FormulaGame app,PartsDiscussion frozen,String originalMessage,EquipmentLapShare share) {
        PartsDiscussionLap.correspondence(frozen,share.recording());h.editText("引用说明（保留原圈，可换行）","","",320,notes->{try {var value=PartsDiscussionLap.capture(app.store.profile.playerName,frozen,originalMessage,share,notes);preview(h,app,value,frozen);}catch(IllegalArgumentException error) {app.notifyUser("引用说明或对应关系无效；原文保持。");}});
    }
    private void preview(HudRenderer h,FormulaGame app,PartsDiscussionLap value,PartsDiscussion expected) {h.review(expected==null?"只收录独立装备圈引用？":"保存这份留言装备圈引用？",details(value,app),"只保存独立引用",()->{boolean saved=expected==null?app.receiveDiscussionLap(value):app.saveDiscussionLap(value,expected);if(saved || app.discussionLaps.pending()!=null) {choosing=false;page=0;selected=value.id();}});}
    private void receive(HudRenderer h,FormulaGame app) {h.prompt("独立装备圈引用 JSON 路径","",512,text->{try {preview(h,app,PartsDiscussionLapCodec.read(Path.of(text.strip())),null);}catch(java.io.IOException | RuntimeException error) {app.notifyUser("引用文件无法读取，请核对独立类型、大小、原文对应和校验。");}});}
    private void export(HudRenderer h,FormulaGame app,PartsDiscussionLap.Entry entry) {
        var suggested=app.store.directory().toAbsolutePath().getParent().resolve("discussion-lap-export.json");
        h.review("导出原留言与完整圈引用？",details(entry.citation(),app),"填写独立导出路径",()->h.promptExportPath(
            "玩家资料目录外的引用文件路径",suggested,(path,replace)->app.exportDiscussionLap(entry,path,replace)));
    }
    static List<String> details(PartsDiscussionLap value,FormulaGame app) {
        var lines=new ArrayList<String>();lines.add("引用者署名声明："+value.author()+"；提供时间 "+value.createdAt());lines.add("话题："+value.discussion().title()+"；方案："+value.discussion().plan().plan().name());lines.add("原留言 "+value.message()+"；署名 "+value.discussion().message(value.message()).author());lines.add(value.discussion().message(value.message()).text());lines.add("对应关系："+value.correspondence().title);lines.addAll(value.parameterDetails());lines.add("引用说明："+value.notes());lines.add("本地原文对应："+value.status(app.partsDiscussions.find(value.discussion().id())).title);lines.addAll(EquipmentLapsHud.detailsLines(value.share().recording(),value.share()));lines.add("接收仅写独立引用库；所附原话题可另行预览合并。完整圈不会写入本机或收到库，不认证真实性、因果或收益。");return List.copyOf(lines);
    }
    private static String cut(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";}
}
