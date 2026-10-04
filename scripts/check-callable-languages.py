"""Execute generated adapters on the actual sandbox images, without AI or production accounts."""
import json
from pathlib import Path
import tempfile
from runner.judge import Runner, LANGUAGES

root=Path('backend/target/native-callable-fixtures')
methods={'number':('long','long long','0'), 'text':('String','std::string','""'), 'ints':('int[]','std::vector<int>','{}'), 'longs':('long[]','std::vector<long long>','{}'), 'flags':('boolean[]','std::vector<bool>','{}'), 'texts':('String[]','std::vector<std::string>','{}'), 'flag':('boolean','bool','false')}
def implementation(language):
    if language=='JAVA':
        return 'public class UserSolution { int count; public void init(){count=0;} '+''.join('public '+t+' '+n+'('+t+' value){count++;return value;}' for n,(t,_,__) in methods.items())+'}'
    if language=='CPP':
        return '#include <bits/stdc++.h>\nclass UserSolution {int count; public: void init(){count=0;} '+''.join(c+' '+n+'('+c+' value){count++;return value;}' for n,(_,c,__) in methods.items())+'};'
    return 'class UserSolution:\n    def init(self): self.count=0\n'+''.join('    def '+n+'(self,value):\n        self.count+=1\n        return value\n' for n in methods)

def main():
    inputs=[[['init'],['number',9223372036854775807],['number',-9223372036854775808],['text','a b\n한글😀"\\'],['ints',[-2147483648,2147483647]],['longs',[-9223372036854775808,9223372036854775807]],['flags',[True,False]],['texts',['','a\tb','😀']],['flag',False],['init'],['ints',[]]], [['init'],['number',2]]]
    # This is the legacy Java driver's byte contract, including UTF-16 surrogate/space escapes.
    expected='9223372036854775807\n-9223372036854775808\n"a\\u0020b\\u000a한글\\ud83d\\ude00\\"\\\\"\n[-2147483648,2147483647]\n[-9223372036854775808,9223372036854775807]\n[true,false]\n["","a\\u0009b","\\ud83d\\ude00"]\nfalse\n[]\n2\n'
    for language in LANGUAGES:
        bundle=json.loads((root/(language+'.json')).read_text())
        plan={'version':'callable-types-'+language,'output_policy':'TOKEN_EXACT','callable':bundle,'tests':[{'id':'types','input':json.dumps(inputs,ensure_ascii=False),'output':expected}]}
        with tempfile.TemporaryDirectory() as directory:
            report=Runner(LANGUAGES[language]['image'],directory).judge(implementation(language).encode(),plan)
            if report['verdict']!='AC': raise AssertionError((language,report))
            print('PASS actual sandbox typed MULTI_API',language,flush=True)
        bundle=json.loads((root/('single-'+language+'.json')).read_text())
        source={'JAVA':'public class UserSolution {int count=0;public int solution(){return ++count;}}','CPP':'class UserSolution {int count=0;public:int solution(){return ++count;}};','PYTHON':'class UserSolution:\n    def __init__(self): self.count=0\n    def solution(self):\n        self.count+=1\n        return self.count\n'}[language]
        plan={'version':'callable-single-'+language,'output_policy':'TOKEN_EXACT','callable':bundle,'tests':[{'id':'independent','input':'[[["solution"]],[["solution"]]]','output':'1\n1\n'}]}
        with tempfile.TemporaryDirectory() as directory:
            runner=Runner(LANGUAGES[language]['image'],directory)
            report=runner.judge(source.encode(),plan)
            assert report['verdict']=='AC',(language,report)
            plan['tests'][0]['input']='[[["solution"],["solution"]]]'
            report=runner.judge(source.encode(),plan)
            assert report['verdict']=='RE',(language,report)
            print('PASS actual sandbox SINGLE_FUNCTION isolation/invalid second call',language,flush=True)
if __name__=='__main__':main()
