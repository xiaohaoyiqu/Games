"""Compose original pixel-electronic scores from notes; no samples or external tools.

Run with Python 3.10+. Output is mono PCM16 / 22050 Hz, 16 bars per loop.
Reverb tails wrap into the beginning to keep every bar and loop boundary continuous.
"""
from array import array
from pathlib import Path
import hashlib
import json
import math
import random
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/Sounds"
RATE = 22050
TAU = math.tau

# MIDI numbers are composed here directly; each phrase spans two bars.
SCORES = {
    "menu": ("海湾启程", 108, [48, 53, 57, 55, 48, 53, 50, 55],
             [72, 76, 79, 76, 74, 72, 67, 71, 72, 74, 77, 76, 74, 71, 67, 72]),
    "workshop": ("静夜造路", 88, [45, 53, 48, 55, 45, 50, 53, 52],
                 [76, 72, 71, 69, 72, 76, 79, 77, 76, 74, 72, 71, 69, 67, 71, 72]),
    "race": ("向着终点", 136, [45, 53, 48, 55, 45, 50, 53, 52],
             [76, 79, 81, 79, 76, 74, 72, 74, 76, 81, 83, 81, 79, 76, 74, 71]),
    "mini": ("小小飞驰", 124, [48, 55, 57, 53, 48, 50, 55, 55],
             [79, 76, 72, 76, 81, 79, 76, 74, 77, 81, 84, 81, 79, 77, 74, 72]),
}


def frequency(note):
    return 440 * 2 ** ((note - 69) / 12)


def tone(buffer, start, duration, note, gain, timbre="bell"):
    count = round(duration * RATE)
    begin = round(start * RATE)
    hz = frequency(note)
    # All waveforms use a finite harmonic series; no aliasing square-wave edges.
    harmonics = {"bell": [(1, 1), (2, .22), (3, .09)],
                 "pulse": [(1, 1), (3, .22), (5, .09)],
                 "pad": [(1, 1), (2, .12)], "bass": [(1, 1), (2, .18)]}[timbre]
    harmonics = [(n, amp) for n, amp in harmonics if n * hz < RATE * .43]
    for i in range(count):
        t = i / RATE
        attack = min(1, t / (.06 if timbre == "pad" else .008))
        release = min(1, (duration - t) / (.15 if timbre == "pad" else .025))
        decay = math.exp(-t * (1.6 if timbre in ("pad", "bass") else 4))
        value = sum(math.sin(TAU * hz * n * t) * amp for n, amp in harmonics)
        buffer[(begin + i) % len(buffer)] += value * gain * attack * release * decay


def drum(buffer, start, gain, kind, rng):
    duration = .19 if kind == "kick" else .12 if kind == "snare" else .045
    begin = round(start * RATE)
    previous = 0
    for i in range(round(duration * RATE)):
        t = i / RATE
        if kind == "kick":
            # Integral of a falling frequency: a soft, rounded kick.
            value = math.sin(TAU * (49*t + 8*(1-math.exp(-t*34)))) * math.exp(-t*25)
        else:
            noise = rng.uniform(-1, 1)
            value = (noise - previous) * .45 * math.exp(-t*(35 if kind == "snare" else 90))
            previous = noise
            if kind == "snare":
                value += math.sin(TAU * 180 * t) * .3 * math.exp(-t*40)
        buffer[(begin+i) % len(buffer)] += value * gain * min(1,t/.002)


def write_wav(path, buffer, peak=.72):
    scale = peak / max(.001, max(abs(v) for v in buffer))
    pcm = array("h", (round(max(-1, min(1, v*scale))*32767) for v in buffer))
    if sys.byteorder != "little":
        pcm.byteswap()
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(RATE)
        out.writeframes(pcm.tobytes())
    return {"file": path.name, "frames": len(buffer), "seconds": round(len(buffer)/RATE, 6),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def score(name, title, bpm, roots, melody):
    beat = 60 / bpm
    buffer = array("f", [0]) * round(64 * beat * RATE)
    rng = random.Random(1000 + bpm)
    gentle = name == "workshop"
    for bar in range(16):
        root = roots[bar % 8]
        minor = root % 12 in (9, 2, 4)
        chord = [root+12, root+(15 if minor else 16), root+19, root+23]
        start = bar * 4 * beat
        for note in chord[:3]:
            tone(buffer, start, beat*4.2, note, .027 if gentle else .018, "pad")
        for step in range(8):
            at = start + step*beat/2
            note = chord[[0, 1, 2, 1, 0, 2, 3, 2][step]]
            tone(buffer, at, beat*.7, note+12, .025 if gentle else .038, "bell")
            if not gentle or step % 2 == 0:
                tone(buffer, at, beat*.43, root-(12 if step % 4 != 2 else 0), .14 if name == "race" else .105, "bass")
            if not gentle:
                drum(buffer, at, .065, "hat", rng)
        # A quiet first four bars leave space before the lead enters.
        if bar >= 4 or name == "mini":
            for step in range(4):
                note = melody[((bar % 4)*4 + step) % len(melody)]
                if bar >= 12 and step == 3:
                    note += 12 if name == "mini" else 0
                tone(buffer, start+step*beat, beat*.78, note, .075 if gentle else .105,
                     "bell" if gentle or name == "mini" else "pulse")
        if gentle:
            drum(buffer, start, .075, "kick", rng)
            drum(buffer, start+beat*2, .035, "hat", rng)
        else:
            for pulse in (0, 2) if name == "menu" else (0, 1, 2, 3):
                drum(buffer, start+beat*pulse, .22, "kick", rng)
            for pulse in (1, 3):
                drum(buffer, start+beat*pulse, .14, "snare", rng)
    dry = array("f", buffer)
    for delay_beats, gain in ((.75, .13), (1.5, .055)):
        shift = round(delay_beats*beat*RATE)
        for i, value in enumerate(dry):
            buffer[(i+shift) % len(buffer)] += value*gain
    result = write_wav(OUTPUT / f"music-{name}.wav", buffer)
    result.update(title=title, bpm=bpm, bars=16, loop=True)
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    assets = [score(name, *definition) for name, definition in SCORES.items()]
    for name, notes, spacing in (("go", [72, 79, 84], .075), ("complete", [72, 76, 79, 84, 88], .11)):
        buffer = array("f", [0]) * round((len(notes)*spacing+.45)*RATE)
        for i, note in enumerate(notes):
            tone(buffer, i*spacing, .38, note, .26, "bell")
        assets.append(write_wav(OUTPUT / f"{name}.wav", buffer, .55))
    manifest = {"version": 1, "generator": "scripts/generate_music.py", "sampleRate": RATE,
                "channels": 1, "bits": 16, "source": "Original note patterns and additive synthesis; no external samples.", "assets": assets}
    (OUTPUT / "music-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"Generated {len(assets)} original audio assets ({sum((OUTPUT/a['file']).stat().st_size for a in assets)/1048576:.1f} MiB PCM)")


if __name__ == "__main__":
    main()
