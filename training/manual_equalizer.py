"""Manual EQ recipe only; numerical counterpart of EqualizerData.java."""
import hashlib
import json
import math
from pathlib import Path
import wave

SIZE, HOP = 2048, 512


def validate(recipe, rate):
    if recipe.get('method') not in ('manual-stft-band-v1','profile-suppression-v1'):
        raise ValueError('Unknown equalizer method')
    low, high, db = (recipe[k] for k in ('low_hz','high_hz','gain_db'))
    if not all(math.isfinite(v) for v in (low,high,db)) or not 0 <= low < high <= rate/2 or not -60 <= db <= 0:
        raise ValueError('Invalid equalizer frequency/gain')
    if recipe['method']=='profile-suppression-v1':
        profile=recipe['profile'];power=profile['power'];level=recipe['profile_level_db'];smooth=recipe['smoothing_hz']
        if profile['sample_rate']!=rate or len(power)!=SIZE//2+1 or not all(math.isfinite(v) and v>=0 for v in power):
            raise ValueError('Invalid background profile or sample rate mismatch')
        if not math.isfinite(level) or not -12<=level<=18 or not math.isfinite(smooth) or not 0<=smooth<=200:
            raise ValueError('Invalid profile parameters')


def read_recipe(report, uid, original):
    path=report/'equalizer'/(uid+'.json')
    if not path.exists():return None
    recipe=json.loads(path.read_text(encoding='utf-8-sig'))
    if recipe.get('enabled') is not True:return None
    if recipe.get('uid') != uid or hashlib.sha256(Path(original).read_bytes()).hexdigest()!=recipe.get('source_sha256'):
        raise ValueError(f'Equalizer source changed: {uid}')
    with wave.open(str(original)) as w:
        if w.getsampwidth()!=2:raise ValueError('Equalizer requires PCM16')
        validate(recipe,w.getframerate())
    return recipe


def filter_pcm(pcm,rate,channels,recipe):
    validate(recipe,rate)
    if recipe['gain_db']==0:return pcm
    import numpy as np
    values=np.frombuffer(pcm,dtype='<i2').reshape(-1,channels).astype(np.float64)/32768
    result=np.zeros_like(values);weight=np.zeros(len(values))
    window=np.sqrt(.5-.5*np.cos(2*np.pi*np.arange(SIZE)/SIZE))
    hz=np.fft.rfftfreq(SIZE,1/rate);low,high,db=(recipe[k] for k in ('low_hz','high_hz','gain_db'))
    fade=max(20,(high-low)*.1)
    amount=np.where(hz<low,np.maximum(0,1-(low-hz)/fade),np.where(hz>high,np.maximum(0,1-(hz-high)/fade),1))
    gain=10**(db*(.5-.5*np.cos(np.pi*amount))/20)
    profiled=recipe['method']=='profile-suppression-v1';previous=None
    if profiled:
        noise=np.asarray(recipe['profile']['power'])[:,None]*10**(recipe['profile_level_db']/10)
        radius=int(math.floor(recipe['smoothing_hz']*SIZE/rate+.5));bins=np.arange(SIZE//2+1)
        left_bins=np.maximum(0,bins-radius);right_bins=np.minimum(len(bins)-1,bins+radius)
        decay=math.exp(-HOP/rate/.08);floor=10**(db/20)
    for start in range(-SIZE+HOP,len(values),HOP):
        a,b=max(0,start),min(len(values),start+SIZE);left=a-start;right=b-start
        block=np.zeros((SIZE,channels));block[left:right]=values[a:b]
        spectrum=np.fft.rfft(block*window[:,None],axis=0)
        if profiled:
            raw=np.sqrt(np.maximum(floor*floor,1-noise/np.maximum(1e-20,np.abs(spectrum)**2)))
            cumulative=np.concatenate((np.zeros((1,channels)),np.cumsum(raw,axis=0)),axis=0)
            dynamic=(cumulative[right_bins+1]-cumulative[left_bins])/(right_bins-left_bins+1)[:,None]
            if previous is not None:dynamic=decay*previous+(1-decay)*dynamic
            previous=dynamic;spectrum*=dynamic
        else:spectrum*=gain[:,None]
        block=np.fft.irfft(spectrum,n=SIZE,axis=0)*window[:,None]
        result[a:b]+=block[left:right];weight[a:b]+=window[left:right]**2
    result/=np.maximum(weight[:,None],1e-12)
    return np.clip(np.floor(result*32768+.5),-32768,32767).astype('<i2').tobytes()
