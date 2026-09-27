#!/usr/bin/env python3
"""Report, and optionally check, one session's ICE negotiation timing (WBS-5.1.1, #366).

Usage:
  scripts/ci/ice-timing.py <mockclient.jsonl> [--peers P] [--expect-delay-ms N]

Reads the client log of one session (live-integration.yml gives each session its own directory, so
its logs/mockclient.jsonl holds exactly one) and prints one line per ICE link, offering peer first:

  A->B: offer leg 237 ms, gathering 601 ms, answer leg 255 ms, gap 1093 ms, no restart

The offer leg runs from the offerer's `awaitingCandidates` (its candidates are sent) to the
answerer's `gathering` (the offer has arrived). The answerer then gathers its own candidates, and
the answer leg runs from its `awaitingCandidates` (the answer is sent) to the offerer's `checking`
(the answer has arrived). The gap, the offerer's `awaitingCandidates` to `checking`, is all three
together and is what the adapter's 6000 ms offerer timer bounds. A restart is the offerer reporting
`disconnected` before its first `connected`: the timer fired or ICE failed, and the adapter started
over. A restarted run is no sample of the baseline, since its gap spans the retry.

Every peer of a session logs from the one client JVM, so these spans are read off one clock. That
clock is the wall clock, though, and a WSL2 host steps it back by seconds; a record more than
500 ms behind an earlier one is reported, since a leg spanning the step reads that much short.

With --expect-delay-ms N it checks as well as reports, and exits 1 unless the log has every link
of --peers P, each with both legs at least 2 x N and no restart. That bar assumes every peer holds
each relayed candidate N ms, as the root --ice-relay-delay-ms makes them: the offer and the answer
each pass two relays. A delay on one peer only halves it. Without the flag it only reports, and
exits 0 whatever the log holds.
"""
import argparse
import json
import re
import sys
from datetime import datetime

PEER_ICE = re.compile(r"^peer ice: local=(\d+) remote=(\d+) state=(\w+)$")
PEER_CONNECT = re.compile(r"^peer connect: login=.* id=(\d+) offer=(true|false)$")
TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S.%f"
# Records reach the log in appender order, at most a few milliseconds apart; a step back larger
# than this is the wall clock moving rather than threads interleaving.
CLOCK_STEP_MS = 500
# The offering adapter's timer for an answer (java-ice-adapter 3.3.14, PeerIceModule).
OFFERER_TIMER_MS = 6000


def read(path):
    """The log's records in file order, as (time, component, instance, message). A line that does
    not parse, such as a last line cut short, is skipped."""
    records = []
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            try:
                record = json.loads(line)
                when = datetime.strptime(record["timestamp"], TIMESTAMP_FORMAT)
            except (ValueError, KeyError, TypeError):
                continue
            message = record.get("message")
            if isinstance(message, str):
                records.append((when, record.get("component"), record.get("instance") or "", message))
    return records


def span_ms(start, end):
    """Milliseconds from start to end, or None if either is missing."""
    if start is None or end is None:
        return None
    return round((end - start).total_seconds() * 1000)


def clock_step_ms(records):
    """The largest backward step of the wall clock in file order, in ms, or 0."""
    worst = 0
    latest = None
    for when, _, _, _ in records:
        if latest is not None and span_ms(when, latest) > CLOCK_STEP_MS:
            worst = max(worst, span_ms(when, latest))
        if latest is None or when > latest:
            latest = when
    return worst


def first(events, state):
    """When an instance first reported a state for one remote peer, or None."""
    return next((when for when, reported in events if reported == state), None)


def restarted(events):
    """Whether the offerer went `disconnected` before its first `connected`, by file order, which
    a wall-clock step cannot reorder."""
    for _, state in events:
        if state == "connected":
            return False
        if state == "disconnected":
            return True
    return False


