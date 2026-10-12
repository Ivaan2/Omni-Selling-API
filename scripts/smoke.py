"""Run against a started API + receiver; do not print credentials."""
import os,json,uuid,time,base64,urllib.request,urllib.error
base=os.environ.get('API_URL','http://localhost:8080')
key=str(uuid.uuid4())
def call(method,path,data=None,key=None,admin=False,expected=200):
 headers={'Content-Type':'application/json','X-Correlation-ID':'smoke-'+key_prefix}
 if key:headers['Idempotency-Key']=key
 if admin:
  credentials=os.environ.get('ADMIN_USERNAME','admin')+':'+os.environ['ADMIN_PASSWORD']
  headers['Authorization']='Basic '+base64.b64encode(credentials.encode()).decode()
 request=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
 try:
  with urllib.request.urlopen(request,timeout=10) as response:status=response.status;body=response.read();location=response.headers.get('Location')
 except urllib.error.HTTPError as error:status=error.code;body=error.read();location=None
 assert status==expected,(method,path,status,body.decode())
 return json.loads(body) if body else None,location
key_prefix=key[:8]
catalog,_=call('GET','/api/v1/products')
product=next(p for p in catalog['products'] if p['available'])
request={'customerId':'smoke-customer','items':[{'productId':product['id'],'quantity':2}]}
order,location=call('POST','/api/v1/orders',request,key+'-create',expected=201)
assert location=='/api/v1/orders/'+order['id']
replay,_=call('POST','/api/v1/orders',request,key+'-create',expected=201)
assert replay==order
paid,_=call('POST',location+'/pay',key=key+'-pay')
assert paid['status']=='PAID' and paid['payment']['status']=='SUCCEEDED'
for attempt in range(60):
 current,_=call('GET',location)
 if current['fulfilment']:break
 time.sleep(0.5)
assert current['fulfilment']['status']=='PENDING'
call('POST',location+'/cancel',expected=409)
call('PATCH','/internal'+location,admin=True,expected=409)
unpaid,_=call('POST','/api/v1/orders',request,key+'-unpaid',expected=201)
path='/api/v1/orders/'+unpaid['id']
cancelled,_=call('POST',path+'/cancel')
assert cancelled['status']=='CANCELLED'
call('PATCH','/internal'+path,admin=True)
call('PATCH','/internal'+path,admin=True)
call('GET',path,expected=404)
# Delete the unavailable seed product, leaving purchasable products for future runs.
product_id='00000000-0000-4000-8000-000000000003'
call('PATCH','/internal/api/v1/products/'+product_id,admin=True)
call('PATCH','/internal/api/v1/products/'+product_id,admin=True)
call('GET','/v3/api-docs')
call('GET','/actuator/health/readiness')
print(json.dumps({'result':'PASS','orderId':order['id'],'payment':paid['payment']['status'],'fulfilment':current['fulfilment']['status'],'endpointsChecked':7}))
