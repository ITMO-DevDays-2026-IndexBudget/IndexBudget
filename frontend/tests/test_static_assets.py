import unittest
from types import SimpleNamespace
from server import ROOT, make_handler, load_templates
from generate_diagrams import render
from xml.etree import ElementTree as ET


class StaticAssetsTests(unittest.TestCase):
    def test_drop_table_photo_is_served_by_the_explicit_static_route(self):
        paths = []
        handler = SimpleNamespace(path='/drop-table.jpg', file=paths.append)
        make_handler(None, {}).do_GET(handler)
        self.assertEqual(paths, [ROOT / 'static' / 'drop-table.jpg'])
        self.assertTrue(paths[0].read_bytes().startswith(b'\xff\xd8\xff'))

    def test_diagrams_are_current_landscape_assets_for_every_dataset(self):
        templates = load_templates(ROOT)
        for template in templates.values():
            paths = []
            name = f"{template['id']}-v{template['version']}.svg"
            handler = SimpleNamespace(path='/diagrams/' + name, file=paths.append)
            make_handler(None, templates).do_GET(handler)
            self.assertEqual(paths, [ROOT / 'static/diagrams' / name])
            source = paths[0].read_text()
            self.assertEqual(source, render(template), 'Пересоберите SVG после изменения датасета')
            svg = ET.fromstring(source)
            self.assertGreater(int(svg.attrib['width']), int(svg.attrib['height']))
            labels = [node.text for node in svg.iter('{http://www.w3.org/2000/svg}text')]
            for table in template['catalog']['tables']:
                self.assertIn(table['name'], labels)
                for column in table['columns']:
                    self.assertIn(column['name'], labels)

    def test_diagram_route_rejects_unknown_versions_and_paths(self):
        templates = load_templates(ROOT)
        for path in ('/diagrams/02-shop-v999.svg', '/diagrams/../app.js', '/diagrams/missing.svg'):
            responses = []
            handler = SimpleNamespace(path=path, send=lambda status, body: responses.append(status))
            make_handler(None, templates).do_GET(handler)
            self.assertEqual(responses, [404])
