"""Local files and completion checks shared by the experimental night pipeline."""
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'outputs' / 'night_pipeline'


def latest_last_checkpoint(directory):
    """Lightning may save last-v1.ckpt after restoring into a different folder."""
    paths = [p for p in Path(directory).glob('last*.ckpt')
             if re.fullmatch(r'last(?:-v\d+)?\.ckpt', p.name) and p.is_file()]
    return max(paths, key=lambda p: p.stat().st_mtime_ns) if paths else None


def validate_phoneme_map(original, actual, num_symbols):
    """Allow appended symbols, but never remap an existing checkpoint symbol."""
    for phoneme, ids in original.items():
        if actual.get(phoneme) != ids:
            raise ValueError(f'Checkpoint phoneme IDs changed: {phoneme!r}')
    for ids in actual.values():
        if not ids or any(not isinstance(index, int) or not 0 <= index < num_symbols for index in ids):
            raise ValueError('Phoneme IDs exceed checkpoint embedding dimensions')
    old_ids = {index for ids in original.values() for index in ids}
    for phoneme in actual.keys() - original.keys():
        if old_ids.intersection(actual[phoneme]):
            raise ValueError(f'New phoneme reuses checkpoint IDs: {phoneme!r}')
    return sorted(actual.keys() - original.keys())


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    temp.replace(path)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def duration(path, ffmpeg):
    result = subprocess.run([ffmpeg, '-hide_banner', '-i', str(path)], capture_output=True)
    match = re.search(r'Duration: (\d+):(\d+):(\d+\.\d+)', result.stderr.decode('utf-8', errors='replace'))
    if not match:
        raise ValueError(f'Cannot determine duration: {path.name}')
    hours, minutes, seconds = map(float, match.groups())
    return hours * 3600 + minutes * 60 + seconds


def validate_parts(parts, digest, source_name, total):
    """Reject stale, missing, discontinuous and partial recognition results."""
    if not parts:
        raise ValueError(f'No recognition parts: {source_name}')
    end = 0.0
    for data in parts:
        if data['sha256'] != digest or data['source'] != source_name:
            raise ValueError(f'Source changed: {source_name}')
        start, seconds = float(data['offset']), float(data['seconds'])
        if not math.isfinite(start + seconds) or abs(start - end) > 0.05 or seconds <= 0:
            raise ValueError(f'Gap or invalid duration: {source_name}')
        if not isinstance(data.get('result', {}).get('text'), str):
            raise ValueError(f'Missing transcription: {source_name}')
        end = start + seconds
    if abs(end - total) > 1.0:
        raise ValueError(f'Incomplete audio: {source_name}: {end:.1f}/{total:.1f}s')


def complete_sources():
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    files = sorted(ROOT.glob('*.mp3'))
    if not files:
        raise ValueError('No source MP3 files')
    sources = []
    for source in files:
        digest = sha256(source)
        total = duration(source, ffmpeg)
        folder = ROOT / 'asr' / 'cpu' / digest[:16]
        parts = [read_json(p) for p in sorted(folder.glob('part_*.json'))]
        validate_parts(parts, digest, source.name, total)
        sources.append(dict(name=source.name, sha256=digest, seconds=total))
    return sources
