"""Пересборка статических SVG схем: python3 frontend/generate_diagrams.py [--check]."""
from __future__ import annotations

import argparse
from html import escape
from pathlib import Path

from scenarios import load_templates

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / 'static' / 'diagrams'


def text(x, y, value, style='body'):
    return f'<text x="{x}" y="{y}" class="{style}">{escape(str(value))}</text>'


def table_card(table, x, y, width, relations, columns=1):
    rows = (len(table['columns']) + columns - 1) // columns
    height = 58 + rows * 28 + 40
    parts = [f'<g><rect x="{x}" y="{y}" width="{width}" height="{height}" rx="12" fill="#fff" stroke="#b8cbbb"/>',
             text(x + 20, y + 35, table['name'], 'table'),
             f'<path d="M{x} {y+58} H{x+width}" stroke="#d9e4d8"/>']
    for i, column in enumerate(table['columns']):
        cx = x + (i // rows) * (width // columns)
        cy = y + 80 + (i % rows) * 28
        fk = any(r['from'] == table['name'] and f"({column['name']})" in r['label'] for r in relations)
        key = 'PK' if column['primary'] else 'FK' if fk else ''
        parts += [text(cx + 16, cy, key, 'key'), text(cx + 52, cy, column['name'])]
    count = len(table['indexes'])
    index_label = 'индексов' if 11 <= count % 100 <= 14 else 'индекс' if count % 10 == 1 else 'индекса' if 2 <= count % 10 <= 4 else 'индексов'
    parts += [text(x + 20, y + height - 14, f"{table['rows']:,} строк · {count} {index_label}".replace(',', ' '), 'note'), '</g>']
    return ''.join(parts)


def connector(points, logical=False):
    path = ' '.join(f'{x},{y}' for x, y in points)
    dash = ' stroke-dasharray="7 6"' if logical else ''
    x, y = points[0]
    tx, ty = points[-1]
    px, py = points[-2]
    dx, dy = (1 if tx > px else -1) if tx != px else 0, (1 if ty > py else -1) if ty != py else 0
    arrow = f'{tx - 10*dx + 6*dy},{ty - 10*dy - 6*dx} {tx},{ty} {tx - 10*dx - 6*dy},{ty - 10*dy + 6*dx}'
    return (f'<polyline points="{path}" style="fill:none;stroke:#548269;stroke-width:2"{dash}/>'
            f'<polyline points="{arrow}" style="fill:none;stroke:#548269;stroke-width:2"/>'
            f'<circle cx="{x}" cy="{y}" r="4" fill="#548269"/>')


def render(template):
    catalog = template['catalog']
    tables = {t['name'].split('.')[-1]: t for t in catalog['tables']}
    cards, lines = [], []
    if template['id'] == '02-shop':
        width, height = 1880, 990
        positions = {'categories': (50, 150), 'products': (500, 150), 'order_items': (950, 150),
                     'orders': (1400, 150), 'shipments': (500, 570), 'payments': (950, 570), 'customers': (1400, 570)}
        # Линии идут от FK к PK; маршруты закреплены для семи таблиц shop v2.
        routes = [
            [(500, 252), (440, 252), (440, 224), (380, 224)],
            [(950, 280), (900, 280), (900, 224), (830, 224)],
            [(1280, 252), (1340, 252), (1340, 224), (1400, 224)],
            [(1730, 252), (1830, 252), (1830, 644), (1730, 644)],
            [(1280, 672), (1310, 672), (1310, 224), (1400, 224)],
            [(1280, 700), (1370, 700), (1370, 644), (1400, 644)],
            [(830, 672), (900, 672), (900, 460), (1370, 460), (1370, 224), (1400, 224)],
            [(830, 700), (870, 700), (870, 900), (1790, 900), (1790, 644), (1730, 644)],
        ]
        lines = [connector(route) for route in routes]
        for name, (x, y) in positions.items():
            cards.append(table_card(tables[name], x, y, 330, catalog['relations']))
    elif template['id'] == '04-scenarios':
        width, height = 1300, 510
        cards = [table_card(tables['records'], 60, 140, 470, []), table_card(tables['groups'], 770, 140, 470, [])]
        lines = [connector([(530, 242), (650, 242), (650, 214), (770, 214)], logical=True)]
    else:
        table = next(iter(tables.values()))
        columns = 4 if len(table['columns']) > 25 else 2
        width = 1700 if columns == 4 else 1100
        rows = (len(table['columns']) + columns - 1) // columns
        height = max(430, 140 + 58 + rows * 28 + 100)
        cards = [table_card(table, 50, 140, width - 100, [], columns)]
    subtitle = f"{len(tables)} табл. · {len(catalog['relations'])} внешних ключей · PK — первичный ключ · FK — внешний ключ"
    if catalog['relations']:
        subtitle += ' · стрелка: FK → PK'
    # Убираем место под прежний заголовок, сохраняя расположение таблиц и линий.
    height -= 110
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title description">
<title id="title">{escape(template['name'])} — схема базы</title>
<desc id="description">{escape(subtitle)}. Статическая схема датасета версии {template['version']}.</desc>
<style>text{{font-family:Arial,sans-serif;fill:#203d34}}.body{{font-family:monospace;font-size:16px}}.table{{font-family:monospace;font-size:20px;font-weight:700}}.key{{font-size:12px;font-weight:700;fill:#357c55}}.note{{font-size:15px;fill:#697e70}}</style>
<rect width="{width}" height="{height}" rx="16" fill="#f3f7f0"/>
<g transform="translate(0,-110)">{''.join(lines)}{''.join(cards)}</g>
</svg>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Проверить, что сохранённые SVG соответствуют шаблонам')
    args = parser.parse_args()
    OUTPUT.mkdir(exist_ok=True)
    for template in load_templates(ROOT).values():
        path = OUTPUT / f"{template['id']}-v{template['version']}.svg"
        content = render(template)
        if args.check:
            if not path.is_file() or path.read_text() != content:
                raise SystemExit(f'Пересоберите диаграмму: {path}')
        else:
            path.write_text(content)


if __name__ == '__main__':
    main()
