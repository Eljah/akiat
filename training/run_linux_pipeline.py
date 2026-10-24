"""Resume a prepared Piper corpus on Linux, then export; no Windows watcher needed."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'outputs/night_pipeline'


def status(stage, **details):
    target = STATE / 'linux_status.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(dict(stage=stage, updated=time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                                        pid=os.getpid(), **details), indent=2), encoding='utf-8')
    temporary.replace(target)


def main():
    STATE.mkdir(parents=True, exist_ok=True)
    lock = (STATE / 'linux_pipeline.lock').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if not (STATE / 'train/checkpoints/last.ckpt').is_file():
        raise RuntimeError('Migrated checkpoint is required')
    if not (STATE / 'processed/dataset.jsonl').is_file():
        raise RuntimeError('Prepared corpus is required')
    for stage, marker in [('train', 'training_ready.json'), ('export', 'export_ready.json')]:
        if (STATE / marker).exists():
            continue
        with (STATE / ('linux_' + stage + '.log')).open('a', buffering=1) as log:
            log.write('\nSTART ' + time.strftime('%Y-%m-%dT%H:%M:%S%z') + '\n')
            child = subprocess.Popen([sys.executable, '-u', str(ROOT / 'training/piper_stage.py'), stage],
                                     cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            while child.poll() is None:
                status(stage, child_pid=child.pid)
                time.sleep(15)
            if child.returncode:
                status('failed', failed_stage=stage, returncode=child.returncode)
                raise RuntimeError(f'{stage} failed: {child.returncode}')
    status('complete', model=str(STATE / 'custom_tatar_voice.onnx'))


if __name__ == '__main__':
    main()
