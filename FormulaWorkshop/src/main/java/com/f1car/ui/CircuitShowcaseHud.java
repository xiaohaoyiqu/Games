package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.*;
import java.util.List;

/** Presentation page uses the current editor draft or selected saved map without loading or saving it. */
final class CircuitShowcaseHud {
    private String id;
    private CircuitShowcase.Format format=CircuitShowcase.Format.LANDSCAPE;
    private CircuitShowcase.Palette palette=CircuitShowcase.Palette.MINT;
    private final CircuitShowcase.Cache snapshots=new CircuitShowcase.Cache();
    private final CircuitShowcaseRenderer.Cache images=new CircuitShowcaseRenderer.Cache();
    boolean showing() {return id!=null;}
    void open(String id) {close();this.id=id;}
    boolean close() {boolean active=showing();id=null;snapshots.clear();images.clear();return active;}
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        h.text("地图封面与作品说明",60,160,24,new Color(0x4cebc1),true);h.smallButton(1000,126,212,36,"返回地图卡",()->close());var document=app.circuitShowcaseDocument(id);
        if(document==null) {h.text("这张地图已不在当前草稿或地图库中。",60,230,20,Color.WHITE);return;}
        try {
            var editor=app.circuitEditor();
            var physics=app.vehicleSpec.physicsVersion;
            var snapshot=editor!=null && editor.document()==document
                    ?snapshots.get(editor,physics,format,palette)
                    :snapshots.get(document,app.circuitShowcaseProof(id),physics,format,palette);
            var image=images.get(snapshot);var area=format==CircuitShowcase.Format.LANDSCAPE?new Rectangle(60,194,768,432):new Rectangle(180,194,432,432);g.drawImage(image,area.x,area.y,area.width,area.height,null);
            h.smallButton(860,205,352,38,format.title,()->format=CircuitShowcase.Format.values()[(format.ordinal()+1)%CircuitShowcase.Format.values().length]);h.smallButton(860,255,352,38,"配色："+palette.title,()->palette=CircuitShowcase.Palette.values()[(palette.ordinal()+1)%CircuitShowcase.Palette.values().length]);h.smallButton(860,305,352,38,"查看完整作品说明",()->h.review("完整作品说明",List.of(snapshot.text()),"关闭",()->{}));h.smallButton(860,365,352,42,"导出布局封面 PNG",()->export(h,app,snapshot,true));h.smallButton(860,420,352,42,"导出完整说明 TXT",()->export(h,app,snapshot,false));
            h.wrapped("封面根据当前选中地图绘制。当前编辑图包含未保存修改；其他地图使用已保存内容。",860,506,340,17,new Color(0xa2babd),27);h.wrapped("两种文件分别保存。分享可驾驶内容仍使用地图 JSON 或赛事包。",860,595,340,16,new Color(0xa2babd),25);h.text("封面中的简介为摘要；完整说明保留所有简介与修订文字。关闭预览不保存或切换草稿。",60,687,16,new Color(0xa2babd));
        }catch(IllegalArgumentException error) {h.wrapped("地图文字含不完整字符，暂不能生成作品封面。请返回地图卡检查名称、署名、简介与修订。",60,240,1050,20,Color.WHITE,32);}
    }
    private void export(HudRenderer h,FormulaGame app,CircuitShowcase snapshot,boolean png) {
        String suffix=png?"png":"txt";
        h.promptReviewedExportPath(png?"封面 PNG 完整路径":"作品说明 TXT 完整路径",
                app.store.directory().resolve("circuit-export-showcase."+suffix),
                destination->app.exportReviewedCircuitShowcase(snapshot,destination,png));
    }
}
