"""Typed NBT and region access for a stopped Vanilla 1.7.10 world."""
import gzip
import io
from pathlib import Path
import struct
import time
import zlib
from dataclasses import dataclass


@dataclass
class Tag:
    kind: int
    value: object


FORMATS = {1:'b', 2:'h', 3:'i', 4:'q', 5:'f', 6:'d'}


def decode(data):
    stream = io.BytesIO(data)

    def take(size):
        result = stream.read(size)
        if len(result) != size:
            raise ValueError('Truncated NBT')
        return result

    def number(fmt):
        return struct.unpack('>'+fmt, take(struct.calcsize('>'+fmt)))[0]

    def string():
        return take(number('H')).decode('utf-8')

    def count():
        value = number('i')
        if not 0 <= value <= 16777216:
            raise ValueError('Invalid NBT collection size')
        return value

    def payload(kind):
        if kind in FORMATS:
            value = number(FORMATS[kind])
        elif kind == 7:
            value = take(count())
        elif kind == 8:
            value = string()
        elif kind == 9:
            subtype, length = number('B'), count()
            value = (subtype, [payload(subtype) for _ in range(length)])
        elif kind == 10:
            value = {}
            while True:
                subtype = number('B')
                if not subtype:
                    break
                name = string()
                value[name] = payload(subtype)
        elif kind in (11, 12):
            value = [number('i' if kind == 11 else 'q') for _ in range(count())]
        else:
            raise ValueError('Unsupported NBT tag '+str(kind))
        return Tag(kind, value)

    kind = number('B')
    name = string()
    result = payload(kind)
    if stream.read(1):
        raise ValueError('Trailing NBT bytes')
    return name, result


def encode(name, root):
    def string(value):
        data = value.encode('utf-8')
        return struct.pack('>H', len(data))+data

    def payload(tag):
        kind, value = tag.kind, tag.value
        if kind in FORMATS:
            return struct.pack('>'+FORMATS[kind], value)
        if kind == 7:
            return struct.pack('>i', len(value))+value
        if kind == 8:
            return string(value)
        if kind == 9:
            subtype, values = value
            assert all(item.kind == subtype for item in values)
            return struct.pack('>Bi', subtype, len(values))+b''.join(payload(item) for item in values)
        if kind == 10:
            return b''.join(bytes([item.kind])+string(key)+payload(item) for key,item in value.items())+b'\0'
        if kind in (11, 12):
            return struct.pack('>i', len(value))+b''.join(struct.pack('>i' if kind == 11 else '>q', item) for item in value)
        raise ValueError('Unsupported NBT tag '+str(kind))
    return bytes([root.kind])+string(name)+payload(root)


def plain(tag):
    if tag.kind == 10:
        return {key:plain(value) for key,value in tag.value.items()}
    if tag.kind == 9:
        return [plain(item) for item in tag.value[1]]
    return tag.value


def read_file(path):
    return decode(gzip.decompress(Path(path).read_bytes()))


def write_file(path, name, tag):
    Path(path).write_bytes(gzip.compress(encode(name, tag), mtime=0))


def read_chunk(world, x, z):
    path = Path(world)/'region'/f'r.{x//32}.{z//32}.mca'
    raw = path.read_bytes()
    index = (x % 32)+32*(z % 32)
    location = int.from_bytes(raw[index*4:index*4+4], 'big')
    if not location:
        raise ValueError(f'Chunk {x},{z} is missing')
    offset = (location >> 8)*4096
    length = struct.unpack('>I', raw[offset:offset+4])[0]
    codec = raw[offset+4]
    data = raw[offset+5:offset+4+length]
    return decode({1:gzip.decompress, 2:zlib.decompress}[codec](data))


def write_chunk(world, x, z, name, tag):
    path = Path(world)/'region'/f'r.{x//32}.{z//32}.mca'
    raw = bytearray(path.read_bytes())
    index = (x % 32)+32*(z % 32)
    location = int.from_bytes(raw[index*4:index*4+4], 'big')
    data = zlib.compress(encode(name, tag))
    record = struct.pack('>I',len(data)+1)+b'\x02'+data
    count = (len(record)+4095)//4096
    assert count <= 255
    offset = location >> 8
    if not location or count > (location & 255):
        offset = (len(raw)+4095)//4096
    start, end = offset*4096, (offset+count)*4096
    if len(raw) < end:
        raw.extend(b'\0'*(end-len(raw)))
    raw[start:end] = record.ljust(count*4096,b'\0')
    raw[index*4:index*4+4] = ((offset << 8)|count).to_bytes(4,'big')
    raw[4096+index*4:4100+index*4] = int(time.time()).to_bytes(4,'big')
    temporary = path.with_suffix('.mca.prepared')
    temporary.write_bytes(raw)
    temporary.replace(path)


def block(world, x, y, z, cache):
    key = x//16,z//16
    if key not in cache:
        cache[key] = plain(read_chunk(world,*key)[1])['Level']
    chunk = cache[key]
    section = next((s for s in chunk.get('Sections',[]) if s['Y'] == y//16),None)
    if section is None:
        return 0
    index = ((y % 16)*16+(z % 16))*16+(x % 16)
    result = section['Blocks'][index]
    if 'Add' in section:
        nibble = (section['Add'][index//2] >> (4*(index % 2))) & 15
        result |= nibble << 8
    return result
