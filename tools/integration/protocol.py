"""Vanilla 1.7.10 status queries and stationary offline-login clients."""
import collections
import io
import json
import socket
import struct
import threading
import time


def varint(value):
    value &= 0xffffffff
    output = bytearray()
    while True:
        part = value & 127
        value >>= 7
        output.append(part | (128 if value else 0))
        if not value:
            return bytes(output)


def exact(stream, size):
    output = bytearray()
    while len(output) < size:
        part = stream.read(size - len(output))
        if not part:
            raise EOFError("Server closed the connection")
        output.extend(part)
    return bytes(output)


def read_varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        part = exact(stream, 1)[0]
        value |= (part & 127) << shift
        if part < 128:
            return value
    raise ValueError("Invalid VarInt")


def string(value):
    value = value.encode('utf-8')
    return varint(len(value)) + value


def read_string(stream):
    length = read_varint(stream)
    if length > 1048576:
        raise ValueError("String exceeds packet limit")
    return exact(stream, length).decode('utf-8')


def metadata_age(stream):
    age=None
    while True:
        header=exact(stream,1)[0]
        if header==127:return age
        kind,index=header>>5,header&31
        if kind in (0,1,2,3,6):
            fmt={0:'>b',1:'>h',2:'>i',3:'>f',6:'>iii'}[kind]
            value=struct.unpack(fmt,exact(stream,struct.calcsize(fmt)))[0]
        elif kind==4:value=read_string(stream)
        elif kind==5:
            item=struct.unpack('>h',exact(stream,2))[0]
            if item>=0:
                exact(stream,3);length=struct.unpack('>h',exact(stream,2))[0]
                if length>=0:exact(stream,length)
            value=None
        else:raise ValueError('Unknown metadata kind')
        if index==12 and kind==2:age=value


def frame(packet_id, payload=b''):
    payload = varint(packet_id) + payload
    return varint(len(payload)) + payload


def read_frame(stream):
    length = read_varint(stream)
    if not 0 < length <= 8388608:
        raise ValueError("Invalid packet length")
    packet = io.BytesIO(exact(stream, length))
    return read_varint(packet), packet


def handshake(host, port, state):
    return frame(0, varint(5) + string(host) + struct.pack('>H', port) + varint(state))


def status(host, port, timeout=3):
    with socket.create_connection((host, port), timeout) as connection:
        connection.sendall(handshake(host, port, 1) + frame(0))
        with connection.makefile('rb') as stream:
            packet_id, payload = read_frame(stream)
            if packet_id != 0:
                raise ValueError("Expected a status response")
            result = json.loads(read_string(payload))
    if result.get('version', {}).get('protocol') != 5:
        raise ValueError("Expected Minecraft 1.7.10 protocol 5")
    return result


