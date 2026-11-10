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


def quake_rumble(rng, seconds=9.0):
    """Earthquake close layer: deep grinding rumble with rattling debris and irregular jolts."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= fast_lowpass(brown, 6)
    sub = fast_lowpass(brown, 90)
    grind = bandpass(rng.standard_normal(n), 120, 600) * 0.35 * smooth_noise(n, 3, rng)
    rattle = bandpass(rng.standard_normal(n), 900, 3500) * 0.06 * (smooth_noise(n, 9, rng) ** 3)
    jolts = 0.6 + 0.4 * smooth_noise(n, 1.6, rng) ** 2
    x = (normalize(sub) * 1.0 + normalize(grind) * 0.45 + normalize(rattle) * 0.18) * jolts
    return normalize(loopable(x))


def quake_distant(rng, seconds=10.0):
    """Earthquake far layer: very low, slowly swelling rumble felt more than heard."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= fast_lowpass(brown, 5)
    x = fast_lowpass(brown, 70)
    x *= 0.55 + 0.45 * smooth_noise(n, 0.4, rng)
    return normalize(loopable(x), 0.9)


def quake_crack(rng, seconds=1.6):
    """Rock cracking one-shot: a sharp broadband snap, a few splintering ticks and a short low thud."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    snap = bandpass(rng.standard_normal(n), 300, 6000) * np.exp(-t * 28)
    ticks = np.zeros(n)
    for _ in range(rng.integers(4, 9)):
        at = int(rng.uniform(0.02, 0.5) * RATE)
        ln = int(0.03 * RATE)
        ticks[at:at + ln] += rng.standard_normal(min(ln, n - at)) * np.exp(-np.arange(min(ln, n - at)) / (0.006 * RATE)) * rng.uniform(0.3, 0.8)
    ticks = bandpass(ticks, 800, 7000)
    thud = fast_lowpass(rng.standard_normal(n), 150) * np.exp(-t * 6) * np.minimum(t / 0.01, 1)
    x = normalize(snap) + normalize(ticks) * 0.5 + normalize(thud) * 0.8
    return normalize(x, 0.9)


def earthquake_sounds():
    """Writes only the earthquake files (own seed, so the tornado sounds are untouched)."""
    rng = np.random.default_rng(5150)
    write_ogg("quake_rumble", quake_rumble(rng))
    write_ogg("quake_distant", quake_distant(rng))
    for i in range(1, 4):
        write_ogg(f"quake_crack{i}", quake_crack(rng))


def main():
    rng = np.random.default_rng(1234)
    write_ogg("tornado_roar", tornado_roar(rng))
    write_ogg("tornado_distant", tornado_distant(rng))
    for i in range(1, 4):
        write_ogg(f"rumble{i}", rumble(rng))
        write_ogg(f"wind_gust{i}", gust(rng))
    earthquake_sounds()


if __name__ == "__main__":
    main()
