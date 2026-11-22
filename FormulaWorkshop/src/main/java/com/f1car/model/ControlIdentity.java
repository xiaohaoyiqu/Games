package com.f1car.model;

/** Archived keyboard keys stay byte-for-byte compatible; new conditions are explicit. */
public final class ControlIdentity {
    public static final String KEYBOARD="keyboard-v1";
    private ControlIdentity() { }
    public static boolean valid(String value) { return KEYBOARD.equals(value) || value!=null && value.matches("gamepad-v1-[a-f0-9]{64}"); }
    public static String require(String value) { if(!valid(value)) throw new IllegalArgumentException("Invalid control conditions");return value; }
    public static String online(String value) { return KEYBOARD.equals(require(value))?"keyboard-online-v2":value+"-online-v2"; }
}
