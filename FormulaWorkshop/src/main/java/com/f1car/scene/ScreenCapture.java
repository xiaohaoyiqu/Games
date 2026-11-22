package com.f1car.scene;

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageOutputStream;

/** Read back only the game viewport, deferring requests when its surface is minimized. */
public final class ScreenCapture implements SceneProcessor {
    public static final int MAX_PENDING=8,MAX_PIXELS=4096*4096;
    public enum Phase {PENDING,SAVED,FAILED,REJECTED,CANCELLED}
    public enum Reason {NONE,INVALID_NAME,QUEUE_FULL,CLOSED,SURFACE_TOO_LARGE,READBACK,ENCODE,WRITE}
    public record Outcome(Phase phase,Reason reason,int width,int height,String errorType) {}
    public record Request(long id,String name,Outcome outcome) {}
    public record Status(boolean initialized,boolean closed,int width,int height,long postFrames,int saved,int errors,int rejected,int cancelled,List<Request> pending,Request lastCompleted) {
        public Status {pending=List.copyOf(pending);}
    }
    public static final class Ticket {
        private final long id;private final String name;private volatile Outcome outcome;
        private Ticket(long id,String name,Outcome outcome) {this.id=id;this.name=name;this.outcome=outcome;}
        public long id() {return id;}public String name() {return name;}public Outcome outcome() {return outcome;}
        public Request snapshot() {return new Request(id,name,outcome);}
    }
    @FunctionalInterface interface FrameReader {void read(RenderManager manager,FrameBuffer output,ByteBuffer buffer) throws Exception;}
    @FunctionalInterface interface Writer {void write(Path target,BufferedImage image) throws Exception;}
    private final Path directory;
    private final FrameReader reader;private final Writer writer;
    private final Executor executor;
    private ThreadPoolExecutor worker;
    private Ticket writing;
    private final Queue<Ticket> requests = new ArrayDeque<>();
    private RenderManager manager;
    private ByteBuffer buffer;
    private int width, height;
    private boolean initialized,closed;
    private long submitted,postFrames;private Request lastCompleted;
    public volatile int saved, errors, rejected, cancelled;
    public volatile String lastError = "";

