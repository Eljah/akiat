"""Resumable download of the official IS2AI/TatarTTS male training checkpoint."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import argparse
import hashlib
from pathlib import Path
import time
import requests
from night_common import ROOT, write_json, read_json

URL = 'https://drive.usercontent.google.com/download?id=163itCkfV8u3tp75VbSsS6HsSaCKLhKbO&export=download&confirm=t'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default=URL)
    parser.add_argument('--output', type=Path, default=ROOT / 'checkpoints' / 'tatartts_male.ckpt')
    args = parser.parse_args()
    url = args.url
    target = args.output.resolve()
    if not target.is_relative_to(ROOT):
        raise ValueError('Download output must remain in the repository')
    folder = target.parent
    folder.mkdir(parents=True, exist_ok=True)
    is_checkpoint = target.name == 'tatartts_male.ckpt'
    partial = folder / ('tatartts_male.ranges.partial' if is_checkpoint else target.name + '.partial')
    statefile = folder / ('download_ranges.json' if is_checkpoint else target.name + '.ranges.json')
    block = 4 * 1024**2
    with requests.get(url, headers={'Range':'bytes=0-0'}, timeout=30) as response:
        response.raise_for_status()
        if response.status_code != 206:
            raise RuntimeError('Checkpoint server does not support ranges')
        total = int(response.headers['Content-Range'].split('/')[-1])
    if target.exists() and target.stat().st_size == total:
        return
    if statefile.exists():
        state = read_json(statefile)
        if state['total'] != total:
            raise RuntimeError('Remote checkpoint size changed')
        done = set(state['done'])
    else:
        # Reuse the completed prefix from the original sequential download.
        old = list(folder.glob('tatartts_male.ckpt*.part')) if is_checkpoint else []
        if len(old) == 1 and not partial.exists():
            old[0].replace(partial)
        size = partial.stat().st_size if partial.exists() else 0
        done = set(range(size // block))
        write_json(statefile, dict(total=total, done=sorted(done)))
    count = (total + block - 1) // block
    def fetch(index):
        start, end = index * block, min((index+1)*block,total)-1
        for attempt in range(5):
            try:
                with requests.get(url, headers={'Range':f'bytes={start}-{end}'}, timeout=(15,45)) as response:
                    response.raise_for_status()
                    if response.headers.get('Content-Range') != f'bytes {start}-{end}/{total}':
                        raise ValueError('Wrong range')
                    if len(response.content) != end-start+1:
                        raise ValueError('Incomplete range')
                    return index, response.content
            except (requests.RequestException,ValueError):
                if attempt == 4: raise
                time.sleep(attempt+1)
    with partial.open('r+b' if partial.exists() else 'w+b') as stream, ThreadPoolExecutor(max_workers=4) as pool:
        # Keep only a few responses in memory at any time.
        pending = iter(i for i in range(count) if i not in done)
        jobs = {pool.submit(fetch,i) for i in [next(pending,None) for _ in range(4)] if i is not None}
        while jobs:
            future = next(as_completed(jobs))
            jobs.remove(future)
            index,data = future.result()
            stream.seek(index*block)
            stream.write(data)
            stream.flush()
            done.add(index)
            write_json(statefile, dict(total=total,done=sorted(done)))
            size = sum(min(block,total-i*block) for i in done)
            write_json(folder / 'download_status.json', dict(bytes=size,total=total,percent=round(100*size/total,1)))
            print(f'{target.name}: {size/1e6:.1f}/{total/1e6:.1f} MB',flush=True)
            index = next(pending,None)
            if index is not None: jobs.add(pool.submit(fetch,index))
        stream.truncate(total)
    partial.replace(target)
    print('Checkpoint downloaded.',flush=True)


if __name__ == '__main__':
    main()
