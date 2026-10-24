#!/usr/bin/env python3
"""#508 rig for Linux and macOS: drive `java -jar mock-client-all.jar run` against fake_lobby.py
to HOSTING or PLAYING, SIGKILL the client JVM, and time how long its adapter and mock-game survive.

Usage: drive_kill_posix.py <java> <mock-client-all.jar> <mock-game binary or jar> <adapter jar>
                           <hosting|playing> <run-label> [--hide-setpriv]
--hide-setpriv runs the client with PATH pointing at an empty directory, so its setpriv probe
fails and it uses the reaper instead: the Linux stand-in for macOS, whose sockets close the same
way. Every binary is passed by absolute path, so nothing else needs PATH.
Processes are only ever signalled by PID after their command line was checked again.
"""
import os
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
java, client_jar, game_bin, adapter_jar, state, label = sys.argv[1:7]
hide_setpriv = "--hide-setpriv" in sys.argv[7:]
client_jar, game_bin, adapter_jar = (os.path.abspath(p) for p in (client_jar, game_bin, adapter_jar))
run_dir = os.path.join(HERE, "runs", f"{label}-{state}")
os.makedirs(run_dir, exist_ok=True)
SURVIVAL_WINDOW = 10.0  # the AC's bound
CLOCK = getattr(time, "CLOCK_MONOTONIC_RAW", time.CLOCK_MONOTONIC)


def now():
    return time.clock_gettime(CLOCK)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def ps(pid, field):
    # -ww: macOS ps otherwise cuts the command column short.
    out = subprocess.run(["ps", "-ww", "-o", f"{field}=", "-p", str(pid)], capture_output=True,
                         text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def exited(pid):
    """Gone, or a zombie (exited, not yet reaped by its new parent)."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False
    stat = ps(pid, "stat")
    return stat is None or stat.startswith("Z")


lobby_port, rpc, gpg, alobby = (free_port() for _ in range(4))
token = os.path.join(run_dir, "token.txt")
with open(token, "w") as fh:
    fh.write("not-a-real-token\n")
lobby = subprocess.Popen(
    [sys.executable, os.path.join(HERE, "fake_lobby.py"), str(lobby_port),
     os.path.join(run_dir, "lobby.log")],
    stdout=subprocess.PIPE, text=True)
assert lobby.stdout.readline().strip() == "listening"
env = dict(os.environ)
if hide_setpriv:
    env["PATH"] = tempfile.mkdtemp(prefix="nopath-", dir=run_dir)
out_path = os.path.join(run_dir, "client.out")
out = open(out_path, "w")
args = [
    java, "-jar", client_jar, "run",
    f"--lobby-websocket-url=ws://127.0.0.1:{lobby_port}",
    f"--oauth-access-token-file={token}",
    "--unique-id=rig",
    f"--ice-adapter-binary-path={adapter_jar}",
    f"--mock-game-binary-path={game_bin}",
    f"--ice-adapter-rpc-port={rpc}",
    f"--ice-adapter-gpg-net-port={gpg}",
    f"--ice-adapter-lobby-port={alobby}",
    "--host-title=rig", "--host-map=scmp_007", "--host-mod=faf", "--host-visibility=public",
]
if state == "hosting":
    args.append("--mock-game-launch-delay-seconds=-1")
client = subprocess.Popen(args, cwd=run_dir, stdout=out, stderr=subprocess.STDOUT, env=env,
                          start_new_session=True)
marker = "state entry: HOSTING" if state == "hosting" else "state entry: PLAYING"
deadline = now() + 120
text = ""
while now() < deadline and client.poll() is None:
    with open(out_path, errors="replace") as fh:
        text = fh.read()
    if marker in text:
        break
    time.sleep(0.1)
if marker not in text:
    print(f"{label}-{state}: {marker} not reached; exit={client.poll()}")
    client.kill()
    lobby.kill()
    sys.exit(1)

adapter_pid = int(re.search(r"ICE adapter started, pid=(\d+)", text).group(1))
game_pid = int(re.search(r"mock-game started, pid=(\d+)", text).group(1))
needles = {"adapter": (adapter_pid, f"--rpc-port {rpc}"), "game": (game_pid, f"--gpgnet-port {gpg}")}
for name, (pid, needle) in needles.items():
    line = ps(pid, "command")
    if line is None or needle not in line:
        print(f"{label}-{state}: {name} pid {pid} does not look like ours: {line!r}")
        client.kill()
        lobby.kill()
        sys.exit(1)

time.sleep(2)
listing = subprocess.run(["ps", "-ww", "-eo", "pid=,ppid=,args="], capture_output=True,
                         text=True).stdout
reaper = any(
    len(f) == 3 and f[1] == str(client.pid) and "OrphanReaper" in f[2]
    for f in (line.split(None, 2) for line in listing.splitlines()))
t0 = now()
os.kill(client.pid, signal.SIGKILL)
client.wait(30)
gone = {}
while now() - t0 < SURVIVAL_WINDOW:
    for name, (pid, _) in needles.items():
        if name not in gone and exited(pid):
            gone[name] = now() - t0
    if len(gone) == 2:
        break
    time.sleep(0.02)
lobby.kill()

survivors = []
for name, (pid, needle) in needles.items():
    if name not in gone:
        line = ps(pid, "command")
        if line is not None and needle in line:
            survivors.append(f"{name}:{pid}")
            os.kill(pid, signal.SIGKILL)
verdict = "PASS" if len(gone) == 2 else "FAIL"
fmt = lambda n: f"{gone[n]*1000:.0f} ms" if n in gone else f"alive at +{SURVIVAL_WINDOW:.0f} s"
print(f"{label}-{state}: {verdict} adapter {fmt('adapter')}, game {fmt('game')}, reaper {'yes' if reaper else 'no'}"
      + (f"; killed survivors {', '.join(survivors)}" if survivors else ""))
