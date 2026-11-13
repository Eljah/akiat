import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import wave

from train_reviewed import ROOT, reviewed, split_groups, prepare, resume_snapshot


class ReviewedTrainingTests(unittest.TestCase):
    def test_reviewed_snapshot_and_exact_crop(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'outputs') as directory:
            root = Path(directory)
            edits = root / 'desktop_edits'
            edits.mkdir()
            source = root / 'original.wav'
            pcm = bytes(range(256)) * 200
            with wave.open(str(source), 'wb') as wav:
                wav.setparams((1, 2, 8000, 0, 'NONE', 'not compressed'))
                wav.writeframes(pcm)
            for i in range(12):
                audio = source if i < 6 else root / 'second.wav'
                if i == 6:audio.write_bytes(source.read_bytes())
                record = dict(uid=f'clip{i}', status='reviewed' if i < 10 else 'excluded',
                    training_eligible=True, effective_text_needs_review=False,
                    effective_text='Final corrected text', corrected_text='Wrong full text',
                    original_audio=str(audio), trim_start=.5, trim_end=1.5)
                (edits / f'clip{i}.json').write_text(json.dumps(record))
            records = reviewed(root)
            self.assertEqual(len(records), 10)
            validation = set(split_groups(records))
            self.assertTrue(all(r['uid'] in validation for r in records[:6]) or all(r['uid'] not in validation for r in records[:6]))
            with patch('train_reviewed.reserve'):
                run = root / 'run'
                prepare(run, records)
            self.assertNotIn('Wrong full text', (run / 'corpus/metadata.csv').read_text())
            self.assertEqual(len(list((run / 'corpus/wav').glob('*.wav'))), 10)
            with wave.open(str(run / 'corpus/wav/clip0.wav')) as cut:
                self.assertEqual(cut.readframes(cut.getnframes()), pcm[8000:24000])
            bad = json.loads((edits / 'clip0.json').read_text())
            bad['effective_text_needs_review'] = True
            (edits / 'clip0.json').write_text(json.dumps(bad))
            with self.assertRaises(ValueError):reviewed(root)
            # Resuming uses the frozen corpus even after desktop edits change.
            (run / 'checkpoints').mkdir()
            (run / 'checkpoints/last.ckpt').write_bytes(b'fixture')
            frozen, frozen_validation, checkpoint = resume_snapshot(run)
            self.assertEqual(len(frozen), 10)
            self.assertEqual(frozen_validation, validation)
            self.assertEqual(checkpoint.name, 'last.ckpt')
            (run / 'corpus/metadata.csv').write_text('tampered', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'text changed'):
                resume_snapshot(run)


if __name__ == '__main__':
    unittest.main()
