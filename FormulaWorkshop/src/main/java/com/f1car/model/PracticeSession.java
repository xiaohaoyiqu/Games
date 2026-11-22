package com.f1car.model;

import com.jme3.math.Vector3f;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PracticeSession {
    public enum Lesson {
        START_STOP("L01", "启步与停车", "平稳起步，在绿色停车框内停稳"),
        BRAKING("L02", "直线制动", "以 54–86 km/h 通过测速门，再停入绿色框"),
        CIRCLE_LEFT("L03-L", "左向定圆", "沿标记方向完成一个圆环，保持在宽环带内"),
        CIRCLE_RIGHT("L03-R", "右向定圆", "沿标记方向完成一个圆环，保持在宽环带内"),
        SLALOM("L04", "蛇形通过", "依次通过六座蓝色路门，避免碰锥"),
        CORNER("L05", "单弯与出弯", "通过全部区段标记，观察出口速度与用时"),
        COMPARISON("L14", "改装制动对比", "相同测速范围，比较停车距离与操作结果"),
        FLOW("L06", "连续弯与节奏", "依次通过连续弯路门，保持线路并观察出弯速度"),
        CONSISTENCY("L07", "稳定圈速", "连续完成三个有效圈；圈速极差不超过 1.5 秒或均值的 4%"),
        REACTION("L08", "起跑反应", "等待红灯熄灭再按油门，驶过前方加速终点门"),
        FOLLOW("L09", "跟车距离", "与领车保持 12–28 米间距连续 20 秒，避免接触和越界"),
        OVERTAKE("L10", "干净超车", "超过对手至少 8 米并保持 3 秒，不接触、不越界"),
        REJOIN("L11", "安全汇入", "从缓冲区观察来车，留足空间汇入并稳定行驶 4 秒");
        public final String id, title, description;
        Lesson(String id, String title, String description) { this.id = id; this.title = title; this.description = description; }
        public boolean onCircuit() { return this == CORNER || this==FLOW || this==CONSISTENCY || this==REACTION || traffic(); }
        public boolean traffic() { return this==FOLLOW || this==OVERTAKE || this==REJOIN; }
        public boolean circle() { return this == CIRCLE_LEFT || this == CIRCLE_RIGHT; }
        public boolean braking() { return this == BRAKING || this == COMPARISON; }
    }
    public enum State { READY, RUNNING, SUCCESS, FAILED }
    public record Spawn(Vector3f position, Vector3f forward) {}
    public record GuideGate(float x, float z, float rightX, float rightZ, float halfWidth) {
        private GuideGate(TrackLayout.Sample sample, float halfWidth) {
            this(sample.position().x, sample.position().z, sample.right().x, sample.right().z, halfWidth);
        }
    }
    public static final float CORNER_START = 80, CORNER_END = 245;
    private static final double CONSISTENCY_LIMIT=600;
    private static final int CIRCLE_GATE_COUNT = 8;
    public static final List<GuideGate> SLALOM_GATES = slalomGates();
    private static final double CONE_RADIUS=1.5;
    public final Lesson lesson;
    public final TrackLayout circuit;
    public State state = State.READY;
    public double elapsed;
    public float entrySpeed = -1, exitSpeed, stopDistance = -1;
    public int gates, cones;
    public String feedback = "准备好后加速开始；R 可随时重试";
    private Vector3f brakeStart;
    private double outside;
    private enum StopZone { MOVING, TARGET, OUTSIDE }
    private StopZone stopZone=StopZone.MOVING;
    private double stopped;
    private float lastObservedSpeed=Float.NaN;
    private final double[] parkingBreaks;
    private int roadSegment=-1;
    private boolean entered;
    private final LapProgress circleProgress;
    private final LapProgress consistencyProgress;
    private final LapConsistency consistency=new LapConsistency();
    private final StartPractice startPractice;
    private final TrafficPractice trafficPractice;
    private final float courseStartDistance,courseEndDistance;
    private final TrackLayout.Sample[] courseGates;
    private final TrackLayout.Sample cornerExit;
    private final List<GuideGate> guideGates;
    private final Vector3f reactionGrid;
    private final TrackLayout.Sample reactionGate;
    private float throttleDemand;
    private final boolean[] touched;
    private final TrackLayout.Sample rejoinStart;

    public PracticeSession(Lesson lesson, TrackLayout circuit) {
        this(lesson,circuit,0);
    }
    public PracticeSession(Lesson lesson,TrackLayout circuit,long seed) {
        this.lesson = lesson;
        this.circuit = circuit;
        parkingBreaks=lesson==Lesson.START_STOP || lesson.braking()?new double[7]:null;
        touched=lesson==Lesson.SLALOM?new boolean[SLALOM_GATES.size()*2]:null;
        var circle = lesson.circle() ? TrackLayout.circle(lesson == Lesson.CIRCLE_RIGHT) : null;
        circleProgress = circle == null ? null : new LapProgress(circle, 1, CIRCLE_GATE_COUNT);
        consistencyProgress=lesson==Lesson.CONSISTENCY?new LapProgress(circuit,99999):null;
        startPractice=lesson==Lesson.REACTION?new StartPractice(seed):null;
        trafficPractice=lesson.traffic()?new TrafficPractice(lesson==Lesson.FOLLOW?TrafficPractice.Kind.FOLLOW:lesson==Lesson.OVERTAKE?TrafficPractice.Kind.OVERTAKE:TrafficPractice.Kind.REJOIN,circuit):null;
        rejoinStart=lesson==Lesson.REJOIN?rejoinSpawn():null;
        var course=lesson==Lesson.FLOW?TrackCatalog.forLayout(circuit):null;
        courseStartDistance=course==null?CORNER_START:circuit.length*course.flowStart();
        courseEndDistance=course==null?CORNER_END:circuit.length*course.flowEnd();
        courseGates=lesson==Lesson.FLOW || lesson==Lesson.CORNER?new TrackLayout.Sample[courseGateCount()]:null;
        cornerExit=lesson==Lesson.CORNER && circuit.length>=courseEndDistance-1?circuit.sample(courseEndDistance-1):null;
        if(courseGates!=null) {
            for(int gate=0;gate<courseGates.length;gate++) courseGates[gate]=circuit.sample(gateDistance(gate));
        }
        reactionGrid=startPractice==null?null:circuit.sample(-18).position();
        reactionGate=startPractice==null?null:circuit.sample(42);
        guideGates = buildGuideGates(circle);
    }

    private List<GuideGate> buildGuideGates(TrackLayout circle) {
        if (courseGates != null) {
            var guides = new ArrayList<GuideGate>(courseGates.length);
            for (var sample : courseGates) guides.add(new GuideGate(sample, circuit.width * .5f));
            return List.copyOf(guides);
        }
        if (reactionGate != null) return List.of(new GuideGate(reactionGate, circuit.width * .5f));
        if (lesson == Lesson.CONSISTENCY) return List.of(new GuideGate(circuit.sample(0), circuit.width * .5f));
        if (lesson == Lesson.SLALOM) return SLALOM_GATES;
        if (circle == null) return List.of();

        var guides = new ArrayList<GuideGate>(CIRCLE_GATE_COUNT);
        for (int i = 0; i < CIRCLE_GATE_COUNT; i++) {
            guides.add(new GuideGate(circle.sample(i * circle.length / CIRCLE_GATE_COUNT), 4.5f));
        }
        return List.copyOf(guides);
    }

    private static List<GuideGate> slalomGates() {
        var guides = new ArrayList<GuideGate>(6);
        for (int i = 0; i < 6; i++) guides.add(new GuideGate(i % 2 == 0 ? -6 : 6, -75 + i * 25, 1, 0, 5.5f));
        return List.copyOf(guides);
    }

    public List<GuideGate> guideGates() { return guideGates; }
    public StartPractice startPractice() { return startPractice; }
    public TrafficPractice trafficPractice() { return trafficPractice; }
    public float opponentDistance() { return rejoinStart==null?4:rejoinStart.distance()-57; }
    public float opponentPace() { return lesson==Lesson.FOLLOW?.68f:lesson==Lesson.OVERTAKE?.60f:.82f; }
    public void controls(float throttle) { throttleDemand=Float.isFinite(throttle)?throttle:0; }
    public void observeTraffic(float dt,TrafficPractice.Observation observation) {
        if(trafficPractice==null || complete()) return;
        trafficPractice.update(dt,observation);feedback=trafficPractice.feedback();
        elapsed=trafficPractice.elapsed();
        if(trafficPractice.complete()) { if(trafficPractice.state()==TrafficPractice.State.SUCCESS) success(feedback);else fail(feedback); }
    }
    public void opponentRecovered() {
        if(trafficPractice!=null && !complete()) { trafficPractice.abort("练习车辆需要恢复，按 R 重新开始同一场景。");fail(trafficPractice.feedback()); }
    }
    public LapConsistency consistency() { return consistency; }
    public boolean currentLapValid() { return consistencyProgress==null || consistencyProgress.validLap(); }
    public int courseGateCount() { return lesson==Lesson.FLOW?7:5; }
    public float courseStart() { return courseStartDistance; }
    public float courseEnd() { return courseEndDistance; }
    public float gateDistance(int gate) { return lesson==Lesson.FLOW?courseStart()+(courseEnd()-courseStart())*gate/(courseGateCount()-1):CORNER_START+gate*35; }

    public Spawn spawn() {
        if(rejoinStart!=null) return new Spawn(rejoinStart.position().clone(),rejoinStart.tangent().clone());
        if (lesson.onCircuit()) {
            var sample = circuit.sample(lesson==Lesson.CONSISTENCY || lesson==Lesson.REACTION || lesson.traffic()?-18:courseStart()-24);
            return new Spawn(sample.position(),sample.tangent());
        }
        if (lesson.circle()) {
            var sample = TrackLayout.circle(lesson == Lesson.CIRCLE_RIGHT).sample(-5);
            return new Spawn(sample.position(), sample.tangent());
        }
        return new Spawn(new Vector3f(lesson == Lesson.SLALOM ? 0 : -65, 0, lesson == Lesson.SLALOM ? -112 : -100), Vector3f.UNIT_Z.clone());
    }
    private TrackLayout.Sample rejoinSpawn() {
        return TrackScenery.rejoinSpawn(circuit).orElseGet(()->{
            var sample=circuit.sample(-18);return new TrackLayout.Sample(sample.position().add(sample.right().mult(circuit.width*.5f+5)),sample.tangent(),sample.right(),-18);
        });
    }

    private boolean onRoad(TrackLayout track,Vector3f position,float limit) {
        int segment=track.findSegmentWithinDistance(position,limit,roadSegment);
        if(segment<0) return false;
        roadSegment=segment;
        return true;
    }

    public void update(float dt, Vector3f previous, Vector3f p, float speedMps, float brake) {
        if (state == State.SUCCESS || state == State.FAILED) return;
        if(dt==0) return;
        if(!Float.isFinite(dt) || dt<0 || !Vector3f.isValidVector(previous) || !Vector3f.isValidVector(p)
                || !Float.isFinite(speedMps) || speedMps<0 || !Float.isFinite(brake) || brake<0 || brake>1) {
            fail("车辆或操作观测异常，请按 R 从课程起点重试。");
            return;
        }
        if(startPractice!=null) {
            updateStart(dt,previous,p);
            return;
        }
        if(trafficPractice!=null) {
            state=State.RUNNING;
            if(p.y < -3 || p.y > 8 || previous.distanceSquared(p)>55*55) {
                trafficPractice.abort("车辆离开练习范围，按 R 重试。");
                fail(trafficPractice.feedback());
            }
            return;
        }
        boolean starting = state == State.READY;
        if (starting) {
            if (speedMps < .8f) {
                lastObservedSpeed=speedMps;
                return;
            }
            state = State.RUNNING;
        }
        if(parkingBreaks!=null) {
            updateParking(dt,previous,p,speedMps,brake);
            return;
        }
        if(lesson==Lesson.CONSISTENCY) {
            updateConsistency(dt,previous,p);
            return;
        }
        if(lesson==Lesson.SLALOM) {
            updateSlalom(dt,previous,p);
            return;
        }
        if (lesson.circle()) {
            updateCircle(dt, previous, p, speedMps);
            return;
        }
        updateCornerCourse(dt, previous, p, speedMps, starting);
    }

    private void updateCornerCourse(float dt, Vector3f previous, Vector3f current, float speed, boolean starting) {
        if (previous.distanceSquared(current) > 55 * 55) {
            fail("车辆位置发生跳变，按 R 从课程起点重试。");
            return;
        }
        double frameStart = elapsed, clockOrigin = 0;
        double timeout = (120 - elapsed) / dt;
        double heightExit = exitFraction(previous.y, current.y, -3, 8);
        int next = gates;
        float firstCrossing = -1, lastCrossing = -1;
        while (next < courseGates.length) {
            float crossing = LapProgress.crossingFraction(previous, current, courseGates[next], circuit.width * .5f, false);
            if (!Float.isFinite(crossing) || crossing <= lastCrossing || crossing > heightExit || crossing > timeout) break;
            if (next == 0) {
                firstCrossing = crossing;
                if (lesson == Lesson.FLOW) {
                    frameStart = 0;
                    clockOrigin = crossing;
                    timeout = crossing + 120d / dt;
                }
            }
            lastCrossing = crossing;
            next++;
        }
        double finish = Double.POSITIVE_INFINITY;
        if (next == courseGates.length) {
            if (lesson == Lesson.FLOW) finish = lastCrossing;
            else if (cornerExit != null) {
                float crossing = LapProgress.crossingFraction(previous, current, cornerExit, circuit.width * .5f + 1, true);
                if (crossing > lastCrossing) finish = crossing;
            }
        }
        double through = Math.min(1, Math.min(finish, Math.min(heightExit, timeout)));
        Vector3f end = through == 1 ? current : previous.clone().interpolateLocal(current, (float) through);
        elapsed = through == timeout ? 120 : Math.fma(through - clockOrigin, dt, frameStart);
        if (!onRoad(circuit, end, circuit.width * .5f + 1)) {
            fail("本次驶出道路。重试时观察入口速度和转向位置。");
            return;
        }
        double priorSpeed = Float.isFinite(lastObservedSpeed) ? lastObservedSpeed : speed;
        lastObservedSpeed = speed;
        int before = gates;
        gates = next;
        if (firstCrossing >= 0) entrySpeed = (float)(Math.fma(firstCrossing, speed - priorSpeed, priorSpeed) * 3.6);
        if (through == finish) {
            exitSpeed = (float)(Math.fma(finish, speed - priorSpeed, priorSpeed) * 3.6);
            success((lesson==Lesson.FLOW?"完成连续弯":"完成单弯")+"，入口 "+Math.round(entrySpeed)+"、出口 "+Math.round(exitSpeed)+" km/h；区段 "+format(elapsed)+"。");
        } else if (through == heightExit) {
            fail("车辆离开了练习区域，按 R 返回固定起点。");
        } else if (through == timeout) {
            fail("本次时间已到。重试时先观察路线与目标。");
        } else if (starting || gates != before) {
            feedback = "已通过 " + gates + "/" + courseGateCount() + " 个区段标记 · 入口 " + (entrySpeed<0?"等待":Math.round(entrySpeed)+" km/h");
        }
    }

    private void updateCircle(float dt, Vector3f previous, Vector3f current, float speedMps) {
        double from = elapsed, to = from + dt;
        if (previous.distanceSquared(current) > 55 * 55) {
            fail("车辆位置发生跳变，按 R 从课程起点重试。");
            return;
        }
        double heightExit = Math.fma(exitFraction(previous.y, current.y, -3, 8), dt, from);
        double deadline = Math.min(120, heightExit);
        Vector3f end = deadline < to ? LapProgress.positionAtDeadline(previous, current, from, to, deadline) : current;
        boolean finished = circleProgress.updateTimed(previous, current, end, from, to, deadline);
        elapsed = finished ? circleProgress.finishTime() : Math.min(to, deadline);
        if (finished && elapsed < Math.min(to, deadline)) {
            end = LapProgress.positionAtDeadline(previous, current, from, to, elapsed);
        }
        var ring = TrackLayout.circle(lesson == Lesson.CIRCLE_RIGHT);
        if (onRoad(ring, end, 6)) {
            outside = 0;
        } else {
            outside += elapsed - from;
            if (outside > 1) {
                elapsed -= outside - 1;
                fail("偏离圆环。试着降低入环速度，保持小幅转向。");
                return;
            }
        }
        gates = circleProgress.nextGate();
        if (finished) success("完成定圆。可换另一方向，比较转向和速度控制。");
        else if (to >= deadline) {
            fail(heightExit <= 120 ? "车辆离开了练习区域，按 R 返回固定起点。" : "本次时间已到。重试时先观察路线与目标。");
        } else {
            feedback = "沿圆环方向连续通过标记；本次 " + (int) (speedMps * 3.6f) + " km/h";
        }
    }

    private void updateStart(float dt,Vector3f previous,Vector3f current) {
        state=State.RUNNING;
        double red=startPractice.redFraction(dt);
        boolean rolled=false;
        if(red>0) {
            double x=previous.x-reactionGrid.x,z=previous.z-reactionGrid.z;
            double redX=Math.fma(red,current.x-(double)previous.x,x);
            double redZ=Math.fma(red,current.z-(double)previous.z,z);
            rolled=Math.max(x*x+z*z,redX*redX+redZ*redZ)>(double).7f*.7f;
        }
        float finish=LapProgress.crossingFraction(previous,current,reactionGate,circuit.width*.5f,false);
        boolean valid=previous.distanceSquared(current)<55*55;
        if(Float.isFinite(finish) && startPractice.state()==StartPractice.State.LAUNCHED) {
            double lineY=Math.fma((double)finish,current.y-(double)previous.y,previous.y);
            valid = valid && previous.y>=-3 && previous.y<=8 && lineY>=-3 && lineY<=8
                    && onRoad(circuit,previous,circuit.width*.5f+.6f);
        } else {
            valid = valid && current.y>=-3 && current.y<=8 && onRoad(circuit,current,circuit.width*.5f+.6f);
        }
        startPractice.updateObserved(dt,throttleDemand,rolled,finish,valid);
        elapsed=startPractice.performanceElapsed();
        feedback=startPractice.feedback();
        if(startPractice.complete()) {if(startPractice.state()==StartPractice.State.SUCCESS) success(feedback);else fail(feedback);}
    }

    private void updateParking(float dt,Vector3f a,Vector3f b,float speed,float brake) {
        if(a.distanceSquared(b)>55*55) {fail("车辆位置发生跳变，按 R 从课程起点重试。");return;}
        double from=elapsed;
        double heightExit=exitFraction(a.y,b.y,-3,8);
        double routeExit=Math.min(exitFraction(a.x,b.x,-74,-56),exitFraction(a.z,b.z,-140,45));
        double timeout=(120-from)/dt;
        double through=Math.min(1,Math.min(timeout,Math.min(heightExit,routeExit)));
        double startSpeed=Float.isFinite(lastObservedSpeed)?lastObservedSpeed:speed;
        lastObservedSpeed=speed;
        if(lesson.braking() && !entered && a.z< -18 && b.z>=-18) {
            double crossing=(-18d-a.z)/(b.z-(double)a.z);
            if(crossing<=through) {
                entered=true;
                entrySpeed=(float)(Math.fma(crossing,speed-startSpeed,startSpeed)*3.6);
                if(entrySpeed<54 || entrySpeed>86) {
                    elapsed=Math.fma(crossing,dt,from);
                    fail("测速门入口为 "+Math.round(entrySpeed)+" km/h，目标 54–86 km/h。");
                    return;
                }
            }
        }
        if(Math.min(startSpeed,speed)<.5 && through>0) {
            if(observeStop(dt,a,b,startSpeed,speed,through)) return;
        } else {stopZone=StopZone.MOVING;stopped=0;}
        elapsed=Math.fma(through,dt,from);
        if(timeout<=1 && timeout<=routeExit && timeout<=heightExit) fail("本次时间已到。重试时先观察路线与目标。");
        else if(heightExit<=1 && heightExit<=routeExit) fail("车辆离开了练习区域，按 R 返回固定起点。");
        else if(routeExit<=1) fail(b.z>45?"已驶过停车框。按 R 重试，观察制动开始位置。":"离开直线练习区，按 R 返回起点。");
        else {
            if(brake>.15f && speed>2 && brakeStart==null) brakeStart=b.clone();
            feedback=lesson.braking()?(entered?"已通过测速门，在绿色框内停稳 1 秒":"在黄色测速门前达到 54–86 km/h"):"在绿色框内停稳 1 秒";
        }
    }

    private int addParkingBreak(int count,double start,double end,double boundary,double through) {
        if(start!=end) {
            double fraction=(boundary-start)/(end-start);
            if(fraction>0 && fraction<through) parkingBreaks[count++]=fraction;
        }
        return count;
    }

    private boolean observeStop(float dt,Vector3f a,Vector3f b,double startSpeed,double speed,double through) {
        // Between successive boundaries the car is moving, stopped inside, or stopped outside throughout.
        if(parkingZone(a.x,a.z,startSpeed)!=stopZone) stopped=0;
        int count=addParkingBreak(0,startSpeed,speed,.5,through);
        count=addParkingBreak(count,a.z,b.z,-10,through);
        count=addParkingBreak(count,a.z,b.z,18,through);
        count=addParkingBreak(count,a.z,b.z,38,through);
        count=addParkingBreak(count,a.x,b.x,-70.5,through);
        count=addParkingBreak(count,a.x,b.x,-59.5,through);
        if(count>1) Arrays.sort(parkingBreaks,0,count);
        parkingBreaks[count++]=through;
        double dx=b.x-(double)a.x,dz=b.z-(double)a.z,start=0;
        for(int i=0;i<count;i++) {
            double end=parkingBreaks[i];
            if(end==start) continue;
            double mid=(start+end)*.5;
            double x=Math.fma(mid,dx,a.x),z=Math.fma(mid,dz,a.z);
            StopZone zone=parkingZone(x,z,Math.fma(mid,speed-startSpeed,startSpeed));
            if(zone!=stopZone) {stopped=0;stopZone=zone;}
            if(zone!=StopZone.MOVING) {
                double duration=(end-start)*dt;
                if(stopped+duration>=1) {
                    double finish=start+(1-stopped)/dt;
                    elapsed=Math.fma(finish,dt,elapsed);
                    finishParking(zone,Math.fma(finish,dx,a.x),Math.fma(finish,dz,a.z));
                    return true;
                }
                stopped+=duration;
            }
            start=end;
        }
        return false;
    }

    private static StopZone parkingZone(double x,double z,double speed) {
        if(speed>=.5 || z<=-10) return StopZone.MOVING;
        return z>=18 && z<=38 && Math.abs(x+65)<5.5?StopZone.TARGET:StopZone.OUTSIDE;
    }

    private void finishParking(StopZone zone,double x,double z) {
        if(zone==StopZone.OUTSIDE) {fail("车辆停在目标框外。根据本次位置调整制动起点。");return;}
        if(lesson.braking() && (!entered || brakeStart==null)) {fail("先按目标速度通过测速门，再制动停车。");return;}
        if(brakeStart!=null) stopDistance=(float)Math.hypot(x-brakeStart.x,z-brakeStart.z);
        success(lesson.braking()?"停车完成，入口 "+Math.round(entrySpeed)+" km/h，制动距离 "
                +String.format(java.util.Locale.ROOT,"%.1f",stopDistance)+" m。":"完成启停：车辆已在目标框内停稳。");
    }

    private void updateSlalom(float dt,Vector3f previous,Vector3f current) {
        if(previous.distanceSquared(current)>55*55) {
            fail("车辆位置发生跳变，按 R 从课程起点重试。");
            return;
        }
        double from=elapsed;
        double timeout=(120-from)/dt;
        double exit=Math.min(exitFraction(previous.x,current.x,-22,22),
                exitFraction(previous.z,current.z,Double.NEGATIVE_INFINITY,70));
        exit=Math.min(exit,exitFraction(previous.y,current.y,-3,8));
        double through=Math.min(1,Math.min(exit,timeout));
        double dx=(double)current.x-previous.x,dz=(double)current.z-previous.z;
        int beforeGates=gates,beforeCones=cones;
        boolean missed=false;
        while(gates<SLALOM_GATES.size()) {
            var gate=SLALOM_GATES.get(gates);
            if(previous.z>=gate.z() || current.z<gate.z()) break;
            double crossing=(gate.z()-(double)previous.z)/dz;
            if(crossing>through) break;
            missed=Math.abs(Math.fma(crossing,dx,previous.x)-gate.x())>gate.halfWidth();
            if(!missed) gates++;
            if(missed || gates==SLALOM_GATES.size()) {through=crossing;break;}
        }
        countSlalomCones(previous,Math.fma(through,dx,previous.x),Math.fma(through,dz,previous.z));
        elapsed=Math.fma(through,dt,from);
        if(missed) fail("漏过第 "+(gates+1)+" 道路门。看向下一道门，提前调整。");
        else if(gates==SLALOM_GATES.size()) success("完成蛇形，用时 "+format(elapsed)+"，碰锥 "+cones+"。可尝试更平顺的路线。");
        else if(exit<=1 && exit<=timeout) fail("离开蛇形路线，按 R 再来一次。");
        else if(timeout<=1) fail("本次时间已到。重试时先观察路线与目标。");
        else if(from==0 || beforeGates!=gates || beforeCones!=cones)
            feedback="已通过 "+gates+"/"+SLALOM_GATES.size()+" 道门 · 碰锥 "+cones;
    }

    private static double exitFraction(double start,double end,double low,double high) {
        if(start<low || start>high) return 0;
        if(end<low) return (low-start)/(end-start);
        if(end>high) return (high-start)/(end-start);
        return Double.POSITIVE_INFINITY;
    }

    private void countSlalomCones(Vector3f start,double endX,double endZ) {
        double minZ=Math.min(start.z,endZ)-CONE_RADIUS,maxZ=Math.max(start.z,endZ)+CONE_RADIUS;
        for(int i=0;i<SLALOM_GATES.size();i++) {
            var gate=SLALOM_GATES.get(i);
            if(gate.z()<minZ || gate.z()>maxZ) continue;
            for(int side=0;side<2;side++) {
                int index=2*i+side;
                double x=gate.x()+(side==0?-gate.halfWidth():gate.halfWidth());
                if(!touched[index] && Line2D.ptSegDistSq(start.x,start.z,endX,endZ,x,gate.z())<CONE_RADIUS*CONE_RADIUS) {
                    touched[index]=true;
                    cones++;
                }
            }
        }
    }

    private void updateConsistency(float dt,Vector3f previous,Vector3f current) {
        double from=elapsed,to=from+dt;
        if(previous.distance(current)>55) {
            elapsed=Math.min(to,CONSISTENCY_LIMIT);
            fail("车辆位置发生跳变，按 R 从课程起点重试。");
            return;
        }
        double leaveAt=Double.POSITIVE_INFINITY;
        if(previous.y < -3 || previous.y > 8) leaveAt=from;
        else if(current.y < -3 || current.y > 8) {
            double heightLimit=current.y < -3?-3:8;
            leaveAt=Math.fma(dt,(heightLimit-previous.y)/(current.y-previous.y),from);
        }
        double deadline=Math.min(leaveAt,CONSISTENCY_LIMIT);
        boolean wasValid=consistencyProgress.validLap();
        boolean completed=consistencyProgress.updateTimed(previous,current,from,to,deadline);
        elapsed=Math.min(to,deadline);
        if(completed) {
            consistency.complete(consistencyProgress.lastLap(),consistencyProgress.lastLapValid());
            if(consistency.success()) {
                elapsed=consistencyProgress.lapStartTime();
                success("完成三个连续有效圈：均值 "+format(consistency.mean())+"，极差 "+String.format(java.util.Locale.ROOT,"%.3f s",consistency.spread())+"。");
                return;
            }
        }
        boolean valid=consistencyProgress.validLap();
        if(!valid && (wasValid || completed)) consistency.invalidate();
        if(to>=deadline) {
            fail(leaveAt<=CONSISTENCY_LIMIT?"车辆离开了练习区域，按 R 返回固定起点。":"本次时间已到。重试时先观察路线与目标。");
        } else if(from==0 || completed || valid!=wasValid) {
            feedback=valid?String.format(java.util.Locale.ROOT,"连续有效圈 %d/3 · 极差 %.3f s · 允许 %.3f s",
                    consistency.laps().size(),consistency.spread(),consistency.tolerance())
                    :"本圈越界，连续有效圈已清零；下圈可以重新开始。";
        }
    }

    public boolean complete() { return state == State.SUCCESS || state == State.FAILED; }
    private void success(String message) { state = State.SUCCESS; feedback = message; }
    private void fail(String message) { state = State.FAILED; feedback = message; }
    public static String format(double seconds) {
        double scaled = seconds * 1_000;
        if (!Double.isFinite(scaled) || seconds < 0 || scaled >= Long.MAX_VALUE) return "--:--.---";
        long totalMillis = Math.round(scaled);
        long minutes = totalMillis / 60_000;
        int wholeSeconds = (int) (totalMillis / 1_000 % 60);
        int millis = (int) (totalMillis % 1_000);
        String minutePadding = minutes < 10 ? "0" : "";
        String secondPadding = wholeSeconds < 10 ? "0" : "";
        String millisecondPadding = millis < 10 ? "00" : millis < 100 ? "0" : "";
        return minutePadding + minutes + ':' + secondPadding + wholeSeconds + '.' + millisecondPadding + millis;
    }
}
