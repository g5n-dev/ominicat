#!/usr/bin/env python3
"""Probe the voice example's local gateway without calling any model service."""
import argparse
import base64
import hashlib
import os
import socket
import time
import urllib.error
import urllib.request


def check(port):
    token = os.environ['OMINICAT_TOKEN']
    if not token.strip():
        raise ValueError('OMINICAT_TOKEN must be nonempty')
    authority = f'127.0.0.1:{port}'
    base = f'http://{authority}'
    for attempt in range(30):
        try:
            with urllib.request.urlopen(base + '/health', timeout=1) as response:
                assert response.status == 200
                assert response.read(1024) == b'{"status":"up"}'
            break
        except (OSError, urllib.error.URLError):
            if attempt == 29:
                raise
            time.sleep(1)

    try:
        urllib.request.urlopen(base + '/metrics', timeout=5)
        raise AssertionError('Gateway accepted unauthenticated metrics request')
    except urllib.error.HTTPError as error:
        assert error.code == 401, error.code
    request = urllib.request.Request(base + '/metrics', headers={'Authorization': f'Bearer {token}'})
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200
        assert b'ominicat_active_sessions' in response.read(65536)

    # The server permits absent Origin for native clients. A rejection control is
    # needed as well as a successful browser handshake to detect a stripped header.
    request = urllib.request.Request(base + '/ws', headers={
        'Authorization': f'Bearer {token}', 'Origin': 'https://unrelated.example.invalid',
        'Upgrade': 'websocket', 'Connection': 'Upgrade',
        'Sec-WebSocket-Key': base64.b64encode(os.urandom(16)).decode('ascii'),
        'Sec-WebSocket-Version': '13',
    })
    try:
        urllib.request.urlopen(request, timeout=5)
        raise AssertionError('Gateway accepted an unrelated browser Origin')
    except urllib.error.HTTPError as error:
        assert error.code == 403, error.code

    # Browser-style authentication exercises Host/Origin and subprotocol forwarding.
    key = base64.b64encode(os.urandom(16)).decode('ascii')
    bearer = base64.urlsafe_b64encode(token.encode()).decode('ascii').rstrip('=')
    with socket.create_connection(('127.0.0.1', port), timeout=5) as connection:
        handshake = (f'GET /ws HTTP/1.1\r\nHost: {authority}\r\nOrigin: {base}\r\n'
                     f'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n'
                     f'Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: ominicat, bearer.{bearer}\r\n\r\n')
        connection.sendall(handshake.encode('ascii'))
        header = bytearray()
        while not header.endswith(b'\r\n\r\n'):
            chunk = connection.recv(1)
            if not chunk or len(header) >= 16384:
                raise AssertionError('Missing or oversized WebSocket handshake')
            header.extend(chunk)
        lines = header.decode('ascii').split('\r\n')
        assert lines[0].split()[1] == '101', lines[0]
        fields = dict(line.lower().split(':', 1) for line in lines[1:] if ':' in line)
        expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        # Preserve case for the base64 accept value.
        accept = next(line.split(':', 1)[1].strip() for line in lines if line.lower().startswith('sec-websocket-accept:'))
        assert accept == expected
        assert fields['sec-websocket-protocol'].strip() == 'ominicat'
        # A masked normal-close frame ends the probe without submitting provider work.
        mask = os.urandom(4)
        connection.sendall(b'\x88\x82' + mask + bytes((3 ^ mask[0], 232 ^ mask[1])))

    print('Voice gateway passed: health, authentication, Origin rejection and browser WebSocket upgrade; no model requests.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18088)
    check(parser.parse_args().port)
