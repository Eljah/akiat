"""WSL Piper entry points: verify, preprocess, fine-tune and export on one CPU."""
import argparse
import json
import os
from pathlib import Path
import resource
import runpy
import sys

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'outputs' / 'night_pipeline'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['verify', 'preprocess', 'train', 'export', 'smoke_train'])
    args = parser.parse_args()
    os.environ.update(OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', CUDA_VISIBLE_DEVICES='')
    temporary = ROOT / '.tools' / 'tmp-wsl'
    temporary.mkdir(parents=True, exist_ok=True)
    os.environ['TMPDIR'] = str(temporary)
    os.environ['XDG_CACHE_HOME'] = str(ROOT / '.cache' / 'piper-wsl')
    os.nice(10)
    os.sched_setaffinity(0, {max(os.sched_getaffinity(0))})
    resource.setrlimit(resource.RLIMIT_AS, (8 * 1024**3, 8 * 1024**3))
    import torch
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    import pytorch_lightning as pl
    from piper_train.vits.lightning import VitsModel
    from piper_phonemize import phonemize_espeak, phoneme_ids_espeak, get_espeak_map
    from night_common import read_json, write_json, sha256, validate_phoneme_map
    settings = read_json(ROOT / 'training' / 'night_pipeline.json')
    checkpoint = ROOT / settings['checkpoint']
    original_config = read_json(ROOT / 'checkpoints' / 'tatartts_male.config.json')
    if args.stage == 'preprocess':
        import subprocess
        # A real module entry point is required for multiprocessing to pickle
        # Piper's __main__.Utterance dataclass. runpy leaves our __main__ in place.
        command = [sys.executable, '-m', 'piper_train.preprocess', '--language', 'tt', '--input-dir', str(STATE / 'corpus'),
                    '--output-dir', str(STATE / 'processed'), '--dataset-format', 'ljspeech',
                    '--single-speaker', '--sample-rate', '22050', '--max-workers', '1']
        subprocess.run(command, check=True)
        actual = read_json(STATE / 'processed' / 'config.json')
        for key in ('num_symbols', 'num_speakers'):
            if actual[key] != original_config[key]:
                raise RuntimeError(f'Checkpoint/preprocessor mismatch: {key}')
        validate_phoneme_map(original_config['phoneme_id_map'], actual['phoneme_id_map'], actual['num_symbols'])
        write_json(STATE / 'preprocess_ready.json', {'compatible':True})
        return
    if args.stage == 'export':
        source = STATE / 'train' / 'checkpoints' / 'last.ckpt'
        sys.argv = ['piper_train.export_onnx', str(source), str(STATE / 'custom_tatar_voice.onnx')]
        runpy.run_module('piper_train.export_onnx', run_name='__main__')
        import shutil
        shutil.copyfile(STATE / 'processed' / 'config.json', STATE / 'custom_tatar_voice.onnx.json')
        write_json(STATE / 'export_ready.json', {'model':str(STATE / 'custom_tatar_voice.onnx')})
        return
    if args.stage == 'verify':
        model = VitsModel.load_from_checkpoint(str(checkpoint), map_location='cpu', dataset=None)
        assert model.hparams.num_symbols == original_config['num_symbols']
        assert model.hparams.num_speakers == 1
        assert model.hparams.sample_rate == 22050
        actual_map = {k:list(v) for k,v in get_espeak_map().items()}
        added_symbols = validate_phoneme_map(original_config['phoneme_id_map'], actual_map, model.hparams.num_symbols)
        phrase = 'Борын-борын заманда бер авылда бер бабай яшәгән.'
        phonemes = [p for sentence in phonemize_espeak(phrase, 'tt') for p in sentence]
        ids = phoneme_ids_espeak(phonemes)
        model.eval()
        with torch.no_grad():
            output = model(torch.LongTensor([ids]), torch.LongTensor([len(ids)]), torch.FloatTensor([0.667,1.0,0.8]))
        audio = output.squeeze().cpu().numpy()
        import numpy as np
        import soundfile as sf
        if not len(audio) or not np.isfinite(audio).all():
            raise RuntimeError('Invalid synthesized audio')
        STATE.mkdir(parents=True, exist_ok=True)
        sf.write(str(STATE / 'pretrained_smoke.wav'), audio, 22050)
        write_json(STATE / 'piper_verified.json', {'torch':torch.__version__, 'lightning':pl.__version__,
                   'checkpoint_sha256':sha256(checkpoint), 'seconds':len(audio)/22050,
                   'cpu_only':True, 'sample_rate':22050, 'additional_phonemes':added_symbols})
        print('PASS: checkpoint strict load, phoneme map, and CPU synthesis', flush=True)
        return
    smoke = args.stage == 'smoke_train'
    if smoke:
        import shutil
        import subprocess
        corpus = STATE / 'smoke_corpus'
        (corpus / 'wav').mkdir(parents=True, exist_ok=True)
        phrase = 'Борын-борын заманда бер авылда бер бабай яшәгән.'
        for index in range(3):
            shutil.copyfile(STATE / 'pretrained_smoke.wav', corpus / 'wav' / f'test{index}.wav')
        (corpus / 'metadata.csv').write_text(''.join(f'test{i}|{phrase}\n' for i in range(3)), encoding='utf-8')
        subprocess.run([sys.executable, '-m', 'piper_train.preprocess', '--language', 'tt',
            '--input-dir', str(corpus), '--output-dir', str(STATE / 'smoke_processed'),
            '--dataset-format', 'ljspeech', '--single-speaker', '--sample-rate', '22050',
            '--max-workers', '1'], check=True)
    dataset = STATE / ('smoke_processed' if smoke else 'processed') / 'dataset.jsonl'
    # Fine-tuning starts a new optimizer/epoch schedule; the original checkpoint has already trained many epochs.
    model = VitsModel.load_from_checkpoint(str(checkpoint), map_location='cpu', dataset=[dataset],
        batch_size=1, num_workers=0, num_test_examples=0, validation_split=0.0 if smoke else 0.05)
    from pytorch_lightning.callbacks import ModelCheckpoint
    out = STATE / ('smoke_train' if smoke else 'train')
    callback = ModelCheckpoint(dirpath=out / 'checkpoints', save_last=True, save_top_k=1,
                               every_n_epochs=1, filename='epoch{epoch:04d}')
    class DiskReserve(pl.Callback):
        def on_train_batch_start(self, trainer, pl_module, batch, batch_idx):
            import shutil
            if shutil.disk_usage(ROOT).free < settings['minimum_free_gib'] * 1024**3:
                raise RuntimeError('Disk reserve reached before training batch')
    trainer = pl.Trainer(accelerator='cpu', devices=1, precision=32,
        max_epochs=settings['epochs'], default_root_dir=out, callbacks=[callback, DiskReserve()],
        logger=False if smoke else True, num_sanity_val_steps=0, enable_progress_bar=False,
        fast_dev_run=1 if smoke else False, log_every_n_steps=10)
    resume = out / 'checkpoints' / 'last.ckpt'
    trainer.fit(model, ckpt_path=str(resume) if resume.exists() and not smoke else None)
    if smoke:
        if trainer.global_step < 2 or not all(torch.isfinite(p).all().item() for p in model.parameters()):
            raise RuntimeError('Training smoke test did not produce finite updated GAN weights')
    write_json(STATE / ('training_smoke_passed.json' if smoke else 'training_ready.json'),
               {'completed':True, 'epoch':trainer.current_epoch, 'steps':trainer.global_step,
                'checkpoint_sha256':sha256(checkpoint)})


if __name__ == '__main__':
    main()
