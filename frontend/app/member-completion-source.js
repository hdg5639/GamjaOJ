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
    return {from:result.from,options:result.options,validFor:/^[\p{ID_Continue}$]*$/u,commitCharacters:[]};
  };
}
/** True when the word being completed is a member name after "." (handled by memberCompletionSource). */
export function afterDot(state,from){return state.sliceDoc(Math.max(0,from-1),from)==='.';}
