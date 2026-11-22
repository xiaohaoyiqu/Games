package com.f1car.network;

import com.f1car.model.EventSeries;
import com.f1car.model.QualifyingSession;
import com.f1car.model.Tuning;
import java.util.*;

/** Host-owned multi-stage events for two to four human racers. No invented AI finish times. */
public final class RoomProgram {
    private static final int[] POINTS={10,6,4,2};
    private static final Comparator<Qualifying> GRID_ORDER=(a,b)->QualifyingSession.compareTimes(
            a.lap(),a.setAt(),a.id(),b.lap(),b.setAt(),b.id());
    private static final Comparator<Finish> RESULT_ORDER=(a,b)->{
        if(a.finished()!=b.finished()) return a.finished()?-1:1;
        if(a.finished()) return EventSeries.compareFinishers(a.elapsed(),a.id(),b.elapsed(),b.id());
        return a.distance()==b.distance()?Integer.compare(a.id(),b.id()):Float.compare(b.distance(),a.distance());
    };
    public enum Phase { PRACTICE,QUALIFYING,RACE,COMPLETE }
    public record Driver(int id,String name,Tuning tuning,int team) {
        public Driver { if(id<0 || name==null || name.isBlank() || name.length()>80 || tuning==null || team<0 || team>1) throw new IllegalArgumentException("Invalid room driver"); }
    }
    public record Qualifying(int id,double lap,double setAt) {
        public Qualifying { if(id<0 || !Double.isFinite(lap) || !Double.isFinite(setAt) || !(lap==-1 && setAt==-1 || lap>0 && setAt>=lap && setAt<=QualifyingSession.LIMIT)) throw new IllegalArgumentException("Invalid qualifying time"); }
    }
    public record Finish(int id,boolean finished,double elapsed,float distance) {
        public Finish { if(id<0 || !Double.isFinite(elapsed) || (finished?elapsed<=0 || elapsed>86400:elapsed!=-1) || !Float.isFinite(distance) || distance<0 || distance>1_000_000) throw new IllegalArgumentException("Invalid race finish"); }
    }
    public record Result(int round,List<Qualifying> qualifying,List<Finish> race) {
        public Result {
            if(round<0 || round>2 || qualifying==null || qualifying.size()>4 || race==null || race.size()<2 || race.size()>4)
                throw new IllegalArgumentException("Invalid round result");
            qualifying=ordered(qualifying,GRID_ORDER);
            race=ordered(race,RESULT_ORDER);
        }
        public int points(int id) { for(int p=0;p<race.size();p++) if(race.get(p).id()==id) return race.get(p).finished()?POINTS[p]:0;throw new IllegalArgumentException("Unknown driver"); }
    }
    public record State(String id,int round,Phase phase,List<Driver> drivers,List<Qualifying> qualifying,List<Result> results,long lastSession) {
        public State {
            if(id==null || !id.matches("[a-f0-9-]{36}") || round<0 || round>2 || phase==null || drivers==null || drivers.size()<2 || drivers.size()>4 || qualifying==null || qualifying.size()>4 || results==null || results.size()>3 || lastSession<0)
                throw new IllegalArgumentException("Invalid room event state");
            drivers=List.copyOf(drivers);qualifying=ordered(qualifying,GRID_ORDER);results=List.copyOf(results);
            if(drivers.stream().map(Driver::id).distinct().count()!=drivers.size()) throw new IllegalArgumentException("Duplicate driver");
        }
    }
    private static <T> List<T> ordered(List<T> rows,Comparator<T> order) {
        // Host broadcasts normally arrive in classification order.
        for(int i=1;i<rows.size();i++) if(order.compare(rows.get(i-1),rows.get(i))>0)
            return rows.stream().sorted(order).toList();
        return List.copyOf(rows);
    }
    public record Standing(int id,int points,List<Integer> finishes,List<Integer> qualifying) {}
    private final RoomRules rules;
    private final State state;
    private final List<Standing> driverStandings,teamStandings;
    public RoomProgram(RoomRules rules,State state) {
        this.rules=Objects.requireNonNull(rules);this.state=Objects.requireNonNull(state);
        if(state.round()>=rules.calendar().size() || state.phase()==Phase.COMPLETE && state.round()!=rules.calendar().size()-1
            || state.results().size()!=state.round()+(state.phase()==Phase.COMPLETE && rules.format()!=RoomRules.Format.PRACTICE?1:0)) throw new IllegalArgumentException("Invalid event stage history");
        if(rules.format()==RoomRules.Format.QUICK_RACE && state.phase()!=Phase.RACE && state.phase()!=Phase.COMPLETE
            || rules.format()==RoomRules.Format.PRACTICE && state.phase()!=Phase.PRACTICE && state.phase()!=Phase.COMPLETE) throw new IllegalArgumentException("Invalid format phase");
        if(rules.teams() && (state.drivers().size()!=4 || state.drivers().stream().filter(d->d.team()==0).count()!=2)) throw new IllegalArgumentException("Two teams of two required");
        if (rules.vehicles() != RaceSession.VehicleRule.OPEN) {
            var settings = rules.settings(Phase.RACE);
            for (var driver : state.drivers()) {
                if (!settings.apply(driver.tuning()).equals(driver.tuning()))
                    throw new IllegalArgumentException("Driver setup differs from room rules");
            }
        }
        boolean hasQualifyingStage=rules.format()==RoomRules.Format.WEEKEND || rules.format()==RoomRules.Format.TOUR;
        boolean gridPublished=hasQualifyingStage && (state.phase()==Phase.RACE || state.phase()==Phase.COMPLETE);
        if(gridPublished) roster(state.qualifying().stream().map(Qualifying::id).toList());
        else if(!state.qualifying().isEmpty()) throw new IllegalArgumentException("Unexpected qualifying checkpoint");
        for(int i=0;i<state.results().size();i++) {
            var result=state.results().get(i);if(result.round()!=i) throw new IllegalArgumentException("Noncontiguous race history");
            roster(result.race().stream().map(Finish::id).toList());
            if(hasQualifyingStage) roster(result.qualifying().stream().map(Qualifying::id).toList());
            else if(!result.qualifying().isEmpty()) throw new IllegalArgumentException("Unexpected qualifying history");
        }
        if(hasQualifyingStage && state.phase()==Phase.COMPLETE && !state.qualifying().equals(state.results().get(state.round()).qualifying()))
            throw new IllegalArgumentException("Final grid differs from recorded qualifying");
        driverStandings=calculateDriverStandings();
        teamStandings=calculateTeamStandings();
    }
    public static RoomProgram create(RoomRules rules,List<Driver> drivers) {
        var settings = rules.settings(Phase.RACE);
        var frozen=drivers.stream().map(d->new Driver(d.id(),d.name(),settings.apply(d.tuning()),d.team())).toList();
        return new RoomProgram(rules,new State(UUID.randomUUID().toString(),0,rules.initialPhase(),frozen,List.of(),List.of(),0));
    }
    public RoomRules rules() { return rules; }
    public State state() { return state; }
    public boolean complete() { return state.phase()==Phase.COMPLETE; }
    public String stageName() { return switch(state.phase()) { case PRACTICE->"共同练习";case QUALIFYING->"单圈排位";case RACE->"正赛";case COMPLETE->"系列完成"; }; }
    public RaceSession.Settings settings() { return rules.settings(state.phase()); }
    public List<Integer> grid() { return state.qualifying().isEmpty()?state.drivers().stream().map(Driver::id).toList():state.qualifying().stream().map(Qualifying::id).toList(); }
    public RoomProgram finishPractice(long session) {
        if(state.phase()!=Phase.PRACTICE || session<state.lastSession()) throw new IllegalStateException("Wrong practice stage");
        return copy(state.round(),rules.format()==RoomRules.Format.PRACTICE?Phase.COMPLETE:Phase.QUALIFYING,List.of(),state.results(),session);
    }
    private static Qualifying qualifyingRow(RaceSession.CarState car) {
        requireFinalStatus(car);
        double lap=car.qualifyingTime();
        return new Qualifying(car.id(),lap,lap>0?car.progress().finishTime():-1);
    }
    private Finish raceRow(RaceSession.CarState car) {
        requireFinalStatus(car);
        boolean finished=car.progress().finished();
        if(finished && car.progress().completedLaps()!=rules.laps()) throw new IllegalArgumentException("Wrong race lap count");
        return new Finish(car.id(),finished,finished?car.result():-1,Math.max(0,car.distance()));
    }
    private static void requireFinalStatus(RaceSession.CarState car) {
        if (car.retired() == car.progress().finished())
            throw new IllegalArgumentException("A result requires exactly one of finished or retired");
    }
    // Each broadcast carries the program as it stood after this frame, including the stage transition.
    void validateStage(RaceSession.Frame frame) {
        Phase phase=switch(frame.settings().activity()) {
            case PRACTICE -> Phase.PRACTICE;
            case QUALIFYING -> Phase.QUALIFYING;
            case RACE -> Phase.RACE;
        };
        if(!rules.settings(phase).equals(frame.settings())) throw new IllegalArgumentException("Frame settings mismatch");
        if(!frame.complete()) {
            if(state.phase()!=phase || state.round()!=frame.trackIndex() || state.lastSession()>=frame.session())
                throw new IllegalArgumentException("Wrong active event stage");
            return;
        }
        if(state.lastSession()!=frame.session()) throw new IllegalArgumentException("Wrong result session");
        if(phase==Phase.QUALIFYING && state.phase()==Phase.RACE && state.round()==frame.trackIndex()) {
            for(var car:frame.cars()) if(!state.qualifying().contains(qualifyingRow(car)))
                throw new IllegalArgumentException("Frame differs from qualifying grid");
            return;
        }
        if(phase==Phase.RACE && frame.trackIndex()==state.results().size()-1 && frame.trackIndex()>=0
                && (state.phase()==Phase.PRACTICE || state.phase()==Phase.COMPLETE)) {
            var finishes=state.results().get(frame.trackIndex()).race();
            for(var car:frame.cars()) if(!finishes.contains(raceRow(car)))
                throw new IllegalArgumentException("Frame differs from race results");
            return;
        }
        throw new IllegalArgumentException("Wrong completed event stage");
    }
    public RoomProgram accept(RaceSession.Frame frame) {
        if(complete() || state.phase()==Phase.PRACTICE || !frame.complete() || frame.session()<=state.lastSession() || !frame.settings().equals(settings())
            || frame.trackIndex()!=state.round() || !frame.trackKey().equals(rules.calendar().get(state.round()).key())) throw new IllegalArgumentException("Wrong event result");
        roster(frame.cars().stream().map(RaceSession.CarState::id).toList());
        if(state.phase()==Phase.QUALIFYING) {
            var times=new ArrayList<Qualifying>();
            for(var car:frame.cars()) times.add(qualifyingRow(car));
            return copy(state.round(),Phase.RACE,times,state.results(),frame.session());
        }
        var finishes=new ArrayList<Finish>();
        for(var car:frame.cars()) finishes.add(raceRow(car));
        var results=new ArrayList<>(state.results());results.add(new Result(state.round(),state.qualifying(),finishes));
        boolean last=state.round()==rules.calendar().size()-1;
        return copy(last?state.round():state.round()+1,last?Phase.COMPLETE:Phase.PRACTICE,last?state.qualifying():List.of(),results,frame.session());
    }
    private RoomProgram copy(int round,Phase phase,List<Qualifying> times,List<Result> results,long session) { return new RoomProgram(rules,new State(state.id(),round,phase,state.drivers(),times,results,session)); }
    private void roster(List<Integer> ids) { if(ids.size()!=state.drivers().size() || new HashSet<>(ids).size()!=ids.size() || !new HashSet<>(ids).equals(new HashSet<>(state.drivers().stream().map(Driver::id).toList()))) throw new IllegalArgumentException("Wrong event roster"); }
    public List<Standing> standings() { return driverStandings; }
    public List<Standing> teams() { return teamStandings; }

