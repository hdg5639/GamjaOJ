"""Deploy reviewed CPU normalization and matching app/Runner builds on the app VM.

Keeps the completion/DB containers intact, backs up the DB/private environment,
and never rewrites existing submission or diagnostic execution snapshots.
"""
import argparse,fcntl,json,os,shutil,subprocess,tarfile,time,urllib.request,zipfile
from pathlib import Path

def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def main():
 os.umask(0o077);p=argparse.ArgumentParser();p.add_argument('--web-root',type=Path,required=True);p.add_argument('--runner-ssh',required=True);p.add_argument('--evidence',type=Path,required=True);args=p.parse_args()
 source=Path(__file__).resolve().parents[1];root=args.web_root;proof=json.loads((args.evidence/'release-review.json').read_text())
 if not proof['problems'] or not all(f['status'] in ('READY','EXCLUDED') for f in proof['fits'].values()):raise ValueError('CPU calibration is not reviewable')
 lock=(root/'.deploy.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);previous=(root/'current').resolve();stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());release=root/'releases'/stamp
 backup=root/'backups'/('cpu-time-'+stamp);backup.mkdir(parents=True,mode=0o700)
 shutil.copy2(root/'.env',backup/'environment.before')
 with (backup/'database.dump').open('wb') as output:run(['docker','exec','gamjaoj-postgres-1','pg_dump','-U','gamjaoj','-d','gamjaoj','-Fc'],stdout=output)
 shutil.copytree(previous,release);shutil.copy2(source/'backend/target/gamjaoj.jar',release/'backend/target/gamjaoj.jar');shutil.copytree(source/'runner',release/'runner',dirs_exist_ok=True)
 for name in ['cpu-release.sql','cpu-rollback.sql','release-review.json']:shutil.copy2(args.evidence/name,backup/name)
 image='gamjaoj-web:'+stamp;env=dict(os.environ,GAMJAOJ_IMAGE=image,GAMJAOJ_COMPLETION_IMAGE=(previous/'completion-image.txt').read_text().strip());compose=['docker','compose','--env-file',str(root/'.env'),'-f',str(release/'deploy/compose.yaml')]
 run(['docker','build','--network','none','-f',str(release/'deploy/Dockerfile'),'-t',image,str(release)])
 bundle=backup/'runner.tar.gz'
 with tarfile.open(bundle,'w:gz') as tar:
  tar.add(source/'runner',arcname='runner',filter=lambda info:None if '__pycache__' in info.name or info.name.endswith('.pyc') else info)
  tar.add(source/'deploy/gamjaoj-worker.service',arcname='deploy/gamjaoj-worker.service')
 run(['scp','-q',str(bundle),args.runner_ssh+':gamjaoj-worker/releases/'+stamp+'.tar.gz'])
 run(['ssh',args.runner_ssh,'bash','-s','--',stamp],input='''set -euo pipefail
stamp="$1"
cd "$HOME/gamjaoj-worker"
mkdir "releases/$stamp"
tar -xzf "releases/$stamp.tar.gz" -C "releases/$stamp"
readlink -f current > "releases/$stamp/previous-release.txt"
cp "$HOME/.config/systemd/user/gamjaoj-worker.service" "releases/$stamp/service.before"
systemctl --user stop gamjaoj-worker.service
''',text=True)
 active=subprocess.check_output(['docker','exec','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(*) FROM judge_job WHERE status='RUNNING';"],text=True).strip()
 if active!='0':
  run(['ssh',args.runner_ssh,'systemctl','--user','start','gamjaoj-worker.service']);raise RuntimeError('Runner jobs have not drained; old app is still running')
 config=(root/'.env').read_text();lines=[line for line in config.splitlines() if not line.startswith(('RUNNER_FUNCTIONAL_SLOTS=','RUNNER_USER_EXECUTION_MODE='))];(root/'.env').write_text('\n'.join(lines)+'\nRUNNER_FUNCTIONAL_SLOTS=5\nRUNNER_USER_EXECUTION_MODE=FUNCTIONAL\n')
 applied=False
 try:
  run(compose+['up','-d','--no-deps','--wait','--wait-timeout','180','application'],env=env)
  with (backup/'cpu-release.sql').open() as sql:run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-v','ON_ERROR_STOP=1'],stdin=sql)
  applied=True
  run(['ssh',args.runner_ssh,'bash','-s','--',stamp],input='''set -euo pipefail
stamp="$1"
cd "$HOME/gamjaoj-worker"
ln -s "releases/$stamp" "current-$stamp"
mv -Tf "current-$stamp" current
cp current/deploy/gamjaoj-worker.service "$HOME/.config/systemd/user/gamjaoj-worker.service"
systemctl --user daemon-reload
systemctl --user start gamjaoj-worker.service
systemctl --user is-active gamjaoj-worker.service
''',text=True)
  (release/'image.txt').write_text(image+'\n');(release/'cpu-deployment.json').write_text(json.dumps({'release':stamp,'previous':str(previous),'requestSlots':5,'testParallelism':4,'sandboxSlots':20,'calibration':proof},ensure_ascii=False,indent=2)+'\n')
  link=root/('current-'+stamp);link.symlink_to('releases/'+stamp);link.replace(root/'current')
 except Exception:
  if not applied:
   shutil.copy2(backup/'environment.before',root/'.env');rollback=dict(env,GAMJAOJ_IMAGE=(previous/'image.txt').read_text().strip());run(compose+['up','-d','--no-deps','--wait','--wait-timeout','180','application'],env=rollback);run(['ssh',args.runner_ssh,'systemctl','--user','start','gamjaoj-worker.service'])
  # After publishing CPU snapshots, retain the CPU-capable app. Downgrading it
  # would make newly frozen submissions unsupported by the old Runner.
  raise
 print('DEPLOYED',stamp,proof['problems'],'CPU-normalized problems; requests=5 tests=4 sandboxes=20',flush=True)
if __name__=='__main__':main()
