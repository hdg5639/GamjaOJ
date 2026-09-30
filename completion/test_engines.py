"""Run inside the isolated service image: python - < completion/test_engines.py."""
import json
import time
import unittest
import urllib.request


def complete(language, text, owner='engine-regression'):
    offset = len(text[:text.index('|')].encode('utf-16-le')) // 2
    source = text.replace('|', '', 1)
    request = urllib.request.Request('http://localhost:8090/complete', data=json.dumps(dict(language=language, source=source, offset=offset, owner=owner)).encode(), headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request, timeout=35) as response:
        result=json.load(response)
    assert not result.get('unavailable'), (language,'unavailable')
    return result['items']


class Engines(unittest.TestCase):
    def has(self, language, source, name):
        items=complete(language,source)
        self.assertTrue(any(name in item['label'] for item in items),(language,name,[i['label'] for i in items[:12]]))

    def test_java(self):
        for body,want in [('StringTokenizer st=new StringTokenizer("a"); st.|','nextToken'),
                          ('java.math.BigInteger n=java.math.BigInteger.ONE; n.|','isProbablePrime'),
                          ('java.util.Map<String,java.util.List<String>> m=new java.util.HashMap<>(); m.get("x").get(0).|','substring'),
                          ('Node n=new Node(); n.|','nextValue'),('create().|','nextValue'),('Node.|','staticValue'),
                          ('Strin|','String')]:
            self.has('JAVA','class Parent { public int inheritedValue(){return 1;} } class Node extends Parent { public String nextValue(){return "x";} public static void staticValue(){} } public class Main { Node create(){return new Node();} void run(){'+body+'} }',want)
        self.has('JAVA','class Parent { public int inheritedValue(){return 1;} } class Node extends Parent {} class Main { void run(){Node n=new Node(); n.|} }','inheritedValue')

    def test_cpp(self):
        head='#include <bits/stdc++.h>\nstruct Parent { int inheritedValue(); }; struct Node : Parent { std::string nextValue(); static int staticValue(); };\nNode create();\nint main(){'
        for body,want in [('Node n; n.|','nextValue'),('Node *n; n->|','nextValue'),('auto n=create(); n.|','nextValue'),('Node n; n.|','inheritedValue'),('std::vector<std::string> v; v.at(0).|','substr'),('std::vec|','vector'),('Node::|','staticValue')]:
            self.has('CPP',head+body+'\n}',want)

    def test_python(self):
        head='class Parent:\n    def inherited_value(self): return 1\nclass Node(Parent):\n    def next_value(self) -> str: return "x"\ndef create() -> Node: return Node()\n'
        for body,want in [('n=Node()\nn.|','next_value'),('n=Node()\nn.|','inherited_value'),('create().|','next_value'),('create().next_value().|','split'),('import collections as c\nc.|','deque'),('import math\nmath.|','isclose'),('crea|','create')]:
            self.has('PYTHON',head+body,want)

    def test_python_no_execution(self):
        self.has('PYTHON','raise RuntimeError("must not execute")\ntext="x"\ntext.|','split')

    def test_owner_change_removes_symbols(self):
        complete('PYTHON','class PrivateOwnerName: pass\nPriv|',owner='first-owner')
        items=complete('PYTHON','Priv|',owner='second-owner')
        self.assertFalse(any('PrivateOwnerName' in i['label'] for i in items))

if __name__=='__main__':unittest.main(verbosity=2)
