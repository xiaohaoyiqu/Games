package com.f1car.input;

import com.jme3.input.KeyInput;
import com.jme3.input.KeyNames;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Physical keys change placement only; keyboard driving retains its existing record identity. */
public record KeyboardSettings(int version,Map<Command,Binding> bindings) {
    private static final Pattern HINT_KEY = Pattern.compile("(?<![A-Za-z0-9_-])(SPACE|R|H)(?![A-Za-z0-9_-])");
    public enum Command {
        THROTTLE("throttle","油门"),BRAKE("brake","制动／倒车"),LEFT("left","左转"),RIGHT("right","右转"),
        HANDBRAKE("handbrake","全制动"),RESET("reset","复位／重试"),CAMERA("camera","镜头"),HELP("help","帮助"),GHOST("ghost","幽灵开关");
        public final String action,title;
        Command(String action,String title) { this.action=action;this.title=title; }
    }
    public record Binding(int primary,int alternate) {
        public Binding { if(!allowed(primary) || alternate!=-1 && !allowed(alternate) || primary==alternate) throw new IllegalArgumentException("无效的驾驶按键"); }
        public int[] keys() { return alternate<0?new int[]{primary}:new int[]{primary,alternate}; }
    }
    public KeyboardSettings {
        if(version!=1 || bindings==null || bindings.size()!=Command.values().length) throw new IllegalArgumentException("无效的键盘设置");
        var copy=new EnumMap<Command,Binding>(Command.class);copy.putAll(bindings);var used=new HashSet<Integer>();
        for(var command:Command.values()) { var binding=copy.get(command);if(binding==null) throw new IllegalArgumentException("缺少驾驶按键");for(int key:binding.keys()) if(!used.add(key)) throw new IllegalArgumentException("按键已被其他驾驶动作使用"); }
        bindings=Collections.unmodifiableMap(copy);
    }
    public static KeyboardSettings defaults() {
        var map=new EnumMap<Command,Binding>(Command.class);
        int[][] keys={{KeyInput.KEY_W,KeyInput.KEY_UP},{KeyInput.KEY_S,KeyInput.KEY_DOWN},{KeyInput.KEY_A,KeyInput.KEY_LEFT},{KeyInput.KEY_D,KeyInput.KEY_RIGHT},
            {KeyInput.KEY_SPACE,-1},{KeyInput.KEY_R,-1},{KeyInput.KEY_TAB,KeyInput.KEY_C},{KeyInput.KEY_H,KeyInput.KEY_F1},{KeyInput.KEY_G,-1}};
        for(var command:Command.values()) map.put(command,new Binding(keys[command.ordinal()][0],keys[command.ordinal()][1]));return new KeyboardSettings(1,map);
    }
    public KeyboardSettings bind(Command command,boolean alternate,int key) {
        Objects.requireNonNull(command);if(!allowed(key)) throw new IllegalArgumentException("该按键保留给菜单、截图或编辑器组合键，请换一个键");
        var map=new EnumMap<Command,Binding>(bindings);var old=map.get(command);
        for(var entry:map.entrySet()) for(int occupied:entry.getValue().keys())
            if(occupied==key && (entry.getKey()!=command || (alternate?old.primary():old.alternate())==key)) throw new IllegalArgumentException(name(key)+" 已用于"+entry.getKey().title);
        map.put(command,alternate?new Binding(old.primary(),key):new Binding(key,old.alternate()));return new KeyboardSettings(1,map);
    }
    public String label(Command command) { var b=bindings.get(command);return name(b.primary())+(b.alternate()<0?"":" / "+name(b.alternate())); }
    public String drivingHint(String text) {
        return HINT_KEY.matcher(text).replaceAll(match -> {
            var command = switch (match.group()) {
                case "SPACE" -> Command.HANDBRAKE;
                case "H" -> Command.HELP;
                default -> Command.RESET;
            };
            return Matcher.quoteReplacement(label(command));
        });
    }
    public static String name(int key) { if(key<0) return "未设";String name=KeyNames.getName(key);return name==null?"键 "+key:name; }
    private static boolean allowed(int key) {
        return key>0 && key<=255 && KeyNames.getName(key)!=null && !Set.of(KeyInput.KEY_ESCAPE,KeyInput.KEY_RETURN,KeyInput.KEY_NUMPADENTER,
            KeyInput.KEY_F12,KeyInput.KEY_F6,KeyInput.KEY_LCONTROL,KeyInput.KEY_RCONTROL,KeyInput.KEY_LMENU,KeyInput.KEY_RMENU,KeyInput.KEY_LSHIFT,KeyInput.KEY_RSHIFT).contains(key);
    }
}
