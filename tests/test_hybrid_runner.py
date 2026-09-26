"""Real sandbox smoke for the adapter's Java 8 protocols, not semantic/publication proof."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from runner.judge import Runner, ROOT


@unittest.skipUnless(os.environ.get('GAMJAOJ_DOCKER_TESTS') == '1', 'real Docker opt-in')
class HybridRunnerDockerTests(unittest.TestCase):
    def test_generator_validator_reference_and_oracle_protocols(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = Runner((ROOT / 'runner/java-image.txt').read_text().strip(), tmp)
            version = 'hybrid-check-00000000-0000-0000-0000-000000000001'
            def run(source, value):
                report = runner.judge(source.encode(), {'version': version, 'output_policy': 'RUN_ONLY',
                    'tests': [{'id': 'custom-input', 'input': value, 'output': ''}]})
                self.assertEqual('OK', report['verdict'], report)
                return report['tests'][0]['stdout']
            generator = r'''public class Main { public static void main(String[] args) {
                long seed=new java.util.Scanner(System.in).nextLong();
                System.out.println("[\"1 4\\n2 3\\n\",\"1 1\\n2 3\\n\",\"1 3\\n2 3\\n\",\"1 2\\n2 3\\n\"]");
            }}'''
            inputs = json.loads(run(generator, '123456789\n'))
            self.assertEqual(4, len(inputs))
            validator = '''public class Main { public static void main(String[] args) {
                try {java.util.Scanner s=new java.util.Scanner(System.in);int n=s.nextInt(),w=s.nextInt();
                boolean ok=n>=1&&n<=100&&w>=1&&w<=1000;
                for(int i=0;i<n;i++){int c=s.nextInt(),v=s.nextInt();ok &= c>=1&&c<=1000&&v>=1&&v<=10000;}
                System.out.println(ok&&!s.hasNext()?"VALID":"INVALID");}catch(Exception e){System.out.println("INVALID");}
            }}'''
            report = runner.judge(validator.encode(), {'version': version, 'output_policy': 'TOKEN_EXACT',
                'tests': [{'id': 'input-'+str(i), 'input': value, 'output': 'VALID\n'} for i, value in enumerate(inputs)]})
            self.assertEqual('AC', report['verdict'], report)
            reference = '''public class Main {public static void main(String[] args) {
                java.util.Scanner s=new java.util.Scanner(System.in);int n=s.nextInt(),w=s.nextInt();int[] dp=new int[w+1];
                for(int i=0;i<n;i++){int c=s.nextInt(),v=s.nextInt();for(int j=w;j>=c;j--)dp[j]=Math.max(dp[j],dp[j-c]+v);}
                System.out.println(dp[w]);}}'''
            oracle = '''public class Main {public static void main(String[] args) {
                java.util.Scanner s=new java.util.Scanner(System.in);int n=s.nextInt(),w=s.nextInt();int[] c=new int[n],v=new int[n];
                for(int i=0;i<n;i++){c[i]=s.nextInt();v[i]=s.nextInt();}int best=0;
                for(int mask=0;mask<(1<<n);mask++){int cost=0,value=0;for(int i=0;i<n;i++)if((mask&(1<<i))!=0){cost+=c[i];value+=v[i];}
                if(cost<=w)best=Math.max(best,value);}System.out.println(best);}}'''
            self.assertEqual('3', run(reference, inputs[0]).strip())
            self.assertEqual('3', run(oracle, inputs[0]).strip())
            self.assertEqual('INVALID', run(validator, '1 4\n0 3\n').strip())

            finite = []
            for w in (1, 2):
                for cost in (1, 2):
                    for value in (1, 2):
                        finite.append((f'1 {w}\n{cost} {value}\n', value if cost <= w else 0))
            for w in (1, 2):
                for a in (1, 2):
                    for b in (1, 2):
                        answer = 2 if a+b <= w else 1 if min(a, b) <= w else 0
                        finite.append((f'2 {w}\n{a} 1\n{b} 1\n', answer))
            plan = {'version': version, 'output_policy': 'TOKEN_EXACT', 'tests': [
                {'id': f'finite-{i}', 'input': value, 'output': str(answer)+'\n'}
                for i, (value, answer) in enumerate(finite)]}
            self.assertEqual(16, len(plan['tests']))
            for source in (reference, oracle):
                report = runner.judge(source.encode(), plan)
                self.assertEqual('AC', report['verdict'], report)
            # A shared wrong answer is rejected against server answers, not accepted by agreement.
            wrong = 'public class Main {public static void main(String[] a){System.out.println(1);}}'
            self.assertEqual('WA', runner.judge(wrong.encode(), plan)['verdict'])
            # Incorrect unbounded reuse must also fail in the tiny domain.
            unbounded = reference.replace('for(int j=w;j>=c;j--)', 'for(int j=c;j<=w;j++)')
            self.assertNotEqual(reference, unbounded)
            self.assertEqual('WA', runner.judge(unbounded.encode(), plan)['verdict'])
            invalids = ['0 1\n', '1 -1\n1 1\n', '1 1\n0 1\n', '1 1\n1 0\n', '1 1\n1 1\n9\n']
            invalid_plan = {'version': version, 'output_policy': 'TOKEN_EXACT', 'tests': [
                {'id': f'invalid-{i}', 'input': value, 'output': 'INVALID\n'} for i, value in enumerate(invalids)]}
            self.assertEqual('AC', runner.judge(validator.encode(), invalid_plan)['verdict'])

            for source, index in ((unbounded, 4), (reference.replace('for(int j=w;j>=c;j--)', 'for(int j=w;j>c;j--)'), 0)):
                witness = dict(plan, tests=[plan['tests'][index]])
                self.assertEqual('WA', runner.judge(source.encode(), witness)['verdict'])
            stress_tests = [
                {'id': 'maximum-transitions', 'input': '100 1000\n'+'1 10000\n'*100, 'output': '1000000\n'},
                {'id': 'maximum-cost', 'input': '100 1000\n'+'1000 10000\n'*100, 'output': '10000\n'}]
            stress_plan = dict(plan, tests=stress_tests)
            valid_stress = dict(plan, tests=[dict(test, output='VALID\n') for test in stress_tests])
            self.assertEqual('AC', runner.judge(validator.encode(), valid_stress)['verdict'])
            # Large inputs go only to the reference, never to the bounded exhaustive oracle.
            for _ in range(2):
                report = runner.judge(reference.encode(), stress_plan)
                self.assertEqual('AC', report['verdict'], report)
                for test in report['tests']:
                    self.assertGreaterEqual(test['wall_ms'], 0)
                    self.assertLessEqual(test['wall_ms'], 4000)

            generated = json.loads(run(generator, '-73481234\n'))
            random_inputs = ['3 3\n1 3\n2 1\n3 2\n', '4 8\n3 1\n3 2\n2 3\n1 1\n',
                             '3 1\n2 1\n3 1\n2 3\n', '4 4\n1 1\n1 2\n2 2\n3 3\n']
            all_inputs = list(dict.fromkeys([finite[4][0], finite[0][0]] + [t['input'] for t in stress_tests] + generated + random_inputs))
            def answer_and_tiny(value):
                data = list(map(int, value.split())); n, w = data[:2]; items = list(zip(data[2::2], data[3::2])); best = [0]*(w+1)
                for cost, val in items:
                    for capacity in range(w, cost-1, -1):
                        best[capacity] = max(best[capacity], best[capacity-cost]+val)
                tiny = n <= 4 and w <= 8 and all(cost <= 3 and val <= 3 for cost, val in items)
                return str(best[w])+'\n', tiny
            cases = [{'id': f'package-{i}', 'input': value, 'output': answer_and_tiny(value)[0]} for i, value in enumerate(all_inputs)]
            self.assertLessEqual(len(cases), 20)
            final = dict(plan, title='선택 문제', statement='검토 대기 중인 실행 패키지', tests=cases,
                         samples=[{'input': cases[i]['input'], 'output': cases[i]['output']} for i in (0, 1)])
            validation = dict(plan, tests=[dict(t, output='VALID\n') for t in cases])
            self.assertEqual('AC', runner.judge(validator.encode(), validation)['verdict'])
            self.assertEqual('AC', runner.judge(reference.encode(), dict(plan, tests=cases))['verdict'])
            tiny = dict(plan, tests=[t for t in cases if answer_and_tiny(t['input'])[1]])
            self.assertLess(len(tiny['tests']), len(cases))
            self.assertEqual('AC', runner.judge(oracle.encode(), tiny)['verdict'])
            final_hashes = []
            for _ in range(2):
                report = runner.judge(reference.encode(), final)
                self.assertEqual('AC', report['verdict'], report)
                self.assertLessEqual(sum(t['wall_ms'] for t in report['tests']), 40000)
                final_hashes.append(report['problem_sha256'])
            self.assertEqual(final_hashes[0], final_hashes[1])


if __name__ == '__main__':
    unittest.main()
