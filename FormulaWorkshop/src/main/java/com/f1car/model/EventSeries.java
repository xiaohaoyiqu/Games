package com.f1car.model;

import java.util.*;

/** Immutable stage checkpoints. An accepted result cannot be scored a second time. */
public final class EventSeries {
    public enum Kind { WEEKEND, TOUR, CUSTOM }
    public static final String UNSPECIFIED_CONTROLS="legacy-unspecified";
    public enum Phase { QUALIFYING, RACE, REVIEW, COMPLETE }
    public static final int DRIVERS=4;
    private static final int[] POINTS={10,6,4,2};
    private static final String[] NAMES={"你 · 岚隼","01 · 潮汐","02 · 银杉","03 · 曜石"};

    public record Round(String trackId,String geometry) {
        public Round {
            if(trackId==null || !trackId.matches("[a-z0-9-]{1,60}") || geometry==null || !geometry.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid event circuit");
        }
        public TrackLayout layout() { return TrackCatalog.require(trackId).layout(); }
    }
    public record Qualifying(int driver,double lap,double setAt) {
        public Qualifying {
            checkDriver(driver);
            if(!Double.isFinite(lap) || !Double.isFinite(setAt) || setAt>86400 || !(lap==-1 && setAt==-1 || lap>0 && setAt>=lap))
                throw new IllegalArgumentException("Invalid qualifying result");
        }
        public boolean valid() { return lap>0; }
        public static Qualifying noTime(int driver) { return new Qualifying(driver,-1,-1); }
    }
    public record Finish(int driver,boolean finished,double elapsed,double penalty,float distance) {
        public Finish {
            checkDriver(driver);
            if(!Double.isFinite(elapsed) || elapsed>86400 || (finished ? elapsed<=0 : elapsed!=-1) || !Double.isFinite(penalty) || penalty<0 || penalty>86400
                || !Float.isFinite(distance) || distance<0 || distance>1_000_000) throw new IllegalArgumentException("Invalid race result");
        }
        public double total() { return finished?elapsed+penalty:Double.POSITIVE_INFINITY; }
    }
    public record Settlement(EventRaceClock.Limit limit,double elapsed,String replayId) {
        public Settlement(EventRaceClock.Limit limit,double elapsed) { this(limit,elapsed,null); }
        public Settlement {
            if(limit==null || !Double.isFinite(elapsed) || elapsed<=0 || elapsed>86400)
                throw new IllegalArgumentException("Invalid race settlement");
            if(replayId!=null && !replayId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                throw new IllegalArgumentException("Invalid race replay identity");
        }

        private void verify(int laps,List<Finish> race) {
            int finishers=0;
            double firstFinish=Double.POSITIVE_INFINITY;
            for(var finish:race) {
                if(!finish.finished()) continue;
                if(finish.elapsed()>elapsed) throw new IllegalArgumentException("Finish after race settlement");
                finishers++;
                firstFinish=Math.min(firstFinish,finish.elapsed());
            }
            var clock=EventRaceClock.read(laps,elapsed,finishers,firstFinish);
            // Earlier saves checked the wait with elapsed minus first finish rather than an absolute deadline.
            boolean expired=clock.readyToSettle() || limit==EventRaceClock.Limit.FIRST_FINISH
                    && elapsed-firstFinish>=EventRaceClock.FIRST_FINISH_WAIT_SECONDS;
            if(!expired || clock.limit()!=limit)
                throw new IllegalArgumentException("Race settlement does not match the recorded results and lap count");
        }
    }
    public record Result(List<Qualifying> qualifying,List<Finish> race,Settlement settlement) {
        public Result(List<Qualifying> qualifying,List<Finish> race) { this(qualifying,race,null); }
        public Result { qualifying=orderedQualifying(qualifying);race=orderedRace(race); }
        public int points(int driver) {
            for(int i=0;i<race.size();i++) if(race.get(i).driver()==driver) return race.get(i).finished()?POINTS[i]:0;
            throw new IllegalArgumentException("Unknown driver");
        }
    }
    public record Standing(int driver,int points,List<Integer> finishes,List<Integer> qualifying) {}
    public record State(int version,Kind kind,String physicsVersion,Tuning tuning,int difficulty,int laps,
                        List<Round> calendar,int round,Phase phase,List<Qualifying> qualifying,List<Result> results,String eventId,List<String> raceControls) {
        public State(int version,Kind kind,String physicsVersion,Tuning tuning,int difficulty,int laps,List<Round> calendar,int round,
                     Phase phase,List<Qualifying> qualifying,List<Result> results) {
            this(version,kind,physicsVersion,tuning,difficulty,laps,calendar,round,phase,qualifying,results,UUID.randomUUID().toString());
        }
        public State(int version,Kind kind,String physicsVersion,Tuning tuning,int difficulty,int laps,List<Round> calendar,int round,
                     Phase phase,List<Qualifying> qualifying,List<Result> results,String eventId) {
            this(version,kind,physicsVersion,tuning,difficulty,laps,calendar,round,phase,qualifying,results,eventId,null);
        }
        public State {
            if(eventId==null || !eventId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid event identity");
            if(version!=1 || kind==null || phase==null || physicsVersion==null || physicsVersion.isBlank() || physicsVersion.length()>120
                || tuning==null || difficulty<0 || difficulty>2 || laps!=1 && laps!=3 && laps!=5)
                throw new IllegalArgumentException("Invalid event settings");
            calendar=List.copyOf(Objects.requireNonNull(calendar));results=List.copyOf(Objects.requireNonNull(results));
            qualifying=List.copyOf(Objects.requireNonNull(qualifying));
            int expected=kind==Kind.CUSTOM?calendar.size():kind==Kind.WEEKEND?1:3;
            if(expected<1 || expected>8 || calendar.size()!=expected || round<0 || round>=expected) throw new IllegalArgumentException("Invalid event calendar");
            raceControls=raceControls==null?Collections.nCopies(results.size(),UNSPECIFIED_CONTROLS):List.copyOf(raceControls);
            if(raceControls.size()!=results.size() || raceControls.stream().anyMatch(value->!UNSPECIFIED_CONTROLS.equals(value) && !ControlIdentity.valid(value))) throw new IllegalArgumentException("Invalid event control history");
            boolean reviewed=phase==Phase.REVIEW || phase==Phase.COMPLETE;
            if(results.size()!=round+(reviewed?1:0) || phase==Phase.REVIEW && round==expected-1
                || phase==Phase.COMPLETE && round!=expected-1) throw new IllegalArgumentException("Invalid event history");
            var replayIds=new HashSet<String>();
            for(var result:results) {
                var settlement=result.settlement();
                if(settlement==null) continue;
                settlement.verify(laps,result.race());
                if(settlement.replayId()!=null && !replayIds.add(settlement.replayId()))
                    throw new IllegalArgumentException("Race replay is already linked to another round");
            }
            if(phase==Phase.QUALIFYING) {
                if(!qualifying.isEmpty()) throw new IllegalArgumentException("Qualifying already committed");
            } else {
                qualifying=orderedQualifying(qualifying);
                if(reviewed && !results.get(results.size()-1).qualifying().equals(qualifying))
                    throw new IllegalArgumentException("Qualifying/history mismatch");
            }
        }
    }
    static boolean matchesLinkedReplay(RaceReplay replay,State event,int round) {
        var result=event.results().get(round);
        var settlement=result.settlement();
        return replay!=null && settlement!=null && replay.id().equals(settlement.replayId())
                && matchesCompletedReplay(replay,event,round,settlement.elapsed(),result.race());
    }

    public static boolean matchesCompletedReplay(RaceReplay replay,State event,int round,double elapsed,List<Finish> finishes) {
        if(replay==null || replay.source()!=RaceReplay.Source.LOCAL || replay.end()!=RaceReplay.End.COMPLETED
                || replay.qualifying() || replay.laps()!=event.laps() || !replay.physics().equals(event.physicsVersion())
                || round<0 || round>=event.calendar().size() || finishes.size()!=DRIVERS) return false;
        var route=event.calendar().get(round);
        if(!replay.track().id().equals(route.trackId()) || !replay.track().geometry().equals(route.geometry())) return false;
        var last=replay.frames().get(replay.frames().size()-1);
        if(last.countdown()!=0 || last.elapsed()!=elapsed || last.cars().size()!=DRIVERS) return false;
        for(var car:last.cars()) {
            var finish=finishes.stream().filter(row->row.driver()==car.id()).findFirst().orElse(null);
            if(finish==null || finish.finished()!=car.finished() || finish.penalty()!=car.penalty()
                    || (finish.finished() && finish.elapsed()!=car.finishTime())
                    || finish.distance()!=Math.max(0,car.distance())) return false;
        }
        return true;
    }
    private final State state;
    private final List<Standing> standings;
    private final List<Integer> grid;
    public EventSeries(State state) {
        this.state=Objects.requireNonNull(state);
        standings=calculateStandings();
        grid=state.qualifying().isEmpty()?List.of(0,1,2,3):state.qualifying().stream().map(Qualifying::driver).toList();
    }
    public static EventSeries create(Kind kind,String selectedTrack,String physicsVersion,Tuning tuning,int difficulty,int laps) {
        return create(kind,TrackCatalog.require(selectedTrack).layout(),physicsVersion,tuning,difficulty,laps);
    }
    public static EventSeries create(Kind kind,TrackLayout selectedTrack,String physicsVersion,Tuning tuning,int difficulty,int laps) {
        if(kind==Kind.CUSTOM) throw new IllegalArgumentException("Use an explicit custom calendar");
        var tracks=kind==Kind.WEEKEND?List.of(selectedTrack):List.of(TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout(),TrackCatalog.require("woodland-technical").layout(),TrackCatalog.require("park-highspeed").layout());
        var calendar=tracks.stream().map(track->new Round(track.id,track.geometryKey())).toList();
        return new EventSeries(new State(1,kind,physicsVersion,tuning,difficulty,laps,calendar,0,Phase.QUALIFYING,List.of(),List.of()));
    }
    public static EventSeries createCustom(List<Round> calendar,String physicsVersion,Tuning tuning,int difficulty,int laps) {
        return new EventSeries(new State(1,Kind.CUSTOM,physicsVersion,tuning,difficulty,laps,calendar,0,Phase.QUALIFYING,List.of(),List.of()));
    }
    public static EventSeries createStartingAt(Kind kind,TrackLayout first,String physicsVersion,Tuning tuning,int difficulty,int laps) {
        if(kind!=Kind.TOUR) return create(kind,first,physicsVersion,tuning,difficulty,laps);
        var tracks=new ArrayList<TrackLayout>();tracks.add(first);for(var entry:TrackCatalog.all()) if(!entry.id().equals(first.id) && tracks.size()<3) tracks.add(entry.layout());
        return new EventSeries(new State(1,kind,physicsVersion,tuning,difficulty,laps,tracks.stream().map(track->new Round(track.id,track.geometryKey())).toList(),0,Phase.QUALIFYING,List.of(),List.of()));
    }
    public State state() { return state; }
    public Round currentRound() { return state.calendar().get(state.round()); }
    public String title() { return switch(state.kind()) { case WEEKEND->"短赛事周末";case TOUR->"三站巡回杯";case CUSTOM->state.calendar().size()+" 站自选巡回杯"; }; }
    public String phaseName() { return switch(state.phase()) { case QUALIFYING->"单圈排位";case RACE->"正赛";case REVIEW->"本站回顾";case COMPLETE->"赛事完成"; }; }
    public static String driverName(int driver) { checkDriver(driver);return NAMES[driver]; }
    public boolean compatible(String physicsVersion) {
        return compatible(physicsVersion,id->TrackCatalog.require(id).layout());
    }
    public boolean compatible(String physicsVersion,java.util.function.Function<String,TrackLayout> resolve) {
        if(!state.physicsVersion().equals(physicsVersion)) return false;
        try { return state.calendar().stream().allMatch(r->{var track=resolve.apply(r.trackId());return track!=null && track.id.equals(r.trackId()) && track.geometryKey().equals(r.geometry());}); }
        catch(IllegalArgumentException error) { return false; }
    }
    public EventSeries qualify(List<Qualifying> results) {
        require(Phase.QUALIFYING);
        return copy(state.round(),Phase.RACE,orderedQualifying(results),state.results());
    }
    public EventSeries finishRace(List<Finish> results) {
        return finishRace(results,UNSPECIFIED_CONTROLS);
    }
    public EventSeries finishRace(List<Finish> results,String controls) {
        return finishRace(results,controls,null);
    }
    public EventSeries finishRace(List<Finish> results,String controls,Settlement settlement) {
        require(Phase.RACE);
        var history=new ArrayList<>(state.results());history.add(new Result(state.qualifying(),results,settlement));
        var inputs=new ArrayList<>(state.raceControls());inputs.add(controls);
        return copy(state.round(),state.round()==state.calendar().size()-1?Phase.COMPLETE:Phase.REVIEW,state.qualifying(),history,inputs);
    }
    public EventSeries advance() {
        require(Phase.REVIEW);return copy(state.round()+1,Phase.QUALIFYING,List.of(),state.results());
    }
    private EventSeries copy(int round,Phase phase,List<Qualifying> qualifying,List<Result> results) {
        return copy(round,phase,qualifying,results,state.raceControls());
    }
    private EventSeries copy(int round,Phase phase,List<Qualifying> qualifying,List<Result> results,List<String> controls) {
        return new EventSeries(new State(1,state.kind(),state.physicsVersion(),state.tuning(),state.difficulty(),state.laps(),state.calendar(),round,phase,qualifying,results,state.eventId(),controls));
    }
    private void require(Phase phase) { if(state.phase()!=phase) throw new IllegalStateException("Wrong event phase"); }
    public List<Integer> grid() {return grid;}
    public List<Standing> standings() {return standings;}
    private List<Standing> calculateStandings() {
        var rows=new ArrayList<Standing>();
        for(int driver=0;driver<DRIVERS;driver++) {
            int points=0;var finishes=new ArrayList<>(Collections.nCopies(DRIVERS,0));var qualifying=new ArrayList<>(Collections.nCopies(DRIVERS,0));
            for(var result:state.results()) {
                points+=result.points(driver);
                for(int p=0;p<DRIVERS;p++) {
                    if(result.race().get(p).driver()==driver && result.race().get(p).finished()) finishes.set(p,finishes.get(p)+1);
                    if(result.qualifying().get(p).driver()==driver && result.qualifying().get(p).valid()) qualifying.set(p,qualifying.get(p)+1);
                }
            }
            rows.add(new Standing(driver,points,List.copyOf(finishes),List.copyOf(qualifying)));
        }
        rows.sort((a,b)->{int comparison=compareStanding(a,b);return comparison!=0?comparison:Integer.compare(a.driver(),b.driver());});
        return List.copyOf(rows);
    }
    /** Equal after both countbacks means a shared placing; driver ID only stabilizes display order. */
    public static int compareStanding(Standing a,Standing b) {
        int result=Integer.compare(b.points(),a.points());
        for(int p=0;p<DRIVERS && result==0;p++) result=Integer.compare(b.finishes().get(p),a.finishes().get(p));
        for(int p=0;p<DRIVERS && result==0;p++) result=Integer.compare(b.qualifying().get(p),a.qualifying().get(p));
        return result;
    }
    public int placing(int driver) {
        int rank=1;
        for(int i=0;i<standings.size();i++) {
            if(i>0 && compareStanding(standings.get(i-1),standings.get(i))!=0) rank=i+1;
            if(standings.get(i).driver()==driver) return rank;
        }
        throw new IllegalArgumentException("Unknown driver");
    }
    public static List<Qualifying> orderedQualifying(List<Qualifying> rows) {
        checkRoster(rows==null?null:rows.stream().map(Qualifying::driver).toList());var sorted=new ArrayList<>(rows);
        sorted.sort((a,b)->QualifyingSession.compareTimes(a.lap(),a.setAt(),a.driver(),b.lap(),b.setAt(),b.driver()));
        return List.copyOf(sorted);
    }
    public static List<Finish> orderedRace(List<Finish> rows) {
        checkRoster(rows==null?null:rows.stream().map(Finish::driver).toList());var sorted=new ArrayList<>(rows);
        sorted.sort((a,b)->{
            if(a.finished()!=b.finished()) return a.finished()?-1:1;
            if(a.finished()) return compareFinishers(a.total(),a.driver(),b.total(),b.driver());
            int result=Float.compare(b.distance(),a.distance());
            return result!=0?result:Integer.compare(a.driver(),b.driver());
        });return List.copyOf(sorted);
    }
    public static int compareFinishers(double aTotal,int aDriver,double bTotal,int bDriver) {
        int order=Long.compare(Math.round(aTotal*1000),Math.round(bTotal*1000));
        return order!=0?order:Integer.compare(aDriver,bDriver);
    }
    private static void checkRoster(List<Integer> ids) {
        if(ids==null || ids.size()!=DRIVERS || !new HashSet<>(ids).equals(Set.of(0,1,2,3))) throw new IllegalArgumentException("Four distinct drivers required");
    }
    private static void checkDriver(int driver) { if(driver<0 || driver>=DRIVERS) throw new IllegalArgumentException("Invalid driver"); }
}
