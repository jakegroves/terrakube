#!/usr/bin/env python3
"""Mint a Terrakube PAT JWT (HS256) from the PatSecret. Stdlib only."""
import argparse
import base64
import hashlib
import hmac
import json
import time
import uuid


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--secret", required=True, help="base64url PatSecret")
    ap.add_argument("--email", default="admin@example.com")
    ap.add_argument("--groups", default="TERRAKUBE_ADMIN", help="comma-separated")
    ap.add_argument("--sub", default="loadgen (Token)")
    ap.add_argument("--exp-seconds", type=int, default=86400)
    a = ap.parse_args()

    key = base64.urlsafe_b64decode(a.secret + "=" * (-len(a.secret) % 4))
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": "Terrakube",
        "aud": "Terrakube",
        "sub": a.sub,
        "jti": str(uuid.uuid4()),
        "email": a.email,
        "email_verified": True,
        "name": a.sub,
        "groups": [g for g in a.groups.split(",") if g],
        "iat": now,
        "exp": now + a.exp_seconds,
    }
    signing_input = f"{b64u(json.dumps(header).encode())}.{b64u(json.dumps(payload).encode())}"
    sig = b64u(hmac.new(key, signing_input.encode(), hashlib.sha256).digest())
    print(f"{signing_input}.{sig}")


if __name__ == "__main__":
    main()
