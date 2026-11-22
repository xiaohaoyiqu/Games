package com.f1car.model;

import java.util.*;

/** Driving observations for the circuit author's review. */
public final class CircuitDriveReview {
    public enum Status { RUNNING,SUCCESS,FAILED,STOPPED }
    public enum Event {
        INVALID("圈无效"),LOW_SPEED("持续低速"),CONTACT("车辆接触"),RECOVERY("需要恢复"),HEIGHT("离开高度范围"),TIMEOUT("试跑超时");
        public final String title;Event(String title) { this.title=title; }
    }
    public record Observation(int car,String name,float x,float z,float speed,float throttle,int nextGate,boolean valid,boolean finished,int recoveries) {
        boolean usable(int count) { return car>=0 && car<count && name!=null && !name.isBlank() && name.length()<=80 && Float.isFinite(x) && Float.isFinite(z) && Math.abs(x)<=1000 && Math.abs(z)<=1000 && Float.isFinite(speed) && speed>=0 && speed<=200 && Float.isFinite(throttle) && Math.abs(throttle)<=1 && nextGate>=0 && nextGate<18 && recoveries>=0; }
    }
    public record Marker(Event event,int car,double time,float x,float z,String message) {
        public Marker {
            if(event==null || car<0 || car>3 || !Double.isFinite(time) || time<0 || time>300 || !Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>1000 || Math.abs(z)>1000 || message==null || message.isBlank() || message.length()>160) throw new IllegalArgumentException("Invalid circuit review marker");
        }
        public CircuitInspector.Location location() { return new CircuitInspector.Location(x,z); }
    }
    public record Snapshot(String documentKey,CircuitTrial.Kind kind,int startAnchor,double elapsed,Status status,String summary,
                           List<Observation> cars,List<Marker> markers,List<CircuitDriveTrail.Path> trails) {
        public Snapshot(String documentKey,CircuitTrial.Kind kind,int startAnchor,double elapsed,Status status,String summary,
                        List<Observation> cars,List<Marker> markers) {
            this(documentKey,kind,startAnchor,elapsed,status,summary,cars,markers,List.of());
        }
        public Snapshot {
            if(documentKey==null || !documentKey.matches("[a-f0-9]{64}") || kind==null || startAnchor<0 || startAnchor>47 || !Double.isFinite(elapsed) || elapsed<0 || elapsed>300 || status==null || summary==null || summary.length()>160 || cars==null || cars.size()>kind.drivers || markers==null || markers.size()>64 || cars.stream().anyMatch(car->!car.usable(kind.drivers)) || markers.stream().anyMatch(marker->marker.car()>=kind.drivers)) throw new IllegalArgumentException("Invalid circuit drive review");
            cars=List.copyOf(cars);markers=List.copyOf(markers);
            trails=trails==null?List.of():List.copyOf(trails);
            if(trails.size()>kind.drivers || trails.stream().anyMatch(path->path.car()>=kind.drivers)
                    || trails.stream().map(CircuitDriveTrail.Path::car).distinct().count()!=trails.size())
                throw new IllegalArgumentException("Invalid circuit review trails");
        }
        public boolean matches(CircuitDocument document) { return documentKey.equals(document.driveKey()); }
    }
    private final String documentKey;
    private final CircuitTrial.Kind kind;
    private final int startAnchor;
    private final Observation[] latestByCar;
    private final int[] observationOrder;
    private final List<Marker> markers=new ArrayList<>();
    private final double[] slow;
    private final boolean[] slowReported;
    private final boolean[][] activeContacts;
    private final CircuitDriveTrail[] trails;
    private double elapsed;
    private Status status=Status.RUNNING;
    private String summary="试跑进行中";
    private Snapshot completedSnapshot;
    public CircuitDriveReview(CircuitDocument document,CircuitTrial.Kind kind,int startAnchor) {
        this.documentKey=document.driveKey();
        this.kind=Objects.requireNonNull(kind);
        this.startAnchor=startAnchor;
        latestByCar=new Observation[kind.drivers];
        observationOrder=new int[kind.drivers];
        slow=new double[kind.drivers];
        slowReported=new boolean[kind.drivers];
        activeContacts=new boolean[kind.drivers][kind.drivers];
        trails=new CircuitDriveTrail[kind.drivers];
        Arrays.setAll(trails,index->new CircuitDriveTrail());
    }
    public boolean observe(double time,List<Observation> observed) {
        if(status!=Status.RUNNING) return false;
        if(!Double.isFinite(time) || time<elapsed || time>300 || observed==null || observed.size()!=kind.drivers)
            return rejectObservation();
        int seen=0;
        for(int i=0;i<kind.drivers;i++) {
            var car=observed.get(i);
            if(car==null || !car.usable(kind.drivers)) return rejectObservation();
            int bit=1<<car.car();
            if((seen&bit)!=0) return rejectObservation();
            seen|=bit;
        }
        double dt=Math.min(.25,time-elapsed);elapsed=time;
        for(int i=0;i<kind.drivers;i++) {
            var car=observed.get(i);
            int driver=car.car();
            var previous=latestByCar[driver];
            latestByCar[driver]=car;
            observationOrder[i]=driver;
            trails[driver].observe(time,car);
            if(previous!=null && car.recoveries()!=previous.recoveries()) endContactsFor(driver);
            if(!car.valid() && (previous==null || previous.valid())) note(Event.INVALID,driver,car.x(),car.z(),"这辆车的圈已无效，请查看附近路线与门线。");
            if(!car.finished() && car.speed()<.5f && car.throttle()>.5f) {
                slow[driver]+=dt;if(slow[driver]>=4 && !slowReported[driver]) { slowReported[driver]=true;note(Event.LOW_SPEED,driver,car.x(),car.z(),"有油门指令时持续低速至少四秒；请结合场景与线路观察。"); }
            } else { slow[driver]=0;slowReported[driver]=false; }
        }
        return true;
    }
    private boolean rejectObservation() {
        for(var trail:trails) trail.interrupt();
        return false;
    }
    private void endContactsFor(int driver) {
        for(int other=0;other<kind.drivers;other++) activeContacts[Math.min(driver,other)][Math.max(driver,other)]=false;
    }
    Observation firstCarInLatestFrame() {
        return latestByCar[observationOrder[0]];
    }
    /** Sample each pair in index order after observing the cars, including pairs that have separated. */
    public void observeContact(int first,int second,boolean touching) {
        if(status!=Status.RUNNING || first<0 || second<=first || second>=kind.drivers) return;
        var car=latestByCar[first];
        if(car==null) return;
        boolean started=touching && !activeContacts[first][second];
        activeContacts[first][second]=touching;
        if(started) {
            addMarker(new Marker(Event.CONTACT,first,elapsed,car.x(),car.z(),
                    "与车辆 "+(second+1)+" 的接触在此首次采到。"));
        }
    }
    public boolean note(Event event,int car,float x,float z,String message) {
        if(status!=Status.RUNNING || car<0 || car>=kind.drivers || !Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x)>1000 || Math.abs(z)>1000) return false;
        var marker=new Marker(event,car,elapsed,x,z,message);
        if(markers.stream().anyMatch(old->old.event()==event && old.car()==car && elapsed-old.time()<2 && Math.hypot(old.x()-x,old.z()-z)<4)) return false;
        addMarker(marker);
        return true;
    }
    private void addMarker(Marker marker) {
        if(markers.size()<64) markers.add(marker);else markers.set(63,marker);
    }
    public void end(Status value,String reason,double time) {
        if(status!=Status.RUNNING) return;if(value==null || value==Status.RUNNING || reason==null || reason.length()>160 || !Double.isFinite(time) || time<0) throw new IllegalArgumentException("Invalid review completion");
        elapsed=Math.min(300,Math.max(elapsed,time));status=value;summary=reason;
    }
    public Snapshot snapshot() {
        if(completedSnapshot!=null) return completedSnapshot;
        var cars=new ArrayList<Observation>(kind.drivers);
        if(firstCarInLatestFrame()!=null) for(int driver:observationOrder) cars.add(latestByCar[driver]);
        var paths=new ArrayList<CircuitDriveTrail.Path>(trails.length);
        for(var trail:trails) {
            var path=trail.snapshot();
            if(path!=null) paths.add(path);
        }
        var snapshot=new Snapshot(documentKey,kind,startAnchor,elapsed,status,summary,cars,markers,paths);
        if(snapshot.status()!=Status.RUNNING) completedSnapshot=snapshot;
        return snapshot;
    }
}
