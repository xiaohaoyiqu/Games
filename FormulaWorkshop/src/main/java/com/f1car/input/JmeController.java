package com.f1car.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Thin adapter over jME raw input; raw endpoint calibration avoids double range remapping. */
public final class JmeController implements RawInputListener,JoystickConnectionListener,AutoCloseable {
    private static final Joystick[] EMPTY=new Joystick[0];
    private final InputManager manager;
    private final ControllerInput input;
    private final Consumer<Runnable> dispatch;
    private final Consumer<JoyButtonEvent> button;
    private final Runnable connectionChanged;
    private record ConnectionChange(Joystick joystick,boolean connected) {}
    private final ConcurrentLinkedQueue<ConnectionChange> pendingChanges=new ConcurrentLinkedQueue<>();
    private final AtomicBoolean changeTaskQueued=new AtomicBoolean();
    private Map<Integer,String> deviceKeys=new HashMap<>();
    private Map<Integer,Joystick> deviceInstances=Map.of();
    private final Set<Joystick> disconnected=Collections.newSetFromMap(new IdentityHashMap<>());
    private Joystick[] inventory;
    private volatile boolean closed;
    public JmeController(InputManager manager,ControllerInput input,Consumer<Runnable> dispatch,Consumer<JoyButtonEvent> button,Runnable connectionChanged) {
        this.manager=Objects.requireNonNull(manager);this.input=Objects.requireNonNull(input);this.dispatch=Objects.requireNonNull(dispatch);this.button=Objects.requireNonNull(button);this.connectionChanged=Objects.requireNonNull(connectionChanged);
        synchronizeInventory(null,false);manager.addRawInputListener(this);manager.addJoystickConnectionListener(this);
    }
    public void refresh() {
        applyPendingChanges();
        if(!closed && synchronizeInventory(null,false)) connectionChanged.run();
    }
    private boolean synchronizeInventory(Joystick changedDevice,boolean connected) {
        var available=Objects.requireNonNullElse(manager.getJoysticks(),EMPTY);
        boolean changedAvailability=false;
        if(changedDevice!=null) {
            changedAvailability=connected?disconnected.remove(changedDevice)
                    :listed(available,changedDevice) && disconnected.add(changedDevice);
        }
        if(!changedAvailability && unchanged(available)) return false;
        disconnected.removeIf(joystick->!listed(available,joystick));
        var devices=new ArrayList<ControllerInput.Device>();
        var keys=new HashMap<Integer,String>();
        var instances=new HashMap<Integer,Joystick>();
        var reservedKeys=new HashSet<>(deviceKeys.values());
        var replaced=new HashSet<Integer>();
        var candidates=new TreeMap<Integer,Joystick>();
        for(var joystick:available) {
            if(joystick==null || disconnected.contains(joystick)) continue;
            int id=joystick.getJoyId(),axes=joystick.getAxisCount(),buttons=joystick.getButtonCount();
            if(id<0 || axes<0 || axes>64 || buttons<0 || buttons>128) continue;
            if(deviceInstances.get(id)==joystick) candidates.put(id,joystick);
            else candidates.putIfAbsent(id,joystick);
        }
        for(var joystick:candidates.values()) {
            if(devices.size()==32) break;
            int id=joystick.getJoyId(),axes=joystick.getAxisCount(),buttons=joystick.getButtonCount();
            String name=Objects.toString(joystick.getName(),"Controller").replaceAll("\\p{Cntrl}","").strip();if(name.isEmpty()) name="Controller";if(name.length()>120) name=name.substring(0,120);
            String descriptor=name+"|"+axes+"|"+buttons+"|"+joystick.getAxes().stream().map(axis->axis.getAxisId()+":"+axis.getName()).toList();
            String fingerprint=ControlSettings.hash(descriptor),key=deviceKeys.get(id);
            if(key==null || !key.startsWith(fingerprint+":")) {
                int occurrence=0;
                while(occurrence<100 && reservedKeys.contains(fingerprint+":"+occurrence)) occurrence++;
                if(occurrence==100) continue;
                key=fingerprint+":"+occurrence;
            }
            keys.put(id,key);reservedKeys.add(key);
            instances.put(id,joystick);
            if(deviceInstances.get(id)!=null && deviceInstances.get(id)!=joystick) replaced.add(id);
            devices.add(new ControllerInput.Device(id,key,name,axes,buttons));
        }
        // Reserve the previous identity until the backend removes its disconnected object.
        for(var joystick:disconnected) {
            int id=joystick.getJoyId();
            String key=deviceKeys.get(id);
            if(key!=null) keys.putIfAbsent(id,key);
        }
        boolean changed=inventory!=null && (!devices.equals(input.devices()) || !replaced.isEmpty());
        input.devices(devices,replaced);
        deviceKeys=keys;
        deviceInstances=instances;
        inventory=available.clone();
        return changed;
    }
    private static boolean listed(Joystick[] available,Joystick target) {
        for(var joystick:available) if(joystick==target) return true;
        return false;
    }
    private boolean unchanged(Joystick[] available) {
        if(inventory==null || inventory.length!=available.length) return false;
        for(int index=0;index<inventory.length;index++) {
            if(inventory[index]!=available[index]) return false;
        }
        return true;
    }
    private void changed(Joystick joystick,boolean connected) {
        if(closed) return;
        pendingChanges.add(new ConnectionChange(joystick,connected));
        if(closed) {
            pendingChanges.clear();
        } else if(changeTaskQueued.compareAndSet(false,true)) {
            dispatch.accept(()->{
                changeTaskQueued.set(false);
                applyPendingChanges();
            });
        }
    }
    // Raw input and game updates may run before the dispatched task. Apply each notice once on the game thread.
    private void applyPendingChanges() {
        ConnectionChange change;
        while(!closed && (change=pendingChanges.poll())!=null) {
            boolean updated=synchronizeInventory(change.joystick(),change.connected());
            if(change.joystick()==null) {
                // Some backends report a change without identifying the device.
                input.focusLost();
                updated=true;
            }
            if(updated) connectionChanged.run();
        }
    }
    @Override public void onConnected(Joystick joystick) { changed(joystick,true); }
    @Override public void onDisconnected(Joystick joystick) { changed(joystick,false); }
    @Override public void onJoyAxisEvent(JoyAxisEvent event) {
        applyPendingChanges();
        int id=event.getJoyIndex();
        if(!closed && deviceInstances.get(id)==event.getAxis().getJoystick())
            input.axis(id,event.getAxisIndex(),event.getRawValue());
    }
    @Override public void onJoyButtonEvent(JoyButtonEvent event) {
        applyPendingChanges();
        int id=event.getJoyIndex();
        if(!closed && deviceInstances.get(id)==event.getButton().getJoystick()
                && (input.button(id,event.getButtonIndex(),event.isPressed()) || !event.isPressed()))
            button.accept(event);
    }
    @Override public void beginInput() { refresh(); }
    @Override public void endInput() { }
    @Override public void onMouseMotionEvent(MouseMotionEvent event) { }
    @Override public void onMouseButtonEvent(MouseButtonEvent event) { }
    @Override public void onKeyEvent(KeyInputEvent event) { }
    @Override public void onTouchEvent(TouchEvent event) { }
    @Override public void close() {
        if(closed) return;
        closed=true;
        pendingChanges.clear();
        manager.removeRawInputListener(this);manager.removeJoystickConnectionListener(this);
        input.focusLost();
    }
}
