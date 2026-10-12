"""Demo receiver only: in-memory deduplication, not a production consumer."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
seen=set()
class Handler(BaseHTTPRequestHandler):
 def do_POST(self):
  event=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  duplicate=event['eventId'] in seen
  seen.add(event['eventId'])
  print(json.dumps({'eventId':event['eventId'],'eventType':event['eventType'],'duplicate':duplicate}),flush=True)
  self.send_response(204); self.end_headers()
HTTPServer(('0.0.0.0',8090),Handler).serve_forever()
