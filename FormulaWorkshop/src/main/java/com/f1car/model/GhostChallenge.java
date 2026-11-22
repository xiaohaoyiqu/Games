package com.f1car.model;

/** Offline reference data. Importing this record never certifies a local result. */
public record GhostChallenge(int version, TelemetryLap.Conditions conditions, String trackName,
                             String author, String replyTo, GhostLap lap, String routeGeometry) {
    public GhostChallenge(int version,TelemetryLap.Conditions conditions,String trackName,String author,String replyTo,GhostLap lap) {
        this(version,conditions,trackName,author,replyTo,lap,null);
    }
    public GhostChallenge {
        boolean legacy=version==1 && routeGeometry==null;
        boolean shared=version==2 && routeGeometry!=null && routeGeometry.matches("[a-f0-9]{64}");
        if(!legacy && !shared
            || conditions==null || lap==null || !key(conditions).equals(lap.key())
            || !text(trackName,60) || trackName.isBlank() || !text(author,40)
            || replyTo!=null && !replyTo.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid ghost challenge");
    }
    private static boolean text(String value,int limit) {
        return value!=null && value.length()<=limit && value.codePoints().noneMatch(Character::isISOControl);
    }
    public static String key(TelemetryLap.Conditions c) {
        return "time-trial:v1:"+c.trackId()+":"+c.geometry()+":"+c.physics()+":"+c.performance()+":"+c.controls()+":sectors3-gates18";
    }
    public static String mismatch(TelemetryLap.Conditions source,TelemetryLap.Conditions local) {
        return mismatch(source,local,null,null);
    }
    public static String mismatch(TelemetryLap.Conditions source,TelemetryLap.Conditions local,String sourceRoute,String localRoute) {
        if((!source.geometry().equals(local.geometry()) && (sourceRoute==null || !sourceRoute.equals(localRoute))) || Math.abs(source.length()-local.length())>.001) return "路线几何不同";
        if(!source.physics().equals(local.physics())) return "物理版本不同";
        if(!source.performance().equals(local.performance())) return "改装性能不同";
        if(!source.controls().equals(local.controls())) return "输入条件不同";
        return "";
    }
    public GhostLap referenceFor(TelemetryLap.Conditions local) {
        return referenceFor(local,null);
    }
    public GhostLap referenceFor(TelemetryLap.Conditions local,String localRoute) {
        if(!mismatch(conditions,local,routeGeometry,localRoute).isEmpty()) throw new IllegalArgumentException("Incompatible ghost");
        String localKey=key(local);
        if(lap.key().equals(localKey)) return lap;
        // A copied custom map may have another local ID. Only the visual copy is rebound.
        return new GhostLap(lap.version(),localKey,lap.livery(),lap.duration(),lap.sectors(),lap.samples());
    }
}
