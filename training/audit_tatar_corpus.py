"""Offline, non-destructive lexical/morphological triage of ASR clips."""
import argparse
import bz2
from collections import Counter, defaultdict
import csv
from datetime import datetime
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/tatar_audit'
TOKEN = re.compile(r'[^\W\d_]+(?:[-’\'][^\W\d_]+)*', re.UNICODE)
RULES = {
    'MORPH_UNKNOWN': 'Нет разбора Apertium (проверены исходный и строчный варианты). Не доказательство ошибки.',
    'FORM_UNATTESTED': 'Словоформа не найдена в скачанном частотном списке и UD; это ограничение покрытия.',
    'LEMMA_UNATTESTED': 'Ни одна возможная лемма не подтверждена доступными списками лемм, словоформ или UD.',
    'ORTHOGRAPHIC_VARIANT': 'Все разборы помечены анализатором как орфографически ненормативные.',
    'SCRIPT_MIX': 'Внутри слова смешаны латинские и кириллические буквы.',
    'GRAM_PERSON': 'Возможное рассогласование соседних личного местоимения и конечной формы глагола; нужна проверка контекста.',
    'GRAM_NUM_PLURAL': 'После количественного числительного найдено существительное только во множественном числе; возможна ошибка.',
    'ASR_REPEAT': 'Соседнее слово повторено без пунктуации; повтор может быть намеренным.',
}

def normalize(word):
    return unicodedata.normalize('NFC', word).casefold()

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def linux(path):
    return '/mnt/' + path.drive[0].lower() + path.as_posix()[2:]

def lookup(words, out):
    prefix = ROOT / '.tools/tatar_audit/usr'
    model = prefix / 'share/apertium/apertium-tat/tat.automorf.hfst'
    input_file = out / 'lookup_input.txt'
    input_file.write_text('\n'.join(sorted(words)) + '\n', encoding='utf-8')
    command = ['wsl', '-d', 'Ubuntu', '--', 'nice', '-n', '15', 'env',
               'LD_LIBRARY_PATH=' + linux(prefix / 'lib/x86_64-linux-gnu'),
               linux(prefix / 'bin/hfst-lookup'), '-q', '--pipe-mode=input',
               '-I', linux(input_file), linux(model)]
    result = subprocess.run(command, capture_output=True, timeout=600, check=True)
    (out / 'lookup_raw.txt').write_bytes(result.stdout)
    (out / 'lookup_stderr.txt').write_bytes(result.stderr)
    analyses = defaultdict(set)
    seen = set()
    for line in result.stdout.decode('utf-8').splitlines():
        cols = line.split('\t')
        if len(cols) < 3:
            continue
        word, analysis, weight = cols[:3]
        seen.add(word)
        if weight not in ('inf', 'infinity') and not analysis.endswith('+?'):
            analyses[word].add(analysis)
    if words - seen:
        raise RuntimeError(f'Missing lookup outputs for {len(words-seen)} inputs')
    return analyses, digest(model)

def lemma_parts(analysis):
    # Preserve all alternatives and bound morphemes; do not invent roots by suffix stripping.
    return [part.split('<', 1)[0] for part in analysis.split('+') if '<' in part]

def load_corpora(targets):
    lemmas = defaultdict(list)
    for path in CACHE.glob('tatcorpus2.lemmas_frequency_*.txt'):
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            match = re.match(r'\s*(\d+)\s+(.+?)\s+(<[^>]+>)\s*$', line)
            if match:
                count, lemma, tag = match.groups()
                lemmas[normalize(lemma)].append(dict(count=int(count), tag=tag, source=path.name))
    ud_forms = Counter()
    ud_lemmas = Counter()
    ud_paths = sorted((CACHE / 'ud-tatar').glob('*.conllu'))
    if not ud_paths:
        raise RuntimeError('UD Tatar-NMCTT corpus missing')
    for path in ud_paths:
        for line in path.read_text(encoding='utf-8').splitlines():
            cols = line.split('\t')
            if len(cols) == 10 and cols[0].isdigit():
                ud_forms[normalize(cols[1])] += 1
                if cols[2] != '_':
                    ud_lemmas[normalize(cols[2])] += 1
    forms = Counter()
    rows = 0
    with bz2.open(CACHE / 'tatcorpus3.words_frequency_case-sensitive.bz2', 'rt', encoding='utf-8') as stream:
        for line in stream:
            cols = line.rstrip('\n').split('\t', 1)
            if len(cols) != 2 or not cols[0].strip().isdigit():
                raise ValueError('Unexpected corpus frequency format')
            rows += 1
            key = normalize(cols[1].strip())
            if key in targets:
                forms[key] += int(cols[0])
    return lemmas, ud_forms, ud_lemmas, forms, rows

