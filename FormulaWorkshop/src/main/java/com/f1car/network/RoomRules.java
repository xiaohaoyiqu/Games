package com.f1car.network;

import com.f1car.model.Tuning;
import com.google.gson.Gson;
import java.util.*;

/** One immutable, acknowledged configuration covers the complete event calendar. */
public record RoomRules(Format format,int laps,RaceSession.VehicleRule vehicles,Tuning fixed,boolean teams,List<RoomTrack> calendar) {
    public enum Format {
        QUICK_RACE("单场短赛"),PRACTICE("共同练习"),WEEKEND("短赛事周末"),TOUR("三站积分杯");
        public final String title;Format(String title) { this.title=title; }
    }
    public RoomRules {
        if(format==null || laps!=1 && laps!=3 && laps!=5 || vehicles==null || fixed==null || calendar==null || calendar.size()!=(format==Format.TOUR?3:1))
            throw new IllegalArgumentException("Invalid room rules");
        calendar=List.copyOf(calendar);
    }
    public static RoomRules quick(RoomTrack track,RaceSession.Settings settings) {
        return new RoomRules(settings.activity()==RaceSession.Activity.PRACTICE?Format.PRACTICE:Format.QUICK_RACE,settings.laps(),settings.vehicles(),settings.fixed(),false,List.of(track));
    }
    public String key() { return RoomTrack.digest(new Gson().toJson(this)); }
    public RoomProgram.Phase initialPhase() { return format==Format.QUICK_RACE?RoomProgram.Phase.RACE:RoomProgram.Phase.PRACTICE; }
    public RaceSession.Settings settings(RoomProgram.Phase phase) {
        var activity=phase==RoomProgram.Phase.PRACTICE?RaceSession.Activity.PRACTICE:phase==RoomProgram.Phase.QUALIFYING?RaceSession.Activity.QUALIFYING:RaceSession.Activity.RACE;
        return new RaceSession.Settings(activity,activity==RaceSession.Activity.QUALIFYING?1:laps,vehicles,fixed);
    }
    public RoomRules format(Format value) {
        var maps=new ArrayList<RoomTrack>();maps.add(calendar.get(0));
        if(value==Format.TOUR) for(var entry:com.f1car.model.TrackCatalog.all()) if(maps.size()<3 && maps.stream().noneMatch(map->map.id().equals(entry.id()))) maps.add(RoomTrack.builtin(entry.id()));
        return new RoomRules(value,laps,vehicles,fixed,teams,maps);
    }
    public RoomRules map(int index,RoomTrack track) { var maps=new ArrayList<>(calendar);maps.set(index,track);return new RoomRules(format,laps,vehicles,fixed,teams,maps); }
}
