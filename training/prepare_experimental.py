"""Create explicitly unreviewed short WAV/text pairs; never invent word alignment."""
import os
from pathlib import Path
import re
import shutil
import subprocess

from night_common import ROOT, STATE, read_json, write_json


def boundaries(total, silence_points, maximum=12.0):
    start = 0.0
    while start < total:
        limit = min(total, start + maximum)
        candidates = [p for p in silence_points if start + 5 <= p <= limit]
        end = max(candidates) if candidates and limit < total else limit
        if end - start >= 1.2:
            yield round(start, 3), round(end, 3)
        start = end


def main():
    from windows_budget import apply_budget
    apply_budget()
    for name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS'):
        os.environ[name] = '1'
    os.environ['HF_HOME'] = str(ROOT / '.cache' / 'huggingface')
    os.environ['CUDA_VISIBLE_DEVICES'] = ''
    os.environ['TOKENIZERS_PARALLELISM'] = 'false'
    import imageio_ffmpeg
    import numpy as np
    import soundfile as sf
    import torch
    from transformers import AutoTokenizer, AutoFeatureExtractor, pipeline
    config = read_json(ROOT / 'training' / 'night_pipeline.json')
    if not config['experimental_unreviewed']:
        raise RuntimeError('Experimental unreviewed corpus is disabled')
    sources = read_json(STATE / 'sources.json')
    corpus = STATE / 'corpus'
    (corpus / 'wav').mkdir(parents=True, exist_ok=True)
    (corpus / 'labels').mkdir(exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    recognizer = pipeline('automatic-speech-recognition', model='yasalma/whisper-finetuned-tt-asr',
        tokenizer=AutoTokenizer.from_pretrained('openai/whisper-small', extra_special_tokens={}),
        feature_extractor=AutoFeatureExtractor.from_pretrained('openai/whisper-small'),
        device=-1, dtype=torch.float32, batch_size=1)
    eos = int(recognizer.tokenizer.eos_token_id)
    recognizer.generation_config.eos_token_id = eos
    recognizer.model.generation_config.eos_token_id = eos
    labels = []
    for source in sources:
        if any(term.casefold() in source['name'].casefold() for term in config['exclude_sources_containing']):
            continue
        audio = ROOT / source['name']
        plan_file = corpus / (source['sha256'][:16] + '.plan.json')
        if not plan_file.exists():
            detected = subprocess.run([ffmpeg, '-hide_banner', '-threads', '1', '-i', str(audio),
                '-af', 'silencedetect=noise=-35dB:d=0.25', '-f', 'null', '-'], capture_output=True, check=True)
            points = [float(x) for x in re.findall(r'silence_start: ([\d.]+)', detected.stderr.decode(errors='replace'))]
            write_json(plan_file, list(boundaries(source['seconds'], points)))
        for index, (start, end) in enumerate(read_json(plan_file)):
            if shutil.disk_usage(ROOT).free < config['minimum_free_gib'] * 1024**3:
                raise RuntimeError('Disk reserve reached; corpus preparation stopped')
            uid = f"{source['sha256'][:16]}_{index:05d}"
            label_path = corpus / 'labels' / f'{uid}.json'
            target = corpus / 'wav' / f'{uid}.wav'
            if label_path.exists():
                saved = read_json(label_path)
                if saved.get('accepted') and not target.exists():
                    raise RuntimeError(f'Missing saved WAV: {target}')
                labels.append(saved)
                continue
            temp_wav = target.with_name(uid + '.tmp.wav')
            subprocess.run([ffmpeg, '-v', 'error', '-threads', '1', '-y', '-ss', str(start), '-i', str(audio),
                '-t', str(end-start), '-ac', '1', '-ar', '22050', '-c:a', 'pcm_s16le', str(temp_wav)], check=True)
            waveform, rate = sf.read(temp_wav, dtype='float32')
            # The actual extracted clip gets its own label; long ASR text is never spread across slices.
            import librosa
            samples = librosa.resample(waveform, orig_sr=rate, target_sr=16000)
            text = ''
            if len(samples) and np.sqrt(np.mean(samples**2)) > 0.002:
                result = recognizer({'array':samples, 'sampling_rate':16000}, return_timestamps=False,
                    generate_kwargs={'language':'tatar', 'task':'transcribe', 'eos_token_id':eos,
                                     'num_beams':1, 'max_new_tokens':192})
                text = ' '.join(result.get('text', '').replace('|', ' ').split())
            accepted = len(text) >= 4 and any(c.isalpha() for c in text)
            record = dict(uid=uid, source=source['name'], start=start, end=end, text=text,
                          accepted=accepted, reviewed=False, speaker_verified=False)
            if accepted:
                temp_wav.replace(target)
            else:
                temp_wav.unlink(missing_ok=True)
            write_json(label_path, record)
            labels.append(record)
            write_json(STATE / 'corpus_progress.json', dict(source=source['name'], clip=index,
                       accepted=sum(x['accepted'] for x in labels)))
            print(f'Prepared {uid}; accepted={accepted}', flush=True)
    accepted = [x for x in labels if x['accepted']]
    temp = corpus / 'metadata.tmp'
    temp.write_text(''.join(f"{x['uid']}|{x['text']}\n" for x in accepted), encoding='utf-8')
    temp.replace(corpus / 'metadata.csv')
    if len(accepted) < config['minimum_clips']:
        raise RuntimeError(f'Only {len(accepted)} usable clips; need {config["minimum_clips"]}')
    write_json(STATE / 'corpus_ready.json', dict(clips=len(accepted), reviewed=False,
               seconds=sum(x['end']-x['start'] for x in accepted)))


if __name__ == '__main__':
    main()
