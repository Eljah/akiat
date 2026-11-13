"""Read-only review snapshot, speaker-change screening and experimental separation.

Run in an isolated SpeechBrain environment. No output is training-approved.
Embedding distances are ranking heuristics, not verified speaker/overlap labels.
"""
import argparse
import hashlib
import html
import json
import math
import os
from pathlib import Path
import resource
import time
import wave

from train_reviewed import ROOT, local_path, reviewed


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def page(folder, rows):
    cards = []
    for row in sorted(rows, key=lambda r: r.get('change_score', 0), reverse=True):
        uid = row['uid']
        esc = html.escape
        players = f'<p>Исходный одобренный фрагмент</p><audio controls preload="none" src="original/{uid}.wav"></audio>'
        if row.get('separation'):
            for channel in ('A', 'B'):
                players += f'<p>Пробная дорожка {channel} — голос не идентифицирован</p><audio controls preload="none" src="separated/{uid}_{channel}.wav"></audio>'
        windows = ', '.join(f'{w["start"]:.1f}–{w["end"]:.1f} с: {w["distance"]:.2f}' for w in row.get('changes', []))
        cards.append(f'<article><h2>{esc(uid)}</h2><p>{esc(row["source"])}, {row["seconds"]:.2f} с</p>'
                     f'<p>{esc(row["text"])}</p><p>Изменение голосовых признаков: {row.get("change_score", 0):.3f}. '
                     f'Это не вероятность второго голоса.</p><p>{esc(windows)}</p>{players}</article>')
    body = '<!doctype html><meta charset="utf-8"><title>Проверка посторонних голосов</title><style>body{font:17px sans-serif;max-width:1000px;margin:30px auto;background:#f4f5f7;color:#20232a}article{background:white;padding:20px;margin:16px 0;border-radius:12px}audio{width:100%}h2{font-size:19px}</style>'
    body += '<h1>Одобренные фрагменты: эксперимент с разделением голосов</h1><p>Исходники не изменены. Дорожки A/B не одобрены для обучения. Нумерация A/B не обозначает нужного диктора. Разделитель работает на 8 кГц: возможны потеря тембра, артефакты, утечка и перестановка голосов. Текст у дорожек требует повторной проверки.</p><p>Ранжирование по изменению ECAPA-признаков в соседних окнах — только способ выбрать примеры для прослушивания. Паузы, интонация, музыка и шум тоже меняют признаки. Одновременная речь этим тестом не доказана.</p>'
    (folder / 'index.html').write_text(body + ''.join(cards), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pilots', type=int, default=5)
    args = parser.parse_args()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=False)
    for name in ('original', 'separated', 'texts'):
        (folder / name).mkdir()
    os.environ.update(OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', CUDA_VISIBLE_DEVICES='',
                      HF_HOME='/mnt/g/akiat-training/voice-clean-models/hf')
    os.nice(15)
    os.sched_setaffinity(0, {max(os.sched_getaffinity(0))})
    resource.setrlimit(resource.RLIMIT_AS, (6 * 1024**3, 6 * 1024**3))
    def status(stage, **details):
        write(folder / 'status.json', dict(stage=stage, time=time.strftime('%Y-%m-%dT%H:%M:%S%z'), **details))
    rows = []
    try:
        status('snapshot')
        report = local_path((ROOT / 'outputs/corpus_review/LATEST.txt').read_text(encoding='utf-8-sig').strip())
        for record in reviewed(report):
            uid = record['uid']
            with wave.open(record['original_audio'], 'rb') as source:
                rate = source.getframerate()
                first, last = round(record['start']*rate), round(record['end']*rate)
                if not 0 <= first < last <= source.getnframes():
                    raise ValueError(f'Invalid trim: {uid}')
                source.setpos(first)
                pcm = source.readframes(last-first)
                with wave.open(str(folder / 'original' / (uid+'.wav')), 'wb') as target:
                    target.setparams(source.getparams())
                    target.writeframes(pcm)
            (folder / 'texts' / (uid+'.txt')).write_text(record['text'], encoding='utf-8')
            rows.append(dict(uid=uid, text=record['text'], source=record['edit'].get('source', ''),
                             original_audio=record['original_audio'], trim_start=record['start'], trim_end=record['end'],
                             seconds=(last-first)/rate, pcm_sha256=hashlib.sha256(pcm).hexdigest(),
                             edit_sha256=record['edit_sha256'], training_eligible=False))
        write(folder / 'inventory.json', rows)
        print(f'Snapshot: {len(rows)} approved clips', flush=True)
        import numpy as np
        import scipy.signal
        import soundfile as sf
        import torch
        from speechbrain.pretrained import EncoderClassifier, SepformerSeparation
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        torch.manual_seed(1234)
        def samples(row, wanted):
            signal, rate = sf.read(str(folder / 'original' / (row['uid']+'.wav')), dtype='float32', always_2d=True)
            signal = signal.mean(axis=1)
            divisor = math.gcd(rate, wanted)
            return scipy.signal.resample_poly(signal, wanted//divisor, rate//divisor).astype('float32')
        status('loading_speaker_model')
        encoder = EncoderClassifier.from_hparams(source='speechbrain/spkrec-ecapa-voxceleb',
            savedir='/mnt/g/akiat-training/voice-clean-models/ecapa', run_opts={'device': 'cpu'})
        for index, row in enumerate(rows):
            status('screening', completed=index, total=len(rows), uid=row['uid'])
            signal = samples(row, 16000)
            windows = []
            for start in range(0, max(1, len(signal)-32000+1), 16000):
                segment = signal[start:start+32000]
                if len(segment) < 16000 or float(np.sqrt(np.mean(segment**2))) < .003:
                    continue
                with torch.no_grad():
                    vector = encoder.encode_batch(torch.from_numpy(segment).unsqueeze(0)).flatten().numpy()
                vector /= max(float(np.linalg.norm(vector)), 1e-12)
                windows.append((start/16000, vector))
            changes = []
            for (left, a), (right, b) in zip(windows, windows[1:]):
                if right-left <= 1.01:
                    changes.append(dict(start=left, end=min(right+2, row['seconds']), distance=float(1-np.dot(a,b))))
            row['changes'] = sorted(changes, key=lambda c: c['distance'], reverse=True)[:3]
            row['change_score'] = max((c['distance'] for c in changes), default=0)
            write(folder / 'analysis.json', rows)
            print(f'Screened {index+1}/{len(rows)} {row["uid"]}: {row["change_score"]:.3f}', flush=True)
        del encoder
        page(folder, rows)
        status('loading_separator')
        separator = SepformerSeparation.from_hparams(source='speechbrain/sepformer-wsj02mix',
            savedir='/mnt/g/akiat-training/voice-clean-models/sepformer', run_opts={'device': 'cpu'})
        candidates = sorted((r for r in rows if 2 <= r['seconds'] <= 12), key=lambda r:r['change_score'], reverse=True)
        selected = []
        seen = set()
        for row in candidates:
            if row['original_audio'] not in seen:
                selected.append(row)
                seen.add(row['original_audio'])
            if len(selected) >= args.pilots:
                break
        for index, row in enumerate(selected):
            status('separating', completed=index, total=len(selected), uid=row['uid'])
            signal = samples(row, 8000)
            started = time.monotonic()
            with torch.no_grad():
                outputs = separator.separate_batch(torch.from_numpy(signal).unsqueeze(0)).squeeze(0).cpu().numpy()
            if outputs.shape != (len(signal), 2) or not np.isfinite(outputs).all():
                raise RuntimeError('Invalid separation output')
            gain = min(1, .98/max(float(np.abs(outputs).max()), 1e-12))
            for channel, label in enumerate(('A', 'B')):
                sf.write(str(folder / 'separated' / f'{row["uid"]}_{label}.wav'), outputs[:,channel]*gain, 8000, subtype='PCM_16')
            row['separation'] = dict(model='speechbrain/sepformer-wsj02mix', sample_rate=8000,
                shared_gain=gain, channel_rms=[float(np.sqrt(np.mean(outputs[:,i]**2))) for i in (0,1)],
                seconds=time.monotonic()-started, target_speaker=None, listening_reviewed=False)
            write(folder / 'analysis.json', rows)
            page(folder, rows)
            print(f'Separated {index+1}/{len(selected)}: {row["uid"]}', flush=True)
        write(folder / 'summary.json', dict(clips=len(rows), seconds=sum(r['seconds'] for r in rows),
             pilots=len(selected), automatic_speaker_decisions=0, source_files_modified=0,
             training_eligible=False, all_separated_outputs_need_listening=True))
        status('complete', clips=len(rows), pilots=len(selected))
    except Exception as exc:
        page(folder, rows)
        status('failed', error=str(exc))
        raise


if __name__ == '__main__':
    main()
