#!/usr/bin/env python3
"""Synthesizes Sortopia's sound effects and cozy lo-fi music loops (SPEC §13).

Deterministic: running it again produces identical files. Usage (inside devenv, which provides
numpy and oggenc):
    python3 tools/gen_audio.py assets [track ...]   # e.g. "world1 menu"; default: all tracks
"""
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

RATE = 22050


def env(n, attack=0.005, release=0.08):
    t = np.arange(n) / RATE
    a = np.clip(t / attack, 0, 1)
    r = np.clip((n / RATE - t) / release, 0, 1)
    return a * r


def tone(freq, dur, kind="sine", vol=0.5, attack=0.005, release=0.08):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    if kind == "sine":
        w = np.sin(2 * np.pi * freq * t)
    elif kind == "soft":
        w = np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(4 * np.pi * freq * t) + 0.1 * np.sin(6 * np.pi * freq * t)
        w /= 1.4
    else:
        w = np.sign(np.sin(2 * np.pi * freq * t)) * 0.3
    return w * env(n, attack, release) * vol


def sweep(f0, f1, dur, vol=0.5):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    f = np.linspace(f0, f1, n)
    phase = 2 * np.pi * np.cumsum(f) / RATE
    return np.sin(phase) * env(n, 0.005, dur * 0.6) * vol


def seq(*parts, gap=0.0):
    out = []
    for p in parts:
        out.append(p)
        if gap:
            out.append(np.zeros(int(gap * RATE)))
    return np.concatenate(out)


def write(path, samples):
    samples = np.clip(samples, -1, 1)
    data = (samples * 32767 * 0.9).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


def note(n):
    """MIDI note number to Hz."""
    return 440.0 * 2 ** ((n - 69) / 12)


# ---------------------------------------------------------------------------------------------
# Music: cozy lo-fi loops (SPEC §13). Warm major-key 7th/9th chords on a soft FM e-piano, swung
# brushed drums, a round sub bass, light vinyl hiss and one signature lead per world playing a
# composed motif. Every loop is 24 bars; tails wrap to the start so it loops seamlessly.

BARS = 24
SWING = 0.58  # where the off-beat eighth lands, as a fraction of the beat

MAJ7 = [0, 4, 7, 11]
MIN7 = [0, 3, 7, 10]
DOM9 = [0, 4, 7, 10, 14]
MAJ9 = [0, 4, 7, 11, 14]
MIN9 = [0, 3, 7, 10, 14]
# (root offset from the key, chord) per bar.
PROG_A = [(0, MAJ7), (9, MIN9), (2, MIN7), (7, DOM9)]
PROG_A2 = [(0, MAJ9), (4, MIN7), (5, MAJ7), (7, DOM9)]
PROG_B = [(5, MAJ7), (4, MIN7), (9, MIN7), (7, DOM9), (5, MAJ9), (7, DOM9), (0, MAJ7), (0, MAJ9)]
MAJOR = [0, 2, 4, 5, 7, 9, 11]


def place(out, sig, start):
    """Adds sig at sample start, wrapping anything past the end to the beginning (seamless loop)."""
    start %= len(out)
    end = start + len(sig)
    if end <= len(out):
        out[start:end] += sig
    else:
        cut = len(out) - start
        out[start:] += sig[:cut]
        place(out, sig[cut:], 0)


def decay_env(n, attack, decay):
    t = np.arange(n) / RATE
    return np.minimum(t / attack, 1.0) * np.exp(-t / decay)


def epiano(freq, dur, vol):
    n = int((dur + 1.2) * RATE)
    t = np.arange(n) / RATE
    index = 1.4 * np.exp(-t / 0.35) + 0.15
    mod = np.sin(2 * np.pi * freq * t)
    tone = np.sin(2 * np.pi * freq * t + index * mod) + 0.25 * np.sin(4 * np.pi * freq * t)
    env = decay_env(n, 0.004, 1.1)
    release = np.clip((dur + 1.2 - t) / 1.2, 0, 1)
    trem = 1 + 0.06 * np.sin(2 * np.pi * 4.5 * t)
    return tone * env * release * trem * vol


