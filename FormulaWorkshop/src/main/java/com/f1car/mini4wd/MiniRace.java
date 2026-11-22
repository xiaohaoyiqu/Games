package com.f1car.mini4wd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The host advances every car and produces the same frame for all participants. */
public final class MiniRace {
    public record Entry(int id, String name, MiniSetup setup, boolean ai) {}
    public record CarFrame(int id, String name, int lane, MiniSetup setup, MiniTrial.Result result, boolean connected,
                           double progress, double elapsed, double speed, double battery, boolean boosting,
                           double x, double y, double heading, int laps, List<Double> lapTimes) {}
    public record Frame(boolean closed, double countdown, double elapsed, boolean complete, List<CarFrame> cars) {
        public Frame { cars = List.copyOf(cars); }
        public List<CarFrame> standings() {
            return cars.stream().sorted(Comparator.comparingInt((CarFrame c) -> c.result == MiniTrial.Result.FINISHED ? 0 : c.result == MiniTrial.Result.RUNNING ? 1 : 2)
                .thenComparingDouble(c -> c.result == MiniTrial.Result.FINISHED ? c.elapsed : -c.progress)).toList();
        }
    }

    private final MiniTrack.Inspection inspection;
    private final List<Entry> entries;
    private final List<MiniTrial> cars = new ArrayList<>();
    private final boolean[] inputs;
    private final boolean[] disconnected;
    private final long[] sampledRevisions;
    private Frame sampledFrame;
    private double countdown = 3, elapsed;

    public MiniRace(MiniTrack.Inspection inspection, List<Entry> entries) {
        this(inspection,entries,null);
    }

    private MiniRace(MiniTrack.Inspection inspection,List<Entry> entries,MiniRace previous) {
        if (entries.size() < 2 || entries.size() > 4) throw new IllegalArgumentException("A race needs two to four cars");
        this.inspection = inspection;
        this.entries = List.copyOf(entries);
        if (this.entries.stream().map(Entry::id).distinct().count() != this.entries.size())
            throw new IllegalArgumentException("Racer identities must be unique");
        inputs = new boolean[entries.size()];
        disconnected = new boolean[entries.size()];
        sampledRevisions=new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            cars.add(previous==null?new MiniTrial(inspection,this.entries.get(i).setup,(i-(entries.size()-1)/2.0)*.18)
                    :previous.cars.get(i).restart());
        }
    }

    MiniRace restart() {return new MiniRace(inspection,entries,this);}

    public void boost(int id, boolean held) {
        int lane = laneOf(id);
        if (lane >= 0 && !disconnected[lane]) inputs[lane] = held;
    }
    public void disconnect(int id) {
        int lane = laneOf(id);
        if (lane < 0) return;
        disconnected[lane] = true;
        inputs[lane] = false;
        cars.get(lane).retire();
    }
    public MiniTrial car(int id) {
        int lane = laneOf(id);
        if (lane < 0) throw new IllegalArgumentException("Unknown racer");
        return cars.get(lane);
    }
    private int laneOf(int id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id == id) return i;
        return -1;
    }

    public void update(double dt) {
        if(!Double.isFinite(dt) || dt<=0) return;
        if (countdown > 0) {
            double used = Math.min(countdown, dt);
            countdown -= used;
            dt -= used;
        }
        if (dt <= 0) return;
        double latest = elapsed;
        boolean running = false;
        for (int i = 0; i < cars.size(); i++) {
            var entry = entries.get(i);
            var car = cars.get(i);
            if (car.result() == MiniTrial.Result.RUNNING) {
                if(entry.ai) car.updateAi(dt);
                else car.update(dt,inputs[i]);
                running |= car.result() == MiniTrial.Result.RUNNING;
                latest = Math.max(latest, car.elapsed());
            }
        }
        elapsed = running ? elapsed + dt : latest;
    }

    public Frame frame() {
        var previous=sampledFrame;
        if(previous!=null && previous.countdown()==countdown && previous.elapsed()==elapsed) {
            boolean unchanged=true;
            for(int i=0;i<cars.size();i++) {
                if(!matchesSample(previous,i)) {
                    unchanged=false;
                    break;
                }
            }
            if(unchanged) return previous;
        }
        var states=new ArrayList<CarFrame>(entries.size());
        boolean complete=true;
        for (int i = 0; i < entries.size(); i++) {
            var car = cars.get(i);
            complete&=car.result()!=MiniTrial.Result.RUNNING;
            if(previous!=null && matchesSample(previous,i)) states.add(previous.cars().get(i));
            else {
                states.add(readCar(i,car));
                sampledRevisions[i]=car.revision();
            }
        }
        sampledFrame=new Frame(inspection.closed(),countdown,elapsed,complete,states);
        return sampledFrame;
    }

    private boolean matchesSample(Frame frame,int lane) {
        return sampledRevisions[lane]==cars.get(lane).revision() && frame.cars().get(lane).connected()==!disconnected[lane];
    }

    private CarFrame readCar(int lane,MiniTrial car) {
        var entry=entries.get(lane);
        var rail=car.segment();
        double fraction=car.fraction();
        var position=rail.point(fraction);
        return new CarFrame(entry.id,entry.name,lane,entry.setup,car.result(),!disconnected[lane],
                car.progress(),car.elapsed(),car.speed(),car.battery(),car.boosting(),position.x(),position.y(),
                rail.heading(fraction),car.laps(),car.lapTimes());
    }
}
