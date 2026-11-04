"""Contract tests for the local etcd Raft lab (do not require Docker)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from raft_election import EtcdLabClient, Proof, QuorumUnavailable, b64


class RaftElectionContractTest(unittest.TestCase):
    def test_refuses_non_loopback_and_non_http(self):
        for uri in (
            "http://etcd1:2379", "https://127.0.0.1:2379",
            "http://127.0.0.1:2379/other", "http://user:pass@127.0.0.1:2379",
            "http://127.0.0.1", "http://127.0.0.1:2379?token=secret"
        ):
            with self.subTest(uri=uri), self.assertRaises(ValueError):
                EtcdLabClient(uri)

    def test_campaign_is_atomic_compare_absent_and_returns_epoch(self):
        client = EtcdLabClient("http://127.0.0.1:2379")
        seen = []
        def fake(route, payload):
            seen.append((route, payload))
            return {"succeeded": True, "header": {"revision": "11"}}
        client._post = fake
        proof = client.claim("/xcloud/raft-lab/check", "alpha", "42")
        self.assertEqual(proof, Proof("/xcloud/raft-lab/check", "alpha", "42", 11))
        self.assertEqual(seen[0][0], "/v3/kv/txn")
        self.assertEqual(seen[0][1]["compare"][0]["target"], "VERSION")
        self.assertEqual(seen[0][1]["compare"][0]["version"], "0")
        self.assertEqual(seen[0][1]["success"][0]["request_put"]["lease"], "42")
        self.assertEqual(seen[0][1]["success"][0]["request_put"]["key"], b64("/xcloud/raft-lab/check"))

    def test_contended_candidate_returns_no_proof(self):
        client = EtcdLabClient("http://localhost:2379")
        client._post = lambda route, body: {"succeeded": False}
        self.assertIsNone(client.claim("/xcloud/raft-lab/check", "beta", "42"))

    def test_protobuf_omits_default_false_on_rejected_campaign(self):
        client = EtcdLabClient("http://127.0.0.1:2379")
        client._post = lambda route, body: {
            "header": {"revision": "7"},
            "responses": [{"response_range": {"kvs": []}}],
        }
        self.assertIsNone(client.claim("/xcloud/raft-lab/check", "beta", "52"))

    def test_failure_to_reach_quorum_is_not_successful_or_false_election(self):
        client = EtcdLabClient("http://localhost:2379")
        def missing(route, body):
            raise QuorumUnavailable("no majority")
        client._post = missing
        with self.assertRaises(QuorumUnavailable):
            client.still_leader(Proof("/xcloud/raft-lab/check", "alpha", "42", 5))
        with self.assertRaises(QuorumUnavailable):
            client.claim("/xcloud/raft-lab/check", "alpha", "42")

    def test_rejects_outside_lab_namespace_and_invalid_ttls(self):
        client = EtcdLabClient("http://127.0.0.1:2379")
        with self.assertRaises(ValueError):
            client.claim("/production/leader", "alpha", "42")
        for ttl in (1, 4, 301):
            with self.subTest(ttl=ttl), self.assertRaises(ValueError):
                client.grant(ttl)

    def test_stale_epoch_proof_is_rejected(self):
        client = EtcdLabClient("http://127.0.0.1:2379")
        client._post = lambda route, payload: {
            "kvs": [{"value": b64("alpha"), "mod_revision": "18"}]
        }
        self.assertFalse(client.still_leader(Proof("/xcloud/raft-lab/key", "alpha", "42", 17)))
        self.assertTrue(client.still_leader(Proof("/xcloud/raft-lab/key", "alpha", "42", 18)))


if __name__ == "__main__":
    unittest.main()
