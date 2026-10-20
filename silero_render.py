#!/usr/bin/env python3
"""Batch Tatar Silero V5 voice audition. Downloads the official model on first run.
No audio is generated until this script is executed on a machine with model access.
"""
from __future__ import annotations
import argparse
import re
import subprocess
import shutil
import wave
from pathlib import Path
import numpy as np

DEFAULT_VOICES = ['tat_alsou', 'tat_bulat', 'tat_zifa']
ALL_TATAR_VOICES = [
 'tat_adiba', 'tat_alsou', 'tat_amir', 'tat_azat', 'tat_batir',
 'tat_bulat', 'tat_damir', 'tat_guzel', 'tat_ildar', 'tat_ilgiz',
 'tat_karim', 'tat_mansur', 'tat_murat', 'tat_rasima', 'tat_rustem',
 'tat_timur', 'tat_zifa', 'tat_zufar', 'tat_zulfiya'
]
SENT_BOUNDARY = re.compile(r'(?<=[.!?…])\s+|\n+', flags=re.UNICODE)


def read_story(path: Path) -> str:
    raw = path.read_text(encoding='utf-8').replace('\ufeff', '')
    if path.suffix.lower() == '.md':
        sections = re.split(r'^---\s*$', raw, maxsplit=1, flags=re.MULTILINE)
        raw = sections[-1] if len(sections) > 1 else raw
    cleaned = []
    for ln in raw.splitlines():
        if re.match(r'^\s*#{1,6}\s+', ln):
            continue
        if ln.lstrip().startswith(('**Жанр:', '**Яшь:', '**Текст статусы:', '**Русча аннотация:')):
            continue
        ln = re.sub(r'\*\*|__|`', '', ln)
        ln = re.sub(r'\s+', ' ', ln).strip()
        if ln:
            cleaned.append(ln)
    return '\n'.join(cleaned)


def split_sentences(text: str, max_chars: int = 260, max_units: int = 0) -> list[str]:
    chunks = []
    for unit in SENT_BOUNDARY.split(text):
        unit = unit.strip(' \n\r\t')
        if not unit:
            continue
        if len(unit) <= max_chars:
            chunks.append(unit)
        else:
            # For long sentences, split at punctuation or a space near the limit.
            while len(unit) > max_chars:
                cut = max(unit.rfind(', ', 50, max_chars), unit.rfind(' ', 50, max_chars))
                if cut <= 0:
                    cut = max_chars
                chunks.append(unit[:cut].strip())
                unit = unit[cut:].strip()
            if unit:
                chunks.append(unit)
    return chunks[:max_units] if max_units else chunks


def apply_lexicon(text: str, dictionary: dict[str, str]) -> str:
    if not dictionary:
        return text
    def substitute(m):
        original = m.group()
        replacement = dictionary.get(original.casefold())
        if replacement is None:
            return original
        return replacement[:1].upper() + replacement[1:] if original[0].isupper() else replacement
    return re.sub(r'(?u)\b[\w+]+\b', substitute, text)


def load_lexicon(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    out = {}
    for lineno, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        try:
            plain, marked = line.split('\t', 1)
        except ValueError as exc:
            raise ValueError(f'Bad TSV stress lexicon line {lineno}') from exc
        out[plain.casefold()] = marked
    return out


def save_wave(path: Path, samples: np.ndarray, sr: int) -> None:
    arr = np.clip(np.asarray(samples).reshape(-1), -1.0, 1.0)
    pcm = (arr * 32767.0).astype('<i2')
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def synthesize(model, chunks: list[str], voice: str, sr: int, pause_seconds: float, lexicon: dict[str, str]) -> np.ndarray:
    output = []
    pause = np.zeros(int(sr * pause_seconds), dtype=np.float32)
    for i, sentence in enumerate(chunks):
        # Note: Silero v5_cis_ext expects + stress marks, especially for ambiguous words.
        spoken = apply_lexicon(sentence, lexicon)
        try:
            audio = model.apply_tts(text=spoken, speaker=voice, sample_rate=sr)
        except Exception as exc:
            raise RuntimeError(f'Failed for {voice}, chunk {i + 1}: {spoken!r}: {exc}') from exc
        if hasattr(audio, 'detach'):
            audio = audio.detach().cpu().numpy()
        output.append(np.asarray(audio, dtype=np.float32).reshape(-1))
        output.append(pause)
    return np.concatenate(output) if output else np.zeros(0, dtype=np.float32)


def main() -> None:
    p = argparse.ArgumentParser(description='Batch Tatar Silero TTS (model v5_cis_ext)')
    p.add_argument('--stories', type=Path, default=Path(__file__).with_name('stories'))
    p.add_argument('--output', type=Path, default=Path(__file__).with_name('outputs'))
    p.add_argument('--model-id', choices=['v5_cis_ext', 'v5_turkic'], default='v5_cis_ext')
    p.add_argument('--voices', nargs='+', default=None)
    p.add_argument('--sample-rate', type=int, choices=[8000, 24000, 48000], default=48000)
    p.add_argument('--max-sentences', type=int, default=0, help='0=full tale, e.g. 8 for quick voice comparison')
    p.add_argument('--pause', type=float, default=0.28)
    p.add_argument('--lexicon', type=Path, default=None, help='TSV original_word TAB stressed_word (native reviewed)')
    p.add_argument('--mp3', action='store_true', help='Also write MP3 via ffmpeg if available')
    args = p.parse_args()
    if not (0 <= args.pause <= 5):
        p.error('--pause must be between 0 and 5 seconds')
    valid_voices = ALL_TATAR_VOICES if args.model_id == 'v5_cis_ext' else ['tat_0']
    if args.voices is None:
        args.voices = DEFAULT_VOICES if args.model_id == 'v5_cis_ext' else ['tat_0']
    bad = [v for v in args.voices if v not in valid_voices]
    if bad:
        p.error(f'Unknown {args.model_id} voice(s): {bad}. Valid: {valid_voices}')
    stories = sorted(args.stories.glob('*.md')) + sorted(args.stories.glob('*.txt'))
    if not stories:
        p.error('No .md/.txt stories available in --stories')
    args.output.mkdir(parents=True, exist_ok=True)
    import torch
    torch.set_num_threads(4)
    print(f'Loading official Silero {args.model_id}; first call needs the internet...')
    model, _ = torch.hub.load(repo_or_dir='snakers4/silero-models',
                              model='silero_tts', language='ru', speaker=args.model_id,
                              trust_repo=True)
    model.eval().to('cpu')
    lex = load_lexicon(args.lexicon)
    for path in stories:
        units = split_sentences(read_story(path), max_units=args.max_sentences)
        if not units:
            print(f'Skipping empty story: {path.name}')
            continue
        for voice in args.voices:
            print(f'{path.stem} / {voice} ({len(units)} segments)')
            audio = synthesize(model, units, voice, args.sample_rate, args.pause, lex)
            wav_path = args.output / f'{path.stem}__{voice}.wav'
            save_wave(wav_path, audio, args.sample_rate)
            print(f'  {wav_path.name} ({len(audio)/args.sample_rate:.1f} s)')
            if args.mp3 and shutil.which('ffmpeg'):
                mp3_path = wav_path.with_suffix('.mp3')
                # EBU R128 loudness normalisation intended for spoken-word releases.
                subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav_path),
                               '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-ar', '48000',
                               '-c:a', 'libmp3lame', '-b:a', '192k', str(mp3_path)], check=True)
    print('Done. Listen to ALL files and verify Tatar pronunciation manually.')

if __name__ == '__main__':
    main()
