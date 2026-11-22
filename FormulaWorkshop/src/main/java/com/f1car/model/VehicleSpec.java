package com.f1car.model;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class VehicleSpec {
    public String id, name, physicsVersion;
    public float massKg, wheelRadius, suspensionRestLength, suspensionStiffness;
    public float engineForce, brakeForce, maxSpeedMps, downforceCoefficient, dragCoefficient;
    private transient AxleAerodynamics aeroStudy;
    private transient AxleDamping dampingStudy;
    private transient AxleFriction frictionStudy;
    private transient String studyBaseline;
    public AxleAerodynamics aeroStudy() {return aeroStudy;}
    public AxleDamping dampingStudy() {return dampingStudy;}
    public AxleFriction frictionStudy() {return frictionStudy;}
    public boolean aeroStudyIdentityValid() {return studyIdentityValid();}
    public boolean studyIdentityValid() {
        if(!hasStudy()) return !studyIdentity(physicsVersion);
        try {return physicsVersion!=null && physicsVersion.equals(computeStudyIdentity());}catch(IllegalArgumentException invalid) {return false;}
    }
    /** Explicit programmatic study copy. Resource loading and player tuning never opt in. */
    public VehicleSpec withAxleAerodynamics(AxleAerodynamics profile) {
        Objects.requireNonNull(profile);var copy=studyCopy();copy.aeroStudy=profile;copy.physicsVersion=copy.computeStudyIdentity();return copy;
    }
    public VehicleSpec withAxleDamping(AxleDamping profile) {Objects.requireNonNull(profile);var copy=studyCopy();copy.dampingStudy=profile;copy.physicsVersion=copy.computeStudyIdentity();return copy;}
    public VehicleSpec withAxleFriction(AxleFriction profile) {Objects.requireNonNull(profile);var copy=studyCopy();copy.frictionStudy=profile;copy.physicsVersion=copy.computeStudyIdentity();return copy;}
    private boolean hasStudy() {return aeroStudy!=null || dampingStudy!=null || frictionStudy!=null;}
    private VehicleSpec studyCopy() {
        if(!hasStudy() && studyIdentity(physicsVersion)) throw new IllegalArgumentException("Missing study configuration");
        var copy=new VehicleSpec();copy.id=id;copy.name=name;copy.massKg=massKg;copy.wheelRadius=wheelRadius;copy.suspensionRestLength=suspensionRestLength;copy.suspensionStiffness=suspensionStiffness;copy.engineForce=engineForce;copy.brakeForce=brakeForce;copy.maxSpeedMps=maxSpeedMps;copy.downforceCoefficient=downforceCoefficient;copy.dragCoefficient=dragCoefficient;
        copy.aeroStudy=aeroStudy;copy.dampingStudy=dampingStudy;copy.frictionStudy=frictionStudy;copy.studyBaseline=hasStudy()?studyBaseline:physicsVersion;return copy;
    }
    private String computeStudyIdentity() {
        if(studyIdentity(studyBaseline)) throw new IllegalArgumentException("Study baseline cannot be a nested study identity");
        if(frictionStudy!=null) {
            String friction=frictionStudy.physicsIdentity(studyBaseline,this);if(aeroStudy==null && dampingStudy==null) return friction;
            // Existing aero/damper-only identities below keep their original version-1 byte inputs.
            return "fw-vehicle-study-2-"+PartsCatalog.hash("vehicle-study-2:"+friction+":aero="+(aeroStudy==null?"none":aeroStudy.key())+":damper="+(dampingStudy==null?"none":dampingStudy.key()));
        }
        if(aeroStudy==null) {if(dampingStudy==null) throw new IllegalArgumentException("Missing study profile");return dampingStudy.physicsIdentity(studyBaseline,this);}
        String aero=aeroStudy.physicsIdentity(studyBaseline,this);if(dampingStudy==null) return aero;
        // The original aero identity already binds the vehicle, baseline and nine physical values.
        return "fw-vehicle-study-1-"+PartsCatalog.hash("vehicle-study-1:"+aero+":"+dampingStudy.key());
    }
    private static boolean studyIdentity(String value) {return value!=null && (value.startsWith("fw-aero-study-") || value.startsWith("fw-damper-study-") || value.startsWith("fw-friction-study-") || value.startsWith("fw-vehicle-study-"));}

    public static VehicleSpec load() {
        try (var stream = Objects.requireNonNull(VehicleSpec.class.getResourceAsStream("/data/vehicle.json"));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, VehicleSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load vehicle specification", e);
        }
    }
}
