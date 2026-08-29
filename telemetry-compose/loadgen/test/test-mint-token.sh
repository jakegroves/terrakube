#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SECRET="ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g="   # a sample base64url secret

TOK=$(python3 mint-token.py --secret "$SECRET" --email admin@example.com --groups TERRAKUBE_ADMIN --sub 'loadgen (Token)' --exp-seconds 86400)

python3 - "$TOK" "$SECRET" <<'PY'
import sys, base64, json, hmac, hashlib
tok, secret = sys.argv[1], sys.argv[2]
h, p, s = tok.split(".")
def d(x): return base64.urlsafe_b64decode(x + "=" * (-len(x) % 4))
hdr, pl = json.loads(d(h)), json.loads(d(p))
assert hdr == {"alg": "HS256", "typ": "JWT"}, hdr
assert pl["iss"] == "Terrakube" and pl["aud"] == "Terrakube", pl
assert pl["email"] == "admin@example.com"
assert pl["groups"] == ["TERRAKUBE_ADMIN"], pl["groups"]
assert pl["email_verified"] is True
assert pl["exp"] - pl["iat"] == 86400
key = base64.urlsafe_b64decode(secret + "=" * (-len(secret) % 4))
want = base64.urlsafe_b64encode(hmac.new(key, f"{h}.{p}".encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
assert s == want, "signature mismatch"
print("mint-token OK")
PY
