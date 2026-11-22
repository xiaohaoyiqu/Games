package com.f1car.model;

import java.util.*;

/** Prospective impact on named activity references if this draft is saved; never mutates a card. */
public final class CircuitRevisionImpact {
    public enum Status { MATCHES,PHYSICS,GEOMETRY,AUTHOR_TARGET,REVALIDATE }
    public record Reference(String source,ActivityCard card) {public Reference {Objects.requireNonNull(source);Objects.requireNonNull(card);}}
    public record Impact(String source,String activity,int stage,Status status,String detail) {}
    private CircuitRevisionImpact() {}
    public static List<Impact> inspect(CircuitDocument draft,CircuitInspector.Report inspection,CircuitTrial.Proof proof,String physics,List<Reference> references) {
        if(references.size()>25) throw new IllegalArgumentException("Too many current activity references");var result=new ArrayList<Impact>();var track=inspection.track();boolean qualified=inspection.ready() && proof!=null && proof.matches(draft,physics);
        for(var reference:references) for(int index=0;index<reference.card().stages().size();index++) {
            var stage=reference.card().stages().get(index);if(!stage.trackId().equals(draft.id())) continue;Status status;String detail;
            if(!reference.card().physics().equals(physics)) {status=Status.PHYSICS;detail="活动固定的物理版本不同，保留原卡，不能沿用到当前条件。";}
            else if(!inspection.ready()) {status=Status.GEOMETRY;detail="当前草稿几何未通过，不能作为该项活动的可用地图；原卡和结果保留。";}
            else if(track==null || !stage.geometry().equals(track.geometryKey())) {status=Status.GEOMETRY;detail="若保存此草稿，该项地图条件不再匹配；原活动及结果保留，需要另建活动。";}
            else if(stage.target()!=null && !stage.target().matches(track,physics)) {status=Status.AUTHOR_TARGET;detail="路线身份仍相同，但作者计时目标的场景或道路条件已改变；不能沿用旧目标。";}
            else if(!qualified) {status=Status.REVALIDATE;detail="该项路线身份仍匹配，但草稿还需本机四车资格；保存不等于取得资格。";}
            else {status=Status.MATCHES;detail="当前草稿的路线条件与本机资格仍匹配；原卡不改写。";}
            result.add(new Impact(reference.source(),reference.card().name(),index+1,status,detail));
        }return List.copyOf(result);
    }
}
