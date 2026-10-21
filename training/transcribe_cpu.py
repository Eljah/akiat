"""Resumable local CPU transcription of root MP3 files; outputs need review."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("HF_HOME", str(ROOT / ".cache" / "huggingface"))
os.environ["CUDA_VISIBLE_DEVICES"] = ""


def save_json(path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def save_stage(stage, detail):
    save_json(ROOT / "asr" / "cpu_status.json",
              dict(stage=stage, detail=detail, updated=time.strftime("%Y-%m-%dT%H:%M:%S%z")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threads", type=int, choices=(1, 2), default=2)
    parser.add_argument("--smoke", action="store_true", help="Transcribe 12 seconds of the shortest file.")
    args = parser.parse_args()
    from windows_budget import apply_budget
    apply_budget()
    for variable in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
        os.environ[variable] = str(args.threads)
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import imageio_ffmpeg
    import numpy as np
    import torch
    from transformers import AutoFeatureExtractor, AutoTokenizer, pipeline

    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    output = ROOT / "asr" / ("cpu_smoke" if args.smoke else "cpu")
    output.mkdir(parents=True, exist_ok=True)
    files = sorted(ROOT.glob("*.mp3"), key=lambda p: p.stat().st_size)
    if not files:
        raise RuntimeError("No MP3 files in repository root")
    model_id = "yasalma/whisper-finetuned-tt-asr"
    save_stage("loading_model", "Downloading/loading Whisper weights; audio recognition has not started")
    print(f"Loading {model_id}; device=cpu; float32; threads={args.threads}", flush=True)
    recognizer = pipeline(
        "automatic-speech-recognition", model=model_id,
        tokenizer=AutoTokenizer.from_pretrained("openai/whisper-small", extra_special_tokens={}),
        feature_extractor=AutoFeatureExtractor.from_pretrained("openai/whisper-small"),
        device=-1, dtype=torch.float32, batch_size=1,
        chunk_length_s=25, stride_length_s=(4, 4),
    )
    eos = recognizer.model.generation_config.eos_token_id
    if isinstance(eos, (list, tuple)):
        if len(eos) != 1:
            raise ValueError(f"Unexpected EOS: {eos}")
        eos = eos[0]
    recognizer.model.generation_config.eos_token_id = int(eos)
    # Pipeline keeps its own copy and passes it to generate() instead of the model's.
    recognizer.generation_config.eos_token_id = int(eos)
    assert int(eos) == recognizer.tokenizer.eos_token_id
    save_stage("smoke_test" if args.smoke else "transcribing", "Model loaded; processing MP3 on CPU")
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    for source in files[:1] if args.smoke else files:
        hasher = hashlib.sha256()
        with source.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(block)
        digest = hasher.hexdigest()
        folder = output / digest[:16]
        folder.mkdir(exist_ok=True)
        part_seconds = 12 if args.smoke else 180
        index = 0
        while True:
            offset = index * part_seconds
            target = folder / f"part_{index:04d}.json"
            if target.exists():
                data = json.loads(target.read_text(encoding="utf-8"))
                if data["seconds"] < part_seconds - 0.01 or args.smoke:
                    break
                index += 1
                continue
            audio = subprocess.run(
                [ffmpeg, "-v", "error", "-threads", "1", "-ss", str(offset), "-i", str(source),
                 "-t", str(part_seconds), "-vn", "-ac", "1", "-ar", "16000",
                 "-f", "f32le", "pipe:1"], check=True, capture_output=True,
            ).stdout
            samples = np.frombuffer(audio, dtype="<f4").copy()
            if len(samples) == 0:
                break
            print(f"Transcribing {source.name} at {offset}s ({len(samples)/16000:.1f}s)", flush=True)
            started = time.monotonic()
            result = recognizer(
                {"array": samples, "sampling_rate": 16000},
                return_timestamps=True,
                generate_kwargs={"language": "tatar", "task": "transcribe",
                                 "eos_token_id": int(eos), "use_cache": True},
            )
            data = dict(source=source.name, sha256=digest, offset=offset,
                        seconds=len(samples)/16000, model=model_id, result=result)
            save_json(target, data)
            with (folder / f"part_{index:04d}_draft.csv").open("w", encoding="utf-8-sig", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["source", "start", "end", "text", "approved", "speaker_id"])
                for chunk in result.get("chunks", []):
                    start, end = chunk["timestamp"]
                    writer.writerow([source.name, offset + start if start is not None else "",
                                     offset + end if end is not None else "", chunk["text"], 0, ""])
            save_json(output / "progress.json", dict(source=source.name, completed_part=index,
                      audio_seconds=offset + len(samples)/16000, last_part_wall_seconds=time.monotonic()-started))
            print(f"Saved {target}; elapsed {time.monotonic()-started:.1f}s", flush=True)
            if args.smoke or len(samples)/16000 < part_seconds - 0.01:
                break
            index += 1
    print("Transcription finished. Review text, speaker and boundaries before TTS training.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        (ROOT / "asr").mkdir(exist_ok=True)
        (ROOT / "asr" / "last_error.txt").write_text(traceback.format_exc(), encoding="utf-8")
        raise
