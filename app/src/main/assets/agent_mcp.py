#!/usr/bin/python3
"""Private stdio MCP adapter for the mobile client's explicitly registered tools."""
import json,sys,urllib.request
url=sys.argv[1]
def request(value):
 data=json.dumps(value).encode();req=urllib.request.Request(url,data,{'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=None) as response:return json.load(response)
for line in sys.stdin:
 try:
  message=json.loads(line);method=message.get('method');identifier=message.get('id')
  if identifier is None:continue
  if method=='initialize':result={'protocolVersion':'2024-11-05','capabilities':{'tools':{}},'serverInfo':{'name':'codex-go-mobile','version':'1'}}
  elif method=='tools/list':result=request({'method':'list'})
  elif method=='tools/call':
   answer=request({'method':'call',**message.get('params',{})});items=[]
   for item in answer.get('contentItems',[]):
    if item.get('type')=='inputText':items.append({'type':'text','text':item.get('text','')})
    elif item.get('type')=='inputImage':
     data=item.get('imageUrl',item.get('url',''))
     if data.startswith('data:'):
      head,data=data.split(',',1);items.append({'type':'image','mimeType':head[5:].split(';')[0],'data':data})
   result={'content':items or [{'type':'text','text':'No output'}],'isError':not answer.get('success',False)}
  elif method=='ping':result={}
  else:raise ValueError('Unsupported method')
  output={'jsonrpc':'2.0','id':identifier,'result':result}
 except Exception as error:output={'jsonrpc':'2.0','id':message.get('id'),'error':{'code':-32603,'message':str(error)[:1000]}}
 print(json.dumps(output,ensure_ascii=False),flush=True)
