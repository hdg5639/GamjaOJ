import {EditorState} from '@codemirror/state';
import {currentCompletions,selectedCompletion,setSelectedCompletion} from '@codemirror/autocomplete';

// Common policy across Java, C++ and Python. A late provider response is not
// inherently more relevant than a declaration or the curated local catalog.
export const completionPriority={document:25,standard:15,member:15};
export function semanticPriority(item,member){
  const name=item.filterText||item.label||'',detail=item.detail||'';
  const internal=/^[_$]|(?:^|[ .:])(?:sun\.|com\.sun\.|jdk\.internal\.|__gnu_cxx|__\w)/.test(name+' '+detail);
  return internal?-30:member?completionPriority.member:0;
}

const selectionEffect=setSelectedCompletion(0).type;
// Keep the selected candidate when only asynchronous results change. Never
// interfere with typing, cursor movement, explicit navigation or popup closing.
export const stableCompletionSelection=EditorState.transactionExtender.of(tr=>{
  if(tr.docChanged||tr.selection||tr.effects.some(e=>e.is(selectionEffect)))return null;
  const before=selectedCompletion(tr.startState),after=selectedCompletion(tr.state);
  if(!before||!after||before===after)return null;
  const options=currentCompletions(tr.state);
  // Prefer the exact object (local sources retain it), then an equivalent item.
  let index=options.indexOf(before);
  if(index<0)index=options.findIndex(c=>c.label===before.label&&c.type===before.type&&c.detail===before.detail);
  return index<0?null:{effects:setSelectedCompletion(index)};
});
