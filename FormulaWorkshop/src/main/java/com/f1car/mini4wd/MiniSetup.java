package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrial.Motor;

/** Parts affect speed, acceleration, rail stability, boost endurance and ramp braking. */
public record MiniSetup(Motor motor, Battery battery, Gear gear, Tire tire, Roller roller, Brake brake,
                        Chassis chassis, Mount mount, Wheel wheel, Bearing bearing, Damper damper, Body body, Integer installedParts) {
    public enum Mount {
        SINGLE("单轴"), DOUBLE("双轴");
        public final String label;
        Mount(String label) { this.label = label; }
    }
    public enum Chassis {
        REAR("后置底盘", Mount.SINGLE), MID("中置底盘", Mount.DOUBLE), FRONT("前置底盘", Mount.SINGLE);
        public final String label;
        public final Mount mount;
        Chassis(String label, Mount mount) { this.label = label; this.mount = mount; }
    }
    public enum Wheel {
        SMALL("小径 24mm", 24), STANDARD("中径 26mm", 26), LARGE("大径 30mm", 30);
        public final String label;
        public final int diameter;
        Wheel(String label, int diameter) { this.label = label; this.diameter = diameter; }
    }
    public enum Bearing {
        PLASTIC("塑料轴承"), BALL("滚珠轴承");
        public final String label;
        Bearing(String label) { this.label = label; }
    }
    public enum Damper {
        NONE("不装阻尼器"), SIDE("侧置阻尼器"), REAR("后置阻尼器");
        public final String label;
        Damper(String label) { this.label = label; }
    }
    public enum Body {
        STANDARD("标准车壳"), LIGHT("轻量车壳"), CLOSED("全覆车壳");
        public final String label;
        Body(String label) { this.label = label; }
    }
    public enum Battery {
        LIGHT("轻量电池"), STANDARD("标准电池"), ENDURANCE("耐力电池");
        public final String label;
        Battery(String label) { this.label = label; }
    }
    public enum Gear {
        ACCELERATION("加速齿轮"), BALANCED("均衡齿轮"), SPEED("高速齿轮");
        public final String label;
        Gear(String label) { this.label = label; }
    }
    public enum Tire {
        GRIP("抓地轮胎"), STANDARD("标准轮胎"), HARD("硬质轮胎");
        public final String label;
        Tire(String label) { this.label = label; }
    }
    public enum Roller {
        STABLE("稳定导轮"), STANDARD("标准导轮"), FAST("低阻导轮");
        public final String label;
        Roller(String label) { this.label = label; }
    }
    public enum Brake {
        LIGHT("轻刹车"), STANDARD("标准刹车"), STRONG("强力刹车");
        public final String label;
        Brake(String label) { this.label = label; }
    }
    public record Performance(double speed, double acceleration, double curveLimit, double rampLimit, double capacity, double braking) {}

    public MiniSetup {
        if (motor == null) motor = Motor.BALANCED;
        if (battery == null) battery = Battery.STANDARD;
        if (gear == null) gear = Gear.BALANCED;
        if (tire == null) tire = Tire.STANDARD;
        if (roller == null) roller = Roller.STANDARD;
        if (brake == null) brake = Brake.STANDARD;
        if (chassis == null) chassis = Chassis.REAR;
        if (mount == null) mount = Mount.SINGLE;
        if (wheel == null) wheel = Wheel.STANDARD;
        if (bearing == null) bearing = Bearing.PLASTIC;
        if (damper == null) damper = Damper.NONE;
        if (body == null) body = Body.STANDARD;
        // Missing fields in pre-assembly saves mean a complete legacy kit.
        if (installedParts == null) installedParts = MiniAssembly.REQUIRED;
        installedParts = installedParts < 0 ? 0 : installedParts & MiniAssembly.ALL;
    }

    public MiniSetup(Motor motor, Battery battery, Gear gear, Tire tire, Roller roller, Brake brake) {
        this(motor, battery, gear, tire, roller, brake, null, null, null, null, null, null, null);
    }
    public static MiniSetup stock() { return new MiniSetup(null, null, null, null, null, null); }
    public static MiniSetup empty() { return stock().withInstalled(0); }
    public boolean has(MiniAssembly.Slot slot) { return (installedParts & slot.bit()) != 0; }
    public boolean ready() { return MiniAssembly.ready(this); }
    public MiniSetup withInstalled(int mask) { return new MiniSetup(motor, battery, gear, tire, roller, brake, chassis, mount, wheel, bearing, damper, body, mask); }
    public MiniSetup withMotor(Motor value) { return preserve(new MiniSetup(value, battery, gear, tire, roller, brake)); }

    private MiniSetup preserve(MiniSetup value) {
        return new MiniSetup(value.motor, value.battery, value.gear, value.tire, value.roller, value.brake, chassis, mount, wheel, bearing, damper, body, installedParts);
    }

    public MiniSetup change(String field, int step) {
        return preserve(switch (field) {
            case "motor" -> withMotor(cycle(motor, Motor.values(), step));
            case "battery" -> new MiniSetup(motor, cycle(battery, Battery.values(), step), gear, tire, roller, brake);
            case "gear" -> new MiniSetup(motor, battery, cycle(gear, Gear.values(), step), tire, roller, brake);
            case "tire" -> new MiniSetup(motor, battery, gear, cycle(tire, Tire.values(), step), roller, brake);
            case "roller" -> new MiniSetup(motor, battery, gear, tire, cycle(roller, Roller.values(), step), brake);
            case "brake" -> new MiniSetup(motor, battery, gear, tire, roller, cycle(brake, Brake.values(), step));
            default -> throw new IllegalArgumentException("Unknown Mini 4WD part");
        });
    }

    public double mass() {
        double[] masses = {chassis == Chassis.MID ? 21 : chassis == Chassis.FRONT ? 20 : 18,
            bearing == Bearing.BALL ? 3 : 2, 7, 17, wheel == Wheel.LARGE ? 14 : wheel == Wheel.SMALL ? 9 : 11,
            battery == Battery.LIGHT ? 40 : battery == Battery.ENDURANCE ? 56 : 48, 8, 3,
            damper == Damper.SIDE ? 10 : damper == Damper.REAR ? 6 : 0, body == Body.LIGHT ? 5 : body == Body.CLOSED ? 13 : 9};
        double result = 0;
        for (var slot : MiniAssembly.Slot.values()) if (has(slot)) result += masses[slot.ordinal()];
        return result;
    }

    public Performance performance() {
        double speed = motor.speed;
        double acceleration = 3.8;
        double curve = MiniTrial.CURVE_LIMIT;
        double ramp = MiniTrial.RAMP_LIMIT;
        double capacity = 1;
        double braking = 4.7;
        switch (battery) {
            case LIGHT -> { speed *= 1.02; acceleration *= 1.08; capacity = .72; }
            case ENDURANCE -> { acceleration *= .90; capacity = 1.65; }
            default -> { }
        }
        switch (gear) {
            case ACCELERATION -> { speed *= .92; acceleration *= 1.30; }
            case SPEED -> { speed *= 1.10; acceleration *= .80; }
            default -> { }
        }
        switch (tire) {
            case GRIP -> { speed *= .98; curve *= 1.12; }
            case HARD -> { speed *= 1.04; curve *= .93; }
            default -> { }
        }
        switch (roller) {
            case STABLE -> { speed *= .98; curve *= 1.10; }
            case FAST -> { speed *= 1.04; curve *= .96; }
            default -> { }
        }
        switch (brake) {
            case LIGHT -> { speed *= 1.02; ramp *= .94; braking *= .80; }
            case STRONG -> { speed *= .97; ramp *= 1.10; braking *= 1.45; }
            default -> { }
        }
        double diameter = wheel.diameter / 26.0;
        speed *= diameter;
        acceleration /= diameter;
        if (bearing == Bearing.BALL) { speed *= 1.025; acceleration *= 1.04; }
        if (chassis == Chassis.MID) curve *= 1.025;
        if (chassis == Chassis.FRONT) ramp *= 1.035;
        if (has(MiniAssembly.Slot.DAMPER)) ramp *= damper == Damper.SIDE ? 1.10 : 1.065;
        // Preserve the established stock model while letting new parts carry a mass cost.
        double addedMass = (chassis == Chassis.MID ? 3 : chassis == Chassis.FRONT ? 2 : 0)
            + (bearing == Bearing.BALL ? 1 : 0) + (wheel == Wheel.LARGE ? 3 : wheel == Wheel.SMALL ? -2 : 0)
            + (has(MiniAssembly.Slot.DAMPER) ? damper == Damper.SIDE ? 10 : 6 : 0)
            + (body == Body.LIGHT ? -4 : body == Body.CLOSED ? 4 : 0);
        acceleration *= 123 / (123 + addedMass);
        return new Performance(speed, acceleration, curve, ramp, capacity, braking);
    }

    public String key() {
        String legacy = motor.name() + '-' + battery.name() + '-' + gear.name() + '-' + tire.name() + '-' + roller.name() + '-' + brake.name();
        if (chassis == Chassis.REAR && mount == Mount.SINGLE && wheel == Wheel.STANDARD && bearing == Bearing.PLASTIC && damper == Damper.NONE && body == Body.STANDARD) return legacy;
        return legacy + '-' + chassis + '-' + mount + '-' + wheel + '-' + bearing + '-' + (has(MiniAssembly.Slot.DAMPER) ? damper : Damper.NONE) + '-' + body;
    }

    private static <T extends Enum<T>> T cycle(T current, T[] values, int step) {
        return values[Math.floorMod(current.ordinal() + step, values.length)];
    }
}
