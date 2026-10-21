"""Download published morphology and corpus statistics; extract tools onto D:, not WSL root."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import requests

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / '.cache/tatar_audit'
TOOLS = ROOT / '.tools/tatar_audit'
BASE = 'https://search.corpus.tatar/stat/'
POS = 'n np v vaux adj adv prn post postadv num cnjadv cnjcoo cnjsub ij cop det ideo abbr'.split()
URLS = [BASE + f'tatcorpus2.lemmas_frequency_{p}.txt' for p in POS] + [
    BASE + 'tatcorpus3.words_frequency_case-sensitive.bz2',
    'https://apertium.projectjj.com/apt/nightly/pool/main/a/apertium-tat/apertium-tat_0.1.0+g1451~18fe9e45-2~sid1_all.deb',
    'https://archive.ubuntu.com/ubuntu/pool/universe/h/hfst/hfst_3.15.4-2_amd64.deb',
    'https://archive.ubuntu.com/ubuntu/pool/universe/h/hfst/libhfst53_3.15.4-2_amd64.deb',
]

def fetch(url):
    target = DATA / url.rsplit('/', 1)[-1]
    if not target.exists():
        with requests.get(url, stream=True, timeout=(20, 120)) as response:
            response.raise_for_status()
            temp = target.with_suffix(target.suffix + '.partial')
            with temp.open('wb') as stream:
                for chunk in response.iter_content(256 * 1024):
                    stream.write(chunk)
            temp.replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f'Ready: {target.name} ({target.stat().st_size} bytes)', flush=True)
    return dict(url=url, file=target.name, bytes=target.stat().st_size, sha256=digest)

def main():
    DATA.mkdir(parents=True, exist_ok=True)
    TOOLS.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        manifest = list(pool.map(fetch, URLS))
    (DATA / 'sources.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    def wsl(path):
        return '/mnt/' + path.drive[0].lower() + path.as_posix()[2:]
    for package in DATA.glob('*.deb'):
        subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'dpkg-deb', '-x', wsl(package), wsl(TOOLS)], check=True)
    ud = DATA / 'ud-tatar'
    if not ud.exists():
        subprocess.run(['git', 'clone', 'https://github.com/UniversalDependencies/UD_Tatar-NMCTT.git', str(ud)], check=True)
        subprocess.run(['git', '-C', str(ud), 'checkout', '56a4abb623800b860060b38170198c26be53d654'], check=True)

if __name__ == '__main__':
    main()
