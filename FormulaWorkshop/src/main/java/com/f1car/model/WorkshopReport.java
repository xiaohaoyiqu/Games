package com.f1car.model;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable result and factual debrief for optional, local workshop commissions. */
public record WorkshopReport(int version,String id,long recordedAt,WorkshopSession.Kind kind,Conditions conditions,Tuning tuning,Grade grade,double elapsed,Metrics metrics,String reason) {
    public enum Grade { RETRY("待重试"),COMPLETE("完成"),STEADY("稳健"),PRECISE("出色");public final String title;Grade(String title) { this.title=title; } }
    public record Conditions(String trackId,String trackName,String geometry,String physics,String controls) {
        public Conditions {
            if(trackId==null || !trackId.matches("[a-z0-9][a-z0-9-]{0,63}") || trackName==null || trackName.isBlank() || trackName.length()>80 || trackName.chars().anyMatch(Character::isISOControl) || geometry==null || !geometry.matches("[a-f0-9]{64}") || physics==null || physics.isBlank() || physics.length()>120 || physics.chars().anyMatch(Character::isISOControl) || !ControlIdentity.valid(controls)) throw new IllegalArgumentException("Invalid workshop conditions");
        }
        public static Conditions of(WorkshopSession.Kind kind,TrackLayout track,String physics) {
            return of(kind,track,physics,ControlIdentity.KEYBOARD);
        }
        public static Conditions of(WorkshopSession.Kind kind,TrackLayout track,String physics,String controls) {
            return kind==WorkshopSession.Kind.PARK?new Conditions("training-pad","综合训练场",padGeometry(),physics,controls):new Conditions(track.id,track.name,track.geometryKey(),physics,controls);
        }
        private static String padGeometry() {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("workshop-pad-v1:spawn=-65,-100;gate=-18;entry=36..60;zone=-65,28,4,10;hold=2;speed<0.4;lane=8;end=45".getBytes(StandardCharsets.UTF_8))); }
            catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        }
    }
    public record Metrics(double entryKph,double stopError,double hold,double strongTime,double peakDeceleration,double spill) {
        public Metrics { for(double value:new double[]{entryKph,stopError,hold,strongTime,peakDeceleration,spill}) if(!Double.isFinite(value) || value< -1 || value>2000) throw new IllegalArgumentException("Invalid workshop metrics");if(entryKph>720 || stopError>1000 || spill>100 || hold>120 || strongTime>120) throw new IllegalArgumentException("Unbounded workshop metrics"); }
    }
    public WorkshopReport {
        if(version!=1 || id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || recordedAt<0 || kind==null || conditions==null || tuning==null || grade==null || !Double.isFinite(elapsed) || elapsed<0 || elapsed>121 || metrics==null || reason==null || reason.isBlank() || reason.length()>160 || reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid workshop report");
        if(grade!=Grade.RETRY && elapsed<=0) throw new IllegalArgumentException("Missing workshop duration");
        if(switch(kind) { case PARK->metrics.hold()<0;case SOFT->metrics.strongTime()<0 || metrics.peakDeceleration()<0;case CUP->metrics.spill()<0; }) throw new IllegalArgumentException("Missing observed workshop metric");
        if(grade!=Grade.RETRY && switch(kind) { case PARK->metrics.entryKph()<36 || metrics.entryKph()>60 || metrics.stopError()<0 || metrics.stopError()>11 || metrics.hold()<2;case SOFT->metrics.strongTime()<0 || metrics.strongTime()>2+1e-6 || metrics.peakDeceleration()<0;case CUP->metrics.spill()<0 || elapsed>=110; }) throw new IllegalArgumentException("Incomplete successful workshop report");
        if(grade!=Grade.RETRY && grade!=gradeFor(kind,metrics)) throw new IllegalArgumentException("Workshop grade disagrees with metrics");
    }
    public boolean success() { return grade!=Grade.RETRY; }
    public static Grade gradeFor(WorkshopSession.Kind kind,Metrics metrics) {
        return switch(kind) { case PARK->metrics.stopError()<=1?Grade.PRECISE:metrics.stopError()<=3?Grade.STEADY:Grade.COMPLETE;case SOFT->metrics.strongTime()<=.25+1e-6?Grade.PRECISE:metrics.strongTime()<=1+1e-6?Grade.STEADY:Grade.COMPLETE;case CUP->metrics.spill()<15?Grade.PRECISE:metrics.spill()<40?Grade.STEADY:Grade.COMPLETE; };
    }
    public String key() { return key(kind,conditions,tuning); }
    public static String key(WorkshopSession.Kind kind,Conditions conditions,Tuning tuning) { return "workshop-v1:"+kind.name()+":"+conditions.trackId()+":"+conditions.geometry()+":"+conditions.physics()+":"+conditions.controls()+":"+tuning.performanceKey(); }
    public double value() { return switch(kind) { case PARK->metrics.stopError();case SOFT->metrics.strongTime();case CUP->metrics.spill(); }; }
    public String observation() {
        return switch(kind) {
            case PARK -> metrics.entryKph()<0?"尚未以有效速度通过测速门。":String.format(Locale.ROOT,"入口 %.1f km/h；停稳 %.1f s%s",metrics.entryKph(),Math.max(0,metrics.hold()),metrics.stopError()<0?"。":String.format(Locale.ROOT,"；中心偏差 %.2f m。",metrics.stopError()));
            case SOFT -> String.format(Locale.ROOT,"强减速 %.2f / 2.00 s；平滑后的最大减速度 %.1f m/s²。",metrics.strongTime(),metrics.peakDeceleration());
            case CUP -> String.format(Locale.ROOT,"虚拟洒水 %.1f%%；活动用时 %.1f s。",metrics.spill(),elapsed);
        };
    }
    public String response(boolean humor) {
        String next=success()?switch(kind) { case PARK->"交接位置已记录，可以重试缩小停车偏差。";case SOFT->"本次在预算内通过全部路门，可以换地图比较节奏。";case CUP->"有效整圈已记录，可以再比较虚拟洒水量。"; }:reason;
        return next+(humor && success()?switch(kind) { case PARK->" 技师终于不用追着车递扳手了。";case SOFT->" 包装箱没有提出新的岗位要求。";case CUP->" 清洁组决定暂时保留这份报告。"; }:"");
    }
    public DriverJournal.Notice notice() { return new DriverJournal.Notice("workshop:"+id,recordedAt,DriverJournal.Activity.CUP,conditions.trackId()+":"+conditions.geometry()+":"+conditions.physics(),conditions.trackName(),kind.title+" · "+grade.title,success(),elapsed,"",ChallengeBook.Medal.NONE,0); }
}
