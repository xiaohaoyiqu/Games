package com.f1car.model;

import com.jme3.math.Vector3f;
import java.util.List;

/** Custom-map objects share their dimensions between the editor, pixel view and physics. */
public record CircuitScene(TrackCatalog.Theme theme,long seed,List<Item> items) {
    public static final int MAX_ITEMS=64;
    public enum Kind { TREE,SIGN,BARRIER,BUILDING }
    public record Item(Kind kind,float x,float z,float yaw,float length,float width) {
        public Item {
            if(kind==null || !Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>280 || Math.abs(z)>280
                || !Float.isFinite(yaw) || !Float.isFinite(length) || !Float.isFinite(width) || length<1 || length>(kind==Kind.BUILDING?24:12) || width<.5f || width>(kind==Kind.BUILDING?16:4))
                throw new IllegalArgumentException("Invalid circuit object");
            yaw=(float)Math.atan2(Math.sin(yaw),Math.cos(yaw));
        }
        public Vector3f position() { return new Vector3f(x,0,z); }
        public Vector3f tangent() { return new Vector3f((float)Math.sin(yaw),0,(float)Math.cos(yaw)); }
        public float clearance() { return solid()?(float)Math.hypot(length*.5,width*.5):3; }
        public boolean solid() { return kind==Kind.BARRIER || kind==Kind.BUILDING; }
        public Item move(float x,float z) { return new Item(kind,x,z,yaw,length,width); }
        public Item rotate(float delta) { return new Item(kind,x,z,yaw+delta,length,width); }
    }
    public CircuitScene {
        if(theme==null || items==null || items.size()>MAX_ITEMS) throw new IllegalArgumentException("Invalid circuit scenery");
        items=List.copyOf(items);
    }
}
