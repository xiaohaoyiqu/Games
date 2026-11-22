package com.f1car.mini4wd;

import java.util.ArrayList;
import java.util.List;

/** Kit assembly rules. Dimensions and performance are deliberately game abstractions. */
public final class MiniAssembly {
    private MiniAssembly() {}

    public enum Slot {
        CHASSIS("底盘", "先选择底盘。中置底盘使用双轴马达，前置和后置底盘使用单轴马达。"),
        BEARING("轴承", "装入四个车轴轴承，支撑前后轮轴。滚珠轴承减小传动损失。"),
        GEAR("传动组", "包含前后轮轴和传动齿轮。大减速比偏加速，小减速比偏极速。"),
        MOTOR("马达", "先安装传动组，再匹配单轴或双轴马达。维护前先取下车壳与电池。"),
        WHEELS("四轮轮组", "四个轮毂和轮胎成组安装。小径偏加速，大径偏极速。"),
        BATTERY("电池", "马达安装后装入两节电池。拆传动和马达前先取出电池。"),
        ROLLER("导轮", "安装前后导轮与支架，让车辆沿轨道护墙转弯。"),
        BRAKE("刹车片", "装在底盘下方，影响坡道稳定性与减速。"),
        DAMPER("质量阻尼器", "选装配重。增加质量，改善游戏中的坡道稳定阈值。"),
        BODY("车壳", "最后扣好车壳。拆装内部零件前需要先取下车壳。");
        public final String label, description;
        Slot(String label, String description) { this.label = label; this.description = description; }
        public int bit() { return 1 << ordinal(); }
    }
    public static final int ALL = (1 << Slot.values().length) - 1;
    public static final int REQUIRED = ALL & ~Slot.DAMPER.bit();

    static boolean ready(MiniSetup setup) {
        return (setup.installedParts() & REQUIRED) == REQUIRED && !wrongMount(setup) && !invalidDamper(setup);
    }

    public static List<String> issues(MiniSetup setup) {
        List<String> result = new ArrayList<>();
        for (Slot slot : Slot.values()) if (slot != Slot.DAMPER && !setup.has(slot)) result.add("缺少" + slot.label);
        if (wrongMount(setup))
            result.add(setup.chassis().label + "需要" + setup.chassis().mount.label + "马达");
        if (invalidDamper(setup)) result.add("阻尼器未选择有效零件");
        return List.copyOf(result);
    }

    private static boolean wrongMount(MiniSetup setup) {
        return setup.has(Slot.MOTOR) && setup.has(Slot.CHASSIS) && setup.mount() != setup.chassis().mount;
    }

    private static boolean invalidDamper(MiniSetup setup) {
        return setup.has(Slot.DAMPER) && setup.damper() == MiniSetup.Damper.NONE;
    }

    public static String installationError(MiniSetup setup, Slot slot, MiniSetup candidate) {
        if (slot == Slot.CHASSIS) {
            if ((setup.installedParts() & ~Slot.CHASSIS.bit()) != 0) return "更换底盘前，请拆下其他零件，或开始一盒新套件。";
            return "";
        }
        if (!setup.has(Slot.CHASSIS)) return "请先安装底盘。";
        if (internal(slot) && setup.has(Slot.BODY)) return "请先取下车壳，再操作内部零件。";
        if ((slot == Slot.MOTOR || slot == Slot.GEAR || slot == Slot.BEARING) && setup.has(Slot.BATTERY)) return "请先取出电池。";
        if ((slot == Slot.GEAR || slot == Slot.WHEELS) && !setup.has(Slot.BEARING)) return "请先安装轴承。";
        if ((slot == Slot.MOTOR || slot == Slot.WHEELS) && !setup.has(Slot.GEAR)) return "请先安装传动组。";
        if (slot == Slot.BATTERY && !setup.has(Slot.MOTOR)) return "请先安装马达。";
        if (slot == Slot.MOTOR && candidate.mount() != setup.chassis().mount) return "接口不兼容：当前底盘需要" + setup.chassis().mount.label + "马达。";
        if (slot == Slot.DAMPER && candidate.damper() == MiniSetup.Damper.NONE) return "请选择侧置或后置阻尼器；不使用时可保持空位。";
        if (slot == Slot.BODY && (setup.installedParts() & (REQUIRED & ~Slot.BODY.bit())) != (REQUIRED & ~Slot.BODY.bit())) return "请先装齐底盘、传动、马达、四轮、电池、导轮和刹车片。";
        return "";
    }

