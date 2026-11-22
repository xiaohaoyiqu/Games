package com.f1car.scene;

import com.f1car.model.PracticeSession;
import com.f1car.model.TrackLayout;
import com.jme3.math.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** Automated driver used only by the opt-in --smoke verification, never normal gameplay. */
public final class VerificationDriver {
    private final DriverAI driver;
    private final PracticeSession.Lesson lesson;
    private final PracticeSession session;
    public VerificationDriver(PracticeSession session) {
        this.session=session;
        lesson = session.lesson;
        TrackLayout path = session.circuit;
        if (lesson.circle()) path = TrackLayout.circle(lesson == PracticeSession.Lesson.CIRCLE_RIGHT);
        if (lesson == PracticeSession.Lesson.SLALOM) {
            List<Vector3f> points = new ArrayList<>();
            points.add(new Vector3f(0, 0, -112));
            for (var gate : session.guideGates()) points.add(new Vector3f(gate.x(),0,gate.z()));
            points.add(new Vector3f(6, 0, 70));
            path = new TrackLayout("verification-snake", "verification", 18, false, points);
        }
        driver = new DriverAI(path, .7f, 0);
    }
    public void update(float dt, FormulaCar car) {
        update(dt,car,List.of(car));
    }
    public void update(float dt,FormulaCar car,List<FormulaCar> traffic) {
        if(lesson==PracticeSession.Lesson.REACTION && !session.startPractice().green()) { car.drive(dt,0,1,0);return; }
        if (lesson == PracticeSession.Lesson.START_STOP || lesson.braking()) {
            float remaining = Math.max(0, 28 - car.position().z);
            float target = Math.min(20, (float) Math.sqrt(10 * remaining));
            float error = target - car.speed();
            Vector3f local = car.body.getPhysicsRotation(null).inverse().mult(new Vector3f(-65, car.position().y, car.position().z + 12).subtract(car.position()));
            float angle = (float) Math.atan2(2 * 2.68f * Math.sin(Math.atan2(local.x, local.z)), 12);
            float steeringRange = Math.max(.11f, .44f / (1 + car.speed() * .044f));
            float steer = Math.max(-1, Math.min(1, angle / steeringRange));
            car.drive(dt, Math.max(0, Math.min(1, error * .6f)), remaining < .4f ? 1 : Math.max(0, Math.min(1, -error * .2f)), steer);
        } else {
            driver.update(dt, car, traffic);
            if(!driver.needsRecovery && lesson==PracticeSession.Lesson.FOLLOW && traffic.size()>1) {
                var leader=traffic.stream().filter(other->other!=car).findFirst().orElseThrow();
                float gap=session.circuit.project(leader.position()).distance()-session.circuit.project(car.position()).distance();
                if(gap>session.circuit.length*.5f) gap-=session.circuit.length;else if(gap< -session.circuit.length*.5f) gap+=session.circuit.length;
                float target=Math.max(0,leader.speed()+(gap-20)*.4f),error=target-car.speed();
                car.drive(dt,Math.max(0,Math.min(1,error*.45f)),Math.max(0,Math.min(1,-error*.22f)),car.steeringDemand());
            }
        }
    }
}
