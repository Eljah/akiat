import json, ast, pathlib, textwrap, nbformat
src = pathlib.Path('/mnt/data/current_drive/01_Whisper_Tatar_Corpus.ipynb.json')
out = pathlib.Path('/mnt/data/00_START_HERE_Whisper_Tatar_v3.ipynb')
n=json.loads(src.read_text(encoding='utf-8'))

def setc(i,s):
    s=textwrap.dedent(s).strip('\n')+'\n'
    ast.parse(s)
    n['cells'][i]['source']=s.splitlines(True)

n['cells'][0]['source']=['# ТАТАРСКИЙ WHISPER · ЗАПУСКАТЬ ЭТУ ВЕРСИЮ v3\n','\n','**v3 (2026-10-20).** Исправлена несовместимость токенизатора, добавлена проверка GPU, короткая проба модели и запись ошибок/прогресса в Google Drive. Сначала режим SMOKE_TEST (первые 3 минуты каждого файла).\n']

setc(2,'''\
import os, sys, time, json, csv, re, shutil, subprocess, math, traceback
from pathlib import Path
import torch
print('Python:', sys.version.split()[0], 'Torch:', torch.__version__)
print('CUDA:', torch.cuda.is_available())
if torch.cuda.is_available():
    print('GPU:', torch.cuda.get_device_name(0))
    if shutil.which('nvidia-smi'):
        subprocess.run(['nvidia-smi'], check=False)
else:
    print('⚠ GPU не подключён: для долгого распознавания выбери Runtime -> Change runtime type -> T4 GPU.')
# Чтобы по ошибке не распознавать 2 часа на CPU, проверяем GPU заранее.
REQUIRE_GPU = True
if REQUIRE_GPU and not torch.cuda.is_available():
    raise RuntimeError('GPU не подключён. Выбери Runtime → Change runtime type → T4 GPU, затем запусти все ячейки снова.')
''')
setc(3,'''\
# Зафиксирована совместимая ветка Transformers 4.x. Версия 5.x меняет API tokenizer.
subprocess.run([sys.executable, '-m', 'pip', 'install', '-q',
                'transformers==4.57.6', 'accelerate>=1.0,<2', 'soundfile>=0.12',
                'pandas>=2.0', 'librosa>=0.10', 'tqdm', 'huggingface_hub>=0.26,<2'], check=True)
subprocess.run(['ffmpeg','-version'], check=True, stdout=subprocess.DEVNULL)
import transformers
print('Transformers:', transformers.__version__)
assert transformers.__version__=='4.57.6', 'Потребуется Runtime -> Restart session после обновления зависимостей.'
''')
# cell 4 retains drive mount; append diagnostic helper
cell4=''.join(n['cells'][4]['source'])
cell4+='''\n# Ошибка записывается в Drive даже если Colab не сохранил вывод ячейки.
ERROR_LOG = ASR/'last_error.txt'
PROGRESS = ASR/'progress.json'
def save_failure(stage, exc):
    report=f'STAGE: {stage}\\nERROR: {type(exc).__name__}: {exc}\\n\\n{traceback.format_exc()}'
    ERROR_LOG.write_text(report, encoding='utf-8')
    print(report[-5000:])
'''
setc(4,cell4)
setc(8,'''\
# Важный фикс: weights берутся из татарской fine-tuned модели,
# tokenizer и feature_extractor — из исходного openai/whisper-small.
# Нельзя передавать в pipeline только MODEL_ID: config токенизатора вызывает AttributeError.
from transformers import pipeline, AutoTokenizer, AutoFeatureExtractor, AutoConfig
MODEL_ID = 'yasalma/whisper-finetuned-tt-asr'
BASE_MODEL = 'openai/whisper-small'
DEVICE = 0 if torch.cuda.is_available() else -1
DTYPE = torch.float16 if DEVICE == 0 else torch.float32
try:
    # Явно переопределяем v5-style extra_special_tokens: transformers 4.x ждёт словарь.
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, use_fast=True, extra_special_tokens={})
    feature_extractor = AutoFeatureExtractor.from_pretrained(BASE_MODEL)
    cfg = AutoConfig.from_pretrained(MODEL_ID)
    assert len(tokenizer) == cfg.vocab_size, f'Несовпадение словаря: {len(tokenizer)} != {cfg.vocab_size}'
    recognizer = pipeline('automatic-speech-recognition', model=MODEL_ID,
          tokenizer=tokenizer, feature_extractor=feature_extractor,
          device=DEVICE, dtype=DTYPE,
          chunk_length_s=25, stride_length_s=(4, 4), batch_size=1)
    print('УСПЕХ: Whisper создан; татарские веса:', MODEL_ID, '; словарь:', BASE_MODEL)
except Exception as e:
    save_failure('load_model_and_tokenizer',e)
    raise

# Маленький реальный прогон выявляет проблемы раньше, чем начнётся обработка 2 часов.
PREFLIGHT_SECONDS = 12
try:
    sid = 'audio_02' if 'audio_02' in sources else next(iter(sources))
    preflight_wav = WORK/'preflight_12sec.wav'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y',
                    '-i',str(sources[sid]),'-t',str(PREFLIGHT_SECONDS),
                    '-ac','1','-ar','16000',str(preflight_wav)],check=True)
    test = recognizer(str(preflight_wav), generate_kwargs={'language':'tatar','task':'transcribe'})
    print('УСПЕХ: пробная расшифровка:',test.get('text','')[:500])
    ERROR_LOG.unlink(missing_ok=True)
except Exception as e:
    save_failure('preflight_recognition',e)
    raise
''')
setc(10,'''\
SMOKE_TEST = True  # сначала одна полная 3-минутная часть каждого файла
PART_LENGTH_SEC = 180
GENERATE_WORD_TIMESTAMPS = True

def extract_part(src,start,duration,out):
    subprocess.run(['ffmpeg','-hide_banner','-nostats','-loglevel','error','-y',
                    '-ss',str(start),'-i',str(src),'-t',str(duration),'-vn',
                    '-ac','1','-ar','16000','-c:a','pcm_s16le',str(out)],check=True)

for sid,src in sources.items():
    dur,_,_=probe_duration(src)
    task_dir=ASR/sid
    task_dir.mkdir(parents=True,exist_ok=True)
    count=math.ceil(dur/PART_LENGTH_SEC)
    for j in tqdm(range(count),desc=src.name[:40]):
        if SMOKE_TEST and j>0: break
        target=task_dir/f'part_{j:04d}.json'
        if target.exists(): continue
        offset=j*PART_LENGTH_SEC
        chunk_sec=min(PART_LENGTH_SEC,dur-offset)
        tmp=WORK/'current_asr_part.wav'
        try:
            extract_part(src,offset,chunk_sec,tmp)
            try:
                result=recognizer(str(tmp),return_timestamps='word' if GENERATE_WORD_TIMESTAMPS else True,
                       generate_kwargs={'language':'tatar','task':'transcribe'})
            except (ValueError,NotImplementedError) as e:
                if not GENERATE_WORD_TIMESTAMPS: raise
                print('Временные метки слов не поддерживаются, используем сегменты:',str(e)[:300])
                result=recognizer(str(tmp),return_timestamps=True,
                       generate_kwargs={'language':'tatar','task':'transcribe'})
            data={'source_id':sid,'source_name':src.name,'offset_sec':offset,
                  'chunk_sec':chunk_sec,'model':MODEL_ID,'result':result}
            # Временный файл в том же каталоге, атомарная запись с Google Drive best-effort.
            temp_target=target.with_suffix('.writing')
            temp_target.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
            temp_target.replace(target)
            progress={key:len(list((ASR/key).glob('part_*.json'))) for key in sources}
            PROGRESS.write_text(json.dumps(progress,ensure_ascii=False,indent=2),encoding='utf-8')
            ERROR_LOG.unlink(missing_ok=True)
        except Exception as e:
            save_failure(f'{sid}:chunk:{j}:offset:{offset}',e)
            raise
        finally:
            tmp.unlink(missing_ok=True)
    print(sid,'готово частей:',len(list(task_dir.glob('part_*.json'))),'из',count)
''')
# append CSV merge to original cell12, replacing old save block, so full run extends partial reviewed dataset
c12=''.join(n['cells'][12]['source'])
old="""if not draft.exists():
    df.to_csv(draft,index=False,encoding='utf-8-sig')
    print('Создан файл для ручной вычитки:',draft)
else:
    print('Существующая ВЫЧИТКА НЕ ПЕРЕЗАПИСАНА:',draft)"""
