package com.f1car.ui;

import com.f1car.model.CircuitEditor;
import com.f1car.model.CircuitScene;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

/** Drawing data for the scenery on the current editor board. */
final class CircuitSceneryDrawing {
    private static final Color TREE=new Color(62,167,112),BUILDING=new Color(146,166,165);
    private static final Mark[] EMPTY=new Mark[0];
    private record Footprint(double angle,int left,int top,int width,int height) {}
    private record Mark(CircuitScene.Item item,float x,float y,Color color,Footprint footprint) {}

    private final Color ink,highlight,selected;
    private List<CircuitScene.Item> projectedItems;
    private TrackMapView.Transform projectedView;
    private Mark[] marks=EMPTY;

    CircuitSceneryDrawing(Color ink,Color highlight,Color selected) {
        this.ink=ink;
        this.highlight=highlight;
        this.selected=selected;
    }

    void clear() {
        projectedItems=null;
        projectedView=null;
        marks=EMPTY;
    }

    void draw(Graphics2D graphics,List<CircuitScene.Item> items,TrackMapView.Transform view,CircuitEditor editor) {
        if(items!=projectedItems || view!=projectedView) project(items,view);
        if(marks.length==0) return;
        var layer=(Graphics2D)graphics.create();
        try {
            var transform=layer.getTransform();
            int active=editor==null?-1:editor.selectedItem();
            for(int index=0;index<marks.length;index++) {
                var mark=marks[index];
                layer.setTransform(transform);
                layer.translate(mark.x(),mark.y());
                layer.setColor(editor!=null && editor.isItemSelected(index)?selected:mark.color());
                if(index==active) {
                    layer.setColor(highlight);
                    layer.drawOval(-9,-9,18,18);
                    layer.setColor(selected);
                }
                var footprint=mark.footprint();
                if(footprint==null) layer.fillOval(-5,-5,10,10);
                else {
                    layer.rotate(footprint.angle());
                    layer.fillRect(footprint.left(),footprint.top(),footprint.width(),footprint.height());
                }
            }
        } finally {
            layer.dispose();
        }
    }

    private void project(List<CircuitScene.Item> items,TrackMapView.Transform view) {
        boolean sameView=view==projectedView;
        boolean sameScale=sameView || projectedView!=null && view.scale()==projectedView.scale();
        var next=items.isEmpty()?EMPTY:new Mark[items.size()];
        for(int index=0;index<next.length;index++) {
            var item=items.get(index);
            var previous=index<marks.length?marks[index]:null;
            if(sameView && previous!=null && item==previous.item()) next[index]=previous;
            else {
                var footprint=footprint(item,view.scale(),sameScale?previous:null);
                var color=switch(item.kind()) {case TREE->TREE;case BUILDING->BUILDING;default->ink;};
                next[index]=new Mark(item,view.x(item.x()),view.y(item.z()),color,footprint);
            }
        }
        projectedItems=items;
        projectedView=view;
        marks=next;
    }

    private static Footprint footprint(CircuitScene.Item item,float scale,Mark previous) {
        if(!item.solid()) return null;
        if(previous!=null && previous.footprint()!=null && Float.compare(item.yaw(),previous.item().yaw())==0
                && item.length()==previous.item().length() && item.width()==previous.item().width()) return previous.footprint();
        var tangent=item.tangent();
        return new Footprint(Math.atan2(-tangent.z,tangent.x),Math.round(-item.length()*scale/2),Math.round(-item.width()*scale/2),
                Math.max(2,Math.round(item.length()*scale)),Math.max(2,Math.round(item.width()*scale)));
    }
}