    private List<Standing> calculateDriverStandings() {
        var rows=new ArrayList<Standing>();
        for(var driver:state.drivers()) {
            int points=0;var finishes=new ArrayList<>(Collections.nCopies(4,0));var qualifying=new ArrayList<>(Collections.nCopies(4,0));
            for(var result:state.results()) {
                points+=result.points(driver.id());for(int p=0;p<result.race().size();p++) if(result.race().get(p).id()==driver.id() && result.race().get(p).finished()) finishes.set(p,finishes.get(p)+1);
                for(int p=0;p<result.qualifying().size();p++) if(result.qualifying().get(p).id()==driver.id() && result.qualifying().get(p).lap()>0) qualifying.set(p,qualifying.get(p)+1);
            }
            rows.add(new Standing(driver.id(),points,List.copyOf(finishes),List.copyOf(qualifying)));
        }
        rows.sort((a,b)->{int c=compare(a,b);return c!=0?c:Integer.compare(a.id(),b.id());});return List.copyOf(rows);
    }
    private List<Standing> calculateTeamStandings() {
        var rows=new ArrayList<Standing>();
        for(int team=0;team<2;team++) {
            int points=0;var finishes=new ArrayList<>(Collections.nCopies(4,0));var qualifying=new ArrayList<>(Collections.nCopies(4,0));
            for(var driver:state.drivers()) if(driver.team()==team) { var row=driverStandings.stream().filter(s->s.id()==driver.id()).findFirst().orElseThrow();points+=row.points();for(int p=0;p<4;p++) { finishes.set(p,finishes.get(p)+row.finishes().get(p));qualifying.set(p,qualifying.get(p)+row.qualifying().get(p)); } }
            rows.add(new Standing(team,points,List.copyOf(finishes),List.copyOf(qualifying)));
        }
        rows.sort((a,b)->{int c=compare(a,b);return c!=0?c:Integer.compare(a.id(),b.id());});return List.copyOf(rows);
    }
    public static int compare(Standing a,Standing b) {
        int result=Integer.compare(b.points(),a.points());for(int p=0;p<4 && result==0;p++) result=Integer.compare(b.finishes().get(p),a.finishes().get(p));
        for(int p=0;p<4 && result==0;p++) result=Integer.compare(b.qualifying().get(p),a.qualifying().get(p));return result;
    }
}