new="""if draft.exists():
    # После SMOKE_TEST не теряем новые записи при полном запуске.
    # Ручные правки к уже существующим временным сегментам сохраняются.
    old_df=pd.read_csv(draft).fillna('')
    def segment_key(row):
        return (str(row['source_id']),round(float(row['start']),2),round(float(row['end']),2))
    old_keys={segment_key(r) for _,r in old_df.iterrows()}
    added=df[[segment_key(r) not in old_keys for _,r in df.iterrows()]]
    merged=pd.concat([old_df,added],ignore_index=True).sort_values(['source_id','start'])
    merged.to_csv(draft,index=False,encoding='utf-8-sig')
    print('Сохранены ручные правки, добавлено новых сегментов:',len(added))
else:
    df.to_csv(draft,index=False,encoding='utf-8-sig')
    print('Создан файл для ручной вычитки:',draft)"""
assert old in c12
setc(12,c12.replace(old,new))
# Clear stale execution/error outputs and huge widget state
for cell in n['cells']:
    if cell['cell_type']=='code':
        cell['outputs']=[];cell['execution_count']=None
        for key in ['executionInfo','outputId','referenced_widgets']:
            cell['metadata'].pop(key,None)
        ast.parse(''.join(cell['source']))
n['metadata'].pop('widgets',None)
nbformat.validate(nbformat.from_dict(n))
out.write_text(json.dumps(n,ensure_ascii=False,indent=1),encoding='utf-8')
print(out, out.stat().st_size, 'bytes; cells=',len(n['cells']))
