import unittest
from types import SimpleNamespace
from server import ROOT, make_handler


class StaticAssetsTests(unittest.TestCase):
    def test_drop_table_photo_is_served_by_the_explicit_static_route(self):
        paths = []
        handler = SimpleNamespace(path='/drop-table.jpg', file=paths.append)
        make_handler(None, {}).do_GET(handler)
        self.assertEqual(paths, [ROOT / 'static' / 'drop-table.jpg'])
        self.assertTrue(paths[0].read_bytes().startswith(b'\xff\xd8\xff'))
