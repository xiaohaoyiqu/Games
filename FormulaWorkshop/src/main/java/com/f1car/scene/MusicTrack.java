package com.f1car.scene;

/** Original, offline synthesized scores. Each file contains a complete seamless phrase. */
public enum MusicTrack {
    MENU("海湾启程", "menu", 108),
    WORKSHOP("静夜造路", "workshop", 88),
    RACE("向着终点", "race", 136),
    MINI("小小飞驰", "mini", 124);

    public final String title, asset;
    public final int bpm;
    MusicTrack(String title, String file, int bpm) {
        this.title = title; this.asset = "Sounds/music-" + file + ".wav"; this.bpm = bpm;
    }
}
