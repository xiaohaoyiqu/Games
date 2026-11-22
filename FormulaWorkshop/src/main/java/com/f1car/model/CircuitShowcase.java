package com.f1car.model;

import java.util.*;

/** A local presentation snapshot. Images and text carry no importable proof or driving results. */
public final class CircuitShowcase {
    public enum Format {
        LANDSCAPE("横版 1280×720",1280,720),SQUARE("方版 960×960",960,960);
        public final String title;public final int width,height;
        Format(String title,int width,int height) {this.title=title;this.width=width;this.height=height;}
    }
    public enum Palette {
        MINT("薄荷",0x06141d,0x4cebc1),BLUE("海蓝",0x101728,0x83bbff),AMBER("暖橙",0x201b1c,0xffc477);
        public final String title;public final int background,accent;
        Palette(String title,int background,int accent) {this.title=title;this.background=background;this.accent=accent;}
    }
    private final CircuitDocument document;
    private final String physics;
    private final Format format;
    private final Palette palette;
    private final CircuitInspector.Report report;
    private final CircuitCard.Status status;
    private CircuitShowcase(CircuitDocument document,CircuitTrial.Proof proof,String physics,Format format,Palette palette,CircuitInspector.Report report) {
        this.document=Objects.requireNonNull(document);this.format=Objects.requireNonNull(format);this.palette=Objects.requireNonNull(palette);this.report=Objects.requireNonNull(report);
        this.physics=physics;status=CircuitCard.status(document,proof,physics,report);
    }
    private static void validate(CircuitDocument document,String physics,Format format,Palette palette) {
        Objects.requireNonNull(document);Objects.requireNonNull(format);Objects.requireNonNull(palette);var card=document.metadata();if(!PartsCatalog.text(document.name(),60) || !PartsCatalog.text(card.author(),40) || !PartsCatalog.text(card.description(),240) || !PartsCatalog.text(card.revision(),120) || !PartsCatalog.text(physics,120) || physics.isBlank()) throw new IllegalArgumentException("Invalid showcase text");
    }
    public static CircuitShowcase capture(CircuitDocument document,CircuitTrial.Proof proof,String physics,Format format,Palette palette) {validate(document,physics,format,palette);return new CircuitShowcase(document,proof,physics,format,palette,CircuitInspector.inspect(document));}
    public CircuitDocument document() {return document;}
    public String physics() {return physics;}
    public Format format() {return format;}
    public Palette palette() {return palette;}
    public CircuitInspector.Report report() {return report;}
    public CircuitCard.Status status() {return status;}
    public boolean matches(CircuitDocument current,CircuitTrial.Proof proof,String physics) {return document.equals(current) && this.physics.equals(physics) && status==CircuitCard.status(current,proof,physics,report);}
    public String lengthLabel() {return report.track()==null?"路线长度：尚不能计算":"路线长度：约 "+Math.round(report.track().length)+" 米";}
    public String authorLabel() {return document.metadata().author().isBlank()?"作者：未署名":"作者："+document.metadata().author();}
    public String statusLabel() {return "导出时本机状态："+status.title;}
    public String text() {
        var card=document.metadata();var lines=new ArrayList<String>();Collections.addAll(lines,"Formula Workshop · 地图作品说明",document.name(),authorLabel(),"作者难度标签："+card.difficulty().title,"简介：",card.description().isBlank()?"未填写":card.description(),"修订备注：",card.revision().isBlank()?"未填写":card.revision(),lengthLabel(),"路宽："+document.width()+" 米；路线点："+document.anchors().size()+"；场景物件："+document.scene().items().size(),statusLabel(),"制作时物理版本："+physics,"源地图身份："+document.id(),"布局封面根据地图数据绘制；作者难度标签不等于难度认证。","接收者仍需导入地图 JSON，并在本机重新四车验证；PNG／文字不携带资格、成绩或部件。","地图几何检查："+(report.ready()?"当前通过，驾驶效果仍需实际试车。":"当前待处理。"));lines.addAll(report.errors());return String.join("\n",lines)+"\n";
    }
    /** Inspection and presentation invalidate independently; one snapshot, no library-sized image cache. */
    public static final class Cache {
        private CircuitInspector.DraftInspection savedInspection=new CircuitInspector.DraftInspection();
        private CircuitDocument document;private CircuitTrial.Proof proof;private String physics;private Format format;private Palette palette;private CircuitInspector.Report report;private CircuitShowcase value;private int inspections;
        public CircuitShowcase get(CircuitDocument document,CircuitTrial.Proof proof,String physics,Format format,Palette palette) {
            return get(document,proof,physics,format,palette,null);
        }
        public CircuitShowcase get(CircuitEditor editor,String physics,Format format,Palette palette) {
            return get(editor.document(),editor.proof(),physics,format,palette,editor);
        }
        private CircuitShowcase get(CircuitDocument document,CircuitTrial.Proof proof,String physics,Format format,Palette palette,CircuitEditor editor) {
            if(value==null || this.document!=document || this.proof!=proof || !Objects.equals(this.physics,physics) || this.format!=format || this.palette!=palette) {
                validate(document,physics,format,palette);
                var nextReport=report;
                if(this.document!=document) {
                    nextReport=editor==null?savedInspection.inspect(document):editor.inspection();
                    inspections++;
                }
                var next=new CircuitShowcase(document,proof,physics,format,palette,nextReport);value=next;report=nextReport;this.document=document;this.proof=proof;this.physics=physics;this.format=format;this.palette=palette;
            }return value;
        }
        public int inspections() {return inspections;}
        public void clear() {document=null;proof=null;report=null;value=null;savedInspection=new CircuitInspector.DraftInspection();}
    }
}
