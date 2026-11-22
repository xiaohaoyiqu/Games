package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.PartsDiscussionCodec;
import java.awt.Color;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Frozen original discussions, no implicit merge or card/catalog replacement. */
final class DiscussionComparisonHud {
    private final DiscussionRevisionsHud revisions=new DiscussionRevisionsHud();
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),MINT=new Color(0x4cebc1),AMBER=new Color(0xffbe72);
    private boolean open,choosing,parts;private int page;private PartsDiscussion a,b;private String bOrigin="";private PartsDiscussionComparison comparison;
    private List<PartsDiscussion> choices=List.of();private List<String> details=List.of();
    boolean showing() {return open;}
    void open(FormulaGame app,PartsDiscussion value) {a=Objects.requireNonNull(value);b=null;comparison=null;details=List.of();open=true;choose(app);}
    private void choose(FormulaGame app) {choices=app.partsDiscussions.entries();choosing=true;page=0;}
    void close() {open=false;a=b=null;comparison=null;choices=List.of();details=List.of();revisions.dismiss();}
    private void select(FormulaGame app,PartsDiscussion value,String origin) {
        b=value;bOrigin=origin;comparison=new PartsDiscussionComparison(a,b);var lines=new ArrayList<>(comparison.summary());lines.add("A 完整原文与背景");lines.addAll(PartsDiscussionHud.details(a,app.vehicleSpec.physicsVersion));lines.add("B 完整原文与背景");lines.addAll(PartsDiscussionHud.details(b,app.vehicleSpec.physicsVersion));details=List.copyOf(lines);choosing=false;parts=false;
    }
    void render(HudRenderer h,FormulaGame app,Consumer<PartsDiscussion> openThread,BiConsumer<FormulaGame.RevisionDiscussionReview,Boolean> navigate) {
        if(revisions.showing()) {revisions.render(h,app,navigate);return;}
        h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("讨论方案背景对照",60,99,30,INK,true);h.text("A "+cut(a.title(),30)+" · "+(a.equals(app.partsDiscussions.find(a.id()))?"本地原文仍对应":"已保留旧快照；本地原文改变"),60,143,16,SOFT);
        h.smallButton(1030,57,184,38,"返回原话题库",this::close);
        if(choosing) {chooser(h,app);return;}
        h.smallButton(60,157,1154,25,"修订声明 · 明确父子方向与两侧原留言",()->revisions.open(h,app,a,b,bOrigin.equals("本地话题")));
        h.smallButton(60,190,350,37,"查看 A 完整原文",()->h.review("A 原话题",PartsDiscussionHud.details(a,app.vehicleSpec.physicsVersion),"返回对照",()->{}));h.smallButton(430,190,350,37,"查看 B 完整原文",()->h.review("B 原话题",PartsDiscussionHud.details(b,app.vehicleSpec.physicsVersion),"返回对照",()->{}));h.smallButton(800,190,414,37,parts?"查看九项参数":"查看四槽选件与功能指纹",()->parts=!parts);
        h.text(comparison.relationship().title,60,266,18,MINT,true);String bStatus=bOrigin.equals("本地话题")?b.equals(app.partsDiscussions.find(b.id()))?"B 本地 · 原文仍对应":"B 本地已改变 · 保留旧快照":"B 外部文件 · 冻结原文";h.text(bStatus,760,266,15,SOFT);h.wrapped(comparison.vehiclePhysicsMatch()?comparison.trialNotice():"车型或物理不同；这里只比较原文背景，不能当作同条件测量。",60,295,1154,15,AMBER,23);
        if(parts) {
            for(int i=0;i<comparison.equipment().size();i++) {var row=comparison.equipment().get(i);int y=337+i*76;h.text(row.slot().title+" · "+row.status(),60,y,17,INK,true);h.text("A "+cut(row.a().part(),46),60,y+27,14,SOFT);h.text("B "+cut(row.b().part(),46),600,y+27,14,SOFT);h.text(row.declaredA()==null?"A 未内嵌完整定义":"A "+cut(row.declaredA().name(),30),60,y+52,13,AMBER);h.text(row.declaredB()==null?"B 未内嵌完整定义":"B "+cut(row.declaredB().name(),30),600,y+52,13,AMBER);}
        }else {
            h.text("原参数",60,336,15,SOFT);h.text("A",490,336,16,MINT,true);h.text("B",720,336,16,MINT,true);h.text("B − A（百分点）",950,336,15,SOFT);
            for(int i=0;i<comparison.parameters().size();i++) {var row=comparison.parameters().get(i);int y=369+i*32;h.text(row.field().title,60,y,17,INK);h.text(row.a()+"%",490,y,17,SOFT);h.text(row.b()+"%",720,y,17,SOFT);h.text(String.format(Locale.ROOT,"%+d",row.delta()),990,y,17,row.delta()==0?SOFT:AMBER);}
            var x=a.plan().plan().build();var y=b.plan().plan().build();h.text("试验点数上限：A "+x.budget()+"；B "+y.budget()+"。点数不是性能评分。",60,674,15,SOFT);
        }
        h.smallButton(60,705,350,38,"重新明确选择 B",()->choose(app));h.smallButton(430,705,350,38,"查看完整差异及两侧原文",()->h.review("两份冻结背景的完整对照",details,"返回对照",()->{}));h.smallButton(800,705,414,38,"打开 B 的本地话题",()->{var current=app.partsDiscussions.find(b.id());if(!b.equals(current)) {app.notifyUser("本地 B 已改变或未收录；冻结对照保持，请回话题库重新查看。");return;}var chosen=b;close();openThread.accept(chosen);});
        h.text("未合并留言、采用参数、安装部件或生成新测量；不按同名或时间认定版本关系。",60,764,13,AMBER);
    }
    private void chooser(HudRenderer h,FormulaGame app) {
        h.smallButton(60,190,550,37,"读取讨论文件作 B · 只预览",()->h.prompt("原讨论交换文件 JSON 完整路径","",512,text->{try {preview(h,app,PartsDiscussionCodec.read(Path.of(text.strip())));}catch(java.io.IOException | RuntimeException error) {app.notifyUser("讨论文件无法读取，请检查原类型、字段、大小和内容校验。");}}));h.smallButton(630,190,584,37,"明确刷新 B 候选列表",()->choose(app));
        h.wrapped(choices==app.partsDiscussions.entries()?"明确选择一份本地话题作为 B；相同卡身份和相同题名都不会自动决定版本先后。":"本地列表已改变；保留旧候选，先明确刷新再选择。",60,267,1154,16,AMBER,25);
        int pages=Math.max(1,(choices.size()+3)/4);page=Math.min(page,pages-1);
        for(int i=page*4;i<Math.min(choices.size(),page*4+4);i++) {var value=choices.get(i);int y=320+(i-page*4)*75;h.smallButton(60,y,1154,39,cut(value.title(),40)+" · "+cut(value.plan().plan().name(),26),()->{if(choices!=app.partsDiscussions.entries() || !value.equals(app.partsDiscussions.find(value.id()))) {app.notifyUser("候选话题已变化，请明确刷新后重选。");return;}select(app,value,"本地话题");});h.text("话题 "+value.id()+" · "+value.messages().size()+" 条原留言",72,y+63,13,SOFT);}
        if(choices.isEmpty()) h.wrapped("本地没有其他讨论；可从原讨论交换文件预览 B，文件只用于本页，未收录或合并。",60,355,1100,20,SOFT,30);
        h.smallButton(60,705,350,38,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages,480,729,17,SOFT);h.smallButton(800,705,414,38,"下一页",()->page=Math.min(pages-1,page+1));
    }
    private void preview(HudRenderer h,FormulaGame app,PartsDiscussion value) {
        var frozen=new PartsDiscussionComparison(a,value);var lines=new ArrayList<>(frozen.summary());lines.addAll(PartsDiscussionHud.details(value,app.vehicleSpec.physicsVersion));lines.add("确认只把已经解析的 B 用于只读对照，不再读取之后改变的输入文件，也不收录话题。");
        h.review("把这份原讨论作 B？",lines,"只用于本页对照",()->select(app,value,"外部文件冻结原文"));
    }
    private static String cut(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";}
}
