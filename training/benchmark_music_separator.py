"""Offline CPU benchmark using existing local Demucs weights; no editor mutation.
Run from WSL with .tools/voice-clean-venv/bin/python after profile investigation.
"""
import os
for key in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS'):
    os.environ[key]='1'
os.nice(10)
import json
import time
from pathlib import Path
import numpy as np
import torch
from scipy.signal import resample_poly
from demucs.pretrained import get_model
from demucs.apply import apply_model
from investigate_music_profile import read,write,db_ratio,ROOT,RATE

torch.set_num_threads(1)
torch.set_num_interop_threads(1)
torch.manual_seed(42)
folder=Path((ROOT/'outputs/MUSIC_PROFILE_EXPERIMENT_LATEST.txt').read_text().strip().replace('D:\\Java\\akiat',str(ROOT)).replace('\\','/'))
model=get_model('955717e8',repo=ROOT/'.tools/music-clean-models/hub/checkpoints').cpu().eval()
def separate(x):
    y=resample_poly(x,2,1)
    t=torch.from_numpy(np.stack([y,y]).astype('float32'))
    ref=t.mean(0);mean=ref.mean();std=ref.std();t=(t-mean)/std
    start=time.monotonic()
    with torch.inference_mode():
        stems=apply_model(model,t[None],device='cpu',shifts=0,split=True,overlap=.25,segment=7,progress=False,num_workers=0)[0]
    stems=stems*std+mean
    voice=stems[model.sources.index('vocals')].mean(0).numpy()
    print('Separated',len(x)/RATE,'seconds in',round(time.monotonic()-start,2),'seconds',flush=True)
    return resample_poly(voice,1,2)[:len(x)]

original=ROOT/'outputs/night_pipeline/corpus/wav'
clips=[read(original/f'acdd51b547eaf8f9_{i:05d}.wav') for i in (48,49,50)]
allx=np.concatenate(clips);allvoice=separate(allx)
metrics=[];offset=0
for idx,x in zip((48,49,50),clips):
    voice=allvoice[offset:offset+len(x)];offset+=len(x)
    for strength in (.5,.75,1.):
        y=x-strength*(x-voice)
        name=f'demucs_{int(strength*100):03d}'
        write(folder/f'fragment_{idx:05d}'/(name+'.wav'),y)
        write(folder/f'fragment_{idx:05d}'/(name+'_removed.wav'),x-y)
        if idx==49:
            metrics.append({'method':name,'reference_pause_attenuation_db':-db_ratio(y[67275:86987],x[67275:86987])})
control=folder/'control';x=read(control/'mixture.wav');clean=read(control/'clean_synthetic_voice.wav')
voice=separate(x);clean_processed=separate(clean)
for strength,row in zip((.5,.75,1.),metrics):
    y=x-strength*(x-voice);clean_y=clean-strength*(clean-clean_processed)
    row['control_snr_after_db']=db_ratio(clean,y-clean)
    row['voice_only_error_snr_db']=db_ratio(clean,clean_y-clean)
    write(control/(row['method']+'.wav'),y)
(folder/'separator_results.json').write_text(json.dumps({'model':'955717e8','context_clips':[48,49,50],'metrics':metrics},indent=2))
print(json.dumps(metrics,indent=2),flush=True)
