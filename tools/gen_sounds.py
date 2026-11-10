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


def quake_boom(rng, seconds=5.0):
    """Earthquake boom: the deep thump people hear as the P-wave arrives, rolling into a low rumble."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    # Sub-bass thump: a sine sweeping down from ~55 Hz to ~28 Hz with a fast attack.
    freq = 28 + 27 * np.exp(-t * 3.0)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    thump = np.sin(phase) * np.minimum(t / 0.02, 1) * np.exp(-t * 2.2)
    body = fast_lowpass(rng.standard_normal(n), 90) * np.minimum(t / 0.05, 1) * np.exp(-t * 0.9)
    crackle = bandpass(rng.standard_normal(n), 300, 2500) * np.exp(-t * 6) * 0.15
    x = normalize(thump) * 1.0 + normalize(body) * 0.6 + normalize(crackle) * 0.25
    return normalize(x, 0.95)


def collapse_crash(rng, seconds=3.2):
    """Structure collapse: heavy impact, breaking masonry, then a long tail of tumbling and settling debris."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    impact = fast_lowpass(rng.standard_normal(n), 180) * np.minimum(t / 0.01, 1) * np.exp(-t * 5)
    crash = bandpass(rng.standard_normal(n), 400, 7000) * np.exp(-t * 3.5)
    # Tumbling debris: many short knocks of varying size, thinning out over time.
    debris = np.zeros(n)
    for _ in range(int(rng.integers(70, 110))):
        at = int(min(n - 1, rng.exponential(0.7) * RATE))
        ln = int(rng.uniform(0.01, 0.05) * RATE)
        seg = rng.standard_normal(min(ln, n - at)) * np.exp(-np.arange(min(ln, n - at)) / (0.008 * RATE))
        debris[at:at + len(seg)] += seg * rng.uniform(0.2, 1.0)
    debris = bandpass(debris, 150, 5000)
    x = normalize(impact) * 0.9 + normalize(crash) * 0.55 + normalize(debris) * 0.7
    return normalize(x, 0.95)


def earthquake_cinematic_sounds():
    """Boom and collapse crashes (own seed, so earlier files stay identical)."""
    rng = np.random.default_rng(7331)
    write_ogg("quake_boom", quake_boom(rng))
    for i in range(1, 4):
        write_ogg(f"collapse_crash{i}", collapse_crash(rng))


def earthquake_sounds():
    """Writes only the earthquake files (own seed, so the tornado sounds are untouched)."""
    rng = np.random.default_rng(5150)
    write_ogg("quake_rumble", quake_rumble(rng))
    write_ogg("quake_distant", quake_distant(rng))
    for i in range(1, 4):
        write_ogg(f"quake_crack{i}", quake_crack(rng))
# ---- wildfire: own copies of the original helpers so these sounds never change when shared helpers are retuned

