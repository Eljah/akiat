#!/usr/bin/env python3
"""Convert manually corrected turn-level intervals into Piper LJSpeech dataset.
CSV: start,end,text, where start/end are seconds or HH:MM:SS.sss.
Keep ONLY the father's voice, with explicit consent to synthesis.
"""
from __future__ import annotations
import argparse, csv, json, subprocess, hashlib
from pathlib import Path


def seconds(x: str) -> float:
    parts = str(x).strip().replace(',', '.').split(':')
    if not 1 <= len(parts) <= 3:
        raise ValueError(f'Bad timestamp: {x!r}')
    result = 0.0
    for part in parts:
        result = result * 60 + float(part)
    return result


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--audio', required=True, type=Path)
    p.add_argument('--segments', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--min-seconds', type=float, default=1.2)
    p.add_argument('--max-seconds', type=float, default=14)
    args=p.parse_args()
    if not args.audio.exists():
        p.error('Source audio not found')
    if not args.segments.exists():
        p.error('Segments CSV not found')
    args.output.mkdir(parents=True, exist_ok=True)
    wav_dir=args.output/'wav'
    wav_dir.mkdir(exist_ok=True)
    kept=[]
    rejected=[]
    with args.segments.open(encoding='utf-8-sig', newline='') as f:
        rd=csv.DictReader(f)
        if set(['start','end','text'])-set(rd.fieldnames or []):
            p.error('CSV must include start,end,text columns')
        for i,row in enumerate(rd,1):
            try:
                start,end=seconds(row['start']),seconds(row['end'])
                dur=end-start
                text=' '.join(row['text'].strip().split())
                if not args.min_seconds <= dur <= args.max_seconds:
                    raise ValueError(f'Bad duration {dur:.2f}s')
                if not text or '|' in text or '[' in text or ']' in text:
                    raise ValueError('Empty, markup, or pipe in transcript')
                if start < 0:
                    raise ValueError('Negative start')
                uid=f'father_{i:04d}'
                target=wav_dir/f'{uid}.wav'
                cmd=['ffmpeg','-hide_banner','-loglevel','error','-y',
                     '-ss',f'{start:.3f}','-i',str(args.audio),'-t',f'{dur:.3f}',
                     '-vn','-ac','1','-ar','22050','-c:a','pcm_s16le',str(target)]
                subprocess.run(cmd,check=True)
                kept.append((uid,text,dur))
            except (ValueError,subprocess.CalledProcessError) as exc:
                rejected.append({'row':i,'reason':str(exc)})
    (args.output/'metadata.csv').write_text(''.join(f'{uid}|{txt}\n' for uid,txt,_ in kept),encoding='utf-8')
    info={'total_segments':len(kept),'seconds':round(sum(x[2] for x in kept),1),
          'rejected':rejected,'source_filename':args.audio.name,
          'source_sha256':hashlib.sha256(args.audio.read_bytes()).hexdigest()}
    (args.output/'validation_report.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(info,ensure_ascii=False,indent=2))
    print('IMPORTANT: listen to each WAV and ensure it matches the text and contains only father speech.')

if __name__=='__main__':
    main()
