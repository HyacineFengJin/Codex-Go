#!/usr/bin/env python3
"""Exercise the real local app-server without signing in or starting model inference."""
import json
import os
import re
from pathlib import Path
import selectors
import shutil
import subprocess
import tempfile
import time

version = re.search(r"versionName '([^']+)'", (Path(__file__).resolve().parent.parent / 'app/build.gradle').read_text())[1]

with tempfile.TemporaryDirectory(prefix='codex-go-protocol-') as temporary:
    root = Path(temporary)
    account = root / 'codex'; account.mkdir()
    workspace = root / 'workspace'; workspace.mkdir()
    (account / 'config.toml').write_text('[analytics]\nenabled = false\n')
    env = dict(os.environ, CODEX_HOME=str(account))
    process = subprocess.Popen([shutil.which('codex') or 'codex', 'app-server', '--listen', 'stdio://'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
    selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
    sequence = 0
    buffer = b''
    def call(method, params):
        global sequence, buffer
        sequence += 1
        process.stdin.write((json.dumps({'id': sequence, 'method': method, 'params': params}) + '\n').encode()); process.stdin.flush()
        until = time.monotonic() + 25
        while time.monotonic() < until:
            if b'\n' not in buffer:
                if not selector.select(1): continue
                data = os.read(process.stdout.fileno(), 65536)
                if not data: raise RuntimeError('App-server exited')
                buffer += data
            while b'\n' in buffer:
                line, buffer = buffer.split(b'\n', 1)
                message = json.loads(line)
                if message.get('id') != sequence: continue
                if 'error' in message: raise RuntimeError(method + ': ' + str(message['error']))
                return message['result']
        raise TimeoutError(method)
    try:
        call('initialize', {'clientInfo': {'name': 'codex_go_check', 'title': 'Codex Go check', 'version': version}, 'capabilities': {'experimentalApi': True}})
        process.stdin.write(b'{"method":"initialized","params":{}}\n'); process.stdin.flush()
        assert call('account/read', {'refreshToken': False})['account'] is None
        assert isinstance(call('model/list', {'limit': 100})['data'], list)
        result = call('thread/start', {
            'cwd': str(workspace), 'approvalPolicy': 'untrusted', 'sandbox': 'danger-full-access',
            'dynamicTools': [{'name': 'phone_observe', 'description': 'Observe phone screen', 'inputSchema': {'type': 'object', 'properties': {}, 'additionalProperties': False}}],
        })
        thread = result['thread']['id']
        assert thread
        assert isinstance(call('thread/list', {'limit': 50, 'sortKey': 'updated_at', 'sourceKinds': ['cli', 'vscode', 'appServer', 'unknown']})['data'], list)
        # New threads have no rollout until a user turn. History RPC existence is checked against a deliberately absent UUID.
        for method, params in [('thread/items/list', {'threadId': '00000000-0000-0000-0000-000000000000', 'limit': 100, 'sortDirection': 'asc'})]:
            try: call(method, params)
            except RuntimeError as failure:
                if '-32601' in str(failure):
                    try: call('thread/read', {'threadId': '00000000-0000-0000-0000-000000000000', 'includeTurns': True})
                    except RuntimeError as fallback:
                        assert '-32601' not in str(fallback), 'Legacy history API is missing'
                    print('Verified history fallback: this runtime does not implement thread/items/list.')
        print('PASS: real app-server initialization, isolated auth, model list, dynamic tools, thread start/list and history API')
        print('No model turn was started; no user credentials were read.')
    finally:
        selector.close(); process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
