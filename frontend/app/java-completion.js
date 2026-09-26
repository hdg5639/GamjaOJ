import { javaStandardNames } from './java-standard-completion';
import { ensureSyntaxTree, syntaxTree } from '@codemirror/language';

const identifier = /[\p{ID_Continue}$]+/u;
const cached = new WeakMap();
const lexicalCache = new WeakMap();
// The parser may recover unfinished comments/quotes as Java expressions while typing.
// Scan delimiters as well so that incomplete text never opens a name popup.
function nonCodeRanges(doc) {
  let ranges = lexicalCache.get(doc);
  if (ranges) return ranges;
  ranges = [];
  const text = doc.toString();
  for (let i=0; i<text.length;) {
    const from=i, pair=text.slice(i,i+2), quote=text[i];
    let closed=false;
    if (pair==='//') {
      const end=text.indexOf('\n',i+2);i=end<0?text.length:end;closed=end>=0;
    } else if (pair==='/*') {
      const end=text.indexOf('*/',i+2);i=end<0?text.length:end+2;closed=end>=0;
    } else if (quote==='"' || quote==="'") {
      i++;
      while(i<text.length) {
        if(text[i]==='\\'){i=Math.min(text.length,i+2);continue;}
        if(text[i++]===quote){closed=true;break;}
      }
    } else { i++;continue; }
    ranges.push({from,to:i,closed});
  }
  lexicalCache.set(doc,ranges);
  return ranges;
}

// Local declaration names and a fixed Java 8 standard-library catalog. This is not type resolution or a visibility checker.
export function javaNameCompletion(context) {
  if (context.state.readOnly) return null;
  const {state, pos} = context;
  const ranges=nonCodeRanges(state.doc);
  if(ranges.some(r=>pos>r.from && (pos<r.to || (!r.closed && pos===r.to))))return null;
  const tree = ensureSyntaxTree(state, state.doc.length, 25) || syntaxTree(state);
  for (let node = tree.resolveInner(pos, -1); node; node = node.parent) {
    if (/Comment|StringLiteral|CharacterLiteral|TextBlock/.test(node.name)) return null;
  }
  const word = context.matchBefore(identifier);
  if (!word && !context.explicit) return null;
  let names = cached.get(tree);
  if (!names) {
    names = [];
    tree.iterate({enter(node) {
      if (node.name !== 'Definition' || ranges.some(r=>node.from>=r.from && node.from<r.to)) return;
      const parent = node.node.parent?.name;
      const type = ['ClassDeclaration','InterfaceDeclaration','EnumDeclaration','AnnotationTypeDeclaration'].includes(parent) ? 'class' : parent === 'MethodDeclaration' ? 'function'
        : ['VariableDeclarator', 'FormalParameter', 'SpreadParameter', 'CatchFormalParameter', 'EnhancedForStatement', 'InferredParameters'].includes(parent) ? 'variable' : null;
      if (type) names.push({label:state.sliceDoc(node.from,node.to), type, from:node.from, to:node.to});
    }});
    cached.set(tree, names);
  }
  const options = new Map(javaStandardNames.map(name=>[`class:${name.label}`,name]));
  for (const name of names) {
    if (pos >= name.from && pos <= name.to) continue;
    const key = `${name.type}:${name.label}`;
    options.set(key, {label:name.label, type:name.type, detail:name.type === 'function' ? '문서 내 메서드' : name.type === 'class' ? '문서 내 타입' : '문서 내 변수'});
  }
  return {from:word?.from ?? pos, options:[...options.values()], validFor:/^[\p{ID_Continue}$]*$/u, commitCharacters:[]};
}
