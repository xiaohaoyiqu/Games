package com.f1car.pixel;

import com.f1car.model.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Replay-only LRU: keys are prepared once per roster, with bounded owned frames and pixel bytes. */
public final class ReplaySprites implements AutoCloseable {
    public static final int FRAME_LIMIT=64;
    public static final long BYTE_LIMIT=8L*1024*1024,MAX_FRAME_BYTES=256L*256*4;
    private record Key(BufferedImage source,List<CarLivery.Layer> layers,DriverJournal.Look look,int direction,int directions,PaintRegionStencil stencil) {}
    private record Entry(BufferedImage image,long bytes) {}
    private final int frameLimit;private final long byteLimit;
    private final LinkedHashMap<Key,Entry> frames=new LinkedHashMap<>(16,.75f,true);
    private RaceReplay replay;private BufferedImage[][] atlas;private String vehicle="";
    private String atlasIdentity="";private Map<PaintRegions,PaintRegionStencil> stencils=Map.of();
    private int[] driverIds=new int[0];private Key[][] keys=new Key[0][];private long bytes,renders;private int preparedKeys;
    public ReplaySprites() {this(FRAME_LIMIT,BYTE_LIMIT);}
    public ReplaySprites(int frameLimit,long byteLimit) {if(frameLimit<1 || frameLimit>FRAME_LIMIT || byteLimit<MAX_FRAME_BYTES || byteLimit>BYTE_LIMIT) throw new IllegalArgumentException("Invalid replay cache budget");this.frameLimit=frameLimit;this.byteLimit=byteLimit;}
    public boolean prepared() {return replay!=null;}
    public void prepare(RaceReplay next,BufferedImage[][] sources,String model) {
        prepare(next,sources,model,"");
    }
    public void prepare(RaceReplay next,BufferedImage[][] sources,String model,String identity) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(model);
        Objects.requireNonNull(identity);
        if(next==replay && sources==atlas && model.equals(vehicle) && identity.equals(atlasIdentity)) return;
        if(sources.length!=5) throw new IllegalArgumentException("Invalid replay livery atlas");

        var compiled=new HashMap<PaintRegions,PaintRegionStencil>();
        var prepared=new Key[next.drivers().size()][];
        var ids=new int[prepared.length];
        int count=0;
        for(int index=0;index<ids.length;index++) {
            var driver=next.drivers().get(index);
            var source=Objects.requireNonNull(sources[driver.tuning().livery()]);
            if(source.length<8 || source.length>32) throw new IllegalArgumentException("Invalid replay direction count");
            int size=Objects.requireNonNull(source[0]).getWidth();
            var appearance=driver.appearance();
            var mask=appearance==null?null:appearance.usableRegions(model,identity,size);
            var layers=appearance!=null && appearance.vehicle().equals(model)?appearance.design().layers():List.<CarLivery.Layer>of();
            if(layers.stream().anyMatch(layer->layer.target()!=CarLivery.Target.ALL && (mask==null || !mask.supports(layer.target()))))
                layers=layers.stream().filter(layer->layer.target()==CarLivery.Target.ALL || mask!=null && mask.supports(layer.target())).toList();
            PaintRegionStencil stencil=null;
            if(mask!=null && layers.stream().anyMatch(layer->layer.target()!=CarLivery.Target.ALL)) {
                stencil=compiled.computeIfAbsent(mask,regions->{
                    var previous=stencils.get(regions);
                    return previous!=null?previous:new PaintRegionStencil(regions);
                });
            }
            var row=new Key[source.length];
            for(int i=0;i<source.length;i++) {
                var image=Objects.requireNonNull(source[i]);
                if(size<16 || size>256 || image.getWidth()!=size || image.getHeight()!=size)
                    throw new IllegalArgumentException("Invalid replay sprite dimensions");
                row[i]=new Key(image,layers,driver.look(),i,source.length,stencil);
            }
            ids[index]=driver.id();
            prepared[index]=row;
            count+=row.length;
        }
        // Finish validating every driver's atlas before releasing images from the active roster.
        if(sources!=atlas) clearFrames();
        else retainPreparedFrames(prepared);
        replay=next;
        atlas=sources;
        vehicle=model;
        atlasIdentity=identity;
        stencils=Map.copyOf(compiled);
        keys=prepared;
        driverIds=ids;
        preparedKeys=count;
    }

    private void retainPreparedFrames(Key[][] prepared) {
        if(frames.isEmpty()) return;
        var visible=new HashSet<Key>();
        for(var row:prepared) Collections.addAll(visible,row);
        var iterator=frames.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();
            if(visible.contains(entry.getKey())) continue;
            var frame=entry.getValue();
            iterator.remove();
            bytes-=frame.bytes();
            frame.image().flush();
        }
    }
    public BufferedImage image(int driver,int direction) {
        Key[] row=null;for(int i=0;i<driverIds.length;i++) if(driverIds[i]==driver) {row=keys[i];break;}if(row==null || direction<0 || direction>=row.length) throw new IllegalArgumentException("Unknown replay sprite");var key=row[direction];if(key.layers().isEmpty() && key.look()==null) return key.source();var cached=frames.get(key);if(cached!=null) return cached.image();
        double angle=key.direction()*Math.PI*2/key.directions();
        var result=LiveryPainter.compose(key.source(),key.layers(),key.look(),angle,key.stencil());
        long cost=(long)result.getWidth()*result.getHeight()*4;while(frames.size()>=frameLimit || bytes+cost>byteLimit) {var iterator=frames.entrySet().iterator();var oldest=iterator.next().getValue();iterator.remove();bytes-=oldest.bytes();oldest.image().flush();}
        frames.put(key,new Entry(result,cost));bytes+=cost;renders++;return result;
    }
    public int cachedFrames() {return frames.size();}
    public long residentPixelBytes() {return bytes;}
    public long renderedFrames() {return renders;}
    public int preparedKeyCount() {return preparedKeys;}
    private void clearFrames() {if(!frames.isEmpty()) {for(var frame:frames.values()) frame.image().flush();frames.clear();}bytes=0;}
    @Override public void close() {clearFrames();replay=null;atlas=null;vehicle="";atlasIdentity="";stencils=Map.of();if(driverIds.length!=0) {driverIds=new int[0];keys=new Key[0][];}preparedKeys=0;}
}
