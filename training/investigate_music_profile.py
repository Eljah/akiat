"""Offline, reproducible reference-music experiment. Never writes editor recipes.

Run with the project Windows .venv. Outputs are private/ignored under outputs/.
Synthetic controls measure a known voice plus held-out reference music; their
scores do NOT establish intelligibility or generalization to other melodies.
"""
import os
for key in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS'):
    os.environ[key] = '1'
import hashlib
import json
from pathlib import Path
import wave
from datetime import datetime
import numpy as np
from scipy import signal, ndimage
from manual_equalizer import filter_pcm

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT/'outputs/corpus_review/20261021-134447'
UID = 'acdd51b547eaf8f9_00049_copy_77355145b99446689f67d8b76266e0a0'
N, H = 2048, 512
RATE = 22050

def read(path):
    with wave.open(str(path)) as w:
        assert w.getsampwidth() == 2 and w.getframerate() == RATE
        return np.frombuffer(w.readframes(w.getnframes()), '<i2').reshape(-1,w.getnchannels()).mean(axis=1)/32768

def write(path, x):
    with wave.open(str(path),'wb') as w:
        w.setparams((1,2,RATE,0,'NONE','not compressed'))
        w.writeframes(np.clip(np.rint(x*32768),-32768,32767).astype('<i2').tobytes())

def stft(x):
    return signal.stft(x, fs=RATE, window='hann', nperseg=N, noverlap=N-H)[2]

def inverse(z, length):
    return signal.istft(z, fs=RATE, window='hann', nperseg=N, noverlap=N-H)[1][:length]

def db_ratio(a,b):
    return float(10*np.log10((np.sum(a*a)+1e-20)/(np.sum(b*b)+1e-20)))

def dictionary(v, rank):
    rng=np.random.default_rng(42)
    w=rng.random((len(v),rank))+.1
    h=rng.random((rank,v.shape[1]))+.1
    for _ in range(100):
        h *= (w.T@(v/np.maximum(w@h,1e-12)))/np.maximum(w.sum(axis=0)[:,None],1e-12)
        w *= ((v/np.maximum(w@h,1e-12))@h.T)/np.maximum(h.sum(axis=1)[None,:],1e-12)
        norm=np.maximum(w.sum(axis=0),1e-12);w/=norm;h*=norm[:,None]
    return w

def nmf_mask(z, noise_basis, strength, floor_db=-18):
    # Fixed reference dictionary + target-specific residual dictionary (speech).
    # KL-divergence multiplicative updates; deterministic initialization.
    v=np.maximum(abs(z),1e-12);rng=np.random.default_rng(17)
    k=noise_basis.shape[1]
    w=np.concatenate([noise_basis, v[:,rng.choice(v.shape[1],16,replace=False)]+1e-8],axis=1)
    w/=w.sum(axis=0);h=np.full((w.shape[1],v.shape[1]),np.mean(v.sum(axis=0))/w.shape[1])
    for _ in range(100):
        h *= (w.T@(v/np.maximum(w@h,1e-12)))/np.maximum(w.sum(axis=0)[:,None],1e-12)
        ratio=v/np.maximum(w@h,1e-12)
        w[:,k:] *= (ratio@h[k:].T)/np.maximum(h[k:].sum(axis=1)[None,:],1e-12)
        norm=np.maximum(w[:,k:].sum(axis=0),1e-12);w[:,k:]/=norm;h[k:]*=norm[:,None]
    music=w[:,:k]@h[:k];speech=w[:,k:]@h[k:]
    mask=speech**2/np.maximum(speech**2+(strength*music)**2,1e-20)
    return np.maximum(10**(floor_db/20),ndimage.gaussian_filter(mask,(.6,.7)))

