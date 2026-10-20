import json, copy, re, ast
from pathlib import Path
src=Path('/mnt/data/tatar_v4_work/00_START_HERE_Whisper_Tatar_v3.ipynb.json')
d=json.loads(src.read_text(encoding='utf-8'))
nb=copy.deepcopy(d)
# remove outputs/history, preserve cell IDs
for c in nb['cells']:
    c['metadata']={'id': c['id']}
    if c['cell_type']=='code':
        c['outputs']=[]
        c['execution_count']=None
nb['metadata']={"colab": {"provenance": []}, "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}, "language_info": {"name": "python"}}

nb['cells'][0]['source']=[
 '# ТАТАРСКИЙ WHISPER · ЗАПУСКАТЬ ЭТУ ВЕРСИЮ v4\n',
 '\n',
 '**v4 (20.10.2026).** Исправляет второй подтверждённый сбой: `generation_config.eos_token_id` задан как `[50257]`, но `WhisperTimeStampLogitsProcessor` в Transformers 4.57.6 ожидает `int` для среза. Теперь приводим к скалярному `50257` до любой генерации и проверяем распознавание с метками слов на 12 секундах.\n',
 '\n',
 'Предусмотрены GPU T4, `SMOKE_TEST=True` (только первые три минуты каждого файла), журнал ошибок `asr/last_error.txt`, сохранение частей и рабочая таблица без автоматического одобрения речевых сегментов.\n'
]
cell=nb['cells'][8]
code=''.join(cell['source'])
anchor="    print('УСПЕХ: Whisper создан; татарские веса:', MODEL_ID, '; словарь:', BASE_MODEL)"
assert anchor in code
patch='''    # v4: Проверяем формат EOS. В generation_config татарского чекпоинта это [50257],
    # хотя timestamps-processor (Transformers 4.57.6) выполняет срез [:eos_token_id].
    # Список в таком срезе вызывает TypeError, поэтому приводим к одному int.
    eos = recognizer.model.generation_config.eos_token_id
    if isinstance(eos, (tuple, list)):
        assert len(eos) == 1, f'Ожидается ОДИН EOS токен, а получено {eos!r}'
        eos = eos[0]
    eos = int(eos)
    assert eos == int(tokenizer.eos_token_id), (
        f'Разные EOS: checkpoint={eos}, openai/whisper-small={tokenizer.eos_token_id}')
    recognizer.model.generation_config.eos_token_id = eos
    # Базовый config уже содержит целочисленный 50257; проверим, не меняя его.
    assert int(recognizer.model.config.eos_token_id) == eos
    assert isinstance(recognizer.model.generation_config.eos_token_id, int)
    print('v4: generation_config.eos_token_id =', eos, '(int) — поддерживает timestamps')
'''
code=code.replace(anchor, patch+anchor)
old='''    test = recognizer(str(preflight_wav), generate_kwargs={'language':'tatar','task':'transcribe'})
    print('УСПЕХ: пробная расшифровка:',test.get('text','')[:500])
    ERROR_LOG.unlink(missing_ok=True)'''
new='''    test = recognizer(str(preflight_wav), generate_kwargs={'language':'tatar','task':'transcribe'})
    print('УСПЕХ: тест без меток:', test.get('text','')[:500])
    # Повторно используем короткий фрагмент, но уже с метками: именно этот режим сломался в v3.
    test_word = recognizer(str(preflight_wav), return_timestamps='word',
                           generate_kwargs={'language':'tatar','task':'transcribe'})
    assert isinstance(test_word.get('chunks'), list), 'Модель не возвратила chunks для word timestamps.'
    print('УСПЕХ: word timestamps, фрагментов:', len(test_word['chunks']))
    print('ПРИМЕР:', test_word['chunks'][:3])
    ERROR_LOG.unlink(missing_ok=True)'''
assert old in code
code=code.replace(old,new)
cell['source']=code.splitlines(keepends=True)
# Smoke test should be enabled for first run, using same timestamps mode as full
smoke=''.join(nb['cells'][10]['source'])
assert 'SMOKE_TEST = True' in smoke
smoke=smoke.replace('SMOKE_TEST = True  # сначала одна полная 3-минутная часть каждого файла', 'SMOKE_TEST = True  # v4: проба по 3 минуты; после успешного выполнения поменять на False')
# Point errors more precisely
smoke=smoke.replace("save_failure(f'{sid}:chunk:{j}:offset:{offset}',e)","save_failure(f'v4:{sid}:chunk:{j}:offset:{offset}',e)")
nb['cells'][10]['source']=smoke.splitlines(keepends=True)
# Ensure all code compiles, no stray old execution errors, nbformat validation
import nbformat
validated=nbformat.from_dict(nb)
nbformat.validate(validated)
for i,c in enumerate(nb['cells']):
    if c['cell_type']=='code':
        ast.parse(''.join(c['source']),filename=f'cell_{i}.py')
assert len(nb['cells'])==17
assert not any(c.get('outputs') for c in nb['cells'] if c['cell_type']=='code')
dst=Path('/mnt/data/00_START_HERE_Whisper_Tatar_v4.ipynb')
dst.write_text(json.dumps(nb,ensure_ascii=False,indent=1),encoding='utf-8')
print('CREATED',dst,'SIZE',dst.stat().st_size,'CODE CELLS',sum(c['cell_type']=='code' for c in nb['cells']))
print('KEY PATCH:\n',patch)
