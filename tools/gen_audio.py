#!/usr/bin/env python3
"""Synthesizes Sortopia's placeholder sound effects and music loops (SPEC §13).

Deterministic: running it again produces identical files. Usage:
    python3 tools/gen_audio.py assets
"""
import sys
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


def music(root, tempo, pattern_seed):
    rng = np.random.default_rng(pattern_seed)
    beat = 60.0 / tempo
    bars = 8
    pent = [0, 2, 4, 7, 9, 12, 14, 16]
    chords = [[0, 4, 7], [-3, 0, 4], [-7, -3, 0], [-5, -1, 2]]
    total = int(bars * 4 * beat * RATE)
    out = np.zeros(total)
    for bar in range(bars):
        ch = chords[bar % 4]
        start = int(bar * 4 * beat * RATE)
        for c in ch:
            pad = tone(note(root - 12 + c), 4 * beat, "sine", 0.09, attack=0.6, release=1.2)
            out[start:start + len(pad)] += pad[: total - start]
        for step in range(8):
            if rng.random() < 0.7:
                n = root + ch[0] + pent[rng.integers(0, len(pent))]
                s = start + int(step * beat / 2 * RATE)
                pl = tone(note(n), beat * 0.9, "soft", 0.13, attack=0.01, release=0.4)
                end = min(total, s + len(pl))
                out[s:end] += pl[: end - s]
    # Short crossfade so the loop is seamless.
    fade = int(0.05 * RATE)
    out[:fade] *= np.linspace(0, 1, fade)
    out[-fade:] *= np.linspace(1, 0, fade)
    return out


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
    settings = [(60, 84, 1), (62, 90, 2), (64, 80, 3), (65, 76, 4), (57, 72, 5), (59, 70, 6), (67, 88, 7), (55, 66, 8)]
    for i, (r, t, s) in enumerate(settings, start=1):
        write(root / "music" / f"world{i}.wav", music(r, t, s))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "assets")
