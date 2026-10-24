"""nginx 설정 확인용 가짜 앱(user 8080, admin 8081, analyzer 5000). deploy/nginx/verify/run.sh가 띄운다."""
import threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

LOG = open('/tmp/we-fakes.log', 'a', buffering=1)


def log(*parts):
    LOG.write(' '.join(str(p) for p in parts) + '\n')


class Base(BaseHTTPRequestHandler):
    name = '?'

    def log_message(self, *args):
        pass

    def reply(self, code, body=b'', headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def sse(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('X-Accel-Buffering', 'no')
        self.end_headers()
        self.wfile.write(b': connected\n\n'); self.wfile.flush()
        for i in range(5):
            time.sleep(1)
            self.wfile.write(('event: stat\ndata: %d\n\n' % i).encode()); self.wfile.flush()


class User(Base):
    def do_GET(self):
        log('user', self.path, 'host=' + str(self.headers.get('Host')), 'xfp=' + str(self.headers.get('X-Forwarded-Proto')),
            'xff=' + str(self.headers.get('X-Forwarded-For')))
        if self.path.startswith('/api/v1/stats/stream'):
            return self.sse()
        self.reply(200, b'user-app ' + self.path.encode())


class Admin(Base):
    def do_GET(self):
        u = urlparse(self.path)
        log('admin', self.path, 'cookie=' + str(self.headers.get('Cookie')), 'token=' + str(self.headers.get('X-Internal-Token')))
        if u.path == '/admin/api/v1/live/auth':
            cam = parse_qs(u.query).get('cameraId', [''])[0]
            if 'ADMIN_SESSION=ok' not in (self.headers.get('Cookie') or ''):
                return self.reply(401)
            if cam == '2':
                return self.reply(403)
            return self.reply(204)
        if u.path == '/admin/api/v1/recordings/1/video':
            return self.reply(200, b'', {'X-Accel-Redirect': '/protected/media/dummy/videos/a.mp4'})
        if u.path == '/admin/api/v1/recordings/1/boxes':
            return self.reply(200, b'', {'X-Accel-Redirect': '/protected/media/dummy/boxes/a.json.gz'})
        if u.path in ('/admin/api/v1/stats/stream', '/admin/api/v1/notifications/stream'):
            return self.sse()
        self.reply(200, b'admin-app ' + self.path.encode())


class Analyzer(Base):
    def do_GET(self):
        token = str(self.headers.get('X-Internal-Token'))
        log('analyzer', self.path, 'token=' + token)
        hdr = {'X-Seen-Token': token}
        if self.path == '/analyzer/v1/cameras/1/snapshot':
            return self.reply(200, b'SNAPSHOT', hdr)
        if self.path == '/analyzer/v1/cameras/1/live':
            self.send_response(200)
            self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame')
            self.send_header('X-Seen-Token', token)
            self.end_headers()
            for i in range(5):
                self.wfile.write(('--frame\r\nchunk %d\r\n' % i).encode()); self.wfile.flush()
                time.sleep(1)
            return
        self.reply(404, b'analyzer-404')


for port, handler in ((8080, User), (8081, Admin), (5000, Analyzer)):
    server = ThreadingHTTPServer(('127.0.0.1', port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
print('fakes ready', flush=True)
while True:
    time.sleep(3600)
