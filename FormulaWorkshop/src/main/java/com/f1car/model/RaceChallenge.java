package com.f1car.model;

/** Short clean races reuse the normal ordered lap counter and actual vehicle physics. */
public final class RaceChallenge {
    public static final double LIMIT=360;
    public enum Kind {
        DUEL("C01","围场单挑","一圈内击败对手，保持整圈有效且不接触。",1,-18,-10,.82f),
        CHASE("C02","两圈追击","从后方起步，在两圈内追上并干净获胜。",2,-42,-8,.74f),
        DEFEND("C03","最后两圈防守","带着起跑间距领先出发，两圈内守住位置。",2,-8,-30,.94f);
        public final String id,title,description;
        public final int laps;
        public final float playerDistance,opponentDistance,pace;
        Kind(String id,String title,String description,int laps,float playerDistance,float opponentDistance,float pace) {
            this.id=id;this.title=title;this.description=description;this.laps=laps;this.playerDistance=playerDistance;this.opponentDistance=opponentDistance;this.pace=pace;
        }
    }
    public enum State { RUNNING, SUCCESS, FAILED }
    private final Kind kind;
    private State state=State.RUNNING;
    private String feedback;
    private double finish=-1;
    public RaceChallenge(Kind kind) { this.kind=kind;feedback=kind.description; }
    public Kind kind() { return kind; }
    public State state() { return state; }
    public boolean complete() { return state!=State.RUNNING; }
    public String feedback() { return feedback; }
    public double finishTime() { return finish; }
    public void update(double time,LapProgress player,LapProgress opponent,boolean contact) {
        if(complete()) return;
        if(!Double.isFinite(time) || time<0 || player.finished() && (!Double.isFinite(player.finishTime()) || player.finishTime()<=0 || player.finishTime()>time)
            || opponent.finished() && (!Double.isFinite(opponent.finishTime()) || opponent.finishTime()<=0 || opponent.finishTime()>time)) {
            fail("本次计时无效，请重试挑战。");return;
        }
        if(player.finished() && player.completedLaps()!=kind.laps || opponent.finished() && opponent.completedLaps()!=kind.laps) {
            fail("挑战圈数不完整，请重试本次挑战。");return;
        }
        if(contact) { fail("本次与对手接触。重试时为并排行驶留出空间。");return; }
        if(!player.validLap() || player.completedLaps()>0 && !player.lastLapValid()) { fail("本次圈速无效。保持在道路内完成顺序检查门。");return; }
        if(player.finished() && player.finishTime()<=LIMIT) {
            if(opponent.finished() && opponent.finishTime()==player.finishTime()) fail("同时冲线，挑战要求领先对手完赛。按 R 重试。");
            else if(opponent.finished() && opponent.finishTime()<player.finishTime()) fail("对手先完赛，按 R 重试本次挑战。");
            else { state=State.SUCCESS;finish=player.finishTime();feedback="干净获胜 · "+PracticeSession.format(finish); }
        } else if(opponent.finished() && opponent.finishTime()<=LIMIT) fail("对手先完赛，按 R 重试本次挑战。");
        else if(time>=LIMIT) fail("本次挑战时间已到，按 R 重试。");
    }
    private void fail(String message) { state=State.FAILED;feedback=message; }
    public void abort(String message) { if(!complete()) fail(message); }
}