def grammar_flags(tokens, text):
    flags = []
    pronouns = {'мин': ('p1', 'sg'), 'син': ('p2', 'sg'), 'без': ('p1', 'pl'), 'сез': ('p2', 'pl')}
    numbers = set('ике өч дүрт биш алты җиде сигез тугыз ун егерме утыз кырык илле алтмыш җитмеш сиксән туксан йөз мең'.split())
    for previous, current in zip(tokens, tokens[1:]):
        if text[previous['end']:current['start']].strip():
            continue  # do not cross punctuation or other clause boundaries
        a, b = previous['normalized'], current['normalized']
        analyses = current['analyses']
        if a == b:
            flags.append((current['index'], 'ASR_REPEAT'))
        if a in pronouns and analyses:
            finite = [x for x in analyses if any(tag in x for tag in ('<v>', '<vaux>')) and re.search(r'<p[123]>', x)
                      and not any(t in x for t in ('<imp>', '<ger', '<gpr', '<prc', '<gna', '<subst>', '<nom>', '<acc>', '<dat>', '<gen>', '<loc>', '<abl>'))]
            # Only if every alternative is finite; third-person number is intentionally not checked.
            person, number = pronouns[a]
            if len(finite) == len(analyses) and not any(f'<{person}><{number}>' in x for x in finite):
                flags.append((current['index'], 'GRAM_PERSON'))
        if a in numbers and analyses and all('<n>' in x and '<pl>' in x.split('+')[0]
                and '<px' not in x and '<subst>' not in x for x in analyses):
            flags.append((current['index'], 'GRAM_NUM_PLURAL'))
    return flags

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    corpus = ROOT / 'outputs/night_pipeline/corpus'
    out = args.output or ROOT / 'outputs/corpus_review' / datetime.now().strftime('%Y%m%d-%H%M%S')
    out.mkdir(parents=True, exist_ok=False)
    (out / 'suspicious').mkdir()
    paths = sorted((corpus / 'labels').glob('*.json'))
    records = [json.loads(p.read_text(encoding='utf-8')) for p in paths]
    before = {str(p.relative_to(ROOT)): digest(p) for p in paths + [corpus / 'metadata.csv']}
    words = {variant for record in records for match in TOKEN.finditer(record['text'])
             for variant in (unicodedata.normalize('NFC', match[0]), normalize(match[0]))}
    print(f'Analyzing {len(records)} clips, {len(words)} case-sensitive word variants', flush=True)
    analyses, model_sha = lookup(words, out)
    targets = {normalize(w) for w in words}
    targets.update(normalize(lemma) for aa in analyses.values() for a in aa for lemma in lemma_parts(a))
    lemmas, ud_forms, ud_lemmas, forms, corpus_rows = load_corpora(targets)
    print(f'Corpus scan complete: {corpus_rows} wordform entries', flush=True)
    reports, token_rows, counts = [], [], Counter()
    for record in records:
        text = record['text']
        tokens = []
        for i, match in enumerate(TOKEN.finditer(text), 1):
            word = match[0]
            key = normalize(word)
            aa = sorted(analyses.get(unicodedata.normalize('NFC', word), set()) | analyses.get(key, set()))
            candidates = sorted({normalize(l) for a in aa for l in lemma_parts(a)})
            # A clitic alone must not provide evidence for an unattested lexical base.
            bases = sorted({normalize(a.split('<', 1)[0]) for a in aa})
            evidence = {l: dict(lemma_list=lemmas.get(l, []), ud_lemma=ud_lemmas[l],
                       written_corpus_surface=forms[l], ud_surface=ud_forms[l]) for l in candidates}
            confirmed = any(lemmas.get(l) or ud_lemmas[l] or forms[l] or ud_forms[l] for l in bases)
            flags = []
            if not aa:
                flags.append('MORPH_UNKNOWN')
            if not forms[key] and not ud_forms[key]:
                flags.append('FORM_UNATTESTED')
            if aa and not confirmed:
                flags.append('LEMMA_UNATTESTED')
            if aa and all('Err/Orth' in a for a in aa):
                flags.append('ORTHOGRAPHIC_VARIANT')
            if re.search('[A-Za-z]', word) and re.search('[А-Яа-яӘәӨөҮүҖҗҢңҺһЁё]', word):
                flags.append('SCRIPT_MIX')
            token = dict(index=i, start=match.start(), end=match.end(), word=word, normalized=key,
                         analyses=aa, lexical_bases=bases, lemma_evidence=evidence,
                         corpus_form_count=forms[key], ud_form_count=ud_forms[key], flags=flags)
            tokens.append(token)
        for index, code in grammar_flags(tokens, text):
            tokens[index-1]['flags'].append(code)
        # A valid morphological form absent in this finite corpus is not by itself suspicious.
        for t in tokens:
            t['review'] = any(code != 'FORM_UNATTESTED' for code in t['flags'])
            t['priority'] = 'high' if ('MORPH_UNKNOWN' in t['flags'] and 'FORM_UNATTESTED' in t['flags']) or 'SCRIPT_MIX' in t['flags'] else 'medium'
            t['mark'] = f"[T{t['index']:03d}:{','.join(t['flags'])}]" if t['review'] else ''
            counts.update(t['flags'] if t['review'] else [])
            token_rows.append(dict(uid=record['uid'], **t))
        marked = text
        for t in reversed(tokens):
            if t['review']:
                marked = marked[:t['end']] + t['mark'] + marked[t['end']:]
        issues = [t for t in tokens if t['review']]
        report = dict(**record, annotated_text=marked, tokens=tokens, issue_count=len(issues),
                      high_priority=sum(t['priority']=='high' for t in issues),
                      audio_relative=f"../../night_pipeline/corpus/wav/{record['uid']}.wav",
                      review_status='pending' if issues else 'no_automatic_flags')
        reports.append(report)
        if issues:
            (out / 'suspicious' / (record['uid'] + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            details = '\n'.join(f"{t['mark']} {t['word']} | леммы: {', '.join(t['lexical_bases']) or 'не определены'} | частота словоформы: {t['corpus_form_count']} | UD: {t['ud_form_count']}" for t in issues)
            (out / 'suspicious' / (record['uid'] + '.txt')).write_text(
                f"{record['uid']}\n{record['source']} [{record['start']:.3f}–{record['end']:.3f} сек]\n"
                f"WAV: {corpus / 'wav' / (record['uid']+'.wav')}\nВ обучении: {record['accepted']}\n\n"
                f"Исходный текст:\n{text}\n\nС пометками:\n{marked}\n\n{details}\n\nИсправленный текст (заполняется вручную):\n", encoding='utf-8')
    for name, data in [('all_clips.jsonl', reports), ('all_tokens.jsonl', token_rows)]:
        with (out / name).open('w', encoding='utf-8') as stream:
            for row in data:
                stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    with (out / 'issues.tsv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t')
        writer.writerow(['uid','token_index','word','codes','priority','lexical_bases','corpus_form_count','ud_form_count','start_char','end_char','analysis','corrections'])
        for t in token_rows:
            if t['review']:
                writer.writerow([t['uid'],t['index'],t['word'],','.join(t['flags']),t['priority'],';'.join(t['lexical_bases']),t['corpus_form_count'],t['ud_form_count'],t['start'],t['end'],';'.join(t['analyses']),''])
    summary = dict(clips=len(reports), accepted_clips=sum(r['accepted'] for r in reports),
                   suspicious_clips=sum(r['issue_count']>0 for r in reports),
                   suspicious_accepted_clips=sum(r['accepted'] and r['issue_count']>0 for r in reports),
                   tokens=len(token_rows), flagged_tokens=sum(t['review'] for t in token_rows),
                   high_priority_tokens=sum(t['review'] and t['priority']=='high' for t in token_rows),
                   codes=dict(counts), corpus_wordform_entries=corpus_rows,
                   corpus_lemma_entries=len(lemmas), ud_wordforms=len(ud_forms),
                   analyzer_sha256=model_sha, created=datetime.now().isoformat(),
                   source_files_unchanged=all(digest(ROOT / p)==h for p,h in before.items()))
    if not summary['source_files_unchanged']:
        raise RuntimeError('Source labels/metadata changed during audit')
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'input_checksums.json').write_text(json.dumps(before, indent=2), encoding='utf-8')
    manifest = json.loads((CACHE / 'sources.json').read_text())
    ud_commit = subprocess.check_output(['git','-C',str(CACHE/'ud-tatar'),'rev-parse','HEAD'], text=True).strip()
    manifest.append(dict(url='https://github.com/UniversalDependencies/UD_Tatar-NMCTT', commit=ud_commit,
                         files={p.name:digest(p) for p in (CACHE/'ud-tatar').glob('*.conllu')}))
    (out / 'sources.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    write_html(out, reports, summary)
    (out / 'README.txt').write_text('Автоматическая проверка, НЕ исправленный обучающий корпус.\n'
        'Откройте index.html: пометки, причины и аудио. Папка suspicious содержит TXT и JSON по каждому подозрительному фрагменту.\n'
        'Формат: слово[T001:КОД1,КОД2]. T001 — номер слова в исходном фрагменте; offsets в JSON — позиции символов Python, конец исключён.\n'
        'issues.tsv — одна строка на проблемное слово. all_tokens.jsonl — разборы и корпусные свидетельства всех слов, включая неподозрительные.\n'
        'Лемма/лексическая основа анализатора не равна этимологическому корню. Все неоднозначные разборы сохранены.\n'
        'Отсутствие в корпусе НЕ доказывает несуществование слова. Неизвестные имена, диалектные формы, заимствования возможны.\n'
        'Частотные списки лемм корпуса также построены с Apertium: это не независимая ручная лингвистическая проверка. UD — отдельный небольшой ручной корпус.\n'
        'Грамматика: только локальные гипотезы о лице/числе и числе после числительных; полный синтаксис, семантика и пропуски не проверяются.\n'
        'Фрагменты могут разрывать предложения; прослушайте соседние WAV. Отсутствие меток не означает правильность расшифровки.\n'
        'Исходные labels, metadata.csv, WAV и действующее обучение не изменены. Исправления в этой папке сами в обучение не попадут.\n\n'
        + '\n'.join(f'{k}: {v}' for k,v in RULES.items())
        + '\n\nИсточники правил: https://universaldependencies.org/tt/feat/Person.html ; https://universaldependencies.org/tt/pos/NUM.html ; https://ibtrussia.org/sites/default/files/files/TTR_Gram_R_web.pdf\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    print('REPORT:', out, flush=True)

def write_html(out, reports, summary):
    cards = []
    for r in sorted(reports, key=lambda r:(-r['high_priority'], -r['issue_count'],r['source'],r['start'])):
        if not r['issue_count']:
            continue
        text = r['text']; marked = ''; end=0
        for t in r['tokens']:
            marked += html.escape(text[end:t['start']])
            if t['review']:
                title = '\n'.join(RULES[c] for c in t['flags']) + '\nЛеммы: ' + ', '.join(t['lexical_bases']) + f"\nЧастота словоформы: {t['corpus_form_count']}"
                marked += f'<mark class="{t["priority"]}" title="{html.escape(title,quote=True)}">{html.escape(t["word"])}</mark><small>{html.escape(t["mark"])}</small>'
            else:
                marked += html.escape(t['word'])
            end=t['end']
        marked += html.escape(text[end:])
        cards.append(f'<article data-high="{r["high_priority"]}"><h3>{html.escape(r["uid"])}</h3><p>{html.escape(r["source"])} · {r["start"]:.1f}–{r["end"]:.1f} с · В обучении: {r["accepted"]}</p><audio controls preload="none" src="{r["audio_relative"]}"></audio><p class="text">{marked}</p><a href="suspicious/{r["uid"]}.txt">TXT для исправлений</a> · <a href="suspicious/{r["uid"]}.json">Разборы и корпусные частоты</a></article>')
    page = '<!doctype html><meta charset="utf-8"><title>Проверка татарского корпуса</title><style>body{font:17px system-ui;max-width:1100px;margin:30px auto;padding:15px;background:#f6f7fa}article{background:white;padding:18px;margin:16px 0;border:1px solid #ddd;border-radius:10px}.text{line-height:2.1}mark{background:#fff0a0}.high{background:#ffc2bc}small{font-size:10px;color:#555}input{padding:10px;width:65%}audio{width:100%}</style><h1>Подозрительные расшифровки</h1>'
    page += f'<p>Проверено {summary["clips"]} фрагментов; пометки в {summary["suspicious_clips"]}. Это гипотезы для прослушивания, а не автоматические исправления.</p><p><a href="README.txt">Условные обозначения и ограничения</a> · <a href="issues.tsv">Таблица слов</a></p><input id="query" placeholder="Поиск по слову, коду, исходнику"><label><input id="high" type="checkbox" style="width:auto"> Только высокий приоритет</label>'
    page += ''.join(cards) + '<script>function filter(){let q=document.querySelector("#query").value.toLocaleLowerCase(),h=document.querySelector("#high").checked;document.querySelectorAll("article").forEach(e=>e.hidden=!e.textContent.toLocaleLowerCase().includes(q)||(h&&+e.dataset.high===0))}document.querySelector("#query").oninput=filter;document.querySelector("#high").onchange=filter;</script>'
    (out/'index.html').write_text(page,encoding='utf-8')

if __name__ == '__main__':
    main()
