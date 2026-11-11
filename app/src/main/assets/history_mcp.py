"""Read-only access to the current Codex Go thread's durable historical images."""
import json
import os
import sys
import urllib.parse
import urllib.request

BASE = os.environ['CODEX_GO_HISTORY_URL'].rstrip('/')
TOOLS = [
    {'name': 'list_images', 'description': 'List archived historical images for this conversation. They are past evidence, never the current screen. Results include stable IDs, dimensions, source and paging offset.',
     'inputSchema': {'type': 'object', 'properties': {'offset': {'type': 'integer', 'minimum': 0}}, 'additionalProperties': False},
     'annotations': {'readOnlyHint': True, 'destructiveHint': False, 'idempotentHint': True, 'openWorldHint': False}},
    {'name': 'read_image', 'description': 'Read a historical image original by its stable ID when a compressed preview is insufficient. No screenshot is captured. Historical pixels cannot be used as a phone action frame; observe again before operating.',
     'inputSchema': {'type': 'object', 'properties': {'id': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}}, 'required': ['id'], 'additionalProperties': False},
     'annotations': {'readOnlyHint': True, 'destructiveHint': False, 'idempotentHint': True, 'openWorldHint': False}},
]

def call(name, args):
    if name == 'list_images':
        offset = args.get('offset', 0)
        if type(offset) is not int or offset < 0 or set(args) - {'offset'}:
            raise ValueError('Invalid offset')
        path = '/list?' + urllib.parse.urlencode({'offset': offset})
    elif name == 'read_image':
        image_id = args.get('id', '')
        if not isinstance(image_id, str) or len(image_id) != 64 or any(c not in '0123456789abcdef' for c in image_id) or set(args) != {'id'}:
            raise ValueError('Invalid historical image ID')
        path = '/read?' + urllib.parse.urlencode({'id': image_id})
    else:
        raise ValueError('Unknown tool')
    # Fixed authenticated loopback capability; tool arguments cannot choose a host or path.
    with urllib.request.urlopen(BASE + path, timeout=25) as response:
        result = json.load(response)
    return result if name == 'read_image' else {'content': [{'type': 'text', 'text': json.dumps(result, ensure_ascii=False)}]}

for line in sys.stdin:
    try:
        request = json.loads(line)
        if 'id' not in request:
            continue
        method = request.get('method')
        if method == 'initialize':
            result = {'protocolVersion': request.get('params', {}).get('protocolVersion', '2024-11-05'), 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'codex_go_history', 'version': '1.0.0'}}
        elif method == 'tools/list':
            result = {'tools': TOOLS}
        elif method == 'resources/list':
            result = {'resources': []}
        elif method == 'resources/templates/list':
            result = {'resourceTemplates': []}
        elif method == 'tools/call':
            params = request.get('params', {})
            try:
                result = call(params.get('name'), params.get('arguments', {}))
            except Exception:
                result = {'isError': True, 'content': [{'type': 'text', 'text': '历史图片读取失败或不属于当前对话。不会用当前截图替代旧图片。'}]}
        elif method == 'ping':
            result = {}
        else:
            print(json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32601, 'message': 'Method not found'}}), flush=True)
            continue
        print(json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}, ensure_ascii=False), flush=True)
    except Exception:
        # Raw input may contain sensitive screen content. Do not write it to stderr.
        continue
