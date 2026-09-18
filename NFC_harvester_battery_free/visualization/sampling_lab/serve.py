#!/usr/bin/env python3
"""Serve the sampling lab and three read-only dataset files, using only stdlib."""
import argparse
import errno
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
from urllib.parse import unquote, urlsplit
import webbrowser

APP = Path(__file__).resolve().parent
DATASET = APP.parent.parent / 'data' / 'synthetic_multirate' / 'dataset'
ASSETS = {'index.html', 'styles.css', 'app.mjs', 'sampler.mjs', 'exporters.mjs'}
DATA_FILES = {'samples.csv', 'metadata.json', 'events.csv'}


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, '.mjs': 'text/javascript'}

    def translate_path(self, path):
        route = unquote(urlsplit(path).path)
        if route == '/':
            return str(APP / 'index.html')
        if route.removeprefix('/') in ASSETS:
            return str(APP / route.removeprefix('/'))
        if route.startswith('/dataset/') and route[len('/dataset/'):] in DATA_FILES:
            return str(DATASET / route[len('/dataset/'):])
        return str(APP / '__not_found__')

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        super().end_headers()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--open', action='store_true', help='Open the selected URL in a browser')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('Port must be between 1 and 65535.')
    for name in DATA_FILES:
        if not (DATASET / name).is_file():
            parser.error(f'Missing dataset file: {DATASET / name}. See data/synthetic_multirate/README.md.')
    for port in range(args.port, 65536):
        try:
            server = ThreadingHTTPServer((args.host, port), partial(Handler, directory=str(APP)))
            break
        except OSError as error:
            if error.errno != errno.EADDRINUSE:
                raise
            print(f'Port {port} is in use; trying the next port.', flush=True)
    else:
        parser.error(f'No available port between {args.port} and 65535.')
    with server:
        url = f'http://{args.host}:{server.server_port}'
        print(f'Densor sampling lab: {url}', flush=True)
        if args.open:
            threading.Thread(target=webbrowser.open_new_tab, args=(url,), daemon=True).start()
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
