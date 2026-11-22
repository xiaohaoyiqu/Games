package com.f1car.input;

import com.f1car.model.ControlIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Raw device axis numbers are learned, never inferred from a marketing name. */
public record ControlSettings(int version,boolean enabled,String device,Axis steering,Axis throttle,Axis brake,
                              int menuX,int menuY,Map<Command,Integer> buttons) {
    public enum Command { CONFIRM,BACK,PAUSE,RESET,CAMERA,HELP,REVERSE,BOOST }
    public enum Channel { STEERING,THROTTLE,BRAKE }
    private record DrivingIdentity(Axis steering,Axis throttle,Axis brake,int reverse,String value) {
        boolean matches(ControlSettings settings,int reverseButton) {
            return reverse==reverseButton && steering.equals(settings.steering)
                    && throttle.equals(settings.throttle) && brake.equals(settings.brake);
        }
    }
    // A preview can compare today's bindings with an earlier attempt on every redraw.
    // Entries are immutable. Concurrent misses can evict an entry but cannot change its value.
    private static volatile DrivingIdentity latestIdentity,previousIdentity;
    public record Axis(int id,float rest,float negative,float positive,float deadzone,float curve,boolean signed) {
        public Axis {
            if(id< -1 || id>63 || !finiteRange(rest,-1,1) || !finiteRange(negative,-1,1) || !finiteRange(positive,-1,1)
                || !finiteRange(deadzone,0,.4f) || !finiteRange(curve,.5f,3) || Math.abs(positive-rest)<.25f
                || signed && (Math.abs(negative-rest)<.25f || (negative-rest)*(positive-rest)>=0))
                throw new IllegalArgumentException("Invalid controller axis");
        }
        public static Axis unbound(boolean signed) { return new Axis(-1,0,-1,1,.08f,1,signed); }
        public float value(float raw) {
            if(id<0 || !Float.isFinite(raw)) return 0;
            float displacement=Math.max(-1,Math.min(1,raw))-rest;
            boolean forward=displacement*(positive-rest)>=0;
            if(!signed && !forward) return 0;
            float value=Math.min(1,Math.abs(displacement/(forward?positive-rest:negative-rest)));
            value=value<=deadzone?0:(float)Math.pow((value-deadzone)/(1-deadzone),curve);
            return signed && !forward?-value:value;
        }
        public Axis response(float zone,float exponent) { return new Axis(id,rest,negative,positive,zone,exponent,signed); }
        public Axis inverted() { return signed?new Axis(id,rest,positive,negative,deadzone,curve,true):new Axis(id,positive,negative,rest,deadzone,curve,false); }
        // This spelling was used by the original gamepad-v1 record keys.
        private String identityText() {
            return "Axis[id="+id+", rest="+rest+", negative="+negative+", positive="+positive
                    +", deadzone="+deadzone+", curve="+curve+", signed="+signed+"]";
        }
    }
    public ControlSettings {
        if(version!=1 || device==null || device.length()>100 || !device.isEmpty() && !device.matches("[a-f0-9]{64}:[0-9]{1,2}")
            || steering==null || !steering.signed() || throttle==null || throttle.signed() || brake==null || brake.signed()
            || menuX< -1 || menuX>63 || menuY< -1 || menuY>63 || buttons==null || buttons.size()!=Command.values().length)
            throw new IllegalArgumentException("Invalid controller settings");
        var copy=new EnumMap<Command,Integer>(Command.class);copy.putAll(buttons);
        if(copy.values().stream().anyMatch(id->id==null || id< -1 || id>127)
            || copy.values().stream().filter(id->id>=0).distinct().count()!=copy.values().stream().filter(id->id>=0).count())
            throw new IllegalArgumentException("Conflicting controller buttons");
        buttons=Collections.unmodifiableMap(copy);
    }
    public static ControlSettings defaults() {
        var buttons=new EnumMap<Command,Integer>(Command.class);int[] ids={0,1,7,3,2,6,4,5};
        for(var command:Command.values()) buttons.put(command,ids[command.ordinal()]);
        return new ControlSettings(1,false,"",Axis.unbound(true),Axis.unbound(false),Axis.unbound(false),0,1,buttons);
    }
    public boolean calibrated() {
        return !device.isEmpty() && steering.id()>=0 && throttle.id()>=0 && brake.id()>=0
                && calibrationConflict().isEmpty();
    }
    public String calibrationConflict() {return calibrationConflict(null);}
    public String calibrationConflict(Channel changed) {
        if(steering.id()>=0 && (steering.id()==throttle.id() && changed!=Channel.BRAKE
                || steering.id()==brake.id() && changed!=Channel.THROTTLE))
            return "转向和踏板共用了同一轴，请分别校准。";
        if(throttle.id()<0 || throttle.id()!=brake.id() || changed==Channel.STEERING) return "";
        boolean throttleRises=throttle.positive()>throttle.rest();
        boolean brakeRises=brake.positive()>brake.rest();
        if(throttleRises==brakeRises) return "共轴油门和制动必须使用相反方向。";
        float throttleEdge=deadzoneEdge(throttle);
        float brakeEdge=deadzoneEdge(brake);
        if(throttleRises?throttleEdge<brakeEdge:brakeEdge<throttleEdge)
            return "共轴油门和制动的有效范围重叠，请松开两者重新校准。";
        return "";
    }
    private static float deadzoneEdge(Axis axis) {
        return axis.rest()+axis.deadzone()*(axis.positive()-axis.rest());
    }
    public Axis axis(Channel channel) { return switch(channel) { case STEERING->steering;case THROTTLE->throttle;case BRAKE->brake; }; }
    public ControlSettings enabled(boolean value) { return new ControlSettings(1,value,device,steering,throttle,brake,menuX,menuY,buttons); }
    public ControlSettings device(String value) { return new ControlSettings(1,enabled,value,steering,throttle,brake,menuX,menuY,buttons); }
    public ControlSettings axis(Channel channel,Axis value) { return new ControlSettings(1,enabled,device,channel==Channel.STEERING?value:steering,channel==Channel.THROTTLE?value:throttle,channel==Channel.BRAKE?value:brake,menuX,menuY,buttons); }
    public ControlSettings menu(boolean horizontal,int id) { return new ControlSettings(1,enabled,device,steering,throttle,brake,horizontal?id:menuX,horizontal?menuY:id,buttons); }
    public ControlSettings button(Command command,int id) {
        var copy=new EnumMap<>(buttons);int old=copy.get(command);
        for(var other:Command.values()) if(other!=command && id>=0 && copy.get(other)==id) copy.put(other,old);
        copy.put(command,id);return new ControlSettings(1,enabled,device,steering,throttle,brake,menuX,menuY,copy);
    }
    /** Paint, menu navigation, pause buttons and transient device IDs do not alter driving. */
    public String identity() {
        if(!enabled) return ControlIdentity.KEYBOARD;
        int reverse=buttons.get(Command.REVERSE);
        var latest=latestIdentity;
        if(latest!=null && latest.matches(this,reverse)) return latest.value();
        var previous=previousIdentity;
        if(previous!=null && previous.matches(this,reverse)) return previous.value();
        String value="gamepad-v1-"+hash(steering.identityText()+"|"+throttle.identityText()+"|"+brake.identityText()+"|reverse="+reverse);
        previousIdentity=latest;
        latestIdentity=new DrivingIdentity(steering,throttle,brake,reverse,value);
        return value;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean finiteRange(float value,float low,float high) { return Float.isFinite(value) && value>=low && value<=high; }
}
