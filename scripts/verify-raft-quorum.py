#!/usr/bin/env python3
"""Exercise a disposable 3-member Raft quorum, election CAS, failover and quorum loss.

Requires Docker Compose and unoccupied 127.0.0.1 ports 2379, 2381, 2383.
Does not launch the Java coordinator or modify any production deployment.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import subprocess
import time

from raft_election import EtcdLabClient, QuorumUnavailable

COMPOSE = Path(__file__).resolve().parents[1] / "deploy/raft-lab/compose.yml"
PORTS = (2379, 2381, 2383)


def compose(project, *args):
    command = ["docker", "compose", "-p", project, "-f", str(COMPOSE), *args]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("Docker Compose could not complete") from None
    if result.returncode:
        # Avoid reflecting arbitrary Docker environment details in public evidence.
        raise RuntimeError(f"Docker Compose {args[0]} failed (exit {result.returncode})")


def wait_for_quorum(clients, timeout=75):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if all(client.read("/xcloud/raft-lab/healthcheck") is None for client in clients):
                return
        except QuorumUnavailable:
            pass
        time.sleep(0.4)
    raise RuntimeError("The three-node etcd cluster did not become healthy")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("target/raft-quorum-evidence.json"))
    args = parser.parse_args()
    project = "xcloud-raft-" + secrets.token_hex(4)
    clients = [EtcdLabClient(f"http://127.0.0.1:{port}") for port in PORTS]
    checks = {}
    key = "/xcloud/raft-lab/" + secrets.token_hex(16)
    started = False
    try:
        # Compose is always torn down; use no persistent volumes or shared cluster.
        started = True
        compose(project, "up", "-d")
        wait_for_quorum(clients)
        checks["threeMemberQuorumStarted"] = True

        lease_a = clients[0].grant()
        lease_b = clients[1].grant()
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [
                pool.submit(clients[0].claim, key, "candidate-a", lease_a),
                pool.submit(clients[1].claim, key, "candidate-b", lease_b),
            ]
            claims = [future.result(timeout=10) for future in futures]
        winners = [proof for proof in claims if proof is not None]
        assert len(winners) == 1, "Exactly one candidate must win election"
        leader = winners[0]
        rejected = (lease_b, "candidate-b") if leader.owner == "candidate-a" else (lease_a, "candidate-a")
        assert clients[2].still_leader(leader)
        checks["exactlyOneLeaderAndMonotonicEpoch"] = True

        # Losing one node still preserves majority agreement.
        compose(project, "stop", "etcd1")
        assert clients[1].still_leader(leader)
        checks["oneMemberFailurePreservesQuorum"] = True

        # Explicit lease revocation models a terminated leader; stale proof is rejected.
        clients[1].revoke(leader.lease)
        successor = clients[1].claim(key, rejected[1], rejected[0])
        assert successor is not None and successor.epoch > leader.epoch
        assert not clients[1].still_leader(leader)
        assert clients[2].still_leader(successor)
        checks["newLeaderFencesOldProof"] = True

        # Losing two of three nodes must prevent new leadership decisions.
        compose(project, "stop", "etcd3")
        refused = False
        try:
            clients[1].still_leader(successor)
        except QuorumUnavailable:
            refused = True
        assert refused, "A lone member must not return successful linearizable ownership proof"
        checks["majorityLossFailsClosed"] = True

        # Restoring the quorum allows linearizable observation of the last committed leader.
        compose(project, "start", "etcd3")
        end = time.monotonic() + 45
        recovered = False
        while time.monotonic() < end:
            try:
                if clients[1].still_leader(successor):
                    recovered = True
                    break
            except QuorumUnavailable:
                pass
            time.sleep(0.5)
        assert recovered, "Restored majority must preserve the committed lease"
        checks["quorumRecoveryPreservesCommittedLeadership"] = True

        report = {
            "recordedAt": datetime.now(timezone.utc).isoformat(),
            "experiment": "Actual etcd Raft 3-member quorum / lease CAS / 1-node survival / 2-node failure",
            "checks": checks,
            "result": "PASS",
            "scope": "Disposable localhost Docker Compose; does not run a Java coordinator",
            "limitations": [
                "Raft-backed consensus verified, but Java control-plane leadership is not integrated",
                "No automatic lease renewal, production TLS/authentication or worker failover",
                "One- and two-node process stops; not a hostile asymmetric network partition",
                "PostgreSQL availability, external side-effect fencing and production HA remain unproven",
            ],
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"result": report["result"], "checks": checks}, indent=2))
    finally:
        if started:
            compose(project, "down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    main()
