import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';

export function statementImageUrl(src){return typeof src==='string'&&(/^\/api\/problem-images\/[a-f0-9-]{36}$/.test(src)||/^\/problem-illustrations\/[a-f0-9]{64}\.svg$/.test(src))?src:'';}
const parser=unified().use(remarkParse).use(remarkGfm);
export function statementIllustrationLayout(statement,images=[]){
 const source=typeof statement==='string'?statement:'',tree=parser.parse(source),referenced=new Set(),placements=new Map();
 const definitions=new Map(tree.children.filter(n=>n.type==='definition').map(n=>[n.identifier,n.url]));
 function collect(node){if(node.type==='imageReference'&&statementImageUrl(definitions.get(node.identifier)))referenced.add(definitions.get(node.identifier));if(node.type==='image'&&statementImageUrl(node.url))referenced.add(node.url);for(const child of node.children||[])collect(child);}
 collect(tree);
 const remainder=[];
 for(const image of images){
  if(!statementImageUrl(image.src)||referenced.has(image.src))continue;
  const matches=image.afterParagraph?tree.children.filter(node=>node.type==='paragraph'&&source.slice(node.position.start.offset,node.position.end.offset).trim()===image.afterParagraph.trim()):[];
  if(matches.length===1){const offset=matches[0].position.end.offset;placements.set(offset,[...(placements.get(offset)||[]),image]);referenced.add(image.src);}
  else remainder.push(image);
 }
 return {placements,remainder};
}
export function remarkStatementIllustrations({placements}){
 return tree=>{tree.children=tree.children.flatMap(node=>[node,...(placements.get(node.position?.end?.offset)||[]).flatMap(image=>[
  {type:'paragraph',data:{hProperties:{'data-inline-illustration':'true'}},children:[{type:'image',url:image.src,alt:image.alt,title:image.caption||null}]},
  ...(image.explanation?[{type:'paragraph',data:{hProperties:{className:['statement-figure-explanation']}},children:[{type:'text',value:image.explanation}]}]:[])
 ])]);};
}
