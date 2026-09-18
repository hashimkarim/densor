"""Check the local app's HTTP surface without a browser or third-party packages."""
from functools import partial
from http.server import ThreadingHTTPServer
from pathlib import Path
import sys
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from serve import APP, Handler


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(APP)))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_app_and_dataset_routes(self):
        for route in ['/', '/styles.css', '/app.mjs', '/sampler.mjs', '/exporters.mjs',
                      '/dataset/metadata.json', '/dataset/events.csv', '/dataset/samples.csv']:
            with self.subTest(route=route), urlopen(self.origin + route) as response:
                self.assertEqual(response.status, 200)
                self.assertGreater(len(response.read()), 0)
                if route.endswith('.mjs'):
                    self.assertEqual(response.headers.get_content_type(), 'text/javascript')

    def test_unrelated_repository_files_not_served(self):
        for route in ['/../README.md', '/%2e%2e/README.md', '/dataset/../README.md',
                      '/dataset/ground_truth.csv', '/.git/config', '/serve.py', '/dataset/']:
            with self.subTest(route=route), self.assertRaises(HTTPError) as error:
                urlopen(self.origin + route)
            self.assertEqual(error.exception.code, 404)
            error.exception.close()


if __name__ == '__main__':
    unittest.main()
