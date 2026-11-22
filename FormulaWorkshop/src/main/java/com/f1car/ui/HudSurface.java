package com.f1car.ui;

import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.*;

/** One reusable CPU canvas and RGBA staging buffer, replaced only when its dimensions change. */
final class HudSurface implements AutoCloseable {
    private final boolean opaque;
    HudSurface() {this(false);}
    HudSurface(boolean opaque) {this.opaque=opaque;}
    private BufferedImage canvas;
    private int[] pixels,row;
    private ByteBuffer bytes;
    private IntBuffer rgba;
    private Image image;
    boolean resize(int width,int height) {
        if(canvas!=null && canvas.getWidth()==width && canvas.getHeight()==height) return false;
        if(width<1 || height<1 || width>4096 || height>4096) throw new IllegalArgumentException("Unsupported UI surface");
        close();canvas=new BufferedImage(width,height,opaque?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);pixels=((DataBufferInt)canvas.getRaster().getDataBuffer()).getData();row=new int[width];
        bytes=BufferUtils.createByteBuffer(Math.multiplyExact(Math.multiplyExact(width,height),4)).order(ByteOrder.BIG_ENDIAN);rgba=bytes.asIntBuffer();image=new Image(Image.Format.RGBA8,width,height,bytes,ColorSpace.Linear);return true;
    }
    BufferedImage canvas() { return canvas; }
    Image image() { return image; }
    void upload() {
        if(image==null) throw new IllegalStateException("UI surface is closed");
        int width=canvas.getWidth(),alpha=opaque?0xff000000:0;bytes.clear();rgba.clear();
        for(int y=canvas.getHeight()-1;y>=0;y--) { int offset=y*width;for(int x=0;x<width;x++) row[x]=Integer.rotateLeft(pixels[offset+x]|alpha,8);rgba.put(row); }
        rgba.flip();image.setUpdateNeeded();
    }
    @Override public void close() {
        if(image!=null) image.dispose();if(canvas!=null) canvas.flush();image=null;canvas=null;pixels=row=null;bytes=null;rgba=null;
    }
}
