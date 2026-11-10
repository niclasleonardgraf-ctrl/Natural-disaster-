#!/usr/bin/env python3
"""Synthesizes the mod's sounds (original work, released as CC0 with the mod).

Modelled on real tornado recordings and eyewitness descriptions:
- far:   a continuous low "freight train" rumble with a slow, rhythmic chug and deep resonances
- mid:   a rushing roar, broadband but heavy in the low-mids, with turbulent surges
- core:  overwhelming, dense, full-band roar with rapid turbulence and debris crackle
- howl:  wind whistling past obstacles: narrow resonant bands that sweep in pitch
- impacts: heavy debris thuds, wood snapping (sharp crack + splinters + creak), small debris rattles
- thunder: rolling rumbles with an initial crack

Writes mono OGG Vorbis (mono is required for positional audio). Needs numpy and ffmpeg (libvorbis).
Usage: python3 tools/gen_sounds.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "realisticdisasters", "sounds")


# ----------------------------------------------------------------------------- building blocks

def spectral(x, gain_fn):
    """Apply a frequency-domain gain curve gain_fn(freqs)."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    return np.fft.irfft(spec * gain_fn(freqs), len(x))


def lowpass(x, fc, order=4):
    return spectral(x, lambda f: 1 / (1 + (f / fc) ** order))


def highpass(x, fc, order=4):
    return spectral(x, lambda f: 1 / (1 + (fc / np.maximum(f, 1)) ** order))


def band(x, lo, hi, order=4):
    return highpass(lowpass(x, hi, order), lo, order)


def resonance(x, f0, width):
    """Narrow resonant peak (gaussian in frequency)."""
    return spectral(x, lambda f: np.exp(-((f - f0) / width) ** 2))


def brown(n, rng):
    b = np.cumsum(rng.standard_normal(n))
    return highpass(b, 8, 2)


def curve(n, rate_hz, rng, lo=0.0, hi=1.0):
    """Smooth random modulation curve between lo and hi."""
    pts = max(4, int(n / RATE * rate_hz) + 2)
    ctrl = rng.random(pts)
    xs = np.linspace(0, pts - 1, n)
    c = np.interp(xs, np.arange(pts), ctrl)
    # smooth the linear interpolation
    k = max(1, int(RATE / rate_hz / 4))
    c = np.convolve(c, np.ones(k) / k, mode="same")
    return lo + (hi - lo) * (c - c.min()) / (np.ptp(c) + 1e-9)


