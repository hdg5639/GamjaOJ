import {snippetCompletion} from '@codemirror/autocomplete';
import {ensureSyntaxTree,syntaxTree} from '@codemirror/language';

// Explicit editor abbreviations, independent of language-server availability.
// Tab indents snippet lines using the editor's configured indent unit.
export const codeSnippets={
 JAVA:[
  ['sysout sout','줄바꿈 출력','System.out.println(${1});${0}'],
  ['souf soutf','서식 출력','System.out.printf("${1:%s\\n}", ${2:value});${0}'],
  ['serr syserr','표준 오류 출력','System.err.println(${1});${0}'],
  ['main psvm','main 메서드','public static void main(String[] args) {\n\t${0}\n}'],
  ['fori','인덱스 반복문','for (int ${1:i} = 0; ${1:i} < ${2:n}; ${1:i}++) {\n\t${0}\n}'],
  ['iter foreach','향상된 for문','for (${1:Type} ${2:item} : ${3:items}) {\n\t${0}\n}'],
  ['ifn','null 검사','if (${1:value} == null) {\n\t${0}\n}'],
  ['inn','null이 아닌지 검사','if (${1:value} != null) {\n\t${0}\n}'],
  ['br','BufferedReader 선언','java.io.BufferedReader ${1:br} = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));${0}'],
  ['st','StringTokenizer 선언','java.util.StringTokenizer ${1:st} = new java.util.StringTokenizer(${2:line});${0}'],
  ['sb','StringBuilder 선언','StringBuilder ${1:sb} = new StringBuilder();${0}'],
 ],
 CPP:[
  ['main','main 함수','int main() {\n\t${0}\n\treturn 0;\n}'],
  ['cout','줄바꿈 출력','std::cout << ${1:value} << \'\\n\';${0}'],
  ['cin','입력','std::cin >> ${1:value};${0}'],
  ['fastio','빠른 입출력 설정','std::ios::sync_with_stdio(false);\nstd::cin.tie(nullptr);${0}'],
  ['fori','인덱스 반복문','for (int ${1:i} = 0; ${1:i} < ${2:n}; ++${1:i}) {\n\t${0}\n}'],
  ['foreach','범위 기반 for문','for (const auto& ${1:item} : ${2:items}) {\n\t${0}\n}'],
  ['sortv','컨테이너 정렬','std::sort(${1:values}.begin(), ${1:values}.end());${0}'],
  ['vec','vector 선언','std::vector<${1:int}> ${2:values}(${3:n});${0}'],
 ],
 PYTHON:[
  ['main','main 함수와 실행 가드','def main():\n\t${1:pass}\n\n\nif __name__ == "__main__":\n\tmain()${0}'],
  ['ifmain','main 실행 가드','if __name__ == "__main__":\n\t${0}'],
  ['pr','출력','print(${1})${0}'],
  ['fori','range 반복문','for ${1:i} in range(${2:n}):\n\t${0}'],
  ['fore','enumerate 반복문','for ${1:i}, ${2:value} in enumerate(${3:values}):\n\t${0}'],
  ['defn','함수 정의','def ${1:solve}(${2}):\n\t${0}'],
  ['readints','한 줄의 정수 리스트 입력','${1:values} = list(map(int, input().split()))${0}'],
  ['readint','정수 하나 입력','${1:n} = int(input())${0}'],
  ['fastio','빠른 입력 설정','import sys\ninput = sys.stdin.readline${0}'],
 ]
};
const options=Object.fromEntries(Object.entries(codeSnippets).map(([language,rows])=>[language,
 rows.flatMap(([aliases,description,template])=>aliases.split(' ').map(label=>snippetCompletion(template,{
  label,type:'text',detail:`코드 템플릿 · ${description}`,info:'Tab/Enter로 펼치기 · Tab/Shift+Tab으로 입력 위치 이동 · Esc로 종료',boost:50
 })))
]));
// Incomplete quotes/comments may be recovered as code by the incremental parser.
function insideText(language,text){
 for(let i=0;i<text.length;){
  const c=text[i],pair=text.slice(i,i+2);
  if((language==='PYTHON'&&c==='#')||(language!=='PYTHON'&&pair==='//')){
   const end=text.indexOf('\n',i);if(end<0)return true;i=end+1;continue;
  }
  if(language!=='PYTHON'&&pair==='/*'){
   const end=text.indexOf('*/',i+2);if(end<0)return true;i=end+2;continue;
  }
  if(language==='CPP'&&c==='R'&&text[i+1]==='"'){
   const raw=text.slice(i).match(/^R"([^\s()\\]{0,16})\(/);
   if(raw){const close=')'+raw[1]+'"',end=text.indexOf(close,i+raw[0].length);if(end<0)return true;i=end+close.length;continue;}
  }
  if(c==='"'||c==="'"){
   if(language==='CPP'&&c==="'"&&/[0-9a-fA-F]/.test(text[i-1]||'')&&/[0-9a-fA-F]/.test(text[i+1]||'')){i++;continue;}
   const quote=(language!=='CPP'&&text.slice(i,i+3)===c.repeat(3))?c.repeat(3):c;
   i+=quote.length;let closed=false;
   while(i<text.length){
    if(text[i]==='\\'){i+=2;continue;}
    if(text.startsWith(quote,i)){i+=quote.length;closed=true;break;}
    i++;
   }
   if(!closed)return true;
   continue;
  }
  i++;
 }
 return false;
}
export function snippetCompletionSource(language){
 return context=>{
  const {state,pos}=context;
  if(state.readOnly)return null;
  const word=context.matchBefore(/[A-Za-z][A-Za-z0-9_]*/);
  if(!word)return null;
  // Templates begin a statement/declaration, never a member, string or comment.
  if(state.sliceDoc(state.doc.lineAt(pos).from,word.from).trim())return null;
  if(insideText(language,state.sliceDoc(0,pos)))return null;
  const tree=ensureSyntaxTree(state,pos,25)||syntaxTree(state);
  for(let node=tree.resolveInner(pos,-1);node;node=node.parent)
   if(/Comment|String|CharLiteral|CharacterLiteral|TextBlock/.test(node.name))return null;
  return {from:word.from,options:options[language]||[],validFor:/^[A-Za-z][A-Za-z0-9_]*$/,commitCharacters:[]};
 };
}
