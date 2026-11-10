#!/usr/bin/env python3
"""Synthesizes the mod's sounds from filtered noise (original work, released as CC0 with the mod).

Writes mono OGG Vorbis files (mono is required for positional audio in Minecraft).
Requires numpy and an ffmpeg binary with libvorbis.
Usage: python3 tools/gen_sounds.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "realisticdisasters", "sounds")


def fast_lowpass(x, cutoff):
    """Vectorised FFT low-pass for long buffers."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    spec *= 1 / (1 + (freqs / cutoff) ** 4)
    return np.fft.irfft(spec, len(x))


def bandpass(x, lo, hi):
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    spec *= (1 / (1 + (lo / np.maximum(freqs, 1)) ** 4)) * (1 / (1 + (freqs / hi) ** 4))
    return np.fft.irfft(spec, len(x))


def smooth_noise(n, rate_hz, rng):
    """Slow random modulation curve in 0..1."""
    pts = max(4, int(n / RATE * rate_hz) + 2)
    ctrl = rng.random(pts)
    xs = np.linspace(0, pts - 1, n)
    return np.interp(xs, np.arange(pts), ctrl)


def loopable(x, fade=0.5):
    """Crossfade the tail into the head so the buffer loops seamlessly."""
    f = int(fade * RATE)
    head, tail = x[:f].copy(), x[-f:].copy()
    ramp = np.linspace(0, 1, f)
    x = x[:-f].copy()
    x[:f] = head * ramp + tail * (1 - ramp)
    return x


