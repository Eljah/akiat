"""Independent CPU fine-tune from original weights and frozen reviewed edits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys
import time
import wave

ROOT = Path(__file__).resolve().parents[1]


def local_path(value):
    value = str(value)
    if os.name != 'nt' and len(value) > 2 and value[1] == ':':
        return Path('/mnt/' + value[0].lower() + '/' + value[3:].replace('\\', '/'))
    return Path(value)


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, value):
    path = Path(path)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def reviewed(report):
    records = []
    for path in sorted((report / 'desktop_edits').glob('*.json')):
        raw = path.read_bytes()
        edit = json.loads(raw.decode('utf-8-sig'))
        if edit.get('status') != 'reviewed':
            continue
        if edit.get('training_eligible') is not True or edit.get('effective_text_needs_review'):
            raise ValueError(f'Reviewed edit requires attention: {path.name}')
        text = edit.get('effective_text', '').strip()
        if not text or '|' in text or '\n' in text or '\r' in text:
            raise ValueError(f'Invalid final training text: {path.name}')
        uid = edit['uid']
        if not uid or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in uid):
            raise ValueError('Invalid clip ID')
        record = dict(uid=uid, text=text, original_audio=str(local_path(edit['original_audio'])),
            start=edit['trim_start'], end=edit['trim_end'], edit=edit,
            edit_sha256=hashlib.sha256(raw).hexdigest())
        from manual_equalizer import read_recipe
        equalizer = read_recipe(report, uid, local_path(edit['original_audio']))
        if equalizer:
            record['equalizer'] = equalizer
        records.append(record)
    if len(records) < 10:
        raise ValueError('At least 10 reviewed clips required')
    return records


def split_groups(records):
    groups = sorted({r['original_audio'] for r in records})
    if len(groups) < 2:
        raise ValueError('At least two independent original clips required')
    random.Random(1234).shuffle(groups)
    validation = set(groups[:max(1, round(len(groups) * .1))])
    return [r['uid'] for r in records if r['original_audio'] in validation]


def reserve():
    for path, gib in [(ROOT, 5), (Path('/mnt/c'), 2)]:
        if path.exists() and shutil.disk_usage(path).free < gib * 1024**3:
            raise RuntimeError(f'Not enough free disk space: {path}; reserve {gib} GiB')


def prepare(run, records):
    reserve()
    if run.exists():
        raise RuntimeError('Use a new run directory; never resume old experiment implicitly')
    run.mkdir(parents=True)
    (run / 'audio').mkdir()
    corpus = run / 'corpus'
    (corpus / 'wav').mkdir(parents=True)
    for record in records:
        with wave.open(record['original_audio'], 'rb') as source:
            rate = source.getframerate()
            start, end = round(record['start'] * rate), round(record['end'] * rate)
            if not 0 <= start < end <= source.getnframes():
                raise ValueError(f'Invalid trim: {record["uid"]}')
            if record.get('equalizer'):
                from manual_equalizer import filter_pcm
                full = filter_pcm(source.readframes(source.getnframes()), rate, source.getnchannels(), record['equalizer'])
                frame_size = source.getsampwidth()*source.getnchannels()
                audio = full[start*frame_size:end*frame_size]
            else:
                source.setpos(start)
                audio = source.readframes(end - start)
            with wave.open(str(corpus / 'wav' / (record['uid'] + '.wav')), 'wb') as target:
                target.setparams(source.getparams())
                target.writeframes(audio)
            record['seconds'] = (end - start) / rate
            record['audio_sha256'] = hashlib.sha256(audio).hexdigest()
    (corpus / 'metadata.csv').write_text(''.join(r['uid'] + '|' + r['text'] + '\n' for r in records), encoding='utf-8')
    validation = split_groups(records)
    write(run / 'manifest.json', dict(records=records, validation_uids=validation,
        checkpoint=str(ROOT / 'checkpoints/tatartts_male.ckpt'), epochs_start=1))
    shutil.copyfile(ROOT / 'outputs/night_pipeline/first_epoch_demo/story.txt', run / 'story.txt')
    return validation


def resume_snapshot(run):
    manifest = read(run / 'manifest.json')
    records = manifest['records']
    for record in records:
        with wave.open(str(run / 'corpus/wav' / (record['uid'] + '.wav')), 'rb') as source:
            digest = hashlib.sha256(source.readframes(source.getnframes())).hexdigest()
        if digest != record['audio_sha256']:
            raise ValueError(f'Frozen corpus changed: {record["uid"]}')
    metadata = ''.join(r['uid'] + '|' + r['text'] + '\n' for r in records)
    if (run / 'corpus/metadata.csv').read_text(encoding='utf-8') != metadata:
        raise ValueError('Frozen training text changed')
    checkpoint = run / 'checkpoints/last.ckpt'
    if not checkpoint.is_file():
        raise FileNotFoundError(checkpoint)
    return records, set(manifest['validation_uids']), checkpoint


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--epochs', type=int, default=40)
    parser.add_argument('--inspect', action='store_true')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    run = args.run_dir.resolve()
    resume_checkpoint = None
    if args.resume:
        records, validation, resume_checkpoint = resume_snapshot(run)
    else:
        report = local_path((ROOT / 'outputs/corpus_review/LATEST.txt').read_text(encoding='utf-8-sig').strip())
        records = reviewed(report)
    if args.inspect:
        print(json.dumps(dict(clips=len(records), seconds=sum(r['end']-r['start'] for r in records),
                              validation_clips=len(split_groups(records))), indent=2))
        return
    import fcntl
    import resource
    lock = (ROOT / 'outputs/reviewed_training.lock').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    reserve()
    os.nice(10)
    os.sched_setaffinity(0, {max(os.sched_getaffinity(0))})
    resource.setrlimit(resource.RLIMIT_AS, (8 * 1024**3, 8 * 1024**3))
    os.environ.update(OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', CUDA_VISIBLE_DEVICES='')
    if not args.resume:
        validation = set(prepare(run, records))
    temporary = run / 'tmp'
    temporary.mkdir(exist_ok=True)
    os.environ['TMPDIR'] = str(temporary)
    def status(stage, **details):
        write(run / 'status.json', dict(stage=stage, updated=time.strftime('%Y-%m-%dT%H:%M:%S%z'), pid=os.getpid(), **details))
    try:
        status('resume' if args.resume else 'preprocess', clips=len(records), epochs=args.epochs)
        if not args.resume:
            subprocess.run([sys.executable, '-m', 'piper_train.preprocess', '--language', 'tt',
                '--input-dir', str(run / 'corpus'), '--output-dir', str(run / 'processed'),
                '--dataset-format', 'ljspeech', '--single-speaker', '--sample-rate', '22050', '--max-workers', '1'], check=True)
        from night_common import validate_phoneme_map
        config = read(run / 'processed/config.json')
        original = read(ROOT / 'checkpoints/tatartts_male.config.json')
        if config['audio']['sample_rate'] != original['audio']['sample_rate']:
            raise ValueError('Sample rate mismatch')
        for key in ('num_symbols', 'num_speakers'):
            if config.get(key) != original.get(key):
                raise ValueError(f'Preprocess config mismatch: {key}')
        validate_phoneme_map(original['phoneme_id_map'], config['phoneme_id_map'], config['num_symbols'])
        import torch
        import numpy as np
        import soundfile as sf
        import pytorch_lightning as pl
        from piper_train.vits.lightning import VitsModel
        from piper_phonemize import phonemize_espeak, phoneme_ids_espeak
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        pl.seed_everything(1234)
        dataset = run / 'processed/dataset.jsonl'
        rows = [json.loads(line) for line in dataset.read_text().splitlines()]
        if len(rows) != len(records):
            raise RuntimeError('Preprocessing lost reviewed clips')
        model = VitsModel.load_from_checkpoint(str(resume_checkpoint or ROOT / 'checkpoints/tatartts_male.ckpt'), map_location='cpu',
            dataset=[dataset], batch_size=1, num_workers=0, num_test_examples=0, validation_split=0)
        full = model._train_dataset.dataset
        val_indices = [i for i, row in enumerate(rows) if Path(row['audio_path']).stem in validation]
        if not val_indices or len(val_indices) == len(rows) or len(full) != len(rows):
            raise RuntimeError('Invalid grouped train/validation split')
        model._train_dataset = torch.utils.data.Subset(full, [i for i in range(len(rows)) if i not in val_indices])
        model._val_dataset = torch.utils.data.Subset(full, val_indices)
        demos = run / 'audio'
        demos.mkdir(exist_ok=True)
        class Progress(pl.Callback):
            def on_train_start(self, trainer, module):
                if args.resume:
                    write(run / f'resume_to_{args.epochs}_{time.time_ns()}.json', dict(
                        checkpoint=str(resume_checkpoint), next_epoch=trainer.current_epoch+1,
                        global_step=trainer.global_step, target_epochs=args.epochs))
                    print(f'RESUMED next_epoch={trainer.current_epoch+1} global_step={trainer.global_step}', flush=True)
            def on_train_batch_start(self, trainer, module, batch, index):
                reserve()
            def on_train_batch_end(self, trainer, module, outputs, batch, index):
                status('train', epoch=trainer.current_epoch+1, batch=index+1, batches=len(model._train_dataset), epochs=args.epochs)
                if index < 3 or (index+1) % 10 == 0:
                    print(f'TRAIN epoch={trainer.current_epoch+1} batch={index+1}', flush=True)
            def on_train_epoch_end(self, trainer, module):
                epoch = trainer.current_epoch+1
                status('render', epoch=epoch)
                training = module.training
                try:
                    module.eval()
                    with torch.no_grad(), torch.random.fork_rng(devices=[]):
                        torch.manual_seed(1234)
                        story = ' '.join((run / 'story.txt').read_text(encoding='utf-8').split())
                        if not story:raise RuntimeError('Empty demo story')
                        phonemes = [p for sentence in phonemize_espeak(story, 'tt') for p in sentence]
                        ids = phoneme_ids_espeak(phonemes)
                        audio = module(torch.LongTensor([ids]), torch.LongTensor([len(ids)]), torch.FloatTensor([.667,1,.8])).squeeze().cpu().numpy()
                        if not audio.size or not np.isfinite(audio).all():raise RuntimeError('Invalid synthesized audio')
                        peak = float(np.abs(audio).max())
                        if peak > .98:audio *= .98/peak
                        path = demos / f'kuyan_belen_yoldyz_epoch{epoch:02d}_full.wav'
                        if path.exists():raise FileExistsError(path)
                        sf.write(str(path), audio, 22050, subtype='PCM_16')
                finally:
                    module.train(training)
                loss = trainer.callback_metrics.get('val_loss')
                write(demos / f'epoch{epoch:02d}.json', dict(epoch=epoch, seconds=len(audio)/22050,
                    val_loss=float(loss) if loss is not None else None, wav=str(path), listening_reviewed=False,
                    model_calls=1, inserted_pause_seconds=0))
                print(f'EPOCH {epoch} AUDIO {path}', flush=True)
        checkpoint = pl.callbacks.ModelCheckpoint(dirpath=run / 'checkpoints', save_last=True,
            save_top_k=1, every_n_epochs=1, filename='epoch{epoch:04d}')
        trainer = pl.Trainer(accelerator='cpu', devices=1, precision=32, max_epochs=args.epochs,
            default_root_dir=run, callbacks=[Progress(), checkpoint], num_sanity_val_steps=0,
            enable_progress_bar=False, log_every_n_steps=10)
        # Explicit resume restores optimizer, schedulers and epoch/step counters too.
        trainer.fit(model, ckpt_path=str(resume_checkpoint) if resume_checkpoint else None)
        status('complete', epochs=args.epochs)
    except Exception as exc:
        status('failed', error=str(exc))
        raise


if __name__ == '__main__':
    main()
