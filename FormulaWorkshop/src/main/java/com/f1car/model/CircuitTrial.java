package com.f1car.model;

import java.util.*;

/** Promotion requires four actual cars to finish valid laps without recovery. */
public final class CircuitTrial {
    public static final String PROTOCOL="four-cars-stock-v1";
    public static final double LIMIT=300;
    public enum State { RUNNING,SUCCESS,FAILED }
    public enum Kind {
        FOUR_CARS(4,"四车验证"),SOLO(1,"作者单车试驾");
        public final int drivers;public final String title;Kind(int drivers,String title) { this.drivers=drivers;this.title=title; }
    }
    public record Proof(String documentKey,String physicsVersion,String protocol,List<Double> laps,long recordedAt) {
        public Proof {
            if(documentKey==null || !documentKey.matches("[a-f0-9]{64}") || physicsVersion==null || physicsVersion.isBlank() || physicsVersion.length()>120
                || protocol==null || protocol.isBlank() || laps==null || laps.size()!=4 || laps.stream().anyMatch(t->t==null || !Double.isFinite(t) || t<=0 || t>LIMIT) || recordedAt<0)
                throw new IllegalArgumentException("Invalid circuit validation record");
            laps=List.copyOf(laps);
        }
        public boolean matches(CircuitDocument document,String physicsVersion) { return PROTOCOL.equals(protocol) && this.physicsVersion.equals(physicsVersion) && documentKey.equals(document.driveKey()); }
    }
    private final CircuitDocument document;
    private final Kind kind;
    private final TrackLayout track;
    private final int startAnchor;
    private final CircuitDriveReview review;
    private State state=State.RUNNING;
    private String feedback="你与三辆 AI 都需完成一圈有效路线，越界或复位需重试。";
    private double elapsed;
    private List<Double> laps=List.of();
    public CircuitTrial(CircuitDocument document) {this(document,Kind.FOUR_CARS,0,CircuitInspector.inspect(document));}
    public static CircuitTrial solo(CircuitDocument document,int startAnchor) {
        return new CircuitTrial(document,Kind.SOLO,startAnchor,CircuitInspector.inspect(document));
    }
    CircuitTrial(CircuitDocument document,Kind kind,int startAnchor,CircuitInspector.Report inspection) {
        if(inspection.track()==null || kind==Kind.FOUR_CARS && !inspection.ready() || startAnchor<0 || startAnchor>=document.anchors().size()) throw new IllegalArgumentException("Circuit geometry is not ready");
        this.document=document;this.kind=kind;this.startAnchor=startAnchor;
        if(startAnchor==0) {
            track=inspection.track();
        } else {
            var points=new ArrayList<>(document.anchors());
            Collections.rotate(points,-startAnchor);
            track=document.points(points).track();
        }
        review=new CircuitDriveReview(document,kind,startAnchor);if(kind==Kind.SOLO) feedback="从所选路线点试驾；R 重开，暂停后可返回编辑器查看位置。";
    }
    public Kind kind() { return kind; }
    public TrackLayout track() { return track; }
    public int startAnchor() { return startAnchor; }
    public CircuitDriveReview review() { return review; }
    public State state() { return state; }
    public boolean complete() { return state!=State.RUNNING; }
    public String feedback() { return feedback; }
    public double elapsed() { return elapsed; }
    public void abort(String reason) { if(!complete()) { state=State.FAILED;feedback=Objects.requireNonNull(reason);review.end(CircuitDriveReview.Status.FAILED,reason,elapsed); } }
    public void stop() { if(!complete()) { state=State.FAILED;feedback="已结束试跑，可返回编辑器继续调整。";review.end(CircuitDriveReview.Status.STOPPED,feedback,elapsed); } }
    public void update(double time,List<LapProgress> cars) {
        if(complete()) return;
        if(!Double.isFinite(time) || time<elapsed || cars==null || cars.size()!=kind.drivers) { abort("试跑记录异常，请重试。");return; }
        for(int i=0;i<kind.drivers;i++) {
            if(cars.get(i)==null) {abort("试跑记录异常，请重试。");return;}
        }
        elapsed=time;
        boolean allFinished=true;
        for(int i=0;i<cars.size();i++) {
            var car=cars.get(i);
            boolean finished=car.finished();
            if(!car.validLap() || car.completedLaps()>1 || finished && (!car.lastLapValid() || car.completedLaps()!=1 || !Double.isFinite(car.lastLap()) || car.lastLap()<=0
                || !Double.isFinite(car.finishTime()) || car.finishTime()>Math.min(LIMIT,time) || car.finishTime()<car.lastLap())) { abort("车辆 "+(i+1)+" 的圈无效，请调整地图或重新试跑。");return; }
            allFinished&=finished;
        }
        if(allFinished) {
            laps=cars.stream().map(LapProgress::lastLap).toList();state=State.SUCCESS;feedback=kind==Kind.FOUR_CARS?"四辆车均合法完赛，可保存并加入单机选图。":"单车合法完成一圈，可返回编辑器继续调整。";review.end(CircuitDriveReview.Status.SUCCESS,feedback,elapsed);
        } else if(time>=LIMIT) {
            var car=review.firstCarInLatestFrame();
            if(car!=null) review.note(CircuitDriveReview.Event.TIMEOUT,car.car(),car.x(),car.z(),"五分钟内未完成本次试跑。");
            abort("五分钟内未能全部合法完赛，请调整路线或重试。");
        }
    }
    public Proof proof(String physicsVersion,long timestamp) {
        if(kind!=Kind.FOUR_CARS || state!=State.SUCCESS) throw new IllegalStateException("No completed four-car circuit validation");
        return new Proof(document.driveKey(),physicsVersion,PROTOCOL,laps,timestamp);
    }
}
