"""Optional finite creation smoke load. Not a capacity benchmark.
Example: python3 scripts/load.py --requests 200 --concurrency 8
"""
import argparse,concurrent.futures,json,time,uuid,urllib.request,urllib.error,statistics,platform
parser=argparse.ArgumentParser()
parser.add_argument('--url',default='http://localhost:8080')
parser.add_argument('--requests',type=int,default=100)
parser.add_argument('--concurrency',type=int,default=4)
args=parser.parse_args()
assert 1<=args.concurrency<=100 and 1<=args.requests<=10000
payload=json.dumps({'customerId':'load-demo','items':[{'productId':'00000000-0000-4000-8000-000000000001','quantity':1}]}).encode()
def request(_):
 started=time.perf_counter()
 req=urllib.request.Request(args.url+'/api/v1/orders',payload,{'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())},method='POST')
 try:
  with urllib.request.urlopen(req,timeout=15) as response:status=response.status;response.read()
 except urllib.error.HTTPError as error:status=error.code
 except (urllib.error.URLError,TimeoutError):status=0
 return (time.perf_counter()-started)*1000,status
started=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:results=list(executor.map(request,range(args.requests)))
elapsed=time.perf_counter()-started
latencies=sorted(ms for ms,status in results)
def percentile(p):return latencies[min(len(latencies)-1,int((len(latencies)-1)*p))]
print(json.dumps({'environment':platform.platform(),'url':args.url,'operation':'create-order','concurrency':args.concurrency,'requests':args.requests,'durationSeconds':elapsed,'achievedRequestsPerSecond':args.requests/elapsed,'errorRate':sum(status!=201 for _,status in results)/args.requests,'latencyMs':{'median':statistics.median(latencies),'p95':percentile(.95),'p99':percentile(.99)},'limitations':'finite closed-loop creation-only smoke; no production-capacity or spike proof'},indent=2))
