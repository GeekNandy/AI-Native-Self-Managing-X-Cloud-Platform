#!/usr/bin/env python3
"""Restricted localhost etcd v3 quorum client for disposable Raft failure experiments.

NOT a production coordinator election service: leases are intentionally not renewed.
"""
import base64
from dataclasses import dataclass
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


class QuorumUnavailable(RuntimeError):
    """A consensus result could not be established; do not treat as non-leadership."""


def b64(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


@dataclass(frozen=True)
class Proof:
    key: str
    owner: str
    lease: str
    epoch: int


class EtcdLabClient:
    def __init__(self, endpoint):
        url = urlsplit(endpoint)
        if (url.scheme != "http" or url.hostname not in ("127.0.0.1", "localhost")
                or url.username or url.password or url.path not in ("", "/")
                or url.query or url.fragment or url.port is None):
            raise ValueError("Raft lab accepts only explicit localhost HTTP endpoints")
        self.endpoint = f"http://127.0.0.1:{url.port}"

    def _post(self, route, payload):
        request = Request(
            self.endpoint + route,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=3) as response:
                answer = json.load(response)
            if not isinstance(answer, dict) or "error" in answer or "code" in answer:
                raise ValueError("etcd returned an invalid consensus response")
            return answer
        except (HTTPError, URLError, OSError, ValueError, json.JSONDecodeError) as error:
            # Do not report raw etcd errors, which can embed internal endpoint data.
            raise QuorumUnavailable("Cannot obtain a valid etcd quorum response") from None

    def read(self, key):
        response = self._post("/v3/kv/range", {"key": b64(key)})
        records = response.get("kvs", [])
        if not records:
            return None
        row = records[0]
        return (
            base64.b64decode(row["value"]).decode("utf-8"),
            int(row["mod_revision"]),
        )

    def grant(self, ttl=120):
        if ttl < 5 or ttl > 300:
            raise ValueError("Lab TTL must be 5 to 300 seconds")
        response = self._post("/v3/lease/grant", {"TTL": ttl})
        return str(int(response["ID"]))

    def claim(self, key, owner, lease):
        if not key.startswith("/xcloud/raft-lab/") or not owner or len(owner) > 128:
            raise ValueError("Use an isolated lab key and a bounded owner name")
        response = self._post(
            "/v3/kv/txn",
            {
                "compare": [{"key": b64(key), "target": "VERSION", "result": "EQUAL", "version": "0"}],
                "success": [{"request_put": {"key": b64(key), "value": b64(owner), "lease": str(lease)}}],
                "failure": [{"request_range": {"key": b64(key)}}],
            },
        )
        if response.get("succeeded") is True:
            return Proof(key, owner, str(lease), int(response["header"]["revision"]))
        # Protobuf JSON encoding omits default false booleans in some etcd releases.
        # A rejected compare still carries a header and the requested failure response.
        if response.get("succeeded") is False or (
            "succeeded" not in response and "header" in response
            and bool(response.get("responses"))
        ):
            return None
        raise QuorumUnavailable("etcd transaction did not return a consensus decision")

    def still_leader(self, proof):
        # Linearizable read: failure to reach quorum is an error, not a positive vote.
        return self.read(proof.key) == (proof.owner, proof.epoch)

    def revoke(self, lease):
        self._post("/v3/lease/revoke", {"ID": str(lease)})
