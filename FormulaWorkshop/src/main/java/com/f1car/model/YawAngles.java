package com.f1car.model;

public final class YawAngles {
    private YawAngles() {}

    public static double principal(double angle) {
        if(angle>=-Math.PI && angle<=Math.PI) return angle;
        return Math.atan2(Math.sin(angle),Math.cos(angle));
    }

    static double shortestChange(float from,float to) {
        double difference=principal(to)-principal(from);
        if(difference>Math.PI) return difference-2*Math.PI;
        if(difference< -Math.PI) return difference+2*Math.PI;
        return difference;
    }
}
