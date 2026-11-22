package com.f1car.input;

import java.util.*;

/** Pure event state. No frame-integrated axis values, implicit device takeover or native calls. */
public final class ControllerInput {
    public record Device(int id,String key,String name,int axes,int buttons) {
        public Device {
            if(id<0 || key==null || !key.matches("[a-f0-9]{64}:[0-9]{1,2}") || name==null || name.isBlank() || name.length()>120 || axes<0 || axes>64 || buttons<0 || buttons>128) throw new IllegalArgumentException("Invalid device");
        }
        public String displayName() { return "#"+id+" "+name; }
        public boolean hasAxis(int axis) { return axis>=0 && axis<axes; }
    }
    public record Drive(float throttle,float brake,float steer,boolean reverse) {
        public static final Drive STOP=new Drive(0,1,0,false);
    }
    private static final class State {
        final Device device;
        final float[] axes;
        final BitSet buttons,awaitingRelease;
        long generation;
        long observedAxes;
        Map<Integer,Float> rawView=Map.of();
        ControlSettings previewSettings;
        Drive preview;
        State(Device device,long generation) {
            this.device=device;
            this.generation=generation;
            axes=new float[device.axes()];
            buttons=new BitSet(device.buttons());
            awaitingRelease=new BitSet(device.buttons());
        }
        float raw(int axis,float fallback) {
            return axis>=0 && axis<axes.length && (observedAxes & (1L<<axis))!=0?axes[axis]:fallback;
        }
        Map<Integer,Float> rawView() {
            if(rawView!=null) return rawView;
            var values=new HashMap<Integer,Float>();
            for(long bits=observedAxes;bits!=0;bits&=bits-1) {
                int axis=Long.numberOfTrailingZeros(bits);
                values.put(axis,axes[axis]);
            }
            rawView=Map.copyOf(values);
            return rawView;
        }
        void clear(long generation) {
            this.generation=generation;
            observedAxes=0;
            // Backends that send only button transitions may never repeat a held press after focus returns.
            awaitingRelease.or(buttons);
            buttons.clear();
            rawView=Map.of();
            previewSettings=null;
        }
    }
    private Map<Integer,State> states=Map.of();
    private long lastGeneration;
    private Map<String,State> byKey=Map.of();
    private List<Device> devices=List.of();
    private boolean armed;
    private ControlSettings armedSettings;
    private String armedKey="";
    private long drivingAxes;
    private State menuDevice;
    private int menuX,menuY;
    private static final float MENU_RELEASE=.35f;
    private static final float MENU_REPEAT_DELAY=.35f, MENU_REPEAT_INTERVAL=.13f;
    private double menuWait;
    private int menuDirection;
    public List<Device> devices() { return devices; }
    public void devices(List<Device> devices) {devices(devices,Set.of());}