def partials(freq, dur, vol, parts, attack=0.003):
    """A sum of (ratio, level, decay) sine partials: mallets, bells, boxes."""
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for ratio, level, decay in parts:
        out += level * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-t / decay)
    return out * np.minimum(t / attack, 1.0) * vol


def pluck(freq, dur, vol, damp=0.993, rng=None):
    """Karplus-Strong muted guitar."""
    n = int(dur * RATE)
    period = max(2, int(RATE / freq))
    buf = rng.uniform(-1, 1, period)
    out = np.empty(n)
    for i in range(n):
        out[i] = buf[i % period]
        buf[i % period] = damp * 0.5 * (buf[i % period] + buf[(i + 1) % period])
    return out * np.exp(-np.arange(n) / RATE / 0.5) * vol


def flute(freq, dur, vol, rng):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    vib = 1 + 0.006 * np.sin(2 * np.pi * 5 * t) * np.minimum(t / 0.3, 1)
    phase = 2 * np.pi * np.cumsum(freq * vib) / RATE
    tone = np.sin(phase) + 0.12 * np.sin(2 * phase) + 0.02 * rng.standard_normal(n)
    env = np.minimum(t / 0.06, 1) * np.clip((dur - t) / 0.12, 0, 1)
    return tone * env * vol


def bell(freq, dur, vol):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    index = 2.0 * np.exp(-t / 0.6)
    tone = np.sin(2 * np.pi * freq * t + index * np.sin(2 * np.pi * freq * 3.5 * t))
    return tone * decay_env(n, 0.002, 0.9) * vol


LEADS = {
    "marimba": lambda f, d, v, r: partials(f, max(d, 0.6), v, [(1, 1.0, 0.35), (3.9, 0.35, 0.06), (10, 0.08, 0.02)]),
    "celesta": lambda f, d, v, r: partials(f, max(d, 1.0), v, [(1, 1.0, 0.7), (2, 0.4, 0.4), (3, 0.15, 0.2)]),
    "musicbox": lambda f, d, v, r: partials(f * 2, 1.4, v * 0.8, [(1, 1.0, 0.9), (2.76, 0.3, 0.3), (5.4, 0.1, 0.1)]),
    "pluck": lambda f, d, v, r: pluck(f, max(d, 0.45), v * 1.2, rng=r),
    "flute": lambda f, d, v, r: flute(f * 2, d, v * 0.8, r),
    "steelpan": lambda f, d, v, r: partials(f * 2, max(d, 0.8), v * 0.8, [(1, 1.0, 0.45), (2, 0.6, 0.3), (3.01, 0.25, 0.15)], 0.006),
    "bells": lambda f, d, v, r: bell(f * 2, max(d, 1.2), v * 0.7),
    "kalimba": lambda f, d, v, r: partials(f * 2, max(d, 0.9), v * 0.8, [(1, 1.0, 0.5), (5.4, 0.25, 0.05), (2.0, 0.08, 0.2)]),
    "epiano": lambda f, d, v, r: epiano(f * 2, d, v * 0.9),
}


def noise_hit(rng, dur, decay, vol, smooth=1):
    n = int(dur * RATE)
    x = rng.standard_normal(n)
    if smooth > 1:
        x = np.convolve(x, np.ones(smooth) / smooth, mode="same")
    return x * np.exp(-np.arange(n) / RATE / decay) * vol


def kick(vol):
    n = int(0.3 * RATE)
    t = np.arange(n) / RATE
    f = 48 + 70 * np.exp(-t / 0.04)
    return np.sin(2 * np.pi * np.cumsum(f) / RATE) * np.exp(-t / 0.16) * vol


