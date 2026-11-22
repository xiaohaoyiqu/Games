package com.f1car.model;

/** Recovery is separate from pace control: queued traffic must not count as a stuck car. */
public final class AiRecovery {
    public enum Reason { NONE, STUCK, WRONG_WAY, OVERTURNED, OUT_OF_WORLD }
    private float stopped,wrongWay;
    private Reason reason=Reason.NONE;
    public Reason reason() { return reason; }
    public boolean requested() { return reason!=Reason.NONE; }
    public void reset() { stopped=wrongWay=0;reason=Reason.NONE; }
    public void requestInvalidObservation(Reason fault) {
        if(fault!=Reason.OUT_OF_WORLD && fault!=Reason.OVERTURNED)
            throw new IllegalArgumentException("Invalid vehicle observation reason");
        if(!requested()) reason=fault;
    }
    public void update(float dt,float speed,float alignment,float up,float height,boolean blocked) {
        if(!Float.isFinite(dt) || dt<=0 || requested()) return;
        dt=Math.min(.1f,dt);
        if(!Float.isFinite(height) || height < -4 || height > 12) { reason=Reason.OUT_OF_WORLD;return; }
        if(!Float.isFinite(up) || up<.15f) { reason=Reason.OVERTURNED;return; }
        stopped=!blocked && speed<1 ? stopped+dt : 0;
        wrongWay=!blocked && alignment<-.4f && speed<8 ? wrongWay+dt : 0;
        if(wrongWay>3) reason=Reason.WRONG_WAY;
        else if(stopped>6) reason=Reason.STUCK;
    }
}
