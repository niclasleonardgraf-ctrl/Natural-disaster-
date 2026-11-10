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


def howl_tone(n, rng, base, spread, rate):
    """A wind 'whistle': a sine whose pitch wanders slowly, faded in and out by smooth noise."""
    freq = base + spread * (smooth_noise(n, rate, rng) - 0.5) * 2
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    tone = np.sin(phase) + 0.3 * np.sin(phase * 2.01)
    return tone * smooth_noise(n, rate * 1.7, rng) ** 2


def blizzard_howl(rng, seconds=10.0):
    """Close blizzard layer: rushing mid-band noise with ghostly wandering whistles riding on top."""
    n = int(seconds * RATE)
    white = rng.standard_normal(n)
    rush = bandpass(white, 300, 2600) * (0.5 + 0.5 * smooth_noise(n, 0.9, rng))
    hiss = bandpass(rng.standard_normal(n), 3000, 7000) * 0.12 * smooth_noise(n, 4, rng)
    whistles = sum(howl_tone(n, rng, f, f * 0.25, 0.35) for f in (520, 760, 1100)) * 0.09
    # resonant narrow bands give the "howl through gaps" colour
    bands = sum(bandpass(rng.standard_normal(n), f * 0.92, f * 1.08) * smooth_noise(n, 0.6, rng) for f in (420, 640, 900)) * 0.9
    x = rush * 0.9 + hiss + whistles * np.max(np.abs(rush)) + bands
    return normalize(loopable(x, 0.8))


def blizzard_distant(rng, seconds=12.0):
    """Far blizzard layer: a low moaning wind with slow surges."""
    n = int(seconds * RATE)
    low = fast_lowpass(rng.standard_normal(n), 260)
    moan = howl_tone(n, rng, 230, 60, 0.25) * 0.25 * np.std(low) * 3
    x = (low + moan) * (0.55 + 0.45 * smooth_noise(n, 0.4, rng))
    return normalize(loopable(x, 1.0), 0.85)


def sandstorm_howl(rng, seconds=10.0):
    """Close sandstorm layer: dry hissing grit over a roaring rush, surging in gusts, barely tonal."""
    n = int(seconds * RATE)
    roar = fast_lowpass(rng.standard_normal(n), 500)
    grit = bandpass(rng.standard_normal(n), 2200, 8000)
    # sand grains: sparse crackle bursts
    crackle = bandpass(rng.standard_normal(n) * (rng.random(n) < 0.02), 3000, 9000) * 2.0
    surge = 0.5 + 0.5 * smooth_noise(n, 0.8, rng)
    whistle = howl_tone(n, rng, 340, 70, 0.3) * 0.05
    x = (roar * 1.2 + grit * 0.45 * smooth_noise(n, 2.5, rng) + crackle * 0.3) * surge
    x = x + whistle * np.max(np.abs(x))
    return normalize(loopable(x, 0.8))


def sandstorm_distant(rng, seconds=12.0):
    """Far sandstorm layer: a deep dull roar, like a wall of wind rolling closer."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= fast_lowpass(brown, 6)
    x = fast_lowpass(brown, 220) + fast_lowpass(rng.standard_normal(n), 600) * 0.15
    x *= 0.65 + 0.35 * smooth_noise(n, 0.5, rng)
    return normalize(loopable(x, 1.0), 0.9)


def main():
    rng = np.random.default_rng(1234)
    write_ogg("tornado_roar", tornado_roar(rng))
    write_ogg("tornado_distant", tornado_distant(rng))
    for i in range(1, 4):
        write_ogg(f"rumble{i}", rumble(rng))
        write_ogg(f"wind_gust{i}", gust(rng))
    storm_rng = np.random.default_rng(5678)  # separate stream: older sounds stay byte-identical
    write_ogg("blizzard_howl", blizzard_howl(storm_rng))
    write_ogg("blizzard_distant", blizzard_distant(storm_rng))
    write_ogg("sandstorm_howl", sandstorm_howl(storm_rng))
    write_ogg("sandstorm_distant", sandstorm_distant(storm_rng))


if __name__ == "__main__":
    main()
