package com.f1car.persistence;

import java.nio.file.FileSystemException;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Bounded save metadata only: no archive bytes, JSON, path probes, digests or arbitrary exception messages. */
public final class SaveDiagnostics {
    public enum Stage {ENCODE,PREPARE,BACKUP,CREATE_TEMP,WRITE_TEMP,REPLACE,FALLBACK_REPLACE,CLEANUP}
    public record Cause(String type,String filesystemReason) {}
    public record Failure(long attempt,Stage stage,int encodedBytes,boolean replaced,List<Cause> causes,List<Cause> cleanupCauses) {
        public Failure {causes=List.copyOf(causes);cleanupCauses=List.copyOf(cleanupCauses);}
    }
    public enum Work {READ_CURRENT,ENCODE,BACKUP,WRITE_TEMP,VERIFY,MOVE,FALLBACK_MOVE,DELETE_TEMP}
    public record Timing(long attempt,long elapsedNanos,int encodedBytes,boolean replaced,Map<Work,Long> workNanos) {
        public Timing {workNanos=Map.copyOf(workNanos);}
    }
    interface Span extends AutoCloseable {@Override void close();}
    private static final Span NO_TIMING=()->{};
    private static final Logger LOG=Logger.getLogger(SaveDiagnostics.class.getName());
    private final String store;private long attempt;private Stage stage=Stage.ENCODE;private int bytes=-1;private boolean replaced;private Failure failure;
    private final LongSupplier nanoTime;
    private EnumMap<Work,Long> workNanos;
    private long started;
    SaveDiagnostics(String store) {this(store,null);}
    SaveDiagnostics(String store,LongSupplier nanoTime) {this.store=store;this.nanoTime=nanoTime;}
    public Failure failure() {return failure;}
    void begin() {
        attempt=attempt==Long.MAX_VALUE?attempt:attempt+1;stage=Stage.ENCODE;bytes=-1;replaced=false;failure=null;
        if(nanoTime!=null) {started=nanoTime.getAsLong();workNanos=new EnumMap<>(Work.class);}
    }
    Span measure(Work work) {
        var times=workNanos;
        if(times==null) return NO_TIMING;
        long start=nanoTime.getAsLong();
        return ()->times.merge(work,nanoTime.getAsLong()-start,Long::sum);
    }
    Timing finish() {
        if(workNanos==null) return null;
        var timing=new Timing(attempt,nanoTime.getAsLong()-started,bytes,replaced,workNanos);
        workNanos=null;
        return timing;
    }
    void at(Stage stage) {this.stage=Objects.requireNonNull(stage);}
    void encoded(int bytes) {this.bytes=bytes;}
    void replaced() {replaced=true;}
    void failed(Throwable error) {if(failure==null) {failure=new Failure(attempt,stage,bytes,replaced,causes(error),List.of());log();}}
    void cleanupFailed(Throwable error) {
        if(failure==null) failure=new Failure(attempt,Stage.CLEANUP,bytes,replaced,causes(error),List.of());
        else failure=new Failure(failure.attempt(),failure.stage(),failure.encodedBytes(),failure.replaced(),failure.causes(),causes(error));
        log();
    }
    private static List<Cause> causes(Throwable error) {var result=new ArrayList<Cause>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());for(var current=error;current!=null && result.size()<4 && seen.add(current);current=current.getCause()) {String reason=current instanceof FileSystemException file?Objects.toString(file.getReason(),""):"";var safe=new StringBuilder();for(int i=0;i<reason.length() && safe.length()<240;i++) {char c=reason.charAt(i);safe.append(Character.isISOControl(c)?' ':c);}String type=current.getClass().getName();result.add(new Cause(type.substring(0,Math.min(160,type.length())),safe.toString()));}return List.copyOf(result);}
    private void log() {try {LOG.warning("Archive save: store="+store+" attempt="+failure.attempt()+" stage="+failure.stage()+" encodedBytes="+failure.encodedBytes()+" replaced="+failure.replaced()+" causes="+failure.causes()+" cleanupCauses="+failure.cleanupCauses());}catch(RuntimeException ignored) { /* A failing log handler must not alter persistence or recovery. */ }}
}
