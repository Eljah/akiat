"""Experimental complex side-to-mid reference transfer; no corpus mutation."""
import os
os.environ['OPENBLAS_NUM_THREADS']='1'
import json
import wave
from pathlib import Path
import numpy as np
from scipy import signal,ndimage
from investigate_music_profile import ROOT,read,write,db_ratio

folder=Path((ROOT/'outputs/MUSIC_PROFILE_EXPERIMENT_LATEST.txt').read_text().strip())
with wave.open(str(folder/'source_context_native.wav')) as w:
    rate=w.getframerate();x=np.frombuffer(w.readframes(w.getnframes()),'<i2').reshape(-1,2).astype(float)/32768
n=4096;hop=1024
def stft(x):return signal.stft(x,fs=rate,nperseg=n,noverlap=n-hop)[2]
def inv(z,length):return signal.istft(z,fs=rate,nperseg=n,noverlap=n-hop)[1][:length]
def smooth(x):return ndimage.uniform_filter1d(x.real,3,axis=0)+1j*ndimage.uniform_filter1d(x.imag,3,axis=0)
a=round(15.0510204081633*rate);b=round(15.9449886621315*rate);cut=a+int(.6*(b-a))
mid=x.mean(axis=1);side=(x[:,0]-x[:,1])/2
m=stft(mid[a:cut])[:,2:-2];s=stft(side[a:cut])[:,2:-2]
cross=smooth(np.mean(m*np.conj(s),axis=1));sp=smooth(np.mean(abs(s)**2,axis=1)).real
transfer=cross/np.maximum(sp+np.max(sp)*.002,1e-15)
transfer*=np.minimum(1,10/np.maximum(abs(transfer),1e-15))
estimate=inv(stft(side)*transfer[:,None],len(mid))
results=[]
for strength in (.5,.8,1.):
    y=mid-strength*estimate;name=f'stereo_profile_{round(100*strength):03d}'
    row={'method':name,'reference_pause_attenuation_db':-db_ratio(y[a:b],mid[a:b]),
      'heldout_pause_attenuation_db':-db_ratio(y[cut:b],mid[cut:b]),
      'learned_pause_attenuation_db':-db_ratio(y[a:cut],mid[a:cut])}
    for idx in (48,49,50):
        start=(idx-48)*12*rate;end=start+12*rate
        write(folder/f'fragment_{idx:05d}'/(name+'.wav'),signal.resample_poly(y[start:end],1,2))
        write(folder/f'fragment_{idx:05d}'/(name+'_removed.wav'),signal.resample_poly(strength*estimate[start:end],1,2))
    # Stereo control: held-out reference + centered synthetic voice, then 1% L/R mismatch.
    voice=signal.resample_poly(read(folder/'control/clean_synthetic_voice.wav'),2,1)
    music=np.tile(x[cut:b],(int(np.ceil(len(voice)/(b-cut))),1))[:len(voice)]
    music*=np.sqrt(np.mean(voice**2))/np.sqrt(np.mean(music.mean(axis=1)**2))*10**(-15/20)
    for imbalance in (0,.01):
        mix=music+np.stack([voice*(1+imbalance),voice*(1-imbalance)],axis=1)
        mm=mix.mean(axis=1);ss=(mix[:,0]-mix[:,1])/2
        out=mm-strength*inv(stft(ss)*transfer[:,None],len(mm))
        row[f'control_snr_after_imbalance_{imbalance}_db']=db_ratio(voice,out-voice)
    results.append(row)
(folder/'stereo_results.json').write_text(json.dumps({'metrics':results,'reference_transfer':{
  'real':transfer.real.tolist(),'imag':transfer.imag.tolist(),'fft_size':n,'hop':hop,'rate':rate,
  'train_absolute_seconds':[591.0510204081633,576+cut/rate],'gain_cap':10,'regularization':.002}},indent=2))
print(json.dumps(results,indent=2))
