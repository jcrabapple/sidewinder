#!/usr/bin/env python3
"""Fake GoToSocial for the import-watcher E2E: serves the built app plus the few endpoints Sidewinder's import page uses.
The test driver sets the counts with POST /__set and reads the request log with GET /__state."""
import http.server, json, re, socketserver, sys
from urllib.parse import urlparse

DIST, PORT = sys.argv[1], int(sys.argv[2])
S = {'following_count': 3, 'followers_count': 7, 'statuses_count': 10, 'lists_count': 0, 'blocks_count': 156,
     'mutes_count': 0, 'media_storage': '1 MB', 'stats_polls': 0, 'imports': []}
STATS = ('followers_count', 'following_count', 'statuses_count', 'lists_count', 'blocks_count', 'mutes_count', 'media_storage')
BASE = f'http://127.0.0.1:{PORT}'
ACCOUNT = {'id': '01TESTACCOUNT0000000000000', 'username': 'jason', 'acct': 'jason', 'display_name': 'Jason', 'locked': False,
           'bot': False, 'created_at': '2026-01-01T00:00:00.000Z', 'note': '', 'url': BASE + '/@jason', 'avatar': '', 'avatar_static': '',
           'header': '', 'header_static': '', 'followers_count': 7, 'following_count': 3, 'statuses_count': 10, 'emojis': [], 'fields': []}


class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=DIST, **k)

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/api/v1/exports/stats':
            S['stats_polls'] += 1
            return self._send(200, {k: S[k] for k in STATS})
        if path == '/api/v1/accounts/verify_credentials':
            return self._send(200, ACCOUNT)
        if path == '/__state':
            return self._send(200, S)
        if path.startswith('/api/') or path.startswith('/oauth'):
            return self._send(404, {'error': 'not found'})
        return super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n)
        if path == '/__set':
            S.update(json.loads(body))
            return self._send(200, S)
        if path == '/api/v1/import':
            text = body.decode('utf-8', 'replace')
            data = re.search(r'name="data".*?\r\n\r\n(.*?)\r\n--', text, re.S)
            field = lambda name: (re.search(r'name="%s"\r\n\r\n(.*?)\r\n--' % name, text, re.S) or [None, None])[1]
            lines = [l for l in (data.group(1) if data else '').split('\n') if l.strip()]
            S['imports'].append({'bytes': n, 'lines': len(lines), 'type': field('type'), 'mode': field('mode')})
            return self._send(202, {'status': 'accepted'})
        return self._send(404, {'error': 'not found'})


class T(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


T(('127.0.0.1', PORT), H).serve_forever()
