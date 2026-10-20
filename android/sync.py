#!/usr/bin/env python3
"""Copy the game (../index.html) into app/src/main/assets and make it run offline.

Swaps the three.js CDN script for the bundled vendor/three.min.js and the
Google Fonts stylesheet for vendor/fonts.css with the fonts inlined.
"""
import base64, pathlib, re, shutil, sys

here = pathlib.Path(__file__).resolve().parent
src = here.parent / 'index.html'
assets = here / 'app' / 'src' / 'main' / 'assets'
vendor = here / 'vendor'

html = src.read_text(encoding='utf-8')

three = re.compile(r'<script src="https://cdnjs\.cloudflare\.com/ajax/libs/three\.js/r128/three\.min\.js"></script>')
fonts = re.compile(r'<link rel="stylesheet" href="https://fonts\.googleapis\.com/css2\?[^"]*">')
preconnect = re.compile(r'<link rel="preconnect"[^>]*>\n?')
for name, pat in (('three.js script', three), ('Google Fonts link', fonts)):
    if not pat.search(html):
        sys.exit(f'sync: could not find the {name} in {src}; update sync.py')
html = three.sub('<script src="three.min.js"></script>', html)
html = fonts.sub('<link rel="stylesheet" href="fonts.css">', html)
html = preconnect.sub('', html)
left = re.findall(r'(?:src|href)="(https?://[^"]+)"', html)
if left:
    print('sync: warning, still loads from the network:', *left, sep='\n  ')

css = (vendor / 'fonts.css').read_text(encoding='utf-8')
def inline(m):
    data = base64.b64encode((vendor / m.group(1)).read_bytes()).decode()
    return f'url(data:font/woff2;base64,{data})'
css = re.sub(r'url\((fonts/[^)]+)\)', inline, css)

if assets.exists():
    shutil.rmtree(assets)
assets.mkdir(parents=True)
(assets / 'index.html').write_text(html, encoding='utf-8')
(assets / 'fonts.css').write_text(css, encoding='utf-8')
shutil.copy(vendor / 'three.min.js', assets / 'three.min.js')
print(f'sync: copied {src} into {assets}')
