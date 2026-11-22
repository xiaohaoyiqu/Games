package com.f1car.model;

import java.util.regex.Pattern;

/** User-adjustable, bounded settings. A preset is a complete reproducible configuration. */
public record Tuning(float power,float brakes,float suspension,float wing,int livery,float brakeBias,
                     float frontSpring,float rearSpring,float frontDamping,float rearDamping) {
    public static final Tuning STOCK = new Tuning(1f, 1f, 1f, 1f, 0);
    private static final Pattern PERFORMANCE_KEY=Pattern.compile(
            "[0-9]{2,3}(-[0-9]{2,3}){3}(-b(3[5-9]|[45][0-9]|6[0-5]))?"
            +"(-s(8[0-9]|9[0-9]|1[01][0-9]|120)(-(8[0-9]|9[0-9]|1[01][0-9]|120)){3})?");
    /** Old constructors and pre-balance JSON both retain the neutral 50/50 split. */
    public Tuning(float power,float brakes,float suspension,float wing,int livery) { this(power,brakes,suspension,wing,livery,.5f); }
    public Tuning(float power,float brakes,float suspension,float wing,int livery,float brakeBias) { this(power,brakes,suspension,wing,livery,brakeBias,1,1,1,1); }

    public Tuning {
        power = finiteClamp(power, .82f, 1.12f, 1f);
        brakes = finiteClamp(brakes, .75f, 1.25f, 1f);
        suspension = finiteClamp(suspension, .75f, 1.3f, 1f);
        wing = finiteClamp(wing, .65f, 1.4f, 1f);
        livery = Math.floorMod(livery, 5);
        brakeBias = finiteClamp(brakeBias==0?.5f:brakeBias,.35f,.65f,.5f);
        frontSpring=axisFactor(frontSpring);rearSpring=axisFactor(rearSpring);frontDamping=axisFactor(frontDamping);rearDamping=axisFactor(rearDamping);
    }

    public Tuning change(String field, float amount) {
        float p=power,b=brakes,s=suspension,w=wing,bias=brakeBias,fs=frontSpring,rs=rearSpring,fd=frontDamping,rd=rearDamping;
        switch(field) {
            case "power" -> p+=amount;case "brakes" -> b+=amount;case "suspension" -> s+=amount;case "wing" -> w+=amount;
            case "brakeBias" -> bias=finiteClamp(bias+amount,.35f,.65f,.5f);
            case "frontSpring" -> fs=finiteClamp(fs+amount,.8f,1.2f,1);case "rearSpring" -> rs=finiteClamp(rs+amount,.8f,1.2f,1);
            case "frontDamping" -> fd=finiteClamp(fd+amount,.8f,1.2f,1);case "rearDamping" -> rd=finiteClamp(rd+amount,.8f,1.2f,1);
            case "livery" -> {return withLivery(livery+1);}default -> {return this;}
        }
        return new Tuning(p,b,s,w,livery,bias,fs,rs,fd,rd);
    }

    public String performanceKey() {
        return Math.round(power * 100) + "-" + Math.round(brakes * 100) + "-"
                + Math.round(suspension * 100) + "-" + Math.round(wing * 100)
                + (Math.round(brakeBias*100)==50?"":"-b"+Math.round(brakeBias*100))
                + (frontSpring==1 && rearSpring==1 && frontDamping==1 && rearDamping==1?"":"-s"+Math.round(frontSpring*100)+"-"+Math.round(rearSpring*100)+"-"+Math.round(frontDamping*100)+"-"+Math.round(rearDamping*100));
    }
    /** Absolute editing changes only the requested field; legacy continuous settings remain intact. */
    public Tuning withPercent(PartsCatalog.Field field,int value) {
        java.util.Objects.requireNonNull(field);
        if(value<field.min || value>field.max) throw new IllegalArgumentException("Tuning value outside supported range");
        float next=value/100f;
        return new Tuning(field==PartsCatalog.Field.POWER?next:power,field==PartsCatalog.Field.BRAKES?next:brakes,
            field==PartsCatalog.Field.SUSPENSION?next:suspension,field==PartsCatalog.Field.WING?next:wing,livery,
            field==PartsCatalog.Field.BRAKE_BIAS?next:brakeBias,field==PartsCatalog.Field.FRONT_SPRING?next:frontSpring,
            field==PartsCatalog.Field.REAR_SPRING?next:rearSpring,field==PartsCatalog.Field.FRONT_DAMPING?next:frontDamping,
            field==PartsCatalog.Field.REAR_DAMPING?next:rearDamping);
    }
    public Tuning withLivery(int value) { return new Tuning(power,brakes,suspension,wing,value,brakeBias,frontSpring,rearSpring,frontDamping,rearDamping); }
    public static boolean validPerformanceKey(String value) { return value!=null && PERFORMANCE_KEY.matcher(value).matches(); }
    /** One-percent resolution makes every added performance-key value reproduce the actual model. */
    private static float axisFactor(float value) { return Math.round(finiteClamp(value==0?1:value,.8f,1.2f,1)*100)/100f; }
    public float springRatio(boolean front) { return suspension*(front?frontSpring:rearSpring); }
    public float dampingRatio(boolean front) { return (float)Math.sqrt(suspension)*(front?frontDamping:rearDamping); }
    public String suspensionSummary() { return String.format(java.util.Locale.ROOT,"弹簧 %.0f/%.0f%% · 阻尼 %.0f/%.0f%%",frontSpring*100,rearSpring*100,frontDamping*100,rearDamping*100); }
    public static String performanceDescription(String key) {
        if(!validPerformanceKey(key)) throw new IllegalArgumentException("Invalid performance identity");
        var values=key.split("-");int index=4;String bias="50",fs="100",rs="100",fd="100",rd="100";
        if(index<values.length && values[index].startsWith("b")) bias=values[index++].substring(1);
        if(index<values.length) {fs=values[index++].substring(1);rs=values[index++];fd=values[index++];rd=values[index];}
        return "动/刹/悬/翼 "+String.join("/",java.util.Arrays.copyOf(values,4))+"% · 前刹 "+bias+"% · 弹簧 "+fs+"/"+rs+" 阻尼 "+fd+"/"+rd+"%";
    }

    private static float finiteClamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
