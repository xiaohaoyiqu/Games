package com.f1car.ui;

/** Input is blocked until the outgoing menu is replaced and the new menu returns. */
public final class MenuSlide {
    private Runnable change;
    private float time;
    private int phase;
    private boolean invoking;
    public boolean active() { return phase!=0; }
    public void begin(Runnable change) { if(active()) return;this.change=java.util.Objects.requireNonNull(change);phase=1;time=0; }
    public void cancel() { if(invoking) return;change=null;phase=0;time=0; }
    public void update(float dt) {
        if(!Float.isFinite(dt) || dt<0) return;
        while(active() && dt>0) {
            float duration=phase==1?.16f:.22f,step=Math.min(dt,duration-time);time+=step;dt-=step;
            if(time>=duration-.000001f) {
                time=0;if(phase==1) { var action=change;change=null;invoking=true;try { action.run(); }finally { invoking=false; }phase=2; }
                else phase=0;
            }
        }
    }
    public float offset() { if(!active()) return 0;float t=Math.min(1,time/(phase==1?.16f:.22f));float eased=t*t*(3-2*t);return -1330*(phase==1?eased:1-eased); }
}
