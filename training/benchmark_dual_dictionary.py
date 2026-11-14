"""Fixed music and speech dictionaries, with disjoint TTS train/control time."""
import os
os.environ['OPENBLAS_NUM_THREADS']='1'
import json
from pathlib import Path
import numpy as np
from scipy import ndimage
from investigate_music_profile import ROOT,read,write,stft,inverse,dictionary,db_ratio,RATE

folder=Path((ROOT/'outputs/MUSIC_PROFILE_EXPERIMENT_LATEST.txt').read_text().strip())
meta=json.loads((folder/'results.json').read_text())
ref=read(folder/'reference_music.wav');learn=ref[:int(.6*len(ref))]
full=read(Path(meta['control_voice']));speech_train=full[12*RATE:40*RATE]
assert len(speech_train)>RATE*2
noise_w=dictionary(abs(stft(learn)[:,2:-2]),6)
voice_w=dictionary(abs(stft(speech_train)[:,2:-2]),32)
w=np.concatenate([noise_w,voice_w],axis=1)
def mask_for(x,strength):
    z=stft(x);v=np.maximum(abs(z),1e-12)
    h=np.full((w.shape[1],v.shape[1]),np.mean(v.sum(axis=0))/w.shape[1])
    for _ in range(120):
        h*=(w.T@(v/np.maximum(w@h,1e-12)))/np.maximum(w.sum(axis=0)[:,None],1e-12)
    n=w[:,:6]@h[:6];s=w[:,6:]@h[6:]
    mask=np.maximum(10**(-18/20),ndimage.gaussian_filter(s*s/np.maximum(s*s+(strength*n)**2,1e-20),(.6,.7)))
    return inverse(z*mask,len(x)),mask
rows=[];clean=read(folder/'control/clean_synthetic_voice.wav');music=read(folder/'control/known_music.wav');mix=read(folder/'control/mixture.wav')
for strength in (.5,1.,2.):
    name=f'dual_dictionary_{strength:g}';y,mask=mask_for(mix,strength);alone,_=mask_for(clean,strength)
    row={'method':name,'control_snr_after_db':db_ratio(clean,y-clean),
         'voice_only_error_snr_db':db_ratio(clean,alone-clean),
         'music_attenuation_in_mixture_db':-db_ratio(inverse(stft(music)*mask,len(music)),music),
         'voice_distortion_in_mixture_snr_db':db_ratio(clean,inverse(stft(clean)*mask,len(clean))-clean)}
    write(folder/'control'/(name+'.wav'),y)
    for idx in (48,49,50):
        sub=folder/f'fragment_{idx:05d}';x=read(sub/'00_original.wav');y,_=mask_for(x,strength)
        write(sub/(name+'.wav'),y);write(sub/(name+'_removed.wav'),x-y)
        if idx==49:row['reference_pause_attenuation_db']=-db_ratio(y[67275:86987],x[67275:86987])
    rows.append(row);print(json.dumps(row),flush=True)
np.savez(folder/'dual_dictionary_profile.npz',noise=noise_w,speech=voice_w)
(folder/'dual_dictionary_results.json').write_text(json.dumps({'metrics':rows,'speech_dictionary_source':meta['control_voice'],
  'speech_dictionary_seconds':[12,min(40,len(full)/RATE)],'music_dictionary_seconds':[0,len(learn)/RATE],
  'caveat':'Synthetic control uses same TTS voice, disjoint time; it does not establish fidelity of the real narrator.'},indent=2))