def _wf_fast_lowpass(x, cutoff):
    """Vectorised FFT low-pass for long buffers."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    spec *= 1 / (1 + (freqs / cutoff) ** 4)
    return np.fft.irfft(spec, len(x))


def _wf_bandpass(x, lo, hi):
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / RATE)
    spec *= (1 / (1 + (lo / np.maximum(freqs, 1)) ** 4)) * (1 / (1 + (freqs / hi) ** 4))
    return np.fft.irfft(spec, len(x))


def _wf_smooth_noise(n, rate_hz, rng):
    """Slow random modulation curve in 0..1."""
    pts = max(4, int(n / RATE * rate_hz) + 2)
    ctrl = rng.random(pts)
    xs = np.linspace(0, pts - 1, n)
    return np.interp(xs, np.arange(pts), ctrl)


def _wf_loopable(x, fade=0.5):
    """Crossfade the tail into the head so the buffer loops seamlessly."""
    f = int(fade * RATE)
    head, tail = x[:f].copy(), x[-f:].copy()
    ramp = np.linspace(0, 1, f)
    x = x[:-f].copy()
    x[:f] = head * ramp + tail * (1 - ramp)
    return x


def _wf_normalize(x, peak=0.85):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def fire_crackle(rng, seconds=6.0):
    """Close wildfire layer: soft hiss bed with dense random crackles and snaps."""
    n = int(seconds * RATE)
    bed = _wf_bandpass(rng.standard_normal(n), 300, 3000) * 0.12 * (0.6 + 0.4 * _wf_smooth_noise(n, 3, rng))
    clicks = np.zeros(n)
    count = int(seconds * 55)
    for _ in range(count):
        at = rng.integers(0, n - 2000)
        length = int(rng.integers(40, 900))
        amp = rng.random() ** 2.5
        burst = rng.standard_normal(length) * np.exp(-np.linspace(0, rng.uniform(4, 12), length))
        clicks[at:at + length] += burst * amp
    clicks = _wf_bandpass(clicks, 700, 9000)
    x = bed + clicks * 0.9
    return _wf_normalize(_wf_loopable(x, 0.3), 0.8)


def fire_roar(rng, seconds=8.0):
    """Mid layer: the deep, fluttering roar of a large flame front."""
    n = int(seconds * RATE)
    low = _wf_fast_lowpass(rng.standard_normal(n), 260)
    body = _wf_bandpass(rng.standard_normal(n), 200, 1100) * 0.4
    t = np.arange(n) / RATE
    flutter = 0.75 + 0.25 * np.sin(2 * np.pi * (9 + 3 * _wf_smooth_noise(n, 0.7, rng)) * t)  # flame flicker
    surge = 0.6 + 0.4 * _wf_smooth_noise(n, 0.6, rng)
    x = (low * 1.4 + body * flutter) * surge
    return _wf_normalize(_wf_loopable(x), 0.85)


def fire_distant(rng, seconds=10.0):
    """Far layer: a wide, muffled whoosh-rumble with slow surges."""
    n = int(seconds * RATE)
    brown = np.cumsum(rng.standard_normal(n))
    brown -= _wf_fast_lowpass(brown, 6)
    x = _wf_fast_lowpass(brown, 180) + _wf_fast_lowpass(rng.standard_normal(n), 500) * 0.15
    x *= 0.65 + 0.35 * _wf_smooth_noise(n, 0.4, rng)
    return _wf_normalize(_wf_loopable(x), 0.9)


def fire_pop(rng, seconds=1.2):
    """One-shot: a burning branch snapping (sharp crack + short woody thump + sizzle)."""
    n = int(seconds * RATE)
    t = np.linspace(0, seconds, n)
    crack = _wf_bandpass(rng.standard_normal(n), 1200, 9000) * np.exp(-t * 60)
    thump = _wf_fast_lowpass(rng.standard_normal(n), 300) * np.exp(-t * 18) * 0.8
    sizzle = _wf_bandpass(rng.standard_normal(n), 2500, 8000) * np.exp(-t * 4) * 0.08
    x = crack + thump + sizzle
    return _wf_normalize(x, 0.9)


def fire_whoosh(rng, seconds=2.6):
    """One-shot: a tree torching - a deep rushing whoosh as the crown goes up, with a burst of crackle."""
    n = int(seconds * RATE)
    t = np.linspace(0, seconds, n)
    env = np.minimum(t / 0.18, 1) * np.exp(-np.maximum(t - 0.18, 0) * 1.6)
    rush = _wf_bandpass(rng.standard_normal(n), 60, 900) * env
    hiss = _wf_bandpass(rng.standard_normal(n), 900, 5000) * env * 0.25
    clicks = np.zeros(n)
    for _ in range(90):
        at = int(rng.integers(0, int(n * 0.6)))
        length = int(rng.integers(60, 700))
        clicks[at:at + length] += rng.standard_normal(min(length, n - at)) * np.exp(-np.linspace(0, 8, min(length, n - at))) * rng.random() ** 2
    x = rush * 1.4 + hiss + _wf_bandpass(clicks, 700, 9000) * 0.6 * env
    return _wf_normalize(x, 0.9)


def fire_whirl(rng, seconds=6.0):
    """Loop: a fire whirl - jet-like roar whose brightness pulses as the vortex rotates."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    low = _wf_fast_lowpass(rng.standard_normal(n), 300)
    mid = _wf_bandpass(rng.standard_normal(n), 300, 1600)
    high = _wf_bandpass(rng.standard_normal(n), 1600, 6000)
    rot = 0.5 + 0.5 * np.sin(2 * np.pi * (4.0 + 1.5 * _wf_smooth_noise(n, 0.5, rng)) * t)  # rotation pulse
    x = low * 1.2 + mid * (0.35 + 0.45 * rot) + high * (0.08 + 0.2 * rot)
    x *= 0.75 + 0.25 * _wf_smooth_noise(n, 0.8, rng)
    return _wf_normalize(_wf_loopable(x), 0.85)


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
    earthquake_sounds()
    earthquake_cinematic_sounds()
    write_wildfire()


def write_wildfire():
    # separate seed so adding sounds never changes the existing files
    rng = np.random.default_rng(4321)
    write_ogg("wildfire_crackle", fire_crackle(rng))
    write_ogg("wildfire_roar", fire_roar(rng))
    write_ogg("wildfire_distant", fire_distant(rng))
    for i in range(1, 4):
        write_ogg(f"wildfire_pop{i}", fire_pop(rng))
    write_ogg("wildfire_whoosh", fire_whoosh(rng))
    write_ogg("wildfire_whirl", fire_whirl(rng))


if __name__ == "__main__":
    main()
