"""Download Whisper weights in bounded, resumable HTTP ranges through the configured proxy."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import time

import requests

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("HF_HOME", str(ROOT / ".cache" / "huggingface"))
from huggingface_hub import get_hf_file_metadata, hf_hub_url


def main():
    repo = "yasalma/whisper-finetuned-tt-asr"
    url = hf_hub_url(repo, "model.safetensors")
    metadata = get_hf_file_metadata(url, timeout=30)
    url = hf_hub_url(repo, "model.safetensors", revision=metadata.commit_hash)
    digest, total = metadata.etag, metadata.size
    if not digest or len(digest) != 64 or not total:
        raise ValueError("Expected SHA256 and size for model weights")
    blob = Path(os.environ["HF_HOME"]) / "hub" / ("models--" + repo.replace("/", "--")) / "blobs" / digest
    if blob.exists() and blob.stat().st_size == total:
        print("Model weights already cached.", flush=True)
        return
    folder = ROOT / ".cache" / "model_parts" / digest
    folder.mkdir(parents=True, exist_ok=True)
    block = 4 * 1024 * 1024
    started = time.monotonic()
    completed = 0

    def fetch(index):
        start = index * block
        end = min(start + block, total) - 1
        target = folder / f"{index:05d}.part"
        if target.exists() and target.stat().st_size == end - start + 1:
            return target
        for attempt in range(4):
            try:
                with requests.get(url, headers={"Range": f"bytes={start}-{end}"},
                                  stream=True, timeout=(15, 30)) as response:
                    response.raise_for_status()
                    expected = f"bytes {start}-{end}/{total}"
                    if response.status_code != 206 or response.headers.get("Content-Range") != expected:
                        raise ValueError("Server did not honor requested byte range")
                    temp = target.with_suffix(".tmp")
                    with temp.open("wb") as stream:
                        for chunk in response.iter_content(65536):
                            stream.write(chunk)
                    if temp.stat().st_size != end - start + 1:
                        raise ValueError("Incomplete range")
                    temp.replace(target)
                return target
            except (requests.RequestException, ValueError):
                if attempt == 3:
                    raise RuntimeError(f"Download failed at range {index} after 4 attempts") from None
                time.sleep(2 * (attempt + 1))

    count = (total + block - 1) // block
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = [pool.submit(fetch, index) for index in range(count)]
        for job in as_completed(jobs):
            completed += job.result().stat().st_size
            status = dict(stage="downloading_model", downloaded_bytes=completed, total_bytes=total,
                          percent=round(100 * completed / total, 1),
                          elapsed_seconds=round(time.monotonic() - started, 1),
                          updated=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
            temp_status = ROOT / "asr" / "download_status.tmp"
            temp_status.write_text(json.dumps(status, indent=2), encoding="utf-8")
            temp_status.replace(ROOT / "asr" / "cpu_status.json")
            print(f"Model: {completed / 1e6:.1f}/{total / 1e6:.1f} MB ({status['percent']}%)", flush=True)
    blob.parent.mkdir(parents=True, exist_ok=True)
    temp = blob.with_suffix(".assembling")
    checksum = hashlib.sha256()
    with temp.open("wb") as stream:
        for index in range(count):
            with (folder / f"{index:05d}.part").open("rb") as part:
                while chunk := part.read(1024 * 1024):
                    checksum.update(chunk)
                    stream.write(chunk)
    if checksum.hexdigest() != digest:
        raise RuntimeError("Model SHA256 mismatch; refusing to use downloaded file")
    temp.replace(blob)
    print("Model download complete; SHA256 verified.", flush=True)


if __name__ == "__main__":
    main()