def measure(records):
    """Each link the log shows an offer for, as (offerer, answerer, spans), in offer order."""
    ids = {}
    states = {}
    offers = []
    for when, component, instance, message in records:
        # The client's own lines only: captured adapter and game output carries the same label.
        if component != "MockClient":
            continue
        ice = PEER_ICE.match(message)
        if ice:
            ids.setdefault(instance, int(ice.group(1)))
            states.setdefault((instance, int(ice.group(2))), []).append((when, ice.group(3)))
            continue
        connect = PEER_CONNECT.match(message)
        if connect and connect.group(2) == "true" and (instance, int(connect.group(1))) not in offers:
            offers.append((instance, int(connect.group(1))))
    by_id = {local: instance for instance, local in ids.items()}
    measured = []
    for offerer, answerer_id in offers:
        answerer = by_id.get(answerer_id)
        mine = states.get((offerer, answerer_id), [])
        theirs = states.get((answerer, ids.get(offerer)), [])
        sent = first(mine, "awaitingCandidates")
        arrived = first(theirs, "gathering")
        answered = first(theirs, "awaitingCandidates")
        checking = first(mine, "checking")
        spans = {
            "offer": span_ms(sent, arrived),
            "gathering": span_ms(arrived, answered),
            "answer": span_ms(answered, checking),
            "gap": span_ms(sent, checking),
            "restarted": restarted(mine),
        }
        measured.append((offerer or "?", answerer or "?", spans))
    return measured


def describe(offerer, answerer, spans):
    def value(key):
        return "n/a" if spans[key] is None else f"{spans[key]} ms"

    restart = "ICE restarted, no baseline sample" if spans["restarted"] else "no restart"
    return (
        f"{offerer}->{answerer}: offer leg {value('offer')}, gathering {value('gathering')}, "
        f"answer leg {value('answer')}, gap {value('gap')}, {restart}"
    )


def restart_cause(spans):
    """What most likely used up the offerer's timer, from the first attempt's spans."""
    offer, gathering = spans["offer"], spans["gathering"]
    if offer is None or gathering is None:
        return "the offerer's timer fired or ICE failed, and the spans around it were not all logged"
    # The answer passes the same relays and the same lobby as the offer, so its leg is taken to
    # match the offer's; what the timer leaves for gathering is 6000 ms less both legs.
    allowance = OFFERER_TIMER_MS - 2 * offer
    if gathering > allowance:
        return (
            f"gathering took {gathering} ms of the about {allowance} ms the timer left it, so slow "
            "gathering (STUN) used up the timer, not the delay alone"
        )
    return (
        f"gathering took {gathering} ms, inside the about {allowance} ms the timer left it, so the "
        "delay itself or a slow lobby used up the timer"
    )


def problems(spans, delay):
    """Why a link fails the delay run's check, empty when it passes."""
    found = []
    for leg, what in (("offer", "the offer"), ("answer", "the answer")):
        if spans[leg] is None:
            found.append(f"no {leg} leg was logged")
        elif spans[leg] < 2 * delay:
            found.append(
                f"{leg} leg {spans[leg]} ms is under 2 x {delay} ms, so a relay did not hold "
                f"{what} for the delay"
            )
    if spans["restarted"]:
        found.append("ICE restarted: " + restart_cause(spans))
    return found


def main():
    parser = argparse.ArgumentParser(description="Report one session's ICE negotiation timing.")
    parser.add_argument("log", help="the session's logs/mockclient.jsonl")
    parser.add_argument("--peers", type=int, help="the session's peer count")
    parser.add_argument(
        "--expect-delay-ms",
        type=int,
        help="check that every peer held each relayed candidate this long, and exit 1 if not",
    )
    args = parser.parse_args()
    delay = args.expect_delay_ms
    if delay is not None and (delay < 1 or args.peers is None or args.peers < 2):
        parser.error("--expect-delay-ms needs a positive delay and --peers of at least 2")

    try:
        records = read(args.log)
    except OSError as error:
        print(f"no ICE timing: cannot read {args.log} ({error.strerror})")
        return 0 if delay is None else 1
    links = measure(records)
    if not links:
        print(f"no ICE timing: no 'peer connect: ... offer=true' line in {args.log}")
        return 0 if delay is None else 1

    failed = False
    for offerer, answerer, spans in links:
        print(describe(offerer, answerer, spans))
        if delay is None:
            if spans["restarted"]:
                print(f"  {offerer}->{answerer} restarted: {restart_cause(spans)}")
            continue
        for problem in problems(spans, delay):
            print(f"  FAIL {offerer}->{answerer}: {problem}")
            failed = True
    if args.peers is not None and len(links) != args.peers * (args.peers - 1) // 2:
        print(f"{len(links)} ICE link(s) in the log, where {args.peers} peers make "
              f"{args.peers * (args.peers - 1) // 2}")
        failed = failed or delay is not None
    step = clock_step_ms(records)
    if step:
        print(f"note: the wall clock stepped back {step} ms during this log, so a leg spanning the "
              "step reads up to that much short")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