    void devices(List<Device> inventory,Set<Integer> replaced) {
        if(inventory==null || inventory.size()>32 || inventory.stream().anyMatch(Objects::isNull)
                || inventory.stream().map(Device::id).distinct().count()!=inventory.size()
                || inventory.stream().map(Device::key).distinct().count()!=inventory.size())
            throw new IllegalArgumentException("Invalid devices");
        if(replaced.isEmpty() && devices.equals(inventory)) return;

        var next=new HashMap<Integer,State>();
        var keys=new HashMap<String,State>();
        for(var device:inventory) {
            var old=states.get(device.id());
            var state=old!=null && old.device.equals(device) && !replaced.contains(device.id())?old:new State(device,++lastGeneration);
            next.put(device.id(),state);
            keys.put(device.key(),state);
        }
        if(armedSettings!=null && byKey.get(armedSettings.device())!=keys.get(armedSettings.device())) disarm();
        if(menuDevice!=null && keys.get(menuDevice.device.key())!=menuDevice) resetMenu();
        states=next;
        byKey=keys;
        if(!devices.equals(inventory)) devices=List.copyOf(inventory);
    }
    public boolean connected(ControlSettings settings) { return selected(settings)!=null; }
    public String drivingProblem(ControlSettings settings) {
        if(!settings.enabled()) return "";
        var state=selected(settings);
        if(state==null) return "所选手柄未连接。请连接手柄，松开摇杆和踏板。";
        if(!settings.calibrated()) {
            var conflict=settings.calibrationConflict();
            return conflict.isEmpty()?"手柄驾驶轴尚未全部校准，请在操作设置中完成校准。":conflict;
        }
        var device=state.device;
        if(!device.hasAxis(settings.steering().id()) || !device.hasAxis(settings.throttle().id())
                || !device.hasAxis(settings.brake().id()))
            return "当前手柄缺少已绑定的驾驶轴，请在操作设置中重新校准后开始新的尝试。";
        return "";
    }
    /** Identifies an uninterrupted set of observations from the selected device. Zero means absent. */
    public long observationGeneration(ControlSettings settings) {
        var state=selected(settings);
        return state==null?0:state.generation;
    }
    public Optional<Device> selectedDevice(ControlSettings settings) { return Optional.ofNullable(selected(settings)).map(state->state.device); }
    public void axis(int device,int axis,float raw) {
        var state=states.get(device);
        if(state==null || axis<0 || axis>=state.axes.length) return;
        long bit=1L<<axis;
        if(!Float.isFinite(raw) || raw< -1.01f || raw>1.01f) {
            if((state.observedAxes & bit)!=0) {
                state.observedAxes&=~bit;
                state.rawView=null;
                state.previewSettings=null;
            }
            if(armedSettings!=null && state==selected(armedSettings) && (drivingAxes & bit)!=0) armed=false;
            return;
        }
        float value=Math.max(-1,Math.min(1,raw));
        if((state.observedAxes & bit)!=0 && Float.compare(state.axes[axis],value)==0) return;
        state.axes[axis]=value;
        state.observedAxes|=bit;
        state.rawView=null;
        state.previewSettings=null;
        // A return to center and a second push can arrive in the same input batch.
        if(state==menuDevice && menuDirection!=0 && (axis==menuX || axis==menuY)
                && Math.abs(state.raw(menuX,0))<MENU_RELEASE && Math.abs(state.raw(menuY,0))<MENU_RELEASE)
            menuDirection=0;
    }
    /** Returns true only for a newly pressed button. */
    public boolean button(int device,int button,boolean down) {
        var state=states.get(device);if(state==null || button<0 || button>=state.device.buttons()) return false;
        if(state.awaitingRelease.get(button)) {
            if(!down) state.awaitingRelease.clear(button);
            return false;
        }
        if(state.buttons.get(button)==down) return false;
        state.buttons.set(button,down);
        state.previewSettings=null;
        return down;
    }
    public Map<Integer,Float> raw(ControlSettings settings) { var state=selected(settings);return state==null?Map.of():state.rawView(); }
    public int deviceId(ControlSettings settings) { var state=selected(settings);return state==null?-1:state.device.id(); }
    public boolean pressed(ControlSettings settings,ControlSettings.Command command) {
        var state=selected(settings);int button=settings.buttons().getOrDefault(command,-1);
        return state!=null && button>=0 && state.buttons.get(button);
    }
    public Drive preview(ControlSettings settings) {
        var state=selected(settings);if(state==null) return Drive.STOP;
        return preview(settings,state);
    }
    private Drive preview(ControlSettings settings,State state) {
        if(state.previewSettings!=settings) {
            int reverse=settings.buttons().get(ControlSettings.Command.REVERSE);
            state.preview=new Drive(value(settings.throttle(),state),value(settings.brake(),state),value(settings.steering(),state),
                    reverse>=0 && state.buttons.get(reverse));
            state.previewSettings=settings;
        }
        return state.preview;
    }
    public Drive drive(ControlSettings settings) {
        if(armedSettings!=settings) {
            if(!settings.enabled() || !settings.calibrated()) { block();return Drive.STOP; }
            String key=settings.device()+settings.identity();
            if(!armedKey.equals(key)) { block();armedKey=key; }
            armedSettings=settings;
            drivingAxes=(1L<<settings.steering().id()) | (1L<<settings.throttle().id()) | (1L<<settings.brake().id());
        }
        var state=selected(settings);
        if(state==null || (state.observedAxes & drivingAxes)!=drivingAxes) { armed=false;return Drive.STOP; }
        var value=preview(settings,state);
        if(!armed) {
            if(value.throttle()<=.01f && value.brake()<=.01f && Math.abs(value.steer())<=.01f && !value.reverse()
                    && state.buttons.isEmpty() && state.awaitingRelease.isEmpty()) armed=true;
            return Drive.STOP;
        }
        return value;
    }
    public boolean armed() { return armed; }
    public void block() {disarm();resetMenu();}
    private void disarm() {armed=false;armedSettings=null;armedKey="";drivingAxes=0;}
    private void resetMenu() {menuDevice=null;menuWait=0;menuDirection=0;}
    public void focusLost() {
        for(var state:states.values()) state.clear(++lastGeneration);
        block();
    }
    /** 1 right, 2 down, 3 left, 4 up. Hysteresis plus bounded, frame-rate-independent repeat. */
    public int menu(ControlSettings settings,float dt) {
        if(!settings.enabled() || !Float.isFinite(dt) || dt<0) return 0;
        var state=selected(settings);if(state==null) return 0;
        if(menuDevice!=state || menuX!=settings.menuX() || menuY!=settings.menuY()) {
            resetMenu();
            menuDevice=state;
            menuX=settings.menuX();
            menuY=settings.menuY();
        }
        float x=state.raw(menuX,0),y=state.raw(menuY,0);
        float magnitude=Math.max(Math.abs(x),Math.abs(y));
        if(magnitude<MENU_RELEASE) { menuDirection=0;menuWait=0;return 0; }
        if(magnitude<.6f) return 0;
        int direction=Math.abs(x)>Math.abs(y)?(x>0?1:3):(y>0?2:4);
        if(direction!=menuDirection) { menuDirection=direction;menuWait=MENU_REPEAT_DELAY;return direction; }
        menuWait-=dt;
        if(menuWait>0) return 0;
        // Preserve the ordinary frame overshoot. After multiple missed repeats start a fresh interval.
        menuWait=menuWait<=-MENU_REPEAT_INTERVAL?MENU_REPEAT_INTERVAL:menuWait+MENU_REPEAT_INTERVAL;
        return direction;
    }
    private float value(ControlSettings.Axis axis,State state) { return axis.value(state.raw(axis.id(),Float.NaN)); }
    private State selected(ControlSettings settings) {return byKey.get(settings.device());}
}
