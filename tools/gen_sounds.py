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


def tsunami_roar(rng, seconds=8.0):
    """Close layer: a breaking wall of water - deep churning rush, dense white-water hiss, irregular surges."""
    n = int(seconds * RATE)
    low = fast_lowpass(rng.standard_normal(n), 220) * 1.8
    body = bandpass(rng.standard_normal(n), 300, 1400) * 0.55
    hiss = bandpass(rng.standard_normal(n), 1800, 7000) * 0.22 * (0.5 + 0.5 * smooth_noise(n, 9, rng))
    surge = 0.5 + 0.5 * smooth_noise(n, 1.4, rng)
    x = (low + body + hiss) * surge
    return normalize(loopable(x))


def tsunami_surf(rng, seconds=12.0):
    """Distant layer: heavy surf - slow swells that break into a hiss, over a low ocean rumble."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    rumble = fast_lowpass(rng.standard_normal(n), 160)
    wash = bandpass(rng.standard_normal(n), 400, 3500)
    # three swells per loop with slightly irregular shapes (period divides the loop so it stays seamless)
    phase = (t / seconds * 3.0) % 1.0
    swell = np.clip(np.sin(np.pi * phase) ** 3 * (0.75 + 0.25 * smooth_noise(n, 2, rng)), 0, 1)
    x = rumble * (0.6 + 0.4 * swell) + wash * swell * 0.45
    return normalize(loopable(x, 0.8), 0.85)


def tsunami_crash(rng, seconds=3.5):
    """One-shot: a wave slamming down - low thump, sharp broadband attack, long seething tail."""
    n = int(seconds * RATE)
    t = np.linspace(0, seconds, n)
    thump = fast_lowpass(rng.standard_normal(n), 90) * np.exp(-t * 4) * 2.0
    crash = bandpass(rng.standard_normal(n), 200, 6000)
    env = np.minimum(t / 0.04, 1) * np.exp(-t * 1.3) * (0.7 + 0.3 * smooth_noise(n, 12, rng))
    seethe = bandpass(rng.standard_normal(n), 2500, 8000) * np.minimum(t / 0.3, 1) * np.exp(-t * 0.9) * 0.25
    return normalize(thump + crash * env + seethe)


def tsunami_main():
    rng = np.random.default_rng(5678)
    write_ogg("tsunami_roar", tsunami_roar(rng))
    write_ogg("tsunami_surf", tsunami_surf(rng))
    for i in range(1, 4):
        write_ogg(f"tsunami_crash{i}", tsunami_crash(rng))


def main():
    rng = np.random.default_rng(1234)
    write_ogg("tornado_roar", tornado_roar(rng))
    write_ogg("tornado_distant", tornado_distant(rng))
    for i in range(1, 4):
        write_ogg(f"rumble{i}", rumble(rng))
        write_ogg(f"wind_gust{i}", gust(rng))
    tsunami_main()


if __name__ == "__main__":
    main()