def sweep_band(x, centers, q):
    """Band-pass with a time-varying centre frequency (overlap-add of short frames)."""
    frame, hop = 4096, 1024
    win = np.hanning(frame)
    out = np.zeros(len(x) + frame)
    norm = np.zeros(len(x) + frame)
    freqs = np.fft.rfftfreq(frame, 1 / RATE)
    for start in range(0, len(x) - frame, hop):
        seg = x[start:start + frame] * win
        f0 = centers[start + frame // 2]
        g = np.exp(-((freqs - f0) / (f0 / q)) ** 2) + 0.5 * np.exp(-((freqs - 2 * f0) / (2 * f0 / q)) ** 2)
        out[start:start + frame] += np.fft.irfft(np.fft.rfft(seg) * g, frame) * win
        norm[start:start + frame] += win ** 2
    return out[:len(x)] / np.maximum(norm[:len(x)], 1e-6)


def loopable(x, fade=0.6):
    f = int(fade * RATE)
    ramp = np.linspace(0, 1, f)
    head, tail = x[:f].copy(), x[-f:].copy()
    x = x[:-f].copy()
    x[:f] = head * ramp + tail * (1 - ramp)
    return x


def normalize(x, peak=0.9):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def env_decay(n, attack, decay):
    t = np.arange(n) / RATE
    return np.minimum(t / max(attack, 1e-4), 1) * np.exp(-t / decay)


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
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
    os.remove(path)


# ----------------------------------------------------------------------------- tornado layers

def tornado_distant(rng, seconds=12.0):
    """Freight train: deep continuous rumble, resonant low end, slow rhythmic chug."""
    n = int(seconds * RATE)
    # audible on ordinary speakers: the energy sits around 50-300 Hz, not only in the sub-bass
    base = band(brown(n, rng), 35, 320)
    res = sum(resonance(rng.standard_normal(n), f, w) * g for f, w, g in
              ((55, 8, 0.7), (82, 12, 1.0), (120, 18, 0.9), (170, 25, 0.6), (240, 35, 0.35)))
    chug = 1 + 0.18 * np.sin(2 * np.pi * np.cumsum(curve(n, 0.3, rng, 5.5, 8.0)) / RATE)   # wheels-on-rails rhythm
    surge = curve(n, 0.4, rng, 0.6, 1.0)
    x = (normalize(base) * 0.8 + normalize(res) * 0.6) * chug * surge
    return normalize(loopable(x), 0.95)


def tornado_roar(rng, seconds=10.0):
    """Mid distance: rushing roar, heavy low-mids, turbulent surges, a little hiss."""
    n = int(seconds * RATE)
    w = rng.standard_normal(n)
    low = lowpass(brown(n, rng), 300) * 1.2
    body = band(w, 120, 900) * 0.9
    hiss = band(rng.standard_normal(n), 1500, 6000) * 0.12
    turb = curve(n, 3.0, rng, 0.65, 1.0)
    surge = curve(n, 0.35, rng, 0.55, 1.0)
    x = (normalize(low) + normalize(body) * 0.8 + normalize(hiss) * 0.25 * curve(n, 1.5, rng)) * turb * surge
    return normalize(loopable(x), 0.95)


def tornado_core(rng, seconds=8.0):
    """Inside / right next to the vortex: overwhelming full-band roar, fast turbulence, debris crackle."""
    n = int(seconds * RATE)
    w = rng.standard_normal(n)
    full = lowpass(w, 7000) * 0.6 + lowpass(brown(n, rng), 250) * 0.04
    turb = curve(n, 14.0, rng, 0.55, 1.0) * curve(n, 1.2, rng, 0.75, 1.0)
    # debris crackle: random short clicks (pieces hitting each other)
    crack = np.zeros(n)
    for _ in range(int(seconds * 45)):
        i = rng.integers(0, n - 2000)
        L = rng.integers(200, 1800)
        crack[i:i + L] += rng.standard_normal(L) * env_decay(L, 0.0003, L / RATE / 5) * rng.uniform(0.3, 1.0)
    crack = band(crack, 700, 6000)
    x = normalize(full) * turb + normalize(crack) * 0.35
    x = np.tanh(normalize(x) * 2.4)   # compressed: dense and overwhelming, not peaky
    return normalize(loopable(x, 0.4), 0.95)


def wind_howl(rng, seconds=10.0):
    """Wind whistling around obstacles: resonant bands sweeping in pitch over a rushing bed."""
    n = int(seconds * RATE)
    centers = curve(n, 0.5, rng, 320, 820)
    whistle = sweep_band(rng.standard_normal(n), centers, q=9)
    centers2 = curve(n, 0.7, rng, 600, 1400)
    whistle2 = sweep_band(rng.standard_normal(n), centers2, q=12)
    bed = band(rng.standard_normal(n), 150, 2500)
    x = normalize(whistle) * 0.8 + normalize(whistle2) * 0.35 + normalize(bed) * 0.45
    x *= curve(n, 0.6, rng, 0.5, 1.0)
    return normalize(loopable(x), 0.9)


# ----------------------------------------------------------------------------- impacts

def debris_thud(rng, seconds=0.9):
    """Heavy object hitting the ground: low body thump plus a short gritty noise burst."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    f = rng.uniform(70, 105)
    body = np.sin(2 * np.pi * f * t * (1 - 0.25 * t)) * env_decay(n, 0.002, 0.12)
    knock = band(rng.standard_normal(n), 180, 700) * env_decay(n, 0.0005, 0.06)   # the audible "thunk"
    body = normalize(body) * 0.7 + normalize(knock) * 0.8
    grit = lowpass(rng.standard_normal(n), 1500) * env_decay(n, 0.0005, 0.05)
    rattle = band(rng.standard_normal(n), 800, 4000) * env_decay(n, 0.01, 0.15) * 0.3
    return normalize(body * 1.0 + normalize(grit) * 0.6 + normalize(rattle) * 0.2, 0.95)


def wood_snap(rng, seconds=1.6):
    """Trunk breaking: sharp crack, a burst of splinters, then a groaning creak."""
    n = int(seconds * RATE)
    crack = highpass(rng.standard_normal(n), 400) * env_decay(n, 0.0002, 0.012)
    splinters = np.zeros(n)
    for _ in range(int(rng.integers(25, 45))):
        i = int(rng.uniform(0.005, 0.5) * RATE)
        L = int(rng.integers(150, 900))
        if i + L < n:
            splinters[i:i + L] += rng.standard_normal(L) * env_decay(L, 0.0002, L / RATE / 4) * rng.uniform(0.2, 1.0) * np.exp(-i / RATE / 0.25)
    splinters = band(splinters, 900, 7000)
    t = np.arange(n) / RATE
    creak_f = 180 + 60 * np.sin(2 * np.pi * 1.3 * t)
    creak = np.sin(2 * np.pi * np.cumsum(creak_f) / RATE) * np.sign(np.sin(2 * np.pi * 23 * t)) * 0.5
    creak = band(creak, 120, 1200) * env_decay(n, 0.15, 0.4) * 0.35
    body = lowpass(rng.standard_normal(n), 300) * env_decay(n, 0.001, 0.08)
    return normalize(normalize(crack) + normalize(splinters) * 0.6 + normalize(creak) * 0.3 + normalize(body) * 0.5, 0.95)


def debris_rattle(rng, seconds=1.8):
    """Small debris pelting things nearby: a swarm of tiny ticks and clatters."""
    n = int(seconds * RATE)
    x = np.zeros(n)
    env = np.sin(np.pi * np.arange(n) / n) ** 0.7
    for _ in range(int(rng.integers(60, 110))):
        i = int(rng.integers(0, n - 1200))
        L = int(rng.integers(80, 700))
        x[i:i + L] += rng.standard_normal(L) * env_decay(L, 0.0001, L / RATE / 6) * rng.uniform(0.2, 1.0) * env[i]
    return normalize(np.tanh(normalize(band(x, 600, 7000)) * 2.5), 0.8)


def thunder(rng, seconds=6.0):
    """Rolling thunder: a crack, then several overlapping rumbles of decreasing loudness."""
    n = int(seconds * RATE)
    crack = band(rng.standard_normal(n), 300, 5000) * env_decay(n, 0.001, 0.06) * 0.6
    roll = np.zeros(n)
    for k in range(int(rng.integers(3, 6))):
        start = int(rng.uniform(0.02, 1.8) * RATE)
        L = n - start
        seg = band(rng.standard_normal(L), 40, rng.uniform(250, 450)) * env_decay(L, rng.uniform(0.05, 0.3), rng.uniform(0.6, 1.6))
        roll[start:] += seg * (0.9 ** k)
    x = normalize(crack) * 0.4 + normalize(roll)
    return normalize(x, 0.95)


def gust(rng, seconds=3.0):
    """A single gust: band-limited rush that swells and fades."""
    n = int(seconds * RATE)
    x = band(rng.standard_normal(n), 200, 2400)
    t = np.linspace(0, 1, n)
    env = np.sin(np.pi * t) ** 2 * curve(n, 3, rng, 0.7, 1.0)
    return normalize(x * env, 0.75)


def fire_crackle(rng, seconds=6.0):
    """Close wildfire layer: soft hiss bed with dense random crackles and snaps."""
    n = int(seconds * RATE)
    bed = bandpass(rng.standard_normal(n), 300, 3000) * 0.12 * (0.6 + 0.4 * smooth_noise(n, 3, rng))
    clicks = np.zeros(n)
    count = int(seconds * 55)
    for _ in range(count):
        at = rng.integers(0, n - 2000)
        length = int(rng.integers(40, 900))
        amp = rng.random() ** 2.5
        burst = rng.standard_normal(length) * np.exp(-np.linspace(0, rng.uniform(4, 12), length))
        clicks[at:at + length] += burst * amp
    clicks = bandpass(clicks, 700, 9000)
    x = bed + clicks * 0.9
    return normalize(loopable(x, 0.3), 0.8)


def fire_roar(rng, seconds=8.0):
    """Mid layer: the deep, fluttering roar of a large flame front."""
    n = int(seconds * RATE)
    low = fast_lowpass(rng.standard_normal(n), 260)
    body = bandpass(rng.standard_normal(n), 200, 1100) * 0.4
    t = np.arange(n) / RATE
    flutter = 0.75 + 0.25 * np.sin(2 * np.pi * (9 + 3 * smooth_noise(n, 0.7, rng)) * t)  # flame flicker
    surge = 0.6 + 0.4 * smooth_noise(n, 0.6, rng)
    x = (low * 1.4 + body * flutter) * surge
    return normalize(loopable(x), 0.85)


def fire_distant(rng, seconds=10.0):
    """Far layer: a wide, muffled whoosh-rumble with slow surges."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= fast_lowpass(brown, 6)
    x = fast_lowpass(brown, 180) + fast_lowpass(rng.standard_normal(n), 500) * 0.15
    x *= 0.65 + 0.35 * smooth_noise(n, 0.4, rng)
    return normalize(loopable(x), 0.9)


def fire_pop(rng, seconds=1.2):
    """One-shot: a burning branch snapping (sharp crack + short woody thump + sizzle)."""
    n = int(seconds * RATE)
    t = np.linspace(0, seconds, n)
    crack = bandpass(rng.standard_normal(n), 1200, 9000) * np.exp(-t * 60)
    thump = fast_lowpass(rng.standard_normal(n), 300) * np.exp(-t * 18) * 0.8
    sizzle = bandpass(rng.standard_normal(n), 2500, 8000) * np.exp(-t * 4) * 0.08
    x = crack + thump + sizzle
    return normalize(x, 0.9)


def main():
    rng = np.random.default_rng(1234)
    write_ogg("tornado_distant", tornado_distant(rng))
    write_ogg("tornado_roar", tornado_roar(rng))
    write_ogg("tornado_core", tornado_core(rng))
    write_ogg("wind_howl", wind_howl(rng))
    for i in range(1, 4):
        write_ogg(f"rumble{i}", thunder(rng))
        write_ogg(f"wind_gust{i}", gust(rng))
        write_ogg(f"debris_thud{i}", debris_thud(rng))
        write_ogg(f"wood_snap{i}", wood_snap(rng))
        write_ogg(f"debris_rattle{i}", debris_rattle(rng))
    write_wildfire()


def write_wildfire():
    # separate seed so adding sounds never changes the existing files
    rng = np.random.default_rng(4321)
    write_ogg("wildfire_crackle", fire_crackle(rng))
    write_ogg("wildfire_roar", fire_roar(rng))
    write_ogg("wildfire_distant", fire_distant(rng))
    for i in range(1, 4):
        write_ogg(f"wildfire_pop{i}", fire_pop(rng))


if __name__ == "__main__":
    main()
