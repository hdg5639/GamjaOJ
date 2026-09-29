"""Real sandbox coverage; reference fixtures never enter the public problem API."""
import io
import tarfile
import json
import os
from pathlib import Path
import tempfile
import unittest
from runner.judge import Runner, LANGUAGES, ROOT, unpack_classes, InfrastructureError

class LanguageArtifactTests(unittest.TestCase):
    def test_native_and_python_artifacts_are_whitelisted_and_not_links(self):
        for artifact,name in [('binary','main'),('python','Main.py')]:
            for actual,kind,allowed in [(name,tarfile.REGTYPE,True),(name,tarfile.SYMTYPE,False),
                                        ('../'+name,tarfile.REGTYPE,False),('Other.py',tarfile.REGTYPE,False)]:
                with self.subTest(artifact=artifact,name=actual,kind=kind),tempfile.TemporaryDirectory() as directory:
                    data=io.BytesIO()
                    with tarfile.open(fileobj=data,mode='w') as tar:
                        entry=tarfile.TarInfo(actual);entry.type=kind;entry.linkname='/etc/passwd' if kind==tarfile.SYMTYPE else ''
                        entry.size=1 if kind==tarfile.REGTYPE else 0;tar.addfile(entry,io.BytesIO(b'x'))
                    if allowed:
                        unpack_classes(data.getvalue(),Path(directory),artifact)
                        self.assertEqual(0o555 if artifact=='binary' else 0o444,(Path(directory)/name).stat().st_mode&0o777)
                    else:
                        with self.assertRaises(InfrastructureError):unpack_classes(data.getvalue(),Path(directory),artifact)

@unittest.skipUnless(os.environ.get('GAMJAOJ_DOCKER_TESTS') == '1', 'real Docker opt-in')
class LanguageDockerTests(unittest.TestCase):
    def test_problem_wall_budget_changes_verdict_and_is_reported_exactly(self):
        from runner.judge import checked_profile
        problem={'version':'time-budget-test','output_policy':'TOKEN_EXACT','tests':[{'id':'one','input':'','output':'3\n'}]}
        source=b'import time\ntime.sleep(1.3)\nprint(3)\n'
        for seconds,expected in [(1,'TLE'),(4,'AC')]:
            with self.subTest(seconds=seconds), tempfile.TemporaryDirectory() as directory:
                runner=Runner(LANGUAGES['PYTHON']['image'],directory)
                runner.profile=checked_profile(LANGUAGES['PYTHON'] | {'testWallSeconds':seconds},'PYTHON',runner.image)
                report=runner.judge(source,problem)
                self.assertEqual(expected,report['verdict'],report)
                self.assertEqual(seconds,report['execution_profile']['testWallSeconds'])

    def judge(self, language, source, problem, verdict):
        with tempfile.TemporaryDirectory() as directory:
            report=Runner(LANGUAGES[language]['image'],directory).judge(source.encode(),problem)
        self.assertEqual(verdict,report['verdict'],report)
        self.assertEqual(LANGUAGES[language],report['execution_profile'])
        self.assertEqual(language,report['language'])
        return report

    def test_diagnostic_a_and_b_all_hidden_cases(self):
        refs=json.loads((ROOT/'tests/fixtures/diagnostic-language-references.json').read_text())
        for bank in ('core-a-v2','core-b-v1'):
            for item in json.loads((ROOT/f'diagnostics/{bank}.json').read_text())['items']:
                for language in ('CPP','PYTHON'):
                    with self.subTest(bank=bank,version=item['problem']['version'],language=language):
                        report=self.judge(language,refs[item['problem']['version']][language],item['problem'],'AC')
                        print('BANK',language,item['problem']['version'],len(report['tests']),max(t['wall_ms'] for t in report['tests']),flush=True)

    def test_general_packages_and_custom_run(self):
        sources={
            'sum-v1':{'CPP':'#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}', 'PYTHON':'a,b=map(int,input().split());print(a+b)'},
            'total-v1':{'CPP':'#include <iostream>\nint main(){int n;long long s=0,x;std::cin>>n;while(n--){std::cin>>x;s+=x;}std::cout<<s;}', 'PYTHON':'import sys\na=list(map(int,sys.stdin.read().split()));print(sum(a[1:]))'},
            'valid-parentheses-v1':{'CPP':'#include <iostream>\n#include <string>\nint main(){std::string s;std::cin>>s;int n=0;bool ok=true;for(char c:s){n+=c==\'(\'?1:-1;if(n<0)ok=false;}std::cout<<(ok&&n==0?"YES":"NO");}', 'PYTHON':'s=input().strip();n=0;ok=True\nfor c in s:\n n+=1 if c=="(" else -1\n if n<0:ok=False\nprint("YES" if ok and n==0 else "NO")'}}
        for version, entries in sources.items():
            for language,source in entries.items():
                with self.subTest(version=version,language=language):
                    self.judge(language,source,json.loads((ROOT/f'problems/{version}.json').read_text()),'AC')
        for language,source in sources['sum-v1'].items():
            report=self.judge(language,source,{'version':'custom-v1','output_policy':'RUN_ONLY','tests':[{'id':'custom-input','input':'20 22\n','output':''}]},'OK')
            self.assertEqual('42',report['tests'][0]['stdout'].strip())

    def test_failure_verdicts_and_limits(self):
        cases={
          'CPP':{'WA':'#include <iostream>\nint main(){std::cout<<4;}', 'CE':'int main( {', 'RE':'int main(){return 7;}', 'TLE':'int main(){for(;;){asm volatile("");}}', 'MLE':'#include <cstdlib>\n#include <cstring>\n#include <unistd.h>\nint main(){for(;;){volatile char *p=(char*)malloc(16*1024*1024);if(!p){sleep(30);return 1;}for(int i=0;i<16*1024*1024;i+=4096)p[i]=1;}}', 'OLE':'#include <iostream>\nint main(){for(;;)std::cout<<"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";}'},
          'PYTHON':{'WA':'print(4)', 'CE':'return 1', 'RE':'raise RuntimeError("test")', 'TLE':'while True: pass', 'MLE':'a=[]\nwhile True:a.append(bytearray(16*1024*1024))', 'OLE':'while True:print("x"*4096)'}}
        problem={'version':'limits-v1','output_policy':'TOKEN_EXACT','tests':[{'id':'one','input':'','output':'3'}]}
        for language,entries in cases.items():
            for verdict,source in entries.items():
                with self.subTest(language=language,verdict=verdict):
                    r=self.judge(language,source,problem,verdict)
                    print('LIMIT',language,verdict,r.get('tests',[]),flush=True)
