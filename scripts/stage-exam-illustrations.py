#!/usr/bin/env python3
"""Prepare private PNG import SQL for a staged exam bank. No DB writes or publication."""
import argparse,hashlib,json,struct,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from diagnostics.import_exam_bank import canonical

def quote(value):return "'"+value.replace("'","''")+"'"
def stage(bank,folder):
 lines=['BEGIN;']
 for item in bank['items']:
  image=item.get('image')
  if not image:continue
  path=(folder/image['file']).resolve()
  if not path.is_relative_to(folder.resolve()):raise ValueError('Image outside private bank folder')
  data=path.read_bytes()
  if not data.startswith(b'\x89PNG\r\n\x1a\n') or len(data)>4*1024*1024 or struct.unpack('!II',data[16:24])!=(800,320):raise ValueError('Expected reviewed 800x320 PNG')
  digest=hashlib.sha256(canonical(item['problem']).encode()).hexdigest();png=hashlib.sha256(data).hexdigest()
  lines.append('INSERT INTO problem_illustration(id,problem_version,package_sha256,image_sha256,alt,caption,image_png,width,height) VALUES ('+','.join([quote(image['id']),quote(item['problem']['version']),quote(digest),quote(png),quote(image['alt']),quote(image['caption']),"decode("+quote(data.hex())+",'hex')",'800','320'])+');')
 lines.append('COMMIT;');return '\n'.join(lines)+'\n'
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--bank',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.write_text(stage(json.loads(a.bank.read_text()),a.bank.parent));print('Private image staging SQL written; no DB changed')
