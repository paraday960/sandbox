#!/usr/bin/env bash
# phonebox کلاینت — سمت ایجنت
# استفاده:
#   ./client.sh <URL> <TOKEN> ping|info|storage|docs
#   ./client.sh <URL> <TOKEN> exec "دستور شل"
#   ./client.sh <URL> <TOKEN> py "کد پایتون"
#   ./client.sh <URL> <TOKEN> put <نام> <فایل‌محلی>
#   ./client.sh <URL> <TOKEN> get <نام> <فایل‌محلی>
#   ./client.sh <URL> <TOKEN> rm <نام>
set -euo pipefail
URL="${1%/}"; TOK="$2"; CMD="$3"; shift 3 || true
AUTH=(-H "Authorization: Bearer $TOK" -H "Content-Type: application/json")

case "$CMD" in
  ping)     curl -sS -m 20  "${AUTH[@]}" "$URL/ping" ;;
  info)     curl -sS -m 20  "${AUTH[@]}" "$URL/info" ;;
  storage)  curl -sS -m 20  "${AUTH[@]}" "$URL/storage" ;;
  docs)     curl -sS -m 30  "${AUTH[@]}" "$URL/docs" ;;
  exec)     CMDV="$1" python3 -c 'import json,os;print(json.dumps({"cmd":os.environ["CMDV"]}))' \
              | curl -sS -m 300 "${AUTH[@]}" -d @- "$URL/exec" ;;
  py)       printf '%s' "$1" | curl -sS -m 300 "${AUTH[@]}" --data-binary @- "$URL/py" ;;
  put)      curl -sS -m 900 -H "Authorization: Bearer $TOK" \
              --data-binary @"$2" "$URL/docs/$1" ;;
  get)      curl -sS -m 900 -H "Authorization: Bearer $TOK" "$URL/docs/$1" -o "$2" ;;
  rm)       curl -sS -m 30 -X DELETE -H "Authorization: Bearer $TOK" "$URL/docs/$1" ;;
  pkg-install) CMDV="$1" python3 -c 'import json,os;print(json.dumps({"name":os.environ["CMDV"]}))' \
              | curl -sS -m 60 "${AUTH[@]}" -d @- "$URL/pkg/install" ;;
  pkg-job)   curl -sS -m 20 "${AUTH[@]}" "$URL/pkg/job" ;;
  pkg-search) curl -sS -m 90 "${AUTH[@]}" "$URL/pkg/search?q=$1" ;;
  *) echo "unknown command: $CMD" >&2; exit 2 ;;
esac
echo
