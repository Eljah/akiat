"""Recognize one selected WAV range; leave corpus and editorial saves untouched."""
import argparse
import json
import os
import unicodedata
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--audio', type=Path, required=True)
    parser.add_argument('--start', type=float, required=True)
    parser.add_argument('--end', type=float, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    from windows_budget import apply_budget
    apply_budget()
    root = Path(__file__).resolve().parents[1]
    os.environ.update(HF_HOME=str(root / '.cache/huggingface'), HF_HUB_OFFLINE='1',
                     CUDA_VISIBLE_DEVICES='', TOKENIZERS_PARALLELISM='false',
                     OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1')
    import numpy as np
    import soundfile as sf
    import librosa
    import torch
    from transformers import AutoTokenizer, AutoFeatureExtractor, pipeline
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    with sf.SoundFile(args.audio) as audio:
        rate = audio.samplerate
        if not 0 <= args.start < args.end <= len(audio) / rate + 0.0001:
            raise ValueError('Invalid audio range')
        audio.seek(round(args.start * rate))
        samples = audio.read(round((args.end - args.start) * rate), dtype='float32', always_2d=True).mean(axis=1)
    if not len(samples):
        raise ValueError('Empty audio range')
    samples = librosa.resample(samples, orig_sr=rate, target_sr=16000)
    print('Loading cached Tatar ASR model on CPU', flush=True)
    recognizer = pipeline('automatic-speech-recognition', model='yasalma/whisper-finetuned-tt-asr',
        tokenizer=AutoTokenizer.from_pretrained('openai/whisper-small', extra_special_tokens={}),
        feature_extractor=AutoFeatureExtractor.from_pretrained('openai/whisper-small'),
        device=-1, dtype=torch.float32, batch_size=1)
    eos = int(recognizer.tokenizer.eos_token_id)
    recognizer.generation_config.eos_token_id = eos
    recognizer.model.generation_config.eos_token_id = eos
    print('Recognizing selected range', flush=True)
    text = ''
    if np.sqrt(np.mean(samples**2)) > .002:
        result = recognizer({'array': samples, 'sampling_rate': 16000}, return_timestamps=False,
            generate_kwargs={'language': 'tatar', 'task': 'transcribe', 'eos_token_id': eos,
                             'num_beams': 1, 'max_new_tokens': 192})
        text = ' '.join(result.get('text', '').replace('|', ' ').split())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tokens = []
    if text:
        print('Checking morphology and corpus evidence', flush=True)
        from audit_tatar_corpus import TOKEN, normalize, lookup, lemma_parts, load_corpora, analyze_tokens
        audit_dir = args.output.with_suffix('.audit')
        audit_dir.mkdir(exist_ok=True)
        variants = {v for match in TOKEN.finditer(text)
                    for v in (unicodedata.normalize('NFC', match[0]), normalize(match[0]))}
        if variants:
            analyses, model_sha = lookup(variants, audit_dir.resolve())
            targets = {normalize(w) for w in variants}
            targets.update(normalize(l) for aa in analyses.values() for a in aa for l in lemma_parts(a))
            lemmas, ud_forms, ud_lemmas, forms, _ = load_corpora(targets)
            tokens = analyze_tokens(text, analyses, lemmas, ud_forms, ud_lemmas, forms)
            for token in tokens:
                token['review'] = any(flag != 'FORM_UNATTESTED' for flag in token['flags'])
    args.output.write_text(json.dumps(dict(text=text, audio=str(args.audio.resolve()),
        start=args.start, end=args.end, tokens=tokens, model='yasalma/whisper-finetuned-tt-asr'),
        ensure_ascii=False, indent=2), encoding='utf-8')
    print('Done', flush=True)


if __name__ == '__main__':
    main()