def snare(rng, vol):
    body = tone(185, 0.12, "sine", 0.4, release=0.1)
    brush = noise_hit(rng, 0.22, 0.07, 1.0, smooth=3)
    body = np.pad(body, (0, len(brush) - len(body)))
    return (body * 0.5 + brush * 0.5) * vol


def bass(freq, dur, vol):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    tone_ = np.sin(2 * np.pi * freq * t) + 0.18 * np.sin(4 * np.pi * freq * t)
    env = np.minimum(t / 0.01, 1) * np.exp(-t / 0.9) * np.clip((dur - t) / 0.05, 0, 1)
    return tone_ * env * vol


def voicing(key, root, chord):
    """Chord tones spread around middle C, root left to the bass."""
    notes = [key + root + i for i in chord[1:]]
    return [n_ - 12 if n_ > 72 else n_ + 12 if n_ < 55 else n_ for n_ in notes]


def snap(degree_note, chord_notes):
    """Moves a melody note to the nearest chord tone (any octave) for strong beats."""
    best = min(chord_notes, key=lambda c: min(abs((degree_note - c) % 12), abs((c - degree_note) % 12)))
    shift = (best - degree_note) % 12
    return degree_note + (shift if shift <= 6 else shift - 12)


def music(spec):
    key, tempo, lead, motif, seed = spec["key"], spec["tempo"], spec["lead"], spec["motif"], spec["seed"]
    rng = np.random.default_rng(seed)
    beat = 60.0 / tempo
    bar = 4 * beat
    total = int(BARS * bar * RATE)
    pads = np.zeros(total)
    drums = np.zeros(total)
    low = np.zeros(total)
    melody = np.zeros(total)

    def at(bar_i, beats):
        return int((bar_i * bar + beats * beat) * RATE)

    def swung(eighth):
        return eighth // 2 + (SWING if eighth % 2 else 0.0)

    prog = PROG_A * 2 + PROG_A2 * 2 + PROG_B
    for b in range(BARS):
        root, chord = prog[b % len(prog)]
        chord_notes = voicing(key, root, chord)
        # E-piano comping: a long chord on 1, a soft stab on the swung "and" of 2.
        for n_ in chord_notes:
            place(pads, epiano(note(n_), bar * 0.9, 0.06), at(b, 0.02 * (n_ % 3)))
            if b % 2 == 1:
                place(pads, epiano(note(n_), beat * 0.5, 0.035), at(b, 1 + SWING))
        # Bass: root on 1, fifth or approach on 3.
        bass_root = key + root - 24
        place(low, bass(note(bass_root), beat * 1.8, 0.32), at(b, 0))
        third = bass_root + (7 if b % 2 == 0 else 12)
        place(low, bass(note(third), beat * 1.6, 0.24), at(b, 2))
        # Drums: kick on 1 and the swung "and" of 3, brushed snare on 2 and 4, swung hats.
        place(drums, kick(0.5), at(b, 0))
        place(drums, kick(0.32), at(b, 2 + SWING))
        for s in (1, 3):
            place(drums, snare(rng, 0.22), at(b, s))
        for e in range(8):
            accent = 0.07 if e % 2 == 0 else 0.045
            place(drums, noise_hit(rng, 0.05, 0.012, accent), at(b, swung(e)))
        # Lead: the world's motif, answered in the second half of each phrase, resting every 4th bar.
        phrase = b % 4
        if phrase == 3 and b < 16:
            continue
        line_ = motif[(b // 2) % 2] if b < 16 else motif[2 + (b % 2)]
        for start, length, degree in line_:
            octave, step = divmod(degree, 7)
            n_ = key + MAJOR[step] + 12 * octave
            if float(start).is_integer():
                n_ = snap(n_, [key + root + c for c in chord])
            pos = swung(int(start * 2)) if not float(start).is_integer() else start
            vol = 0.17 * (0.85 + 0.3 * rng.random())
            place(melody, LEADS[lead](note(n_), length * beat, vol, rng), at(b, pos))

    mix = pads * 0.9 + drums * 0.8 + low * 0.9 + melody
    # Lo-fi warmth: a gentle low-pass, then soft saturation, then vinyl hiss and a few crackles.
    mix = np.convolve(mix, np.array([0.15, 0.2, 0.3, 0.2, 0.15]), mode="same")
    mix = np.tanh(mix * 1.4) / 1.4
    hiss = np.convolve(rng.standard_normal(total), np.ones(6) / 6, mode="same") * 0.006
    mix += hiss
    for pos in rng.integers(0, total - 40, 24):
        mix[pos:pos + 3] += rng.choice([-1, 1]) * 0.05 * np.array([1, -0.6, 0.3])
    return mix / np.max(np.abs(mix)) * 0.85


def M(*notes):
    """A bar of melody: (start beat, length in beats, scale degree from the key's tonic)."""
    return list(notes)


# Per world: key (MIDI), tempo, lead, and a 4-bar motif set (two A bars, two B bars).
TRACKS = {
    "world1": dict(key=62, tempo=84, lead="marimba", seed=11, motif=[
        M((0, 0.5, 4), (0.5, 0.5, 5), (1, 1, 7), (2.5, 0.5, 9), (3, 1, 7)),
        M((0, 1, 5), (1.5, 0.5, 4), (2, 1, 2), (3, 0.5, 4)),
        M((0, 1.5, 9), (2, 0.5, 7), (2.5, 0.5, 5), (3, 1, 4)),
        M((0, 1, 2), (1, 1, 4), (2, 2, 0))]),
    "world2": dict(key=65, tempo=82, lead="celesta", seed=12, motif=[
        M((0, 1, 7), (1, 0.5, 9), (1.5, 0.5, 7), (2, 1, 4), (3.5, 0.5, 5)),
        M((0, 1.5, 4), (2, 0.5, 2), (2.5, 0.5, 4), (3, 1, 5)),
        M((0, 0.5, 9), (0.5, 0.5, 8), (1, 1, 7), (2, 2, 4)),
        M((0, 1, 5), (1, 1, 4), (2, 2, 2))]),
    "world3": dict(key=60, tempo=86, lead="musicbox", seed=13, motif=[
        M((0, 0.5, 7), (0.5, 0.5, 9), (1, 0.5, 11), (1.5, 0.5, 9), (2, 1, 7), (3, 1, 4)),
        M((0, 1, 5), (1, 1, 7), (2, 2, 4)),
        M((0, 0.5, 11), (0.5, 0.5, 9), (1, 1, 7), (2.5, 0.5, 5), (3, 1, 4)),
        M((0, 1, 2), (1.5, 0.5, 4), (2, 2, 0))]),
    "world4": dict(key=67, tempo=80, lead="pluck", seed=14, motif=[
        M((0, 0.5, 0), (0.5, 0.5, 2), (1, 0.5, 4), (2, 0.5, 4), (2.5, 0.5, 5), (3, 1, 4)),
        M((0, 0.5, 2), (0.5, 0.5, 4), (1, 1, 2), (2, 1, 0), (3.5, 0.5, -1)),
        M((0, 1, 4), (1, 0.5, 5), (1.5, 0.5, 7), (2, 1, 5), (3, 1, 4)),
        M((0, 1, 2), (1, 1, 1), (2, 2, 0))]),
    "world5": dict(key=63, tempo=78, lead="flute", seed=15, motif=[
        M((0, 2, 4), (2, 1, 5), (3, 1, 7)),
        M((0, 1.5, 5), (1.5, 0.5, 4), (2, 2, 2)),
        M((0, 1, 7), (1, 1, 9), (2, 1.5, 7), (3.5, 0.5, 5)),
        M((0, 2, 4), (2, 2, 0))]),
    "world6": dict(key=64, tempo=82, lead="steelpan", seed=16, motif=[
        M((0, 0.5, 4), (0.5, 0.5, 4), (1, 0.5, 5), (1.5, 0.5, 7), (2.5, 0.5, 7), (3, 1, 5)),
        M((0, 0.5, 4), (0.5, 0.5, 2), (1, 1, 0), (2.5, 0.5, 2), (3, 1, 4)),
        M((0, 1, 7), (1, 0.5, 9), (1.5, 0.5, 7), (2, 1, 5), (3, 1, 4)),
        M((0, 1, 2), (1, 0.5, 4), (1.5, 0.5, 2), (2, 2, 0))]),
    "world7": dict(key=69, tempo=80, lead="bells", seed=17, motif=[
        M((0, 1, 7), (1, 1, 4), (2, 1, 5), (3, 1, 2)),
        M((0, 1.5, 4), (2, 2, 0)),
        M((0, 1, 9), (1, 1, 7), (2, 1, 5), (3, 1, 4)),
        M((0, 2, 2), (2, 2, 4))]),
    "world8": dict(key=58, tempo=84, lead="kalimba", seed=18, motif=[
        M((0, 0.5, 7), (0.5, 0.5, 4), (1, 0.5, 2), (1.5, 0.5, 4), (2, 1, 7), (3, 0.5, 9), (3.5, 0.5, 7)),
        M((0, 1, 5), (1, 0.5, 4), (1.5, 0.5, 2), (2, 2, 4)),
        M((0, 0.5, 9), (0.5, 0.5, 7), (1, 0.5, 5), (1.5, 0.5, 7), (2, 2, 9)),
        M((0, 0.5, 7), (0.5, 0.5, 5), (1, 1, 4), (2, 2, 0))]),
    "menu": dict(key=65, tempo=80, lead="epiano", seed=19, motif=[
        M((0, 1, 4), (1, 0.5, 5), (1.5, 1.5, 7), (3.5, 0.5, 4)),
        M((0, 1.5, 5), (2, 2, 2)),
        M((0, 1, 7), (1, 1, 9), (2, 1, 7), (3, 1, 4)),
        M((0, 2, 2), (2, 2, 0))]),
}


def write_ogg(path, samples):
    """Encodes to Ogg Vorbis with a fixed stream serial, so the output is reproducible."""
    if not shutil.which("oggenc"):
        sys.exit("oggenc not found; run inside the devenv shell")
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "music.wav"
        write(wav, samples)
        path.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["oggenc", "--quiet", "--serial", "1", "-q", "2", "-o", str(path), str(wav)], check=True)


def main(root_dir):
    root = Path(root_dir)
    sfx = root / "sfx"
    write(sfx / "select.wav", tone(880, 0.07, "sine", 0.4, release=0.05))
    write(sfx / "pour.wav", seq(sweep(500, 900, 0.12, 0.35), tone(1100, 0.06, "sine", 0.25)))
    write(sfx / "illegal.wav", seq(tone(220, 0.08, "soft", 0.4), tone(196, 0.12, "soft", 0.4)))
    write(sfx / "tube.wav", seq(tone(784, 0.09, "soft", 0.4), tone(1047, 0.09, "soft", 0.4), tone(1319, 0.2, "soft", 0.4, release=0.15)))
    write(sfx / "complete.wav", seq(*[tone(note(n), 0.13, "soft", 0.45) for n in (72, 76, 79, 84)], tone(note(88), 0.5, "soft", 0.45, release=0.4)))
    write(sfx / "helper.wav", seq(tone(1319, 0.06, "sine", 0.35), tone(1760, 0.12, "sine", 0.35)))
    write(sfx / "click.wav", tone(660, 0.04, "sine", 0.3, release=0.03))
    for old in (root / "music").glob("*.wav"):
        old.unlink()
    only = sys.argv[2:] or list(TRACKS)
    for name in only:
        write_ogg(root / "music" / f"{name}.ogg", music(TRACKS[name]))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "assets")
