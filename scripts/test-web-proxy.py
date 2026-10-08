"""Isolated Docker regression: static Host routing, proxy transport and backend replacement.

Uses a transport fixture, never production DB or containers. Build frontend/out first.
"""
import json, os, pathlib, secrets, subprocess, time, urllib.error, urllib.request
root = pathlib.Path(__file__).resolve().parent.parent
prefix = 'gamja-web-test-' + secrets.token_hex(5)
image = os.environ.get('GAMJAOJ_FRONTEND_TEST_IMAGE', 'gamjaoj-frontend:test')
network, backend, frontend = prefix+'-net', prefix+'-app', prefix+'-front'
control = 'admin-console.test'

def docker(*args):
    return subprocess.check_output(['docker',*args],text=True).strip()

def main():
    docker('build','-f',str(root/'deploy/frontend/Dockerfile'),'-t',image,str(root))
    docker('pull','python:3.13-alpine')
    docker('network','create',network)
    def start_backend():
        docker('run','-d','--name',backend,'--network',network,'--network-alias','application',
            '--cpus','.25','--memory','64m','-v',str(root/'tests/web-proxy-backend.py')+':/fixture.py:ro',
            'python:3.13-alpine','python','/fixture.py')
    def request(path='/',host='gamjaoj.test',method='GET',data=None,headers=None):
        req=urllib.request.Request(base+path,headers={'Host':host,**(headers or {})},method=method,data=data)
        try: response=urllib.request.urlopen(req,timeout=8)
        except urllib.error.HTTPError as error: response=error
        with response: return response.status,response.read(),response.headers
    try:
        start_backend()
        docker('run','-d','--name',frontend,'--network',network,'--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges:true','--tmpfs','/tmp:size=16m,mode=1777',
            '--memory','64m','--cpus','.25','-e','CONTROL_OJ_HOST='+control,
            '-p','127.0.0.1::8080',image)
        info=json.loads(docker('inspect',frontend))[0]
        assert info['State']['Running'], docker('logs',frontend)
        port=info['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
        base='http://127.0.0.1:'+port
        for _ in range(60):
            try:
                if request('/web-healthz')[0]==200: break
            except OSError: pass
            time.sleep(.25)
        else: raise AssertionError('Frontend did not start')
        for _ in range(60):
            if request('/api/echo')[0]==200: break
            time.sleep(.25)
        else: raise AssertionError('Fixture not reachable: '+docker('logs',backend)+'\n'+docker('logs',frontend))
        public=(root/'frontend/out/index.html').read_bytes(); private=(root/'frontend/out/admin-console.html').read_bytes()
        assert request()[1]==public
        assert request(host=control)[1]==private
        for protected_path,protected_host in [('/',control),('/', 'gamjaoj.test'),('/admin-console.html',control)]:
            protected_headers=request(protected_path,host=protected_host)[2]
            assert protected_headers['X-Frame-Options']=='DENY'
            assert protected_headers['X-Content-Type-Options']=='nosniff'
            assert "frame-ancestors 'none'" in protected_headers['Content-Security-Policy']
        assert request(host=control+':443')[1]==private
        assert request(headers={'X-Forwarded-Host':control})[1]==public
        for path in ['/admin-console','/admin-console.html','/admin-console.txt','/api/admin/me']:
            assert request(path)[0]==404,path
        status,raw,headers=request('/api/echo?q=1',host=control,method='POST',data=b'{"value":42}',headers={'Content-Type':'application/json','Cookie':'GAMJAOJ_SESSION=fixture'})
        echo=json.loads(raw)
        assert status==200 and echo['host']==control and echo['body']=='{"value":42}', (status, echo)
        assert echo['path']=='/api/echo?q=1' and echo['cookie']=='GAMJAOJ_SESSION=fixture'
        assert headers['Set-Cookie'].startswith('GAMJAOJ_SESSION=fixture')
        assert request('/internal/judge/probe')[0]==200
        asset=next((root/'frontend/out/_next/static').rglob('*.js'))
        path='/'+str(asset.relative_to(root/'frontend/out'))
        assert request(path)[1]==asset.read_bytes() and 'immutable' in request(path)[2]['Cache-Control']
        docker('rm','-f',backend)
        for _ in range(5):
            assert request()[1]==public and request(host=control)[1]==private
            assert request(path)[1]==asset.read_bytes()
        status,raw,headers=request('/api/echo')
        assert status==503 and headers['Retry-After']=='3' and 'message' in json.loads(raw)
        # Hold the previous IP to prove Docker DNS refresh after recreation.
        blocker=prefix+'-blocker'
        docker('run','-d','--name',blocker,'--network',network,'python:3.13-alpine','sleep','60')
        try:
            start_backend()
            for _ in range(40):
                if request('/api/echo')[0]==200: break
                time.sleep(.25)
            else: raise AssertionError('Backend DNS did not recover')
        finally: docker('rm','-f',blocker)
        print('PASS: Host split, public admin denial, POST/query/cookie forwarding, static assets during backend removal, 503 API, recovery after new backend IP')
    finally:
        for container in [frontend,backend]:
            subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

if __name__=='__main__': main()
