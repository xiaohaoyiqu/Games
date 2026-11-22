package com.f1car.input;

import java.util.*;

/** Explicit centre/rest and endpoints; no moving-axis auto binding while navigating menus. */
public final class AxisCalibration {
    private final ControlSettings.Channel channel;
    private Map<Integer,Float> rest=Map.of();
    private int step,axis=-1;
    private float first;
    public AxisCalibration(ControlSettings.Channel channel) { this.channel=Objects.requireNonNull(channel); }
    public ControlSettings.Channel channel() { return channel; }
    public int step() { return step; }
    public String instruction() {
        if(step==0) return "松开摇杆和扳机，再按确认采集静止值。";
        if(channel==ControlSettings.Channel.STEERING) return step==1?"只向左打满转向，再按确认。":"只向右打满同一轴，再按确认。";
        return channel==ControlSettings.Channel.THROTTLE?"只踩满油门，再按确认。":"只踩满制动，再按确认。";
    }
    public Optional<ControlSettings.Axis> confirm(Map<Integer,Float> raw,ControlSettings.Axis previous) {
        if(raw==null || raw.isEmpty() || raw.size()>64 || raw.entrySet().stream().anyMatch(e->e.getKey()==null || e.getKey()<0 || e.getKey()>63 || e.getValue()==null || !Float.isFinite(e.getValue()) || Math.abs(e.getValue())>1)) throw new IllegalArgumentException("没有有效轴读数，请连接手柄并重试。");
        if(step==0) { rest=Map.copyOf(raw);step=1;return Optional.empty(); }
        if(step==1) {
            axis=movedAxis(raw);
            first=raw.get(axis);
            if(channel==ControlSettings.Channel.STEERING) { step=2;return Optional.empty(); }
            return Optional.of(new ControlSettings.Axis(axis,rest.get(axis),-1,first,previous.deadzone(),previous.curve(),false));
        }
        if(!raw.containsKey(axis)) throw new IllegalArgumentException("转向轴读数缺失，请重试校准。");
        for(int id:raw.keySet()) {
            if(id!=axis && displaced(raw,id))
                throw new IllegalArgumentException("请只操作刚才校准的同一转向轴。");
        }
        try {
            return Optional.of(new ControlSettings.Axis(axis,rest.get(axis),first,raw.get(axis),
                    previous.deadzone(),previous.curve(),true));
        } catch(IllegalArgumentException direction) {
            throw new IllegalArgumentException("转向两端应在静止点的相反方向，请向右打满。",direction);
        }
    }
    private int movedAxis(Map<Integer,Float> raw) {
        int moved=-1;
        for(int id:raw.keySet()) {
            if(!displaced(raw,id)) continue;
            if(moved>=0) throw new IllegalArgumentException("检测到多个轴移动，请只操作正在校准的一项。");
            moved=id;
        }
        if(moved<0) throw new IllegalArgumentException("轴移动不足，请将要绑定的控制打满。");
        return moved;
    }
    private boolean displaced(Map<Integer,Float> raw,int id) {
        var baseline=rest.get(id);
        return baseline!=null && Math.abs(raw.get(id)-baseline)>.35f;
    }
}
