import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import wave
from unittest.mock import patch

from manual_equalizer import filter_pcm,read_recipe
from train_reviewed import ROOT,prepare


class ManualEqualizerTests(unittest.TestCase):
    def test_frequency_response_and_java_parity(self):
        import numpy as np
        folder=ROOT/'outputs/desktop_test/equalizer'
        with wave.open(str(folder/'before.wav')) as wav:pcm=wav.readframes(wav.getnframes());rate=wav.getframerate()
        recipe=dict(method='manual-stft-band-v1',low_hz=150,high_hz=300,gain_db=-24)
        filtered=filter_pcm(pcm,rate,1,recipe)
        with wave.open(str(folder/'java-after.wav')) as wav:java=wav.readframes(wav.getnframes())
        self.assertLessEqual(np.max(np.abs(np.frombuffer(filtered,'<i2').astype(int)-np.frombuffer(java,'<i2'))),1)
        self.assertEqual(filter_pcm(pcm,rate,1,dict(recipe,gain_db=0)),pcm)
        with tempfile.TemporaryDirectory(dir=ROOT/'outputs') as directory:
            root=Path(directory);(root/'equalizer').mkdir();original=folder/'before.wav'
            stored=dict(recipe,enabled=True,uid='test',source_sha256=hashlib.sha256(original.read_bytes()).hexdigest())
            target=root/'equalizer/test.json';target.write_text(json.dumps(stored))
            self.assertEqual(read_recipe(root,'test',original),stored)
            records=[dict(uid='test',text='Final',start=.2,end=1.8,original_audio=str(original),equalizer=stored),dict(uid='second',text='Other',start=.2,end=1.8,original_audio=str(folder/'java-after.wav'))]
            with patch('train_reviewed.reserve'):prepare(root/'run',records)
            with wave.open(str(root/'run/corpus/wav/test.wav')) as w:self.assertEqual(w.readframes(w.getnframes()),filtered[round(.2*rate)*2:round(1.8*rate)*2])
            stored['enabled']=False;target.write_text(json.dumps(stored));self.assertIsNone(read_recipe(root,'test',original))
            stored['enabled']=True;stored['source_sha256']='bad';target.write_text(json.dumps(stored))
            with self.assertRaisesRegex(ValueError,'changed'):read_recipe(root,'test',original)


if __name__=='__main__':unittest.main()
