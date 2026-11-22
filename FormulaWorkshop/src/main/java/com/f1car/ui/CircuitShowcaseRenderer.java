package com.f1car.ui;

import com.f1car.model.CircuitShowcase;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Fixed-size native drawing, never an arbitrary imported image or screenshot of an unrun game. */
public final class CircuitShowcaseRenderer {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd);
    private CircuitShowcaseRenderer() {}
    public static BufferedImage render(CircuitShowcase snapshot) {
        int width=snapshot.format().width,height=snapshot.format().height;boolean wide=snapshot.format()==CircuitShowcase.Format.LANDSCAPE;var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setColor(new Color(snapshot.palette().background));g.fillRect(0,0,width,height);g.setColor(new Color(snapshot.palette().accent));font(g,14);g.drawString("FORMULA WORKSHOP / 地图布局封面",40,30);
            int size=30;List<String> title;do {font(g,size--);title=wrap(g,snapshot.document().name(),width-80);}while(title.size()>2 && size>=20);g.setColor(INK);int y=70;for(String line:title) {g.drawString(line,40,y);y+=34;}
            var map=wide?new Rectangle(40,150,720,470):new Rectangle(40,150,880,430);CircuitCardPreview.draw(g,snapshot.document(),snapshot.report(),map);g.setColor(new Color(snapshot.palette().accent));g.setStroke(new BasicStroke(2));g.drawRect(map.x,map.y,map.width,map.height);
            if(snapshot.report().track()!=null) {var track=snapshot.report().track();var transform=TrackMapView.fit(track,map,14);var start=track.sample(0).position();font(g,13);g.drawString("起点",Math.min(map.x+map.width-32,Math.max(map.x+4,transform.x(start.x)+8)),Math.min(map.y+map.height-8,Math.max(map.y+18,transform.y(start.z)-8)));}
            g.setColor(SOFT);font(g,15);g.drawString("起点 ●  /  实体物件 ■  /  布景 ●",map.x+12,map.y+map.height+25);
            int x=wide?800:40,textY=wide?164:640,textWidth=wide?440:420;var card=snapshot.document().metadata();
            g.setColor(INK);font(g,18);textY=paragraph(g,snapshot.authorLabel(),x,textY,textWidth,2);g.setColor(new Color(snapshot.palette().accent));textY=paragraph(g,"作者难度标签："+card.difficulty().title,x,textY+8,textWidth,2);g.setColor(SOFT);font(g,16);textY=paragraph(g,snapshot.lengthLabel()+" · 路宽 "+snapshot.document().width()+" 米",x,textY+8,textWidth,2);textY=paragraph(g,snapshot.statusLabel(),x,textY+8,textWidth,2);
            g.setColor(INK);font(g,18);paragraph(g,"简介摘要："+(card.description().isBlank()?"尚未填写":card.description()),wide?x:500,wide?textY+16:640,textWidth,wide?5:7);
            g.setColor(SOFT);font(g,15);g.drawString("完整简介、修订与检查信息见导出的作品说明。",x,wide?595:876);
            font(g,16);g.setColor(new Color(snapshot.palette().accent));g.drawString("接收者导入地图 JSON 后，仍需本机四车验证。",40,height-42);font(g,13);g.setColor(SOFT);g.drawString("作者标签不代替验证；布局封面不携带资格、成绩或部件。",40,height-18);
        }finally {g.dispose();}return image;
    }
    private static void font(Graphics2D g,int size) {g.setFont(new Font("Microsoft YaHei",Font.PLAIN,size));}
    /** Wrap by code point and measured width; no isolated half of a supplementary character. */
    public static List<String> wrap(Graphics2D g,String text,int width) {
        if(width<1 || text.length()>4096) throw new IllegalArgumentException("Invalid text area");var lines=new ArrayList<String>();var current=new StringBuilder();for(int offset=0;offset<text.length();) {int point=text.codePointAt(offset);String next=new String(Character.toChars(point));offset+=Character.charCount(point);if(current.length()>0 && g.getFontMetrics().stringWidth(current+next)>width) {lines.add(current.toString());current.setLength(0);}current.append(next);}if(current.length()>0 || lines.isEmpty()) lines.add(current.toString());return List.copyOf(lines);
    }
    private static int paragraph(Graphics2D g,String text,int x,int y,int width,int rows) {var lines=wrap(g,text,width);int step=g.getFont().getSize()+5;for(int index=0;index<Math.min(rows,lines.size());index++) {String line=lines.get(index);if(index==rows-1 && lines.size()>rows) {while(!line.isEmpty() && g.getFontMetrics().stringWidth(line+"…")>width) line=line.substring(0,line.offsetByCodePoints(line.length(),-1));line+="…";}g.drawString(line,x,y);y+=step;}return y;}
    public static byte[] png(CircuitShowcase snapshot) throws IOException {var output=new ByteArrayOutputStream();if(!ImageIO.write(render(snapshot),"png",output)) throw new IOException("PNG writer unavailable");return output.toByteArray();}
    public static final class Cache {
        private CircuitShowcase snapshot;private BufferedImage image;private int renders;
        public BufferedImage get(CircuitShowcase value) {if(snapshot!=value) {clear();image=render(value);snapshot=value;renders++;}return image;}
        public int renders() {return renders;}
        public void clear() {if(image!=null) image.flush();image=null;snapshot=null;}
    }
}