    public static String removalError(MiniSetup setup, Slot slot) {
        if (!setup.has(slot)) return "这个安装位是空的。";
        if (slot == Slot.CHASSIS && (setup.installedParts() & ~Slot.CHASSIS.bit()) != 0) return "请先拆下底盘上的其他零件。";
        if (internal(slot) && setup.has(Slot.BODY)) return "请先取下车壳。";
        if ((slot == Slot.MOTOR || slot == Slot.GEAR || slot == Slot.BEARING) && setup.has(Slot.BATTERY)) return "请先取出电池。";
        if (slot == Slot.GEAR && (setup.has(Slot.MOTOR) || setup.has(Slot.WHEELS))) return "请先拆下马达和四轮轮组。";
        if (slot == Slot.BEARING && (setup.has(Slot.GEAR) || setup.has(Slot.WHEELS))) return "请先拆下传动组和四轮轮组。";
        return "";
    }

    private static boolean internal(Slot slot) { return slot == Slot.BEARING || slot == Slot.GEAR || slot == Slot.MOTOR || slot == Slot.BATTERY; }

    public static String label(MiniSetup setup, Slot slot) {
        return switch (slot) {
            case CHASSIS -> setup.chassis().label;
            case BEARING -> setup.bearing().label;
            case GEAR -> setup.gear().label + " " + switch (setup.gear()) { case ACCELERATION -> "5:1"; case BALANCED -> "4:1"; case SPEED -> "3.5:1"; };
            case MOTOR -> setup.mount().label + " · " + setup.motor().label;
            case WHEELS -> setup.wheel().label + " · " + setup.tire().label;
            case BATTERY -> setup.battery().label;
            case ROLLER -> setup.roller().label;
            case BRAKE -> setup.brake().label;
            case DAMPER -> setup.damper().label;
            case BODY -> setup.body().label;
        };
    }

    public static List<MiniSetup> choices(MiniSetup base, Slot slot) {
        List<MiniSetup> result = new ArrayList<>();
        int count = slot == Slot.MOTOR ? 6 : slot == Slot.WHEELS ? 9 : slot == Slot.BEARING ? 2 : 3;
        for (int i = 0; i < count; i++) {
            result.add(new MiniSetup(
                slot == Slot.MOTOR ? MiniTrial.Motor.values()[i % 3] : base.motor(),
                slot == Slot.BATTERY ? MiniSetup.Battery.values()[i] : base.battery(),
                slot == Slot.GEAR ? MiniSetup.Gear.values()[i] : base.gear(),
                slot == Slot.WHEELS ? MiniSetup.Tire.values()[i % 3] : base.tire(),
                slot == Slot.ROLLER ? MiniSetup.Roller.values()[i] : base.roller(),
                slot == Slot.BRAKE ? MiniSetup.Brake.values()[i] : base.brake(),
                slot == Slot.CHASSIS ? MiniSetup.Chassis.values()[i] : base.chassis(),
                slot == Slot.MOTOR ? MiniSetup.Mount.values()[i / 3] : base.mount(),
                slot == Slot.WHEELS ? MiniSetup.Wheel.values()[i / 3] : base.wheel(),
                slot == Slot.BEARING ? MiniSetup.Bearing.values()[i] : base.bearing(),
                slot == Slot.DAMPER ? MiniSetup.Damper.values()[i] : base.damper(),
                slot == Slot.BODY ? MiniSetup.Body.values()[i] : base.body(), base.installedParts()));
        }
        return List.copyOf(result);
    }
}