class IdleClient:
    def __init__(self, host, port, name, event=lambda *args, **kwargs: None):
        self.name = name
        self.event = event
        self.position = None
        self.rotation = None
        self.dimension = None
        self.game_mode = None
        self.health = None
        self.error = None
        self.login_name = None
        self.roster = []
        self.cows = {}
        self.keepalives = 0
        self.last_packet = time.monotonic()
        self.packet_counts = collections.Counter()
        self.stop_event = threading.Event()
        self.send_lock = threading.Lock()
        self.condition = threading.Condition()
        self.socket = socket.create_connection((host, port), 10)
        self.socket.settimeout(None)
        self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.stream = self.socket.makefile('rb')
        self.socket.sendall(handshake(host, port, 2) + frame(0, string(name)))
        self.reader = threading.Thread(target=self._read, name=name+' reader', daemon=True)
        self.heartbeat = threading.Thread(target=self._heartbeat, name=name+' heartbeat', daemon=True)
        self.reader.start()
        self.heartbeat.start()

    def send(self, packet_id, payload=b''):
        with self.send_lock:
            self.socket.sendall(frame(packet_id, payload))

    def report(self, kind, **values):
        self.event(kind, player=self.name, **values)
        with self.condition:
            self.condition.notify_all()

    def wait_ready(self, timeout=30):
        deadline = time.monotonic() + timeout
        with self.condition:
            while not (self.position is not None and self.game_mode == 0 and self.name in self.roster):
                if self.error:
                    raise RuntimeError(self.error)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('Login did not complete for '+self.name)
                self.condition.wait(min(remaining, .25))

    def _heartbeat(self):
        while not self.stop_event.wait(.05):
            if self.position is not None:
                try:
                    self.send(3, b'\x01')
                except OSError:
                    return

    def _read(self):
        login = True
        try:
            while not self.stop_event.is_set():
                packet_id, payload = read_frame(self.stream)
                self.last_packet = time.monotonic()
                self.packet_counts[packet_id] += 1
                if login:
                    if packet_id == 0:
                        raise RuntimeError(read_string(payload))
                    if packet_id == 1:
                        raise RuntimeError('This environment requires online-mode=false')
                    if packet_id != 2:
                        raise RuntimeError('Unexpected login packet '+str(packet_id))
                    profile = read_string(payload)
                    self.login_name = read_string(payload)
                    if self.login_name != self.name:
                        raise RuntimeError('Unexpected login profile')
                    login = False
                    self.report('login', uuid=profile)
                elif packet_id == 0:
                    token = exact(payload, 4)
                    self.send(0, token)
                    self.keepalives += 1
                elif packet_id == 1:
                    entity, mode, dimension, difficulty, maximum = struct.unpack('>iBbBB', exact(payload, 8))
                    self.game_mode, self.dimension = mode & 7, dimension
                    if self.game_mode != 0 or dimension != 0:
                        raise RuntimeError('Expected Survival in the Overworld')
                    self.report('join', entity=entity, game_mode=self.game_mode, dimension=dimension)
                elif packet_id == 6:
                    self.health = struct.unpack('>f', exact(payload, 4))[0]
                    if self.health <= 0:
                        raise RuntimeError('Idle player died; pause RNGTrader and reset the session')
                elif packet_id == 7:
                    raise RuntimeError('Unexpected respawn or dimension change')
                elif packet_id == 8:
                    x, eye, z, yaw, pitch, ground = struct.unpack('>dddff?', exact(payload, 33))
                    self.position = (x, eye - 1.62, z)
                    self.rotation = (yaw, pitch)
                    self.send(6, struct.pack('>ddddff?', x, eye-1.62, eye, z, yaw, pitch, ground))
                    self.report('position', position=self.position, rotation=self.rotation)
                elif packet_id == 0x0f:
                    entity=read_varint(payload);kind=exact(payload,1)[0];exact(payload,21)
                    age=metadata_age(payload)
                    if kind==92 and age is not None:self.cows[entity]=age
                elif packet_id == 0x1c:
                    entity=struct.unpack('>i',exact(payload,4))[0];age=metadata_age(payload)
                    if entity in self.cows and age is not None:self.cows[entity]=age
                elif packet_id == 0x38:
                    name = read_string(payload)
                    online, latency = struct.unpack('>?h', exact(payload, 3))
                    changed = False
                    if online and name not in self.roster:
                        self.roster.append(name)
                        changed = True
                    elif not online and name in self.roster:
                        self.roster.remove(name)
                        changed = True
                    if changed:
                        self.report('roster', names=list(self.roster))
                elif packet_id == 0x40:
                    raise RuntimeError(read_string(payload))
        except Exception as error:
            if not self.stop_event.is_set():
                self.error = str(error)
                self.report('disconnected', reason=self.error)
        finally:
            self.stop_event.set()
            with self.condition:
                self.condition.notify_all()

    def close(self):
        self.stop_event.set()
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.reader.join(timeout=2)
        self.heartbeat.join(timeout=2)
        self.stream.close()
        self.socket.close()
