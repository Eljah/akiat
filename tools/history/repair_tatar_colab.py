import json, pathlib, ast
src=pathlib.Path('/mnt/data/01_Whisper_Tatar_Corpus.ipynb.json')
dest=pathlib.Path('/mnt/data/01_Whisper_Tatar_Corpus_FIXED.ipynb')
n=json.loads(src.read_text('utf-8'))
fix = '''# Обход несовместимости tokenizer_config.json татарского чекпойнта с transformers v4.
# Базовая модель checkpoint: openai/whisper-small (см. карточку модели на Hugging Face).
# Токенизатор берём у базовой модели, веса распознавателя — у татарской fine-tuned.
from transformers import pipeline, AutoTokenizer, AutoFeatureExtractor, AutoConfig
MODEL_ID = 'yasalma/whisper-finetuned-tt-asr'
BASE_TOKENIZER = 'openai/whisper-small'
DEVICE = 0 if torch.cuda.is_available() else -1
DTYPE = torch.float16 if DEVICE == 0 else torch.float32
if DEVICE == -1:
    print('⚠ GPU выключен. Для обработки 2 часов звука откройте Runtime -> Change runtime type -> T4 GPU.')
# В transformers 4.x extra_special_tokens ожидается как dict. Ставим корректный
# тип явно, не загружая сломанный tokenizer_config.json из fine-tuned checkpoint.
tokenizer = AutoTokenizer.from_pretrained(BASE_TOKENIZER, extra_special_tokens={})
feature_extractor = AutoFeatureExtractor.from_pretrained(BASE_TOKENIZER)
config = AutoConfig.from_pretrained(MODEL_ID)
if len(tokenizer) != config.vocab_size:
    raise RuntimeError(f'Размер словаря не совпал: tokenizer={len(tokenizer)}, model={config.vocab_size}.')
recognizer = pipeline('automatic-speech-recognition', model=MODEL_ID,
                      tokenizer=tokenizer, feature_extractor=feature_extractor,
                      device=DEVICE, dtype=DTYPE,
                      chunk_length_s=25, stride_length_s=(4,4),
                      batch_size=1 if DEVICE == -1 else 4)
print('Whisper loaded:', MODEL_ID, 'base tokenizer:', BASE_TOKENIZER)
'''
n['cells'][8]['source']=fix.splitlines(keepends=True)
n['cells'][10]['source']=[x.replace('SMOKE_TEST = False','SMOKE_TEST = True  # только первые 3 минуты каждого файла; после успешного теста переключить в False') for x in n['cells'][10]['source']]
n['cells'][1]['source'] += ['\n**Версия FIXED:** обход несовместимого токенизатора (с использованием `openai/whisper-small`), режим `SMOKE_TEST=True`. После успешного теста установите `SMOKE_TEST=False`. Проверка синтаксиса выполнена; исполнение в Colab ещё не проверено.\n']
for c in n['cells']:
 if c['cell_type']=='code':
  c['outputs']=[]
  c['execution_count']=None
  ast.parse(''.join(c['source']))
dest.write_text(json.dumps(n,ensure_ascii=False,indent=1),encoding='utf-8')
print(dest,dest.stat().st_size,'cells',len(n['cells']))