def main():
    out=ROOT/'outputs'/('music_profile_experiment_'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    out.mkdir(parents=True)
    edit=json.loads((REPORT/'desktop_edits'/f'{UID}.json').read_text(encoding='utf-8-sig'))
    original=Path(edit['original_audio']);x=read(original)
    a,b=edit['trim_start_frame'],edit['trim_end_frame_exclusive'];ref=x[a:b]
    split=int(.6*len(ref));learn,held=ref[:split],ref[split:]
    write(out/'reference_music.wav',ref)
    # Frequency and time structure of sample. Interior frames avoid padding bias.
    z=stft(ref);v=abs(z[:,2:-2]);power=np.mean(v*v,axis=1);hz=np.fft.rfftfreq(N,1/RATE)
    peaks,_=signal.find_peaks(power,distance=3)
    peaks=sorted(peaks,key=lambda i:power[i],reverse=True)[:12]
    energy=power.sum();u,s,vh=np.linalg.svd(v,full_matrices=False)
    features={'reference_seconds':len(ref)/RATE,'rms_dbfs':db_ratio(ref,np.ones_like(ref)),
      'peaks_hz':[round(float(hz[i]),1) for i in peaks],
      'energy_below_500_fraction':float(power[hz<500].sum()/energy),
      'energy_below_1500_fraction':float(power[hz<1500].sum()/energy),
      'rank1_energy_fraction':float(s[0]**2/np.sum(s*s)),
      'frame_rms_90_10_db':float(20*np.log10(np.percentile(np.linalg.norm(v,axis=0),90)/np.percentile(np.linalg.norm(v,axis=0),10)))}
    # Exact current production profile, learned only on first 60% for control.
    win=np.sqrt(.5-.5*np.cos(2*np.pi*np.arange(N)/N))
    p=np.mean([abs(np.fft.rfft(learn[i:i+N]*win))**2 for i in range(0,len(learn)-N+1,H)],axis=0)
    base=dict(method='profile-suppression-v1',low_hz=0,high_hz=RATE/2,gain_db=-18,
      smoothing_hz=30,profile_level_db=0,profile=dict(sample_rate=RATE,power=p.tolist()))
    noise_power=np.mean(abs(stft(learn)[:,2:-2])**2,axis=1)[:,None]
    bases={k:dictionary(abs(stft(learn)[:,2:-2]),k) for k in (2,6)}
    def process(y,name):
        if name.startswith('current'):
            recipe={**base,'profile_level_db':int(name.split('_')[1])}
            pcm=np.clip(np.rint(y*32768),-32768,32767).astype('<i2').tobytes()
            return np.frombuffer(filter_pcm(pcm,RATE,1,recipe),'<i2').astype(float)/32768,None
        spectrum=stft(y)
        if name.startswith('wiener'):
            strength=float(name.split('_')[1]);mask=np.maximum(.126,1-strength*noise_power/np.maximum(abs(spectrum)**2,1e-20))
            mask=ndimage.gaussian_filter(mask,(.6,.7))
        else:
            _,k,strength=name.split('_');mask=nmf_mask(spectrum,bases[int(k)],float(strength))
        return inverse(spectrum*mask,len(y)),mask
    names=['current_0','current_6','current_12','wiener_1','wiener_4','nmf_2_1','nmf_2_2','nmf_6_1','nmf_6_2']
    voicepath=ROOT/'outputs/reviewed_20261109-195807/audio/kuyan_belen_yoldyz_epoch99_full.wav'
    voice=read(voicepath)[:RATE*12];voice*=.06/max(np.sqrt(np.mean(voice**2)),1e-9)
    # Periodically tiled *held-out* music: explicitly synthetic, not natural music.
    music=np.tile(held,int(np.ceil(len(voice)/len(held))))[:len(voice)]
    music*=np.sqrt(np.mean(voice**2))/np.sqrt(np.mean(music**2))*10**(-15/20)
    mixed=voice+music;control=out/'control';control.mkdir()
    write(control/'clean_synthetic_voice.wav',voice);write(control/'known_music.wav',music);write(control/'mixture.wav',mixed)
    rows=[]
    for name in names:
        y,mask=process(mixed,name);row={'method':name,'control_snr_before_db':db_ratio(voice,music),
          'control_snr_after_db':db_ratio(voice,y-voice)}
        alone,_=process(voice,name);row['voice_only_error_snr_db']=db_ratio(voice,alone-voice)
        # Held-out music-only suppression is independent of target-derived speech bases.
        music_alone,_=process(music,name);row['music_only_attenuation_db']=-db_ratio(music_alone,music)
        if mask is not None:
            passed_voice=inverse(stft(voice)*mask,len(voice));passed_music=inverse(stft(music)*mask,len(music))
            row['music_attenuation_in_mixture_db']=-db_ratio(passed_music,music)
            row['voice_distortion_in_mixture_snr_db']=db_ratio(voice,passed_voice-voice)
        write(control/(name+'.wav'),y);rows.append(row)
        print(json.dumps(row),flush=True)
    for idx in (48,49,50):
        path=original.with_name(f'acdd51b547eaf8f9_{idx:05d}.wav');target=read(path)
        folder=out/f'fragment_{idx:05d}';folder.mkdir();write(folder/'00_original.wav',target)
        for name in names:
            y,_=process(target,name);write(folder/(name+'.wav'),y);write(folder/(name+'_removed.wav'),target-y)
            if idx==49:
                next(r for r in rows if r['method']==name)['reference_pause_attenuation_db']=-db_ratio(y[a:b],target[a:b])
        print('Done real fragment',idx,flush=True)
    metadata={'features':features,'measurements':rows,'source':str(original),
      'source_sha256':hashlib.sha256(original.read_bytes()).hexdigest(),'reference_uid':UID,
      'reference_frames':[a,b],'control_voice':str(voicepath),'control_warning':'Synthetic TTS voice + tiled held-out music, not clean ground truth for original recording.',
      'methods':names,'seed':42,'rate':RATE,'fft_size':N,'hop':H}
    (out/'results.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
    (ROOT/'outputs/MUSIC_PROFILE_EXPERIMENT_LATEST.txt').write_text(str(out),encoding='utf-8')
    print('OUTPUT',out,flush=True)

if __name__=='__main__':main()
