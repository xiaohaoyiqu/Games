package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.nio.file.*;
import java.util.*;

/** Local geometric design workflow. Navigation retains the draft; imports never apply it. */
final class LiveryHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private static final Rectangle CANVAS=new Rectangle(854,182,320,320);
    private static final int[] COLORS={0xf5d784,0xffffff,0xff654f,0x17cfb1,0x789bff,0xff8fc5,0x142633,0xa9d5e7};
    private static final String[] COLOR_NAMES={"金","白","珊瑚","薄荷","蓝","粉","深色","雾蓝"};
    private boolean library,showRegion=true;private int page;private String selected="";private CarLivery incoming,incomingCopy;
    boolean libraryOpen() {return library;}
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        h.panel(22,22,1236,756,new Color(12,28,40,249));h.text(library?"涂装作品库":"像素涂装创作",42,72,31,INK,true);h.text("底色沿用车库 · 自制层在下、车号与已解锁纪念图案在上 · 只影响外观",42,103,15,SOFT);
        if(library) library(h,app);else editor(h,g,app);
        h.smallButton(854,666,366,27,app.liveryEditing.status(),()->DraftRecoveryHud.review(h,app,app.liveryEditing));
        h.smallButton(1030,714,195,40,"返回车库",app::backFromLiveryEditor);
        if(!app.liveries.warning().isEmpty()) h.text(app.liveries.warning(),42,780,13,GOLD);
    }
    boolean click(FormulaGame app,float x,float y) {if(library || !CANVAS.contains(x,y) || app.liveryEditor()==null || !app.liveryEditor().value().vehicle().equals(app.vehicleSpec.id)) return false;app.liveryEditor().place((x-CANVAS.getCenterX())/CANVAS.width*1000,(y-CANVAS.getCenterY())/CANVAS.height*1000);return true;}
    private void editor(HudRenderer h,Graphics2D g,FormulaGame app) {
        var e=app.liveryEditor();if(e==null) return;var value=e.value();
        h.smallButton(42,120,360,33,"名称："+cut(value.name(),19),()->h.editText("作品名称",value.name(),"我的涂装",40,s->e.card(s,value.author(),value.notes())));
        h.smallButton(416,120,360,33,"署名："+cut(value.author(),19),()->h.editText("作品署名（声明）",value.author(),"",40,s->e.card(value.name(),s,value.notes())));
        h.smallButton(790,120,430,33,"作品说明 · "+value.notes().length()+" 字符",()->h.editText("作品说明（单行）",value.notes(),"",320,s->e.card(value.name(),value.author(),s)));
        h.text("图层从上方到下方依次覆盖绘制",42,178,14,SOFT);
        for(int i=0;i<value.layers().size();i++) {int index=i;var l=value.layers().get(i);h.smallButton(42,190+i*28,330,25,(i==e.selected()?"● ":"")+(i+1)+" · "+l.shape().title+" / "+l.target().title,()->e.select(index));}
        if(value.layers().isEmpty()) h.wrapped("添加一种形状开始设计。点选预览可移动当前层，离开或正常关闭后可继续草稿。",42,244,320,17,SOFT,28);
        for(int i=0;i<CarLivery.Shape.values().length;i++) {var shape=CarLivery.Shape.values()[i];h.smallButton(42+i*114,560,106,33,"＋"+shape.title,()->safe(app,()->e.add(shape)));}
        h.smallButton(42,606,158,33,"复制当前层",()->safe(app,e::duplicate));h.smallButton(214,606,158,33,"移除当前层",e::remove);
        h.smallButton(42,646,158,33,"前移绘制 ↑",()->e.reorder(-1));h.smallButton(214,646,158,33,"后移绘制 ↓",()->e.reorder(1));
        var l=e.layer();if(l!=null) {
            row(h,200,"横向",l.x()/10+"%",d->e.update(v->v.position(clamp(v.x()+d*20,-400,400),v.y())));
            row(h,250,"纵向",l.y()/10+"%",d->e.update(v->v.position(v.x(),clamp(v.y()+d*20,-400,400))));
            row(h,300,"宽度",l.width()/10+"%",d->e.update(v->v.size(clamp(v.width()+d*20,20,900),v.height())));
            row(h,350,"高度",l.height()/10+"%",d->e.update(v->v.size(v.width(),clamp(v.height()+d*20,20,900))));
            row(h,400,"旋转",l.rotation()+"°",d->e.update(v->v.rotate(v.rotation()+d*5)));
            row(h,450,"不透明度",l.opacity()+"%",d->e.update(v->v.alpha(clamp(v.opacity()+d*5,10,100))));
            h.smallButton(400,514,370,35,"左右镜像："+(l.mirror()==1?"开启":"关闭"),()->e.update(CarLivery.Layer::mirrored));
            h.smallButton(400,562,370,35,"颜色 #"+String.format(Locale.ROOT,"%06X",l.rgb())+" · 输入六位色号",()->h.prompt("六位 RGB 色号",String.format(Locale.ROOT,"%06X",l.rgb()),7,s->safe(app,()->{String hex=s.strip().replaceFirst("^#","");if(!hex.matches("[a-fA-F0-9]{6}")) throw new IllegalArgumentException("请输入六位 RGB 色号，例如 17CFB1。");e.update(v->v.color(Integer.parseInt(hex,16)));})));
            h.smallButton(400,606,370,32,"部位："+l.target().title+" · 切换",()->e.update(v->v.target(CarLivery.Target.values()[(v.target().ordinal()+1)%CarLivery.Target.values().length])));
        }
        h.smallButton(400,646,176,33,"撤销 · "+e.undoSize(),e::undo);h.smallButton(594,646,176,33,"重做"+(e.canRedo()?" · 可用":""),e::redo);
        g.setColor(new Color(25,43,58));g.fillRect(CANVAS.x,CANVAS.y,CANVAS.width,CANVAS.height);if(value.vehicle().equals(app.vehicleSpec.id)) h.liveryPreview(CANVAS.x,CANVAS.y,CANVAS.width,value,0);else h.text("车型不匹配，暂不预览",875,342,18,GOLD);g.setColor(SOFT);g.drawRect(CANVAS.x,CANVAS.y,CANVAS.width,CANVAS.height);
        if(l!=null && showRegion && value.vehicle().equals(app.vehicleSpec.id)) h.paintRegionOutline(CANVAS.x,CANVAS.y,CANVAS.width,l.target());
        h.text("↑ 车头 · 点选移动",854,172,15,MINT);h.smallButton(1040,154,180,25,"部位范围："+(showRegion?"显示":"隐藏"),()->showRegion=!showRegion);
        for(int i=0;i<COLORS.length;i++) {int color=COLORS[i],x=854+i%4*84,y=522+i/4*36;h.smallButton(x,y,78,30,COLOR_NAMES[i],()->e.update(v->v.color(color)));g.setColor(new Color(color));g.fillRect(x+4,y+2,70,3);}
        int missing=value.unsupportedLayers(h.paintRegions());h.wrapped(missing>0?missing+" 层缺少匹配部位蒙版，暂不显示；仍可保存草稿。":l!=null && l.target()!=CarLivery.Target.ALL?"范围线随部位固定，图层移动／镜像仍在范围内裁切。":"整车模式覆盖不透明像素，包括轮胎；可为当前层选择部位。",854,617,340,14,missing>0?GOLD:SOFT,24);
        h.text((e.dirty()?"草稿未保存":"已保存")+" · "+value.layers().size()+" / 12 层"+(value.equals(app.liveries.active())?" · 当前采用":""),42,704,14,e.dirty()?GOLD:MINT);
        h.smallButton(42,714,170,40,"保存当前作品",app::saveLiveryDraft);h.smallButton(226,714,170,40,"采用已保存版本",app::applyLiveryDraft);h.smallButton(410,714,170,40,"导出当前草稿",()->export(h,app,value));h.smallButton(594,714,170,40,"作品库与导入",()->library=true);h.smallButton(778,714,230,40,"新身份另存副本",()->{app.copyLiveryDraft();});
    }
    private void library(HudRenderer h,FormulaGame app) {
        h.smallButton(42,126,178,35,"继续当前草稿",()->library=false);h.smallButton(232,126,178,35,"新建空白作品",()->replace(h,app,()->{app.newLiveryDraft();library=false;}));h.smallButton(422,126,178,35,h.liveryImportReading()?"读取中 · 重选文件":"导入作品文件",()->importPath(h,app));h.smallButton(612,126,178,35,"重新读取作品库",app.liveries::reload);h.smallButton(802,126,418,35,"恢复底色与原纪念装饰",()->h.confirm("恢复基础外观？","作品库与当前草稿保留。",app::resetLivery));
        var rows=app.liveries.entries();int pages=Math.max(1,(rows.size()+7)/8);page=Math.min(page,pages-1);if(rows.stream().noneMatch(v->v.id().equals(selected))) selected=rows.isEmpty()?"":rows.get(page*8).id();
        for(int i=page*8;i<Math.min(rows.size(),page*8+8);i++) {var value=rows.get(i);int y=190+(i-page*8)*55;h.smallButton(42,y,390,34,(value.id().equals(selected)?"● ":"")+cut(value.name(),24),()->selected=value.id());h.text(cut(value.author(),20)+" · "+value.layers().size()+" 层",50,y+49,13,SOFT);}
        h.smallButton(42,646,185,34,"上一页",()->{page=Math.max(0,page-1);selected="";});h.smallButton(245,646,185,34,"下一页",()->{page=Math.min(pages-1,page+1);selected="";});h.text(rows.size()+" / 16 份 · "+(page+1)+" / "+pages+" 页",42,704,14,SOFT);
        var value=app.liveries.find(selected);if(value!=null) {
            h.text(cut(value.name(),23),478,216,24,INK,true);h.text("署名声明："+cut(value.author(),20),478,251,16,SOFT);h.text("车型："+cut(value.vehicle(),22)+" · "+value.layers().size()+" 层",478,285,16,SOFT);
            h.wrapped(value.notes().isBlank()?"作者尚未填写说明。":cut(value.notes(),60),478,321,350,16,INK,26);
            h.smallButton(478,414,340,32,"查看完整名称、署名、来源和说明",()->h.review("作品原文",List.of(value.name(),"署名声明："+value.author(),"车型："+value.vehicle(),"来源声明："+(value.sourceId().isEmpty()?"未声明":value.sourceId()),value.notes()),"返回作品库",()->{}));
            if(value.vehicle().equals(app.vehicleSpec.id)) h.liveryPreview(900,180,260,value,0);else h.text("车型不匹配，暂不预览",875,342,17,GOLD);
            h.text(value.equals(app.liveries.active())?"当前采用这个版本":"库中的作品；采用需另选",478,468,16,MINT);
            h.smallButton(478,494,340,37,"编辑这个已保存版本",()->replace(h,app,()->{if(app.loadLivery(value)) library=false;}));h.smallButton(836,494,370,37,"导出这个版本",()->export(h,app,value));
            h.smallButton(478,544,340,37,"采用这个已保存版本",()->app.applyLivery(value));
            h.smallButton(836,544,370,37,"移除库副本 · 当前采用保持",()->h.confirm("移除库副本？","当前采用的冻结版本与编辑草稿保留。",()->{if(!app.liveries.remove(value)) app.notifyUser(app.liveries.warning());}));
        }
        if(incoming!=null) {h.smallButton(478,606,340,36,"查看待收录作品",()->reviewIncoming(h,app));h.smallButton(836,606,370,36,"收到内容另存新身份副本",()->{if(incomingCopy==null) incomingCopy=incoming.copy();if(app.receiveLivery(incomingCopy)) {selected=incomingCopy.id();incoming=incomingCopy=null;}});}
    }
    private void importPath(HudRenderer h,FormulaGame app) {
        h.cancelCreationImport();
        var editor=app.liveryEditor();
        long revision=editor.revision();
        String selection=selected;
        var work=app.liveries.find(selection);
        h.prompt("导入涂装 JSON 文件路径",app.store.directory().resolve("livery.json").toAbsolutePath().toString(),512,s->{
            try {
                h.previewLiveryImport(Path.of(s.strip()),()->app.liveryEditor()==editor && editor.revision()==revision
                        && selected.equals(selection) && Objects.equals(app.liveries.find(selection),work));
            } catch(InvalidPathException error) {app.notifyUser("涂装文件路径无效。");}
        });
    }
    void showImported(HudRenderer h,FormulaGame app,CarLivery value) {
        incoming=value;
        incomingCopy=null;
        reviewIncoming(h,app);
    }
    private void reviewIncoming(HudRenderer h,FormulaGame app) {var value=incoming;var paragraphs=new ArrayList<String>();paragraphs.add(value.name()+"\n署名声明："+value.author()+"\n车型："+value.vehicle()+"\n来源声明："+(value.sourceId().isEmpty()?"未声明":value.sourceId())+"\n"+value.notes());paragraphs.add("仅收录作品，不采用外观，不改变底色、性能或纪念图案。已有同身份不同内容时保留原文，可另存新身份副本。部位层需要当前素材提供对应蒙版。");for(int i=0;i<value.layers().size();i++) {var l=value.layers().get(i);paragraphs.add("层 "+(i+1)+" · "+l.shape().title+" / "+l.target().title+" · #"+String.format(Locale.ROOT,"%06X",l.rgb())+"\n位置 "+l.x()/10d+"% / "+l.y()/10d+"% · 大小 "+l.width()/10d+"% × "+l.height()/10d+"%\n旋转 "+l.rotation()+"° · 不透明 "+l.opacity()+"% · 镜像 "+(l.mirror()==1?"开启":"关闭"));}h.review("收录涂装作品",paragraphs,"收录，不采用",()->{if(app.receiveLivery(value)) {selected=value.id();incoming=incomingCopy=null;}});}
    private void export(HudRenderer h,FormulaGame app,CarLivery value) {
        String suggestion=app.store.directory().resolve("livery-export-"+value.id()+".json").toAbsolutePath().toString();
        h.cancelLiveryExportReview();
        boolean fromLibrary=library;
        var editor=app.liveryEditor();
        h.prompt("导出涂装 JSON 文件路径",suggestion,512,s->{
            try {
                h.prepareLiveryExport(value,Path.of(s.strip()),()->library==fromLibrary
                        && (fromLibrary?selected.equals(value.id()):app.liveryEditor()==editor));
            }catch(RuntimeException error) {app.notifyUser("导出路径无效。");}
        });
    }
    private void replace(HudRenderer h,FormulaGame app,Runnable action) {if(app.liveryEditor()!=null && app.liveryEditor().dirty()) h.confirm("替换当前草稿？","尚未保存的编辑会被替换，作品库与当前采用外观保持。",action);else action.run();}
    private static void row(HudRenderer h,int y,String title,String value,java.util.function.IntConsumer change) {h.text(title,400,y+23,18,INK);h.smallButton(560,y,40,32,"−",()->change.accept(-1));h.text(value,622,y+23,18,MINT);h.smallButton(730,y,40,32,"＋",()->change.accept(1));}
    private static void safe(FormulaGame app,Runnable action) {try {action.run();}catch(IllegalArgumentException error) {app.notifyUser(error.getMessage());}}
    private static int clamp(int value,int low,int high) {return Math.max(low,Math.min(high,value));}
    private static String cut(String value,int limit) {return value.codePointCount(0,value.length())>limit?value.substring(0,value.offsetByCodePoints(0,limit-1))+"…":value;}
}
