import {semanticPriority} from './completion-policy';
import {syntaxTree} from '@codemirror/language';
import {insertCompletionText} from '@codemirror/autocomplete';

const types={1:'text',2:'method',3:'function',4:'constructor',5:'property',6:'variable',7:'class',8:'interface',9:'namespace',10:'property',12:'constant',13:'enum',14:'keyword',15:'text',21:'constant',22:'class',25:'type'};
function positionOffset(doc, position) {
  if (!position || !Number.isInteger(position.line) || !Number.isInteger(position.character)
      || position.line<0 || position.line>=doc.lines || position.character<0) return null;
  const line=doc.line(position.line+1);
  return position.character<=line.length ? line.from+position.character : null;
}

/** Apply only inert text edits to this exact document version. No LSP commands are executed. */
export function semanticOption(item, state, from, to, member=false) {
  const edit=item.textEdit, range=edit?.replace||edit?.range;
  const start=range?positionOffset(state.doc,range.start):from;
  const end=range?positionOffset(state.doc,range.end):to;
  if(start==null||end==null||start>from||end<to||end<start)return null;
  let text=edit?.newText??item.insertText??item.label;
  if(typeof text!=='string'||text.length>8192)return null;
  // We request plain text, but tolerate servers returning snippet placeholders.
  if(item.insertTextFormat===2)text=text.replace(/\$\{\d+:([^}]*)\}/g,'$1').replace(/\$\{\d+\}|\$\d+/g,'');
  const callable=[2,3,4].includes(item.kind);
  if(callable&&!text.includes('(')&&!/^\s*\(/.test(state.sliceDoc(end,end+16)))text+='()';
  const label=item.filterText||item.label?.match(/^[\p{ID_Continue}$]+/u)?.[0]||item.label;
  if(!label)return null;
  const additions=(item.additionalTextEdits||[]).map(edit=>({from:positionOffset(state.doc,edit.range?.start),to:positionOffset(state.doc,edit.range?.end),insert:edit.newText}));
  if(additions.some(e=>e.from==null||e.to==null||e.from>e.to||typeof e.insert!=='string'||e.insert.length>8192||e.from<=end&&e.to>=start))return null;
  return {label,displayLabel:item.label,type:types[item.kind]||'variable',detail:item.detail||'',boost:semanticPriority(item,member),
    apply(view, completion, currentFrom, currentTo) {
      if(view.state.doc!==state.doc)return; // Results never modify a newer draft.
      const changes=[...additions,{from:start,to:end,insert:text}].sort((a,b)=>a.from-b.from);
      if(changes.some((e,i)=>i&&e.from<changes[i-1].to))return;
      let cursor=start+text.length;
      if(callable&&text.endsWith('()'))cursor--;
      for(const e of additions)if(e.to<=start)cursor+=e.insert.length-(e.to-e.from);
      view.dispatch({...insertCompletionText(view.state,text,currentFrom,currentTo),changes,selection:{anchor:cursor}});
    }};
}

/** Real Java/C++/Python analysis, alongside immediate local completion while the service warms. */
export function semanticCompletionSource(language) {
  let csrf=null, request=null, revision=0;
  return async context=>{
    const {state,pos}=context;
    context.abortOnDocChange=true;
    if(state.readOnly)return null;
    for(let node=syntaxTree(state).resolveInner(pos,-1);node;node=node.parent)
      if(/Comment|String|CharLiteral|CharacterLiteral|TextBlock/.test(node.name))return null;
    const word=context.matchBefore(/[\p{ID_Continue}$]+/u);
    const before=state.sliceDoc(Math.max(0,pos-2),pos);
    if(!context.explicit&&!word&&!/[.>:]$/.test(before))return null;
    // Keep one in-flight analysis per editor; cancelled UI requests still warm the language server.
    const ticket=++revision;
    if(request)await request.catch(()=>null);
    if(context.aborted||ticket!==revision)return null;
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),29000);
    try {
      request=(async()=>{
        if(!csrf){const response=await fetch('/api/auth/csrf',{cache:'no-store',signal:controller.signal});if(!response.ok)return null;csrf=await response.json();}
        if(!csrf?.headerName||!csrf?.token)return null;
        const response=await fetch('/api/editor/completions',{method:'POST',cache:'no-store',signal:controller.signal,
          headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify({language,source:state.doc.toString(),offset:pos})});
        if(!response.ok){if(response.status===403)csrf=null;return null;}
        return response.json();
      })();
      const result=await request;
      if(context.aborted||ticket!==revision||!Array.isArray(result?.items))return null;
      const from=word?.from??pos;
      const member=/(?:\.|->|::)\s*$/.test(state.sliceDoc(Math.max(0,from-8),from));
      const options=result.items.map(item=>semanticOption(item,state,from,pos,member)).filter(Boolean);
      return options.length?{from,options,commitCharacters:[]}:null;
    } catch { return null; }
    finally {clearTimeout(timer);request=null;}
  };
}
