import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from audit_tatar_corpus import CACHE, ROOT, grammar_flags, lemma_parts, normalize, lookup


def pair(a, b, analyses, separator=' '):
    return [dict(index=1, normalized=a, start=0, end=len(a), analyses=[]),
            dict(index=2, normalized=b, start=len(a)+len(separator),
                 end=len(a)+len(separator)+len(b), analyses=analyses)], a+separator+b


class AuditTests(unittest.TestCase):
    def test_installed_analyzer_with_real_forms(self):
        if not (ROOT/'.tools/tatar_audit/usr/bin/hfst-lookup').exists():
            self.skipTest('Run setup_tatar_audit.py first')
        with TemporaryDirectory(dir=CACHE, prefix='selftest-') as directory:
            aa,_ = lookup({'килде','килдем','балалар','бала','зыфыжыфы'}, Path(directory))
        self.assertTrue(aa['килде'])
        self.assertFalse(aa['зыфыжыфы'])
        self.assertIn('кил', lemma_parts(next(iter(aa['килде']))))
        tokens,text = pair('мин','килде',sorted(aa['килде']))
        self.assertIn((2,'GRAM_PERSON'), grammar_flags(tokens,text))
        tokens,text = pair('мин','килдем',sorted(aa['килдем']))
        self.assertEqual(grammar_flags(tokens,text), [])
        tokens,text = pair('ике','балалар',sorted(aa['балалар']))
        self.assertIn((2,'GRAM_NUM_PLURAL'), grammar_flags(tokens,text))

    def test_ambiguous_person_not_flagged(self):
        tokens,text = pair('мин','килдем',['кил<v><iv><ifi><p1><sg>'])
        self.assertEqual(grammar_flags(tokens,text), [])
        tokens,text = pair('мин','килде',['кил<v><iv><ifi><p3><sg>'])
        self.assertIn((2,'GRAM_PERSON'), grammar_flags(tokens,text))
        tokens[1]['analyses'].append('кил<v><iv><ifi><p1><sg>')
        self.assertEqual(grammar_flags(tokens,text), [])

    def test_plural_after_cardinal_and_clause_boundary(self):
        tokens,text = pair('ике','балалар',['бала<n><pl><nom>'])
        self.assertEqual(grammar_flags(tokens,text), [(2,'GRAM_NUM_PLURAL')])
        tokens,text = pair('ике','бала',['бала<n><nom>'])
        self.assertEqual(grammar_flags(tokens,text), [])
        tokens,text = pair('мин','килде',['кил<v><iv><ifi><p3><sg>'], ', ')
        self.assertEqual(grammar_flags(tokens,text), [])

    def test_no_false_third_person_number_rule(self):
        tokens,text = pair('алар','килде',['кил<v><iv><ifi><p3><sg>'])
        self.assertEqual(grammar_flags(tokens,text), [])

    def test_analysis_preserves_clitic_and_unicode(self):
        self.assertEqual(lemma_parts('ишек<n><px3sp><nom>+и<cop><aor><p3><sg>'), ['ишек','и'])
        self.assertEqual(normalize('ӘЙТТЕ'), 'әйтте')


if __name__ == '__main__':
    unittest.main()
