import {completionPriority} from './completion-policy';
import {ensureSyntaxTree, syntaxTree} from '@codemirror/language';
import {memberCompletions} from './member-completion.mjs';

const nonCode=/Comment|String|CharLiteral|CharacterLiteral|TextBlock/;
// "receiver." completion from the document's own declarations; never shown inside comments or strings.
export function memberCompletionSource(language) {
  return context => {
    const {state,pos}=context;
    if(state.readOnly)return null;
    const tree=ensureSyntaxTree(state,state.doc.length,25)||syntaxTree(state);
    for(let node=tree.resolveInner(pos,-1);node;node=node.parent)if(nonCode.test(node.name))return null;
    const result=memberCompletions(language,state.doc.toString(),pos);
    if(!result)return null;
    return {from:result.from,options:result.options.map(option=>({...option,boost:completionPriority.member})),validFor:/^[\p{ID_Continue}$]*$/u,commitCharacters:[]};
  };
}
/** Member access must not fall back to unrelated global classes/keywords when
 * a receiver type is unknown. Keep C++ namespace access (std::) available to
 * the curated name catalog; dot/pointer access uses local or semantic members. */
export function afterDot(state,from,language,pos){
  return /\.\s*$/.test(state.sliceDoc(Math.max(0,from-8),from))
    || language==='CPP'&&/->\s*$/.test(state.sliceDoc(Math.max(0,from-8),from));
}
