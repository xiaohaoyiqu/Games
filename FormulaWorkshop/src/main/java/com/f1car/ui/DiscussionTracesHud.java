package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.*;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Independent, explicitly saved citations. A canceled file/range dialog never edits the message. */
final class DiscussionTracesHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private final TuningTraceHud chart=new TuningTraceHud();
    private boolean open,viewing;private int page;private String selected="";
    private PartsDiscussion target;private String message;
    private PartsDiscussionTrace shown;private TrialTraceShare excerpt;
    boolean showing() {return open;}
    void open(PartsDiscussion target,String message) {this.target=target;this.message=message;open=true;viewing=false;}
    void render(HudRenderer h,FormulaGame app,Consumer<PartsDiscussion> openThread) {
        h.clearButtons();h.panel(28,24,1224,752,new Color(10,29,39,250));h.text("留言曲线引用",60,102,30,INK,true);h.text("原留言、单组记录和引用范围分别保留；署名与观测来源为提供者声明。",60,143,16,SOFT);
        h.smallButton(914,57,300,38,"返回方案讨论",()->{open=false;viewing=false;shown=null;excerpt=null;});
        var entries=app.discussionTraces.entries();if(entries.stream().noneMatch(entry->entry.attachment().id().equals(selected))) selected=entries.isEmpty()?"":entries.get(0).attachment().id();var entry=app.discussionTraces.find(selected);
        if(viewing && entry!=null) {if(shown!=entry.attachment()) {shown=entry.attachment();excerpt=shown.excerpt();}h.smallButton(60,214,500,34,"返回引用库",()->viewing=false);h.text("引用 "+(shown.first()+1)+"–"+(shown.last()+1)+" 点 · "+shown.correspondence().title,600,239,16,AMBER);h.text(cut(shown.trace().report().conditions().trackName(),36)+" · "+shown.trace().report().conditions().exercise().title,60,292,19,INK);h.discussionTraceView(chart,excerpt);return;}
        h.smallButton(60,214,244,34,"引用曲线到所选留言",()->attach(h,app));h.smallButton(316,214,244,34,"接收独立引用文件",()->receive(h,app));h.smallButton(600,214,294,34,"重新读取引用库",app.discussionTraces::reload);h.smallButton(914,214,300,34,"所选留言与操作说明",()->h.review("曲线引用的范围",target==null?List.of("先返回话题并选择一条留言，再为它引用曲线。","接收引用不会自动收录其中的话题或写入本机报告。") :List.of(target.title(),target.message(message)==null?"原留言暂不可用":target.message(message).text(),"新引用独立保存；原留言和话题背景保持。"),"关闭",()->{}));
        int pages=Math.max(1,(entries.size()+4)/5);page=Math.max(0,Math.min(page,pages-1));for(int i=page*5;i<Math.min(entries.size(),page*5+5);i++) {var item=entries.get(i);var value=item.attachment();int y=265+(i-page*5)*65;h.smallButton(60,y,500,35,(value.id().equals(selected)?"● ":"")+cut(value.discussion().title(),24),()->selected=value.id());h.text(item.origin().title+" · "+value.status(app.partsDiscussions.find(value.discussion().id())).title,72,y+56,13,SOFT);}
        h.smallButton(60,628,180,32,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages+" · "+entries.size()+"/16",260,650,14,SOFT);h.smallButton(380,628,180,32,"下一页",()->page=Math.min(pages-1,page+1));
        if(entry==null) h.wrapped("可从独立试车曲线文件引用一组记录；选择实际采样点范围，再查看并保存。收到的引用可先阅读，再单独收录所附话题。",600,300,600,19,SOFT,32);
        else {var value=entry.attachment();h.text(cut(value.author(),24)+" · 引用者声明",600,286,22,INK,true);h.wrapped(cut(value.notes().isBlank()?value.discussion().message(value.message()).text():value.notes(),82),600,329,590,17,SOFT,27);
            h.smallButton(600,430,614,36,"查看固定曲线与引用范围",()->viewing=true);h.smallButton(600,478,294,36,"查看完整引用说明",()->h.review("曲线引用详情",details(value,app),"关闭",()->{}));h.smallButton(914,478,300,36,"分别收录所附话题",()->{var merge=app.partsDiscussions.preview(value.discussion());var lines=new ArrayList<>(details(value,app));lines.addAll(merge.issues());h.review("查看所附话题合并",lines,merge.valid()?"追加一致的原留言":"保留本地原文",()->{if(merge.valid() && app.receivePartsDiscussion(merge)) {open=false;openThread.accept(app.partsDiscussions.find(value.discussion().id()));}});});
            h.smallButton(600,528,294,36,"导出独立引用",()->export(h,app,entry));h.smallButton(914,528,300,36,"移除本地引用",()->h.confirm("移除此引用？","原话题、原留言、报告和外部文件保留。",()->{if(!app.discussionTraces.remove(entry)) app.notifyUser(app.discussionTraces.warning());}));
        }
        h.text("接收与引用不安装部件、不写本机测量，也不证明因果或改装收益。",60,718,15,AMBER);if(!app.discussionTraces.warning().isEmpty()) h.text(cut(app.discussionTraces.warning(),75),60,751,13,AMBER);
    }
    private void attach(HudRenderer h,FormulaGame app) {
        var before=target;var targetMessage=message;if(before==null || before.message(targetMessage)==null || !before.equals(app.partsDiscussions.find(before.id()))) {app.notifyUser("请返回话题选择当前原留言，再引用曲线。");return;}
        h.prompt("选择独立试车曲线文件的完整路径","",512,path->{try {var source=TrialTraceCodec.read(Path.of(path.strip()));var eligible=new ArrayList<Integer>();for(var trace:source.traces()) try {PartsDiscussionTrace.correspondence(before,source,trace.index());eligible.add(trace.index());}catch(IllegalArgumentException ignored) {}
            if(eligible.isEmpty()) {h.review("曲线与方案不对应",List.of("没有一组记录同时满足方案参数、物理版本及已记录的部件来源。","原话题、原留言与车库保持；请重新选择对应的曲线文件。"),"关闭",()->{});return;}
            h.editNumber("选择记录组（1–6）；可用组："+eligible.stream().map(index->Integer.toString(index+1)).toList(),eligible.get(0)+1,1,6," 组",value->{int index=value-1;if(!eligible.contains(index)) {app.notifyUser("这一组没有对应的曲线或方案条件，请选择可用组。");return false;}selectRange(h,app,before,targetMessage,source,index);return true;});
        }catch(java.io.IOException | RuntimeException error) {app.notifyUser("曲线文件无法读取，请核对类型、路径和校验。");}});
    }
    private void selectRange(HudRenderer h,FormulaGame app,PartsDiscussion before,String message,TrialTraceShare source,int index) {
        int count=source.find(index).samples().size();h.editNumber("引用起点（采样点序号，保留原时间）",1,1,count," 点",first->{h.editNumber("引用终点（包含此点）",count,first,count," 点",last->{h.editText("曲线引用备注（可留空）","","",320,notes->{var value=PartsDiscussionTrace.capture(app.store.profile.playerName,before,message,source,index,first-1,last-1,notes);savePreview(h,app,value,before);});return true;});return true;});
    }
    private void savePreview(HudRenderer h,FormulaGame app,PartsDiscussionTrace value,PartsDiscussion before) {
        if(app.discussionTraces.requiresReload()) {
            h.review("保留这份曲线引用",List.of(app.discussionTraces.warning(),"重读资料库后，可重新核对这份原始引用。"),
                "重读后核对",()->{app.discussionTraces.reload();savePreview(h,app,value,before);});
            return;
        }
        h.review("保存独立曲线引用？",details(value,app),"只保存引用",()->{
            if(app.saveDiscussionTrace(value,before)) {selected=value.id();viewing=false;}
            else savePreview(h,app,value,before);
        });
    }
    private void receive(HudRenderer h,FormulaGame app) {h.prompt("接收讨论曲线引用文件的完整路径","",512,path->{try {var value=PartsDiscussionTraceCodec.read(Path.of(path.strip()));receivePreview(h,app,value,app.discussionTraces.find(value.id()));}catch(java.io.IOException | RuntimeException error) {app.notifyUser("引用文件无法读取，请核对独立类型、范围、条件和校验。");}});}
    private void receivePreview(HudRenderer h,FormulaGame app,PartsDiscussionTrace value,PartsDiscussionTrace.Entry before) {
        if(app.discussionTraces.requiresReload()) {
            h.review("保留收到的曲线引用",List.of(app.discussionTraces.warning(),"重读资料库后，可重新核对这份独立文件的内容。"),
                "重读后核对",()->{app.discussionTraces.reload();receivePreview(h,app,value,app.discussionTraces.find(value.id()));});
            return;
        }
        h.review("只收录独立引用？",details(value,app),"只收录引用",()->{
            if(app.receiveDiscussionTrace(value,before)) {selected=value.id();viewing=false;}
            else receivePreview(h,app,value,before);
        });
    }
    private void export(HudRenderer h,FormulaGame app,PartsDiscussionTrace.Entry entry) {
        h.promptExportPath("曲线引用导出文件完整路径",app.store.directory().resolve("parts-export-discussion-trace.json"),
            (path,replace)->app.exportDiscussionTrace(entry,path,replace));
    }
    static List<String> details(PartsDiscussionTrace value,FormulaGame app) {
        var trace=value.trace().traces().get(0);var report=value.trace().report();var conditions=report.conditions();var first=trace.samples().get(value.first());var last=trace.samples().get(value.last());var lines=new ArrayList<String>();
        lines.add("引用者（声明）："+value.author()+"；记录时间（提供值）"+value.createdAt()+"。原曲线署名："+value.trace().author());lines.add("话题："+value.discussion().title()+"；留言："+value.message());lines.add(value.discussion().message(value.message()).text());
        lines.add("报告 "+report.id()+"；第 "+(trace.index()+1)+" 组 "+report.order().get(trace.index())+"；"+value.correspondence().title);lines.add("路线 "+conditions.trackName()+" · "+conditions.trackId()+"；几何 "+conditions.geometry());lines.add("物理 "+conditions.physics()+"；操作 "+conditions.controls()+"；项目 "+conditions.exercise().title);
        lines.add(String.format(Locale.ROOT,"引用第 %d–%d 点 · 原时间 %.3f–%.3f s；完整组 %d 点，最大间隔 %.0f ms。",value.first()+1,value.last()+1,first.seconds(),last.seconds(),trace.samples().size(),trace.maximumGap()*1000));
        lines.add(trace.positions()==null?"此组没有位置记录，只能引用时间与操作读数。":"位置为原记录的路线投影；失配、倒退与采样间隔仍保留，不补造轨迹。");lines.add("本地对应："+value.status(app.partsDiscussions.find(value.discussion().id())).title);lines.add("备注："+value.notes());lines.add("仅携带所选一组完整曲线；引用范围为标记。所附话题与曲线保持原格式，分别决定是否收录。不是真实性、因果或性能认证。");return List.copyOf(lines);
    }
    private static String cut(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";}
}