    public ScreenCapture(Path directory) {this(directory,(manager,output,buffer)->manager.getRenderer().readFrameBufferWithFormat(output,buffer,Image.Format.RGBA8),ScreenCapture::writePng);}
    ScreenCapture(Path directory,FrameReader reader,Writer writer) {this(directory,reader,writer,null);}
    ScreenCapture(Path directory,FrameReader reader,Writer writer,Executor executor) {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath();
        this.reader=Objects.requireNonNull(reader);
        this.writer=Objects.requireNonNull(writer);
        this.executor=executor;
    }
    public static void writePng(Path target,BufferedImage image) throws Exception {
        publishPng(target,image,(path,source)->{
            try(var output=new FileImageOutputStream(path.toFile())) {
                if(!ImageIO.write(source,"png",output)) throw new IOException("PNG writer unavailable");
            }
        });
    }
    static void publishPng(Path target,BufferedImage image,Writer encoder) throws Exception {
        Files.createDirectories(target.getParent());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid screenshot destination");
        Path temporary=Files.createTempFile(target.getParent(),".screenshot-",".tmp");
        try {
            encoder.write(temporary,image);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,target);}
        } catch(Exception | Error failure) {
            try {Files.deleteIfExists(temporary);} catch(IOException | RuntimeException cleanup) {failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    /** A receipt stays pending through framebuffer readback, encoding and publication. */
    public synchronized Ticket submit(String name) {
        boolean valid=validName(name);
        var reason=!valid?Reason.INVALID_NAME:closed?Reason.CLOSED:pending()>=MAX_PENDING?Reason.QUEUE_FULL:Reason.NONE;
        var ticket=new Ticket(submitted==Long.MAX_VALUE?submitted:++submitted,valid?name:"",new Outcome(reason==Reason.NONE?Phase.PENDING:Phase.REJECTED,reason,width,height,""));
        if(reason==Reason.NONE) requests.add(ticket);else rejected++;return ticket;
    }
    private static boolean validName(String name) {
        if(name==null || name.isBlank() || name.length()>96 || name.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0xd800 && c<=0xdfff || "<>:\"/\\|?*".indexOf(c)>=0)) return false;
        String file=name+"1.png",stem=file.substring(0,file.indexOf('.'));
        return !stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]) *");
    }
    public void request(String name) {submit(name);}
    public synchronized int pending() { return requests.size()+(writing==null?0:1); }
    public synchronized Status status() {
        var pending=new ArrayList<Request>(pending());
        if(writing!=null) pending.add(writing.snapshot());
        for(var request:requests) pending.add(request.snapshot());
        return new Status(initialized,closed,width,height,postFrames,saved,errors,rejected,cancelled,pending,lastCompleted);
    }
    @Override public synchronized void initialize(RenderManager manager, ViewPort viewport) {
        this.manager = manager; initialized = true;closed=false;
        reshape(viewport, viewport.getCamera().getWidth(), viewport.getCamera().getHeight());
    }
    @Override public boolean isInitialized() { return initialized; }
    @Override public synchronized void reshape(ViewPort viewport, int width, int height) {
        if(this.width==width && this.height==height) return;
        this.width = width; this.height = height;
        buffer=null;
    }
    @Override public void preFrame(float tpf) {}
    @Override public void postQueue(RenderQueue queue) {}
    @Override public synchronized void postFrame(FrameBuffer output) {
        if(postFrames<Long.MAX_VALUE) postFrames++;
        if (!initialized || writing!=null || requests.isEmpty() || width < 1 || height < 1) return;
        var ticket=requests.remove();
        if((long)width*height>MAX_PIXELS) {
            errors++;lastError="Surface exceeds capture pixel bound";
            complete(ticket,Phase.FAILED,Reason.SURFACE_TOO_LARGE,width,height,"");
            return;
        }
        Reason stage=Reason.READBACK;
        try {
            if(buffer==null) buffer=BufferUtils.createByteBuffer(width*height*4);
            buffer.clear();
            reader.read(manager,output,buffer);
            var pixels=buffer;
            int capturedWidth=width,capturedHeight=height;
            writing=ticket;
            stage=Reason.WRITE;
            writerExecutor().execute(()->encodeAndWrite(ticket,pixels,capturedWidth,capturedHeight));
        } catch (Exception | OutOfMemoryError failure) {
            writing=null;
            fail(ticket,stage,width,height,failure);
        }
    }
    private Executor writerExecutor() {
        if(executor!=null) return executor;
        if(worker==null) {
            worker=new ThreadPoolExecutor(1,1,5,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task->{
                var thread=new Thread(task,"formula-screenshot");
                thread.setDaemon(false);
                return thread;
            });
            worker.allowCoreThreadTimeOut(true);
        }
        return worker;
    }
    private void encodeAndWrite(Ticket ticket,ByteBuffer pixels,int width,int height) {
        Reason stage=Reason.ENCODE;
        try {
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            int[] rgb=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
            for(int y=0;y<height;y++) {
                int row=(height-1-y)*width;
                for(int x=0;x<width;x++) {
                    int i=(y*width+x)*4;
                    rgb[row+x]=0xff000000 | (pixels.get(i)&255)<<16 | (pixels.get(i+1)&255)<<8 | (pixels.get(i+2)&255);
                }
            }
            stage=Reason.WRITE;
            writer.write(directory.resolve(ticket.name()+"1.png"),image);
            synchronized(this) {
                saved++;lastError="";writing=null;
                complete(ticket,Phase.SAVED,Reason.NONE,width,height,"");
            }
        } catch(Exception | OutOfMemoryError failure) {
            synchronized(this) {
                writing=null;
                fail(ticket,stage,width,height,failure);
            }
        }
    }
    private void fail(Ticket ticket,Reason stage,int width,int height,Throwable failure) {
        errors++;
        String type=failure.getClass().getName();
        lastError=type.substring(0,Math.min(type.length(),160));
        complete(ticket,Phase.FAILED,stage,width,height,lastError);
        System.err.println("Screenshot failed: "+lastError);
    }
    private void complete(Ticket ticket,Phase phase,Reason reason,int width,int height,String type) {
        ticket.outcome=new Outcome(phase,reason,width,height,type);
        lastCompleted=ticket.snapshot();
    }
    @Override public synchronized void cleanup() {
        while(!requests.isEmpty()) {complete(requests.remove(),Phase.CANCELLED,Reason.CLOSED,width,height,"");cancelled++;}
        if(worker!=null) {worker.shutdown();worker=null;}
        buffer=null;manager=null;initialized=false;closed=true;
    }
    @Override public void setProfiler(AppProfiler profiler) {}
}
