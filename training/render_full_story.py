"""Render a complete story in one model call, without inserted pauses."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import time


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--story', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    text = ' '.join(args.story.read_text(encoding='utf-8-sig').split())
    if not text:
        raise ValueError('Empty story')
    os.environ.update(OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', CUDA_VISIBLE_DEVICES='')
    os.nice(15)
    os.sched_setaffinity(0, {max(os.sched_getaffinity(0))})
    resource.setrlimit(resource.RLIMIT_AS, (8 * 1024**3, 8 * 1024**3))
    import torch
    import numpy as np
    import soundfile as sf
    from piper_train.vits.lightning import VitsModel
    from piper_phonemize import phonemize_espeak, phoneme_ids_espeak
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    digest = hashlib.sha256()
    with args.checkpoint.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    model = VitsModel.load_from_checkpoint(str(args.checkpoint), map_location='cpu', dataset=None)
    model.eval()
    torch.manual_seed(1234)
    ids = phoneme_ids_espeak([p for sentence in phonemize_espeak(text, 'tt') for p in sentence])
    print(f'Rendering whole story: {len(ids)} phoneme IDs', flush=True)
    started = time.monotonic()
    with torch.no_grad():
        audio = model(torch.LongTensor([ids]), torch.LongTensor([len(ids)]),
                      torch.FloatTensor([.667, 1, .8])).squeeze().cpu().numpy()
    if not audio.size or not np.isfinite(audio).all():
        raise RuntimeError('Invalid synthesized audio')
    peak = float(np.abs(audio).max())
    if peak > .98:
        audio *= .98 / peak
    rate = int(model.hparams.sample_rate)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(args.output), audio, rate, subtype='PCM_16')
    report = dict(checkpoint=str(args.checkpoint), checkpoint_sha256=digest.hexdigest(),
                  text=text, model_calls=1, inserted_pause_seconds=0, seed=1234,
                  seconds=len(audio)/rate, sample_rate=rate,
                  render_seconds=time.monotonic()-started, listening_reviewed=False)
    args.output.with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
