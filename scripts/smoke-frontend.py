"""Verify deployed static entrypoints and authenticated API routing without changing user data."""
import argparse
from pathlib import Path
import urllib.error, urllib.parse, urllib.request

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--static-dir',type=Path,required=True)
    args=parser.parse_args()
    config=dict(line.split('=',1) for line in args.env_file.read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    base='http://'+config.get('BIND_ADDRESS','127.0.0.1')+':'+config.get('FRONTEND_HTTP_PORT','18082')
    public=urllib.parse.urlsplit(config.get('PUBLIC_BASE_URL') or 'http://localhost').hostname
    def request(path,host=public):
        try: response=urllib.request.urlopen(urllib.request.Request(base+path,headers={'Host':host}),timeout=10)
        except urllib.error.HTTPError as error: response=error
        with response:return response.status,response.read()
    assert request('/web-healthz')[0]==200
    assert request('/')[1]==(args.static_dir/'index.html').read_bytes()
    assert request('/api/me')[0]==401
    assert request('/api/admin/me')[0]==404
    control=config.get('CONTROL_OJ_HOST')
    if control:
        assert request('/',control)[1]==(args.static_dir/'admin-console.html').read_bytes()
        assert request('/api/admin/me',control)[0]==401
    asset=next((args.static_dir/'_next/static').rglob('*.js'))
    assert request('/'+str(asset.relative_to(args.static_dir)))[1]==asset.read_bytes()
    print('PASS: independent frontend health, public/private HTML, static JS, API and admin routing')

if __name__=='__main__':main()
