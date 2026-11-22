package com.f1car.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Descriptive, bounded head-to-head facts. No inferred ability, automatic rival or AI adjustment. */
public final class RivalLedger {
    public record Conditions(String physics,Tuning tuning,int difficulty,int laps,String controls) {
        public Conditions {
            if(!text(physics,120) || tuning==null || difficulty<0 || difficulty>2 || laps!=1 && laps!=3 && laps!=5 || !EventSeries.UNSPECIFIED_CONTROLS.equals(controls) && !ControlIdentity.valid(controls)) throw new IllegalArgumentException("Invalid rival conditions");
            tuning=tuning.withLivery(0);
        }
        public String key() {
            String source="rival-v1\n"+physics+'\n'+Float.toHexString(tuning.power())+':'+Float.toHexString(tuning.brakes())+':'+Float.toHexString(tuning.suspension())+':'+Float.toHexString(tuning.wing())+'\n'+difficulty+':'+laps+'\n'+controls;
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        }
        public String controlsName() { return EventSeries.UNSPECIFIED_CONTROLS.equals(controls)?"历史操作条件未知":ControlIdentity.KEYBOARD.equals(controls)?"键盘":"手柄条件 "+controls.substring(11,19); }
    }
    public record Meeting(String eventId,int round,EventSeries.Kind kind,String eventTitle,String trackId,String trackName,String geometry,Conditions conditions,EventSeries.Result result,long recordedAt) {
        public Meeting {
            if(eventId==null || !eventId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || round<0 || round>7 || kind==null || kind==EventSeries.Kind.WEEKEND && round!=0 || kind==EventSeries.Kind.TOUR && round>2
                || !text(eventTitle,80) || trackId==null || !trackId.matches("[a-z0-9-]{1,60}") || !text(trackName,80) || geometry==null || !geometry.matches("[a-f0-9]{64}") || conditions==null || result==null || recordedAt<0) throw new IllegalArgumentException("Invalid rival meeting");
        }
        public String receipt() { return eventId+":"+round; }
        public String mapKey() { return trackId+":"+geometry; }
        public boolean sameFacts(Meeting other) { return receipt().equals(other.receipt()) && kind==other.kind && trackId.equals(other.trackId) && geometry.equals(other.geometry) && conditions.equals(other.conditions) && result.equals(other.result); }
        public OptionalDouble raceGap(int driver) { checkDriver(driver);var you=finish(0);var rival=finish(driver);return you.finished() && rival.finished()?OptionalDouble.of(you.total()-rival.total()):OptionalDouble.empty(); }
        public EventSeries.Finish finish(int driver) { return result.race().stream().filter(r->r.driver()==driver).findFirst().orElseThrow(); }
        public EventSeries.Qualifying qualifying(int driver) { return result.qualifying().stream().filter(r->r.driver()==driver).findFirst().orElseThrow(); }
    }
    public record Stats(int rounds,int qualifyingAhead,int qualifyingEqual,int qualifyingBehind,int qualifyingUncompared,int raceAhead,int raceEqual,int raceBehind,int playerOnlyFinish,int rivalOnlyFinish,int bothDnf) { }
    public record State(int version,int followed,List<Meeting> meetings) {
        public State {
            if(version!=1 || followed<1 || followed>3 || meetings==null || meetings.size()>256) throw new IllegalArgumentException("Invalid rival ledger");
            meetings=List.copyOf(meetings);if(meetings.stream().map(Meeting::receipt).distinct().count()!=meetings.size()) throw new IllegalArgumentException("Duplicate rival receipt");
        }
        public static State empty() { return new State(1,1,List.of()); }
        public State follow(int driver) { checkDriver(driver);return driver==followed?this:new State(version,driver,meetings); }
        public State record(Meeting meeting) {
            for(var old:meetings) if(old.receipt().equals(meeting.receipt())) { if(!old.sameFacts(meeting)) throw new IllegalArgumentException("Conflicting rival meeting");return this; }
            var next=new ArrayList<>(meetings);next.add(meeting);while(next.size()>256) next.remove(0);return new State(version,followed,next);
        }
        public List<Conditions> conditions() { var result=new LinkedHashMap<String,Conditions>();for(int i=meetings.size()-1;i>=0;i--) { var c=meetings.get(i).conditions();result.putIfAbsent(c.key(),c); }return List.copyOf(result.values()); }
        public List<Meeting> filter(String conditionKey,String mapKey) { return meetings.stream().filter(m->m.conditions().key().equals(conditionKey) && (mapKey.isEmpty() || m.mapKey().equals(mapKey))).toList(); }
        public Stats stats(String conditionKey,String mapKey,int driver) {
            return RivalLedger.stats(filter(conditionKey,mapKey),driver);
        }
    }

    public static Stats stats(List<Meeting> selected,int driver) {
        checkDriver(driver);
        int qa=0,qe=0,qb=0,qu=0,ra=0,re=0,rb=0,po=0,ro=0,dnf=0;
        for(var meeting:selected) {
            var youQ=meeting.qualifying(0);var rivalQ=meeting.qualifying(driver);
            if(!youQ.valid() || !rivalQ.valid()) qu++;else { int comparison=Long.compare(Math.round(youQ.lap()*1000),Math.round(rivalQ.lap()*1000));if(comparison<0) qa++;else if(comparison>0) qb++;else qe++; }
            var you=meeting.finish(0);var rival=meeting.finish(driver);
            if(you.finished() && rival.finished()) { int comparison=Long.compare(Math.round(you.total()*1000),Math.round(rival.total()*1000));if(comparison<0) ra++;else if(comparison>0) rb++;else re++; }
            else if(you.finished()) po++;else if(rival.finished()) ro++;else dnf++;
        }
        return new Stats(selected.size(),qa,qe,qb,qu,ra,re,rb,po,ro,dnf);
    }
    public static List<Meeting> accepted(EventSeries event,long recordedAt,Function<String,TrackLayout> resolve) {
        if(event==null) return List.of();var s=event.state();var meetings=new ArrayList<Meeting>();
        for(int i=0;i<s.results().size();i++) { var r=s.calendar().get(i);String name=r.trackId();try { var track=resolve.apply(r.trackId());if(track!=null && track.id.equals(r.trackId())) name=track.name; }catch(IllegalArgumentException ignored) { }
            var conditions=new Conditions(s.physicsVersion(),s.tuning(),s.difficulty(),s.laps(),s.raceControls().get(i));
            var result=s.results().get(i);
            var scores=new EventSeries.Result(result.qualifying(),result.race());
            meetings.add(new Meeting(s.eventId(),i,s.kind(),event.title(),r.trackId(),name,r.geometry(),conditions,scores,recordedAt)); }
        return List.copyOf(meetings);
    }
    private static void checkDriver(int driver) { if(driver<1 || driver>3) throw new IllegalArgumentException("Choose an original opponent"); }
    private static boolean text(String value,int maximum) { return value!=null && !value.isBlank() && value.length()<=maximum && value.chars().noneMatch(Character::isISOControl); }
    private RivalLedger() { }
}
