package com.f1car.ui;

import com.f1car.mini4wd.MiniAssembly.Slot;
import com.f1car.mini4wd.MiniSetup;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;

/** Shared pixel geometry: the workbench and race use the actual installed parts. */
final class MiniCarPainter {
    private MiniCarPainter() {}

    static void paint(Graphics2D target, double x, double y, double angle, double scale, MiniSetup setup, Color color, boolean cutaway) {
        Graphics2D g = (Graphics2D) target.create();
        g.translate(x, y); g.rotate(angle); g.scale(scale, scale);
        if (setup.has(Slot.CHASSIS)) {
            g.setColor(setup.chassis() == MiniSetup.Chassis.MID ? new Color(0x4f627a) : setup.chassis() == MiniSetup.Chassis.FRONT ? new Color(0x6e596e) : new Color(0x344c58));
            g.fillRect(-18, -8, 36, 16); g.fillRect(-13, -11, 26, 22);
            g.setColor(new Color(0x26313c));
            g.fillRect(-16, -6, 31, 12);
        }
        if (setup.has(Slot.GEAR)) {
            g.setColor(new Color(0xb5c7ce));
            g.fillRect(-12, -16, 2, 32); g.fillRect(10, -16, 2, 32);
            g.fillRect(-11, -1, 23, 2);
            g.setColor(new Color(0xf0c668));
            g.fillRect(-14, -3, 6, 6); g.fillRect(8, -3, 6, 6);
        }
        if (setup.has(Slot.BEARING)) {
            g.setColor(setup.bearing() == MiniSetup.Bearing.BALL ? new Color(0xe6eff5) : new Color(0x929c9c));
            for (int ax : new int[] {-12, 10}) for (int ay : new int[] {-9, 7}) g.fillRect(ax, ay, 3, 3);
        }
        if (setup.has(Slot.MOTOR)) {
            int mx = setup.chassis() == MiniSetup.Chassis.MID ? -4 : setup.chassis() == MiniSetup.Chassis.FRONT ? 6 : -14;
            g.setColor(new Color(0xa9b9c3)); g.fillRect(mx, -5, 9, 10);
            g.setColor(setup.motor() == com.f1car.mini4wd.MiniTrial.Motor.SPEED ? new Color(0xf29c6b) : new Color(0x83cba8));
            g.fillRect(mx, -5, 2, 10);
        }
        if (setup.has(Slot.BATTERY)) {
            g.setColor(new Color(0xd5af59));
            g.fillRect(-7, -8, 15, 4); g.fillRect(-7, 4, 15, 4);
            g.setColor(new Color(0xf7e4a2)); g.fillRect(5, -8, 3, 4); g.fillRect(-7, 4, 3, 4);
        }
        if (setup.has(Slot.WHEELS)) {
            int width = setup.wheel() == MiniSetup.Wheel.LARGE ? 9 : setup.wheel() == MiniSetup.Wheel.SMALL ? 6 : 7;
            g.setColor(setup.tire() == MiniSetup.Tire.GRIP ? new Color(0x444c4d) : new Color(0x161e28));
            for (int ax : new int[] {-11, 11}) for (int ay : new int[] {-13, 13}) {
                g.fillRect(ax - width / 2, ay - 3, width, 6);
                g.setColor(color); g.fillRect(ax - 1, ay - 1, 2, 2);
                g.setColor(setup.tire() == MiniSetup.Tire.GRIP ? new Color(0x444c4d) : new Color(0x161e28));
            }
        }
        if (setup.has(Slot.ROLLER)) {
            int reach = setup.roller() == MiniSetup.Roller.STABLE ? 19 : 17;
            g.setColor(new Color(0x7c949f));
            g.fillRect(-22, -reach, 3, reach * 2); g.fillRect(19, -reach, 3, reach * 2);
            for (int ax : new int[] {-21, 20}) for (int ay : new int[] {-reach, reach}) {
                g.setColor(new Color(0xc9e0dc)); g.fillRect(ax - 2, ay - 2, 5, 5);
                g.setColor(new Color(0x506c77)); g.fillRect(ax, ay, 1, 1);
            }
        }
        if (setup.has(Slot.BRAKE)) { g.setColor(new Color(0xe99c9c)); g.fillRect(-23, -6, 3, 12); }
        if (setup.has(Slot.DAMPER)) {
            g.setColor(new Color(0xe7bc5d));
            if (setup.damper() == MiniSetup.Damper.SIDE) { g.fillRect(-3, -14, 6, 3); g.fillRect(-3, 11, 6, 3); }
            else g.fillRect(-18, -5, 3, 10);
        }
        if (setup.has(Slot.BODY)) {
            g.setColor(color);
            int length = setup.body() == MiniSetup.Body.CLOSED ? 32 : setup.body() == MiniSetup.Body.LIGHT ? 21 : 27;
            if (cutaway) { g.setStroke(new BasicStroke(1)); g.drawRect(-length / 2, -7, length, 14); }
            else {
                g.fillRect(-length / 2, -7, length, 14); g.fillRect(11, -5, 6, 10);
                g.setColor(new Color(0x203444)); g.fillRect(1, -4, 7, 8);
                g.setColor(new Color(0xeaf4ef)); g.fillRect(-9, -2, 8, 4);
            }
        }
        g.dispose();
    }
}
