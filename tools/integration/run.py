#!/usr/bin/env python3
"""Run one isolated Vanilla/Forge acceptance session on Linux."""
import argparse
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import tarfile
import time
import uuid
import zipfile

from protocol import IdleClient, status
from nbt import Tag, read_chunk, write_chunk

HERE = Path(__file__).resolve().parent
SERVER_SHA256 = 'c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc'
NAMES = ['RngTradeTest'] + [f'RngIdle{i:02}' for i in range(1, 12)]


def identity(name):
    value = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    value[6] = (value[6] & 15) | 48
    value[8] = (value[8] & 63) | 128
    return str(uuid.UUID(bytes=bytes(value)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', required=True, type=Path, help='New output directory')
    parser.add_argument('--server-jar', required=True, type=Path)
    parser.add_argument('--launch', required=True, type=Path, help='RFG integration-launch.json')
    parser.add_argument('--java', required=True, help='Java 8 executable')
    parser.add_argument('--xvfb', default='Xvfb')
    parser.add_argument('--port', type=int, default=25578)
    parser.add_argument('--display', type=int, default=1941)
    parser.add_argument('--seconds', type=int, default=3600)
    parser.add_argument('--clock-age', type=int, default=24000, help='Initial baby lifetime in ticks')
    parser.add_argument('--stress', action='store_true', help='Pause the owned client after a forecast and while closed')
    parser.add_argument('--accept-eula', action='store_true')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Read https://www.minecraft.net/eula and pass --accept-eula to run the server')
    if not 1024 <= args.port <= 65535 or not 800 <= args.clock_age <= 24000:
        parser.error('Use port 1024..65535 and clock age 800..24000')
    jar = args.server_jar.resolve()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SERVER_SHA256:
        parser.error('Expected the official Vanilla 1.7.10 server JAR')
    launch = json.loads(args.launch.read_text())
    template = Path(launch['cwd'])
    mods = list((template / 'mods').glob('RNGTrader-*.jar'))
    if len(mods) != 1:
        parser.error('Build with -PintegrationHarness -PexportLaunch runObfClient first')
    with zipfile.ZipFile(mods[0]) as mod:
        if 'rngtrader/integration/Driver.class' not in mod.namelist():
            parser.error('The integration driver is absent; rebuild with -PintegrationHarness')
    lock = Path(f'/tmp/rngtrader-integration-{args.port}.lock').open('a+')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', args.port)) != errno.ECONNREFUSED:
            parser.error('The test port must be unused')
    out = args.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    server_dir, client_dir = out / 'server', out / 'client'
    server_dir.mkdir(); client_dir.mkdir()
    shutil.copy(jar, server_dir / 'server.jar')
    (client_dir / 'mods').mkdir()
    shutil.copy(mods[0], client_dir / 'mods' / mods[0].name)
    with tarfile.open(HERE / 'world.tar.gz') as archive:
        archive.extractall(server_dir, filter='data')
    if args.clock_age != 24000:
        name, chunk = read_chunk(server_dir / 'world', 1, 0)
        for entity in chunk.value['Level'].value['Entities'].value[1]:
            data = entity.value
            if data.get('id', Tag(8, '')).value == 'Cow' and data['Age'].value < 0:
                data['Age'] = Tag(3, -args.clock_age)
        write_chunk(server_dir / 'world', 1, 0, name, chunk)
    properties = dict(
        **{'server-ip': '127.0.0.1', 'server-port': args.port, 'online-mode': 'false',
           'white-list': 'true', 'max-players': 12, 'view-distance': 4,
           'level-name': 'world', 'level-type': 'FLAT', 'level-seed': 1,
           'generator-settings': '2;7,2x3,2;1;', 'generate-structures': 'false',
           'gamemode': 0, 'force-gamemode': 'true', 'difficulty': 1,
           'spawn-animals': 'true', 'spawn-monsters': 'false', 'spawn-npcs': 'true',
           'spawn-protection': 0, 'pvp': 'false', 'player-idle-timeout': 0,
           'snooper-enabled': 'false', 'enable-rcon': 'false', 'enable-query': 'false'})
    (server_dir / 'server.properties').write_text(''.join(f'{k}={v}\n' for k, v in properties.items()))
    (server_dir / 'eula.txt').write_text('eula=true\n')
    (server_dir / 'whitelist.json').write_text(json.dumps([dict(name=n, uuid=identity(n)) for n in NAMES]))
    (server_dir / 'ops.json').write_text('[]\n')
    (client_dir / 'options.txt').write_text('soundCategory_master:0.0\nsoundCategory_music:0.0\nfpsLimit:30\nrenderDistance:2\n')
    owned, logs, players = [], [], []

    def start(command, cwd, filename, **options):
        log = (out / filename).open('w'); logs.append(log)
        process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, **options)
        owned.append(process)
        return process

    def pause_client(duration, cause):
        event = dict(event='client_pause', duration=duration, cause=cause, monotonic=time.monotonic())
        with (out / 'disturbances.jsonl').open('a') as output:
            output.write(json.dumps(event) + '\n')
        os.kill(client.pid, signal.SIGSTOP)
        try:
            time.sleep(duration)
        finally:
            os.kill(client.pid, signal.SIGCONT)

    outcome = 'duration_limit'
    try:
        server = start([args.java, '-Xms256m', '-Xmx1g', '-jar', 'server.jar', 'nogui'],
                       server_dir, 'server.log', stdin=subprocess.PIPE)
        for _ in range(120):
            if server.poll() is not None:
                raise RuntimeError('Server exited during startup')
            if 'Done (' in (out / 'server.log').read_text():
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Server startup timeout')
        xvfb = start([args.xvfb, f':{args.display}', '-screen', '0', '854x480x24', '-nolisten', 'tcp', '-ac'], out, 'xvfb.log')
        time.sleep(.4)
        if xvfb.poll() is not None:
            raise RuntimeError('Xvfb exited')
        command = [('-Xmx2G' if s.startswith('-Xmx') else s) for s in launch['command']]
        command.insert(1, f'-Drngtrader.testPort={args.port}')
        for flag, value in [('--username', NAMES[0]), ('--gameDir', str(client_dir))]:
            command[command.index(flag) + 1] = value
        client = start(command, client_dir, 'client.log', env=dict(os.environ,
                       DISPLAY=f':{args.display}', LIBGL_ALWAYS_SOFTWARE='1', LP_NUM_THREADS='2', ALSOFT_DRIVERS='null'))
        (out / 'processes.json').write_text(json.dumps(dict(server=server.pid, client=client.pid, xvfb=xvfb.pid)))
        for _ in range(180):
            if client.poll() is not None:
                raise RuntimeError('Client exited during startup')
            try:
                if status('127.0.0.1', args.port)['players']['online'] == 1:
                    break
            except (OSError, EOFError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Trading client login timeout')
        for name in NAMES[1:]:
            player = IdleClient('127.0.0.1', args.port, name)
            players.append(player); player.wait_ready(); time.sleep(.15)
        print('READY', out, flush=True)
        started = time.monotonic(); reported = started; fed = started - 300
        trace = None; paused_plan = paused_close = False; receipt_tick = None; close_row = None
        while time.monotonic() - started < args.seconds:
            if (out / 'stop').exists():
                outcome = 'requested_stop'; break
            if client.poll() is not None or server.poll() is not None:
                raise RuntimeError('An owned test process exited')
            errors = [p.error for p in players if p.error]
            if errors:
                raise RuntimeError(str(errors))
            if time.monotonic() - fed >= 310:
                adults = [entity for entity, age in list(players[0].cows.items()) if age == 0]
                if len(adults) >= 2:
                    for entity in adults[:2]:
                        players[0].send(2, struct.pack('>iB', entity, 0))
                    fed = time.monotonic()
            if trace is None:
                files = list((client_dir / 'rngtrader/sessions').glob('*.jsonl'))
                if files:
                    trace = files[0].open()
            if trace:
                while True:
                    position = trace.tell(); line = trace.readline()
                    if not line.endswith('\n'):
                        trace.seek(position); break
                    row = json.loads(line); event = row['event']
                    if event == 'complete':
                        outcome = 'complete'
                    elif event == 'error':
                        outcome = 'error'
                    elif event == 'refresh':
                        print('Refresh', row['number'], row['appended'], flush=True)
                    if not args.stress:
                        continue
                    if event == 'plan' and not paused_plan:
                        pause_client(.25, 'forecast_recorded'); paused_plan = True
                    if event == 'close_for_refresh':
                        close_row = row; receipt_tick = None
                    if event == 'close_receipt' and row['complete']:
                        receipt_tick = row['bounds']['last']
                    if (event == 'clock_marker' and receipt_tick is not None and not paused_close
                            and row['tick'] >= receipt_tick + 34 and row['tick'] < receipt_tick + 40
                            and time.monotonic_ns() + 900_000_000 < close_row['sampleEarliest'] + 5_000_000_000):
                        pause_client(.6, 'merchant_closed'); paused_close = True
                if outcome in ('complete', 'error'):
                    break
            text = (out / 'client.log').read_text(errors='replace')
            if '[CHAT] [RNGTrader] FAIL ' in text:
                outcome = 'preparation_failed'; break
            if time.monotonic() - reported >= 30:
                print('Elapsed', round(time.monotonic() - started), 'seconds', flush=True); reported = time.monotonic()
            time.sleep(.01 if args.stress else .25)
        if trace:
            trace.close()
    finally:
        for player in players:
            player.close()
        if 'server' in locals() and server.poll() is None:
            server.stdin.write(b'stop\n'); server.stdin.flush()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
        for process in reversed(owned):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGCONT)
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait()
        for log in logs:
            log.close()
        (out / 'result.json').write_text(json.dumps(dict(outcome=outcome), indent=2) + '\n')
    print(outcome, flush=True)
    if outcome != 'complete':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