def normalize(x, peak=0.85):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def write_ogg(name, x):
    os.makedirs(OUT, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        path = tmp.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    dst = os.path.join(OUT, name + ".ogg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "4", dst], check=True)
    os.remove(path)


def tornado_roar(rng, seconds=8.0):
    """Close layer: dense rushing roar with slow surges and a gritty mid band."""
    n = int(seconds * RATE)
    white = rng.standard_normal(n)
    low = fast_lowpass(white, 380)
    mid = bandpass(white, 500, 1800) * 0.35
    grit = bandpass(rng.standard_normal(n), 2000, 5000) * 0.08 * smooth_noise(n, 6, rng)
    surge = 0.55 + 0.45 * smooth_noise(n, 0.8, rng)
    x = (low * 1.6 + mid + grit) * surge
    return normalize(loopable(x))


def tornado_distant(rng, seconds=10.0):
    """Far layer: deep, freight-train-like rumble."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= fast_lowpass(brown, 8)  # remove DC drift
    x = fast_lowpass(brown, 140)
    x *= 0.7 + 0.3 * smooth_noise(n, 0.5, rng)
    return normalize(loopable(x), 0.9)


def rumble(rng, seconds=4.5):
    """Distant thunder-ish rumble one-shot with a soft attack and long tail."""
    n = int(seconds * RATE)
    x = fast_lowpass(rng.standard_normal(n), 120) + fast_lowpass(rng.standard_normal(n), 400) * 0.2
    t = np.linspace(0, 1, n)
    env = np.minimum(t / 0.12, 1) * np.exp(-t * 3.2) * (0.6 + 0.4 * smooth_noise(n, 4, rng))
    return normalize(x * env)


def gust(rng, seconds=3.0):
    """Wind gust: band-passed noise that swells and fades."""
    n = int(seconds * RATE)
    x = bandpass(rng.standard_normal(n), 250, 2400)
    t = np.linspace(0, 1, n)
    env = np.sin(np.pi * t) ** 2 * (0.7 + 0.3 * smooth_noise(n, 3, rng))
    return normalize(x * env, 0.7)


def crackle(n, rng, rate, decay=None):
    """Sparse clicks (burning / falling debris), optionally thinning out over time."""
    x = np.zeros(n)
    t = np.linspace(0, 1, n)
    p = rate / RATE * (np.exp(-t * decay) if decay else np.ones(n))
    hits = rng.random(n) < p
    x[hits] = rng.standard_normal(hits.sum())
    return bandpass(np.convolve(x, np.exp(-np.arange(200) / 30.0), mode="same"), 800, 6000)


def meteor_whoosh(rng, seconds=3.4):
    """Close layer: a rushing, rising roar that sweeps up in pitch as the fireball closes in, with fire crackle."""
    n = int(seconds * RATE)
    t = np.linspace(0, 1, n)
    white = rng.standard_normal(n)
    low = bandpass(white, 120, 700)
    high = bandpass(rng.standard_normal(n), 900, 4200)
    sweep = t ** 1.6                      # crossfade low rush -> bright hiss
    x = low * (1 - sweep) * 1.4 + high * sweep * 0.8
    x += fast_lowpass(rng.standard_normal(n), 90) * 1.2 * t   # rumble builds underneath
    x += crackle(n, rng, 60) * 0.25
    env = np.minimum(t / 0.5, 1) ** 2 * (0.35 + 0.65 * t ** 1.5)
    return normalize(x * env)


def meteor_whoosh_distant(rng, seconds=3.6):
    """Far layer: soft, airy rush that swells and fades (no highs, they don't carry)."""
    n = int(seconds * RATE)
    t = np.linspace(0, 1, n)
    x = bandpass(rng.standard_normal(n), 80, 650) + fast_lowpass(rng.standard_normal(n), 120) * 0.6
    env = np.sin(np.pi * np.minimum(t * 1.15, 1)) ** 1.5 * (0.75 + 0.25 * smooth_noise(n, 2, rng))
    return normalize(x * env, 0.75)


def meteor_impact(rng, seconds=4.0):
    """Close impact: hard crack, a deep falling boom, then a tail of rumble and pattering debris."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    crack = rng.standard_normal(n) * np.exp(-t / 0.018)
    freq = 34 + 46 * np.exp(-t / 0.35)    # 80 Hz falling to ~34 Hz
    boom = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t / 0.9)
    body = fast_lowpass(rng.standard_normal(n), 220) * np.exp(-t / 0.6) * 3
    tail = fast_lowpass(rng.standard_normal(n), 120) * np.exp(-t / 1.6) * (0.6 + 0.4 * smooth_noise(n, 3, rng)) * 2
    debris = crackle(n, rng, 120, decay=2.5) * np.minimum(t / 0.3, 1) * 0.5
    x = crack * 0.9 + boom * 1.2 + body + tail + debris
    return normalize(x, 0.95)


def meteor_impact_distant(rng, seconds=5.5):
    """Far impact: muffled thump with a long rolling echo, like distant artillery."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    freq = 30 + 25 * np.exp(-t / 0.4)
    boom = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-t / 1.0)
    roll = fast_lowpass(rng.standard_normal(n), 140) * np.exp(-t / 1.8) * (0.5 + 0.5 * smooth_noise(n, 2.5, rng)) * 3
    env = np.minimum(t / 0.03, 1)
    return normalize((boom + roll) * env, 0.9)


def meteor(rng=None):
    rng = rng or np.random.default_rng(4321)
    for i in range(1, 3):
        write_ogg(f"meteor_whoosh{i}", meteor_whoosh(rng))
        write_ogg(f"meteor_whoosh_distant{i}", meteor_whoosh_distant(rng))
        write_ogg(f"meteor_impact{i}", meteor_impact(rng))
        write_ogg(f"meteor_impact_distant{i}", meteor_impact_distant(rng))


def main():
    rng = np.random.default_rng(1234)
    write_ogg("tornado_roar", tornado_roar(rng))
    write_ogg("tornado_distant", tornado_distant(rng))
    for i in range(1, 4):
        write_ogg(f"rumble{i}", rumble(rng))
        write_ogg(f"wind_gust{i}", gust(rng))
    meteor()


if __name__ == "__main__":
    main()
