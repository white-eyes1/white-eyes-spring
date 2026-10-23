#!/bin/sh
# WSL에서: nginx 컨테이너를 띄워 deploy/nginx 설정을 가짜 앱으로 확인한다.
SRC="$1"                      # 설정 파일이 든 폴더(WSL 경로)
W=/tmp/we-nginx
rm -rf $W /tmp/we-media /tmp/we-fakes.log; mkdir -p $W /tmp/we-media/dummy/videos /tmp/we-media/dummy/boxes
cp "$SRC/nginx.conf" "$SRC/sse.inc" "$SRC/proxy.inc" $W/
printf 'map $host $internal_api_token { default "test-token-0123456789abcdef0123456789"; }\n' > $W/internal-token.conf
head -c 100000 /dev/urandom > /tmp/we-media/dummy/videos/a.mp4
printf '{"boxes":[1,2,3]}' | gzip > /tmp/we-media/dummy/boxes/a.json.gz

docker rm -f we-nginx >/dev/null 2>&1
python3 "$(dirname "$0")/fakes.py" > /tmp/we-fakes.out 2>&1 &
FAKES=$!
sleep 1

echo "== nginx -t"
docker run --rm --network host -v $W/nginx.conf:/etc/nginx/nginx.conf:ro -v $W/sse.inc:/etc/nginx/sse.inc:ro -v $W/proxy.inc:/etc/nginx/proxy.inc:ro \
  -v $W/internal-token.conf:/etc/nginx/internal-token.conf:ro -v /tmp/we-media:/srv/media:ro nginx:alpine nginx -t 2>&1 | tail -3

docker run -d --name we-nginx --network host -v $W/nginx.conf:/etc/nginx/nginx.conf:ro -v $W/sse.inc:/etc/nginx/sse.inc:ro -v $W/proxy.inc:/etc/nginx/proxy.inc:ro \
  -v $W/internal-token.conf:/etc/nginx/internal-token.conf:ro -v /tmp/we-media:/srv/media:ro nginx:alpine >/dev/null
sleep 2
B=http://127.0.0.1

echo "== 1. routing: / -> user app, /admin/ -> admin app"
curl -s $B/hello; echo
curl -s $B/admin/hello; echo
echo "== 1b. proxy headers reach the app"
curl -s -o /dev/null -H 'X-Forwarded-For: 1.2.3.4' $B/hello; grep 'user /hello' /tmp/we-fakes.log | tail -1

echo "== 2. /internal/ and /admin/internal/ are blocked (404)"
curl -s -o /dev/null -w "%{http_code} " $B/internal/x; curl -s -o /dev/null -w "%{http_code} " $B/admin/internal/x; curl -s -o /dev/null -w "%{http_code}\n" $B/admin/internal/live/1

echo "== 3. live auth: snapshot (no cookie 401 / cookie 200 / camera2 403 / bad id 404)"
curl -s -o /dev/null -w "%{http_code} " $B/admin/live/1/snapshot
curl -s -o /dev/null -w "%{http_code} " -H 'Cookie: ADMIN_SESSION=ok' $B/admin/live/1/snapshot
curl -s -o /dev/null -w "%{http_code} " -H 'Cookie: ADMIN_SESSION=ok' $B/admin/live/2/snapshot
curl -s -o /dev/null -w "%{http_code}\n" -H 'Cookie: ADMIN_SESSION=ok' $B/admin/live/abc/snapshot
echo "-- snapshot body + token seen by analyzer + client-supplied token is overwritten + no-store:"
curl -s -i -H 'Cookie: ADMIN_SESSION=ok' -H 'X-Internal-Token: evil' $B/admin/live/1/snapshot | grep -i -E "^HTTP|X-Seen-Token|Cache-Control|SNAPSHOT"
echo "-- analyzer was never reached without a cookie / for camera 2:"
grep -c 'analyzer /analyzer/v1/cameras/2' /tmp/we-fakes.log || true

echo "== 3b. tricky URLs must never reach camera 2 (expect 401/403/500/404, not 200)"
for u in "/admin/live/%31/snapshot" "/admin/live//1/snapshot" "/admin/live/1/../2/snapshot" "/admin/live/2%2f..%2f1/snapshot" "/admin/live/2/snapshot?x=/../1" "/admin/live/2"; do
  printf "%s -> " "$u"; curl -s --path-as-is -o /dev/null -w "%{http_code}
" -H 'Cookie: ADMIN_SESSION=ok' "$B$u"
done
echo "-- analyzer calls for camera 2 so far (must be 0):"; grep -c 'analyzer /analyzer/v1/cameras/2' /tmp/we-fakes.log || true

echo "== 4. live stream is not buffered (first chunk within ~1s, total ~4s)"
curl -s -N -H 'Cookie: ADMIN_SESSION=ok' $B/admin/live/1 | while IFS= read -r line; do echo "$(date +%s.%N | cut -c8-14) $line"; done | grep chunk
echo "-- live without cookie:"; curl -s -o /dev/null -w "%{http_code}\n" $B/admin/live/1

echo "== 5. SSE not buffered (events arrive about 1 second apart), both apps"
for p in /api/v1/stats/stream /admin/api/v1/stats/stream /admin/api/v1/notifications/stream; do
  echo "-- $p"; curl -s -N $B$p | while IFS= read -r line; do echo "$(date +%s.%N | cut -c8-14) $line"; done | grep -E "connected|data" | head -3
done
echo "-- SSE settings in effective config:"
docker exec we-nginx nginx -T 2>/dev/null | grep -n -E "proxy_read_timeout|proxy_buffering" | head -10

echo "== 6. media: video via X-Accel-Redirect, Range, direct access blocked"
curl -s -i -H 'Cookie: ADMIN_SESSION=ok' -H 'Range: bytes=0-99' $B/admin/api/v1/recordings/1/video -o /tmp/we-range.bin -D - | grep -i -E "^HTTP|Content-Range|Content-Length|Accept-Ranges"
echo "range body size: $(wc -c < /tmp/we-range.bin)"
curl -s -o /dev/null -w "full video: %{http_code} %{size_download} bytes\n" $B/admin/api/v1/recordings/1/video
echo "-- direct /protected/media/ (must be 404):"
curl -s -o /dev/null -w "%{http_code} " $B/protected/media/dummy/videos/a.mp4; curl -s -o /dev/null -w "%{http_code}\n" $B/protected/media/dummy/boxes/a.json.gz

echo "== 7. boxes (.json.gz): content type and encoding"
curl -s -i $B/admin/api/v1/recordings/1/boxes -o /tmp/we-boxes.gz -D - | grep -i -E "^HTTP|Content-Type|Content-Encoding"
echo "-- Content-Encoding count (must be 1):"; curl -s -i $B/admin/api/v1/recordings/1/boxes -o /dev/null -D - | grep -ci "content-encoding"
echo "-- curl --compressed decodes JSON:"; curl -s --compressed $B/admin/api/v1/recordings/1/boxes; echo

docker rm -f we-nginx >/dev/null 2>&1
kill $FAKES 2>/dev/null
echo "== done"
