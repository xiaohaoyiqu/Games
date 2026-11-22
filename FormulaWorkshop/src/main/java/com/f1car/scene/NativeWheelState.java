package com.f1car.scene;

import com.f1car.model.NativeWheelObservation;
import java.util.*;

/**
 * Optional research-only extension, linked INTO the same Libbulletjme 22.0.3 library.
 * Never load a sidecar DLL or interpret Bullet pointers with a separately built ABI.
 * The normal dependency has no extension: an explicit open returns UNAVAILABLE.
 */
final class NativeWheelState implements AutoCloseable {
    static final String DESCRIPTOR="fw-wheel-state-1:lbj-22.0.3:scalar-4";
    enum Status {READY,UNAVAILABLE,UNSUPPORTED,FAILED,CLOSED}
    interface Provider {String descriptor();void read(long controller,float[] output);}
    private Provider provider;
    private float[] buffer;
    private long sequence;
    private volatile Status status;
    private volatile NativeWheelObservation reading;
    private static native String descriptor();
    private static native void read(long controller,float[] output);
    static NativeWheelState open(long previousSequence) {return open(new Provider() {public String descriptor() {return NativeWheelState.descriptor();}public void read(long controller,float[] output) {NativeWheelState.read(controller,output);}},previousSequence);}
    static NativeWheelState open(Provider provider) {return open(provider,0);}
    static NativeWheelState open(Provider provider,long previousSequence) {return new NativeWheelState(Objects.requireNonNull(provider),previousSequence);}
    private NativeWheelState(Provider candidate,long previousSequence) {
        if(previousSequence<0) throw new IllegalArgumentException("Invalid preceding wheel frame");sequence=previousSequence;
        try {if(!DESCRIPTOR.equals(candidate.descriptor())) {status=Status.UNSUPPORTED;return;}provider=candidate;buffer=new float[NativeWheelObservation.VALUES];status=Status.READY;}
        catch(UnsatisfiedLinkError missing) {status=Status.UNAVAILABLE;}
        catch(RuntimeException failure) {status=Status.FAILED;}
    }
    Status status() {return status;}
    NativeWheelObservation reading() {return reading;}
    synchronized long sequence() {return sequence;}
    /** Called only at the owning vehicle's post-step callback, never from rendering. */
    synchronized void capture(long controller,float dt) {
        if(status!=Status.READY) return;
        if(controller==0 || !Float.isFinite(dt) || dt<=0 || dt>.25f || sequence==Long.MAX_VALUE) {fail();return;}
        try {
            // A partial native write cannot reuse the previous wheel's contact flags or geometry.
            Arrays.fill(buffer,Float.NaN);provider.read(controller,buffer);
            reading=NativeWheelObservation.decode(++sequence,dt,buffer);
        } catch(UnsatisfiedLinkError | RuntimeException failure) {fail();}
    }
    synchronized void clear() {reading=null;}
    private void fail() {reading=null;provider=null;buffer=null;status=Status.FAILED;}
    synchronized long closeAndSequence() {reading=null;provider=null;buffer=null;status=Status.CLOSED;return sequence;}
    @Override public void close() {closeAndSequence();}
}
