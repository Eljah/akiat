#!/usr/bin/env python3
"""Claims one approved Tatar story from Akiat Server and submits generated MP3.

Only for voices with applicable rights/licences. Runs on a separate CPU/GPU machine.
Requires pip install requests numpy torch and a working ffmpeg executable.
The code reuses existing Silero rendering functions from the project root.
"""
from __future__ import annotations
import os
import sys
import time
import tempfile
import subprocess
from pathlib import Path

import requests
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from silero_render import synthesize, split_sentences, save_wave, load_lexicon

BASE = os.environ.get("AKIAT_ENDPOINT", "http://127.0.0.1:8080").rstrip("/")
USER = os.environ.get("AKIAT_ADMIN_USER", "admin")
PASS = os.environ.get("AKIAT_ADMIN_PASSWORD", "")
WORKER = os.environ.get("AKIAT_WORKER_ID", "silero-01")
MODEL = os.environ.get("AKIAT_SILERO_MODEL", "v5_cis_ext")
VOICE = os.environ.get("AKIAT_SILERO_VOICE", "tat_bulat")
STRESS_LEXICON = os.environ.get("AKIAT_STRESS_LEXICON", "")
if not PASS:
    raise SystemExit("Set AKIAT_ADMIN_PASSWORD")

session = requests.Session()
session.auth = (USER, PASS)

def fetch_next():
    resp = session.post(f"{BASE}/api/tts/jobs/claim", params={"worker": WORKER}, timeout=30)
    resp.raise_for_status()
    return resp.json() if resp.content else None

def run_forever():
    import torch
    torch.set_num_threads(4)
    model, _ = torch.hub.load(
        "snakers4/silero-models", "silero_tts", language="ru", speaker=MODEL,
        trust_repo=True,
    )
    model.eval().cpu()
    dictionary = load_lexicon(Path(STRESS_LEXICON)) if STRESS_LEXICON else {}
    while True:
        job = fetch_next()
        if not job:
            print("No jobs; sleeping", flush=True)
            time.sleep(60)
            continue
        print("Processing", job["id"], job["title"], flush=True)
        try:
            fragments = split_sentences(job["text"], max_chars=250)
            if not fragments:
                raise ValueError("Empty Tatar text")
            audio = synthesize(model, fragments, VOICE, 48000, 0.28, dictionary)
            duration = round(len(audio)/48000)
            with tempfile.TemporaryDirectory(prefix="akiat_") as tmp:
                wav = Path(tmp)/"voice.wav"
                mp3 = Path(tmp)/"voice.mp3"
                save_wave(wav, audio, 48000)
                subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", str(wav), "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
                    "-c:a", "libmp3lame", "-b:a", "192k", str(mp3)], check=True)
                with mp3.open("rb") as fh:
                    response = session.post(
                        f"{BASE}/api/tts/jobs/{job['id']}/complete",
                        data={"durationSeconds": duration},
                        files={"file": ("narration.mp3",fh,"audio/mpeg")},timeout=180)
                    response.raise_for_status()
            print("Ready for human audio review", job["id"], flush=True)
        except Exception as exc:
            print("TTS failed:", repr(exc), flush=True)
            try:
                session.post(f"{BASE}/api/tts/jobs/{job['id']}/fail",
                    json={"error":repr(exc)}, timeout=30).raise_for_status()
            except Exception as report_error:
                print("Could not report TTS error:", report_error, flush=True)

if __name__=="__main__":
    run_forever()
