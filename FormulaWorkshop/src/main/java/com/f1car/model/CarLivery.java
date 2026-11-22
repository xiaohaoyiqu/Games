package com.f1car.model;

import java.util.*;

/** Bounded original geometric overlays. Paint, physics and earned journal emblems stay separate. */
public record CarLivery(int version,String id,String vehicle,String name,String author,String notes,String sourceId,List<Layer> layers) {
    public static final int CAPACITY=12;
    public enum Shape { RECTANGLE("矩形"),ELLIPSE("椭圆"),CHEVRON("箭纹");public final String title;Shape(String title) {this.title=title;} }
    public enum Target { ALL("整车不透明区"),NOSE("车鼻"),BODY("车身"),SIDEPODS("侧箱"),FRONT_WING("前翼"),REAR_WING("后翼"),TYRES("轮胎");public final String title;Target(String title) {this.title=title;} }
    public record Layer(Shape shape,int x,int y,int width,int height,int rotation,int rgb,int opacity,int mirror,Target target) {
        public Layer(Shape shape,int x,int y,int width,int height,int rotation,int rgb,int opacity,int mirror) {this(shape,x,y,width,height,rotation,rgb,opacity,mirror,Target.ALL);}
        public Layer {target=target==null?Target.ALL:target;if(shape==null || x < -400 || x>400 || y < -400 || y>400 || width<20 || width>900 || height<20 || height>900 || rotation < -180 || rotation>179 || rgb<0 || rgb>0xffffff || opacity<10 || opacity>100 || mirror<0 || mirror>1) throw new IllegalArgumentException("Invalid livery layer");}
        public Layer position(int nx,int ny) {return new Layer(shape,nx,ny,width,height,rotation,rgb,opacity,mirror,target);}
        public Layer size(int w,int h) {return new Layer(shape,x,y,w,h,rotation,rgb,opacity,mirror,target);}
        public Layer rotate(int degrees) {return new Layer(shape,x,y,width,height,(int)Math.floorMod((long)degrees+180,360)-180,rgb,opacity,mirror,target);}
        public Layer color(int color) {return new Layer(shape,x,y,width,height,rotation,color,opacity,mirror,target);}
        public Layer alpha(int value) {return new Layer(shape,x,y,width,height,rotation,rgb,value,mirror,target);}
        public Layer mirrored() {return new Layer(shape,x,y,width,height,rotation,rgb,opacity,1-mirror,target);}
        public Layer target(Target value) {return new Layer(shape,x,y,width,height,rotation,rgb,opacity,mirror,Objects.requireNonNull(value));}
        public static Layer initial(Shape shape) {return new Layer(shape,0,-150,80,480,0,0xf5d784,100,0);}
    }
    public CarLivery {
        if(version<1 || version>2 || !validId(id) || vehicle==null || !vehicle.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}") || !text(name,40,false) || !text(author,40,true) || !text(notes,320,true) || sourceId==null || !sourceId.isEmpty() && (!validId(sourceId) || sourceId.equals(id)) || layers==null || layers.size()>CAPACITY) throw new IllegalArgumentException("Invalid livery design");
        layers=List.copyOf(layers);
        if((version==2)!=layers.stream().anyMatch(layer->layer.target()!=Target.ALL)) throw new IllegalArgumentException("Region layers require explicit livery version two");
    }
    public static CarLivery create(String vehicle,String author) {return new CarLivery(1,newId(),vehicle,"我的涂装",author,"","",List.of());}
    public static String newId() {return "livery-"+UUID.randomUUID();}
    public static boolean validId(String value) {return value!=null && value.matches("livery-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private static boolean text(String value,int max,boolean empty) {return value!=null && value.length()<=max && (empty || !value.isBlank()) && PartsCatalog.text(value,max);}
    public CarLivery layers(List<Layer> value) {return new CarLivery(value.stream().anyMatch(layer->layer.target()!=Target.ALL)?2:1,id,vehicle,name,author,notes,sourceId,value);}
    public int unsupportedLayers(PaintRegions regions) {return (int)layers.stream().filter(layer->layer.target()!=Target.ALL && (regions==null || !vehicle.equals(regions.vehicle()) || !regions.supports(layer.target()))).count();}
    public CarLivery card(String title,String credit,String description) {return new CarLivery(version,id,vehicle,title,credit,description,sourceId,layers);}
    public CarLivery copy() {return new CarLivery(version,newId(),vehicle,name,author,notes,id,layers);}
}
