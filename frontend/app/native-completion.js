import {ensureSyntaxTree, syntaxTree} from '@codemirror/language';
import {afterDot} from './member-completion-source';

const catalogs = {
  CPP: {
    type: 'string vector array deque list stack queue priority_queue set multiset unordered_set map multimap unordered_map pair tuple bitset size_t',
    function: 'sort stable_sort reverse lower_bound upper_bound binary_search find count min max min_element max_element accumulate gcd lcm abs swap next_permutation make_pair make_tuple getline stoi stoll to_string push_back pop_back emplace_back push pop top front back begin end size empty clear insert erase resize reserve substr find_first_of',
    variable: 'cin cout cerr endl stringstream istringstream ostringstream numeric_limits',
    keyword: 'auto bool char int long double float void const constexpr static struct class public private return if else for while break continue switch case default using namespace std include'
  },
  PYTHON: {
    type: 'int float str bool list tuple dict set frozenset bytes range',
    function: 'print input len enumerate zip map filter sorted reversed sum min max abs all any round pow divmod ord chr open iter next isinstance append extend insert pop remove clear index count sort reverse split join strip replace startswith endswith items keys values get setdefault update add discard union intersection read readline readlines write heappush heappop heapify heappushpop heapreplace bisect_left bisect_right insort gcd lcm sqrt ceil floor factorial combinations permutations product accumulate',
    class: 'deque Counter defaultdict',
    variable: 'sys stdin stdout math heapq bisect collections itertools functools inf',
    keyword: 'def class return if elif else for while in not and or is None True False import from as pass break continue try except finally with lambda yield'
  }
};
const options = Object.fromEntries(Object.entries(catalogs).map(([language, groups])=>[language,
  Object.entries(groups).flatMap(([type, words])=>words.split(' ').map(label=>({label,type,detail:language==='CPP'?'C++17 표준 후보':'Python 3 표준 후보'})))
]));
const caches = {CPP:new WeakMap(),PYTHON:new WeakMap()};
const nonCode = /Comment|String|CharLiteral|CharacterLiteral/;
// Local names and curated standard names, without a language server or inferred receiver types.
export function nativeNameCompletion(language) {
  return context => {
    const {state,pos} = context;
    if(state.readOnly)return null;
    const tree=ensureSyntaxTree(state,state.doc.length,25)||syntaxTree(state);
    for(let node=tree.resolveInner(pos,-1);node;node=node.parent){
      if(nonCode.test(node.name))return null;
    }
    const word=context.matchBefore(/[\p{ID_Continue}]+/u);
    if(!word&&!context.explicit)return null;
    if(afterDot(state,word?.from??pos,language,pos))return null; // members come from memberCompletionSource
    let names=caches[language].get(tree);
    if(!names){
      names=[];
      tree.iterate({enter(node){
        if(nonCode.test(node.name))return false;
        if(!['Identifier','FieldIdentifier','TypeIdentifier','VariableName','PropertyName'].includes(node.name))return;
        const parent=node.node.parent?.name||'';
        const type=/Function|Call/.test(parent)?'function':/Class|Type/.test(parent)?'type':'variable';
        names.push({label:state.sliceDoc(node.from,node.to),type,from:node.from,to:node.to});
      }});
      caches[language].set(tree,names);
    }
    const choices=new Map(options[language].map(option=>[option.label,option]));
    for(const name of names){
      if(pos>=name.from&&pos<=name.to)continue;
      choices.set(name.label,{label:name.label,type:name.type,detail:'문서 내 이름'});
    }
    return {from:word?.from??pos,options:[...choices.values()],validFor:/^[\p{ID_Continue}]*$/u,commitCharacters:[]};
  };
}
export const cppNameCompletion=nativeNameCompletion('CPP');
export const pythonNameCompletion=nativeNameCompletion('PYTHON');
