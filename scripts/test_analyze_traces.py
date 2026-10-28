import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('analyze_traces', Path(__file__).with_name('analyze-traces.py'))
analyzer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyzer)


class AnalyzeTracesTest(unittest.TestCase):
    def test_ignores_only_incomplete_last_event(self):
        self.assertEqual([{'kind': 'start'}], analyzer.read_events('{"kind":"start"}\n{"kin'))
        with self.assertRaises(ValueError):
            analyzer.read_events('{broken}\n{"kind":"end"}')

    def test_detects_alternating_screens(self):
        screens = [{'package': 'app', 'signature': key, 'step': str(i)}
                   for i, key in enumerate(['a', 'b', 'a', 'b'], 1)]
        self.assertEqual([('1', '4', 2)], analyzer.cycles(screens))

    def test_distinguishes_packages_and_missing_trees(self):
        screens = [{'package': app, 'signature': 'same'} for app in ['a', 'b', 'c', 'd']]
        self.assertEqual([], analyzer.cycles(screens))
        self.assertEqual([], analyzer.cycles([{'signature': None}] * 4))


if __name__ == '__main__':
    unittest.main()
