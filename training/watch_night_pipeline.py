"""Wait for all MP3 transcripts, then prepare, preprocess, train and export once."""
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback

from night_common import ROOT, STATE, complete_sources, read_json, write_json, sha256


def status(stage, **details):
    write_json(STATE / 'status.json', dict(stage=stage, updated=time.strftime('%Y-%m-%dT%H:%M:%S%z'), **details))


def wsl_command(settings, stage):
    return ['wsl.exe', '-d', settings['wsl_distribution'], '--',
            '/mnt/d/Java/akiat/.tools/piper-venv/bin/python', '-u',
            '/mnt/d/Java/akiat/training/piper_stage.py', stage]


def run_stage(settings, stage, command):
    status(stage, command=command)
    with (STATE / f'{stage}.log').open('a', encoding='utf-8') as log:
        log.write(f'\nSTART {time.strftime("%Y-%m-%dT%H:%M:%S%z")}\n')
        log.flush()
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        write_json(STATE / 'child.json', dict(pid=process.pid, stage=stage))
        while process.poll() is None:
            if shutil.disk_usage(ROOT).free < settings['minimum_free_gib'] * 1024**3:
                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
                raise RuntimeError('Disk reserve reached; child stopped')
            time.sleep(5)
        if process.returncode:
            raise RuntimeError(f'{stage} failed with code {process.returncode}; see {stage}.log')


def main():
    # Windows mutex prevents two watchers from launching duplicate training.
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    mutex = kernel.CreateMutexW(None, False, 'Local\\akiat_night_pipeline_D_Java_akiat')
    if not mutex:
        raise ctypes.WinError(ctypes.get_last_error())
    if ctypes.get_last_error() == 183:
        raise RuntimeError('Night watcher is already running')
    STATE.mkdir(parents=True, exist_ok=True)
    write_json(STATE / 'watcher.json', dict(pid=os.getpid(), started=time.strftime('%Y-%m-%dT%H:%M:%S%z')))
    try:
        while True:
            settings = read_json(ROOT / 'training' / 'night_pipeline.json')
            if not settings.get('enabled'):
                status('paused', detail='disabled in night_pipeline.json')
                time.sleep(30)
                continue
            try:
                asr = read_json(ROOT / 'asr' / 'cpu_status.json')
            except (OSError, ValueError):
                asr = {}
            if asr.get('stage') != 'needs_review':
                status('waiting_for_asr', asr_stage=asr.get('stage', 'unavailable'))
                time.sleep(settings['poll_seconds'])
                continue
            try:
                sources = complete_sources()
            except (ValueError, OSError, KeyError) as exc:
                status('waiting_for_complete_transcripts', detail=str(exc))
                time.sleep(settings['poll_seconds'])
                continue
            previous_sources = STATE / 'sources.json'
            if previous_sources.exists() and read_json(previous_sources) != sources:
                raise RuntimeError('Source set changed; existing experimental run must not be reused')
            write_json(previous_sources, sources)
            break
        if not settings.get('experimental_unreviewed'):
            raise RuntimeError('Unreviewed experimental mode is not enabled')
        if not (STATE / 'corpus_ready.json').exists():
            run_stage(settings, 'preparing_corpus', [sys.executable, '-u', str(ROOT / 'training' / 'prepare_experimental.py')])
        while not all((STATE / name).exists() for name in ('piper_verified.json', 'training_smoke_passed.json')):
            status('waiting_for_piper_verification', detail='Requires successful checkpoint synthesis and one-step training test')
            time.sleep(settings['poll_seconds'])
        verified = read_json(STATE / 'piper_verified.json')
        if sha256(ROOT / settings['checkpoint']) != verified['checkpoint_sha256']:
            raise RuntimeError('Checkpoint changed after verification; rerun Piper verification')
        if read_json(STATE / 'training_smoke_passed.json').get('checkpoint_sha256') != verified['checkpoint_sha256']:
            raise RuntimeError('Training smoke test must use the verified checkpoint')
        for stage, marker in [('preprocess','preprocess_ready.json'), ('train','training_ready.json'), ('export','export_ready.json')]:
            if not (STATE / marker).exists():
                run_stage(settings, stage, wsl_command(settings, stage))
        status('complete', model=str(STATE / 'custom_tatar_voice.onnx'), experimental=True)
    except Exception:
        (STATE / 'last_error.txt').write_text(traceback.format_exc(), encoding='utf-8')
        status('failed', detail=traceback.format_exc().splitlines()[-1])
        raise
    finally:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(mutex)


if __name__ == '__main__':
    main()
