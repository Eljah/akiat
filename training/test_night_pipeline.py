import unittest
from night_common import validate_parts, validate_phoneme_map
from prepare_experimental import boundaries


class CompletionTests(unittest.TestCase):
    def test_phoneme_map_extensions_preserve_checkpoint_ids(self):
        original = {'a':[1], 'b':[2]}
        self.assertEqual(validate_phoneme_map(original, {**original, 'c':[3]}, 4), ['c'])
        for actual in ({'a':[2], 'b':[1]}, {'a':[1]}, {**original, 'c':[4]}, {**original, 'c':[1]}):
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                validate_phoneme_map(original, actual, 4)

    def part(self, start=0, seconds=180, digest='abc'):
        return dict(source='a.mp3', sha256=digest, offset=start, seconds=seconds, result={'text':'test'})

    def test_complete(self):
        validate_parts([self.part(), self.part(180, 11)], 'abc', 'a.mp3', 191)

    def test_reject_partial_stale_and_gap(self):
        for parts in ([self.part()], [self.part(digest='old')], [self.part(), self.part(181, 10)], []):
            with self.subTest(parts=parts), self.assertRaises(ValueError):
                validate_parts(parts, 'abc', 'a.mp3', 191)

    def test_silence_boundaries_cover_without_overlap(self):
        clips = list(boundaries(40, [8, 19, 28]))
        self.assertEqual(clips, [(0,8),(8,19),(19,28),(28,40)])
        self.assertTrue(all(1.2 <= b-a <= 12 for a,b in clips))


if __name__ == '__main__':
    unittest.main()
