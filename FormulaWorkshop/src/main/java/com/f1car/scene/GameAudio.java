package com.f1car.scene;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioSource;
import java.util.Arrays;

/** Fixed voice pool: scene transitions never allocate or load another sound. */
public final class GameAudio {
    public enum Cue { COUNTDOWN, GO, COMPLETE }
    interface Voice {
        void volume(float gain);
        void pitch(float pitch);
        void play();
        void pause();
        void stop();
        boolean playing();
    }
    private record NodeVoice(AudioNode node) implements Voice {
        public void volume(float gain) { if(node.getVolume()!=gain) node.setVolume(gain); }
        public void pitch(float pitch) { if(node.getPitch()!=pitch) node.setPitch(pitch); }
        public void play() { node.play(); }
        public void pause() { node.pause(); }
        public void stop() { node.stop(); }
        public boolean playing() { return node.getStatus() == AudioSource.Status.Playing; }
    }
    private final Voice engine;
    private final Voice[] music, cues;
    private final float[] weights = new float[MusicTrack.values().length];
    private MusicTrack track = MusicTrack.MENU;
    private boolean audible = true;

    public GameAudio(AssetManager assets) {
        this(load(assets, "Sounds/engine.wav", true),
            Arrays.stream(MusicTrack.values()).map(t -> load(assets, t.asset, true)).toArray(Voice[]::new),
            new Voice[]{load(assets,"Sounds/cue.wav",false),load(assets,"Sounds/go.wav",false),load(assets,"Sounds/complete.wav",false)});
    }
    GameAudio(Voice engine, Voice[] music, Voice[] cues) {
        if(music.length != weights.length || cues.length != Cue.values().length) throw new IllegalArgumentException("Invalid voice pool");
        this.engine = engine; this.music = music; this.cues = cues;
        engine.volume(0);
        for(var voice : music) voice.volume(0);
    }
    private static Voice load(AssetManager assets, String path, boolean loop) {
        var node = new AudioNode(assets, path, AudioData.DataType.Buffer);
        node.setPositional(false); node.setLooping(loop);
        return new NodeVoice(node);
    }
    private static float unit(float value) { return Float.isFinite(value) ? Math.max(0, Math.min(1,value)) : 0; }

    public void update(float dt, MusicTrack target, boolean musicEnabled, float musicVolume,
                       boolean effectsEnabled, float effectsVolume, boolean focused,
                       boolean driving, float speed, float throttle, boolean duck) {
        audible = focused;
        track = target;
        float musicLevel=musicEnabled && focused?unit(musicVolume)*(duck?.4f:1):0;
        float effectsLevel=effectsEnabled && focused?unit(effectsVolume):0;
        float step = Float.isFinite(dt) ? Math.max(0,Math.min(.1f,dt)) / .8f : 0;
        float total = 0;
        for(int i=0;i<weights.length;i++) {
            float desired = i == target.ordinal() ? 1 : 0;
            weights[i] += Math.max(-step, Math.min(step, desired-weights[i]));
            total += weights[i];
        }
        for(int i=0;i<music.length;i++) {
            // Normalize interrupted crossfades so quickly opening menus cannot raise the mix.
            float gain=weights[i]/Math.max(1,total)*musicLevel;
            music[i].volume(gain);
            if(gain > .0001f) { if(!music[i].playing()) music[i].play(); }
            else if(music[i].playing()) music[i].pause();
        }
        float demand = Float.isFinite(throttle) ? Math.min(1,Math.abs(throttle)) : 0;
        float velocity = Float.isFinite(speed) ? Math.max(0,speed) : 0;
        engine.volume(driving?effectsLevel*(.12f+demand*.2f):0);
        if(driving && effectsLevel>0) {
            // AudioNode accepts pitch from 0.5 through 2.0.
            engine.pitch(Math.max(.5f,Math.min(2f,.65f+velocity/38)));
            if(!engine.playing()) engine.play();
        }
        else if(engine.playing()) engine.pause();
        if(effectsLevel==0) for(var cue:cues) if(cue.playing()) cue.stop();
    }
    public void cue(Cue type, boolean enabled, float volume) {
        if(enabled && audible && unit(volume)>0) {
            var voice = cues[type.ordinal()]; voice.stop(); voice.volume(unit(volume)*.65f); voice.play();
        }
    }
    /** Called even when the app pauses immediately after losing focus. */
    public void silence() {
        audible = false;
        engine.volume(0); if(engine.playing()) engine.pause();
        for(var voice:music) { voice.volume(0); if(voice.playing()) voice.pause(); }
        for(var cue:cues) cue.stop();
    }
    public void stopEffects() { engine.stop(); for(var cue:cues) cue.stop(); }
    public void stop() {
        audible=false;
        stopForShutdown(engine);
        for(var cue:cues) stopForShutdown(cue);
        for(var voice:music) stopForShutdown(voice);
        Arrays.fill(weights,0);
    }
    private static void stopForShutdown(Voice voice) {
        try { voice.stop(); }
        catch(RuntimeException unavailable) { /* The other voices may still be reachable. */ }
    }
    public MusicTrack track() { return track; }
    public boolean musicPlaying() { return Arrays.stream(music).anyMatch(Voice::playing); }
    public boolean enginePlaying() { return engine.playing(); }
}
