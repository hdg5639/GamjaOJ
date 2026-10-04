import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import {statementIllustrationLayout,remarkStatementIllustrations} from '../app/statement-illustrations.mjs';
const manifest=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/problem-illustrations-v1.json',import.meta.url)));
const inline=manifest.filter(x=>x.afterParagraph).map(x=>({...x,src:'/problem-illustrations/'+x.file}));
test('all twelve reviewed anchors are real Markdown paragraphs and insert image then explanation',()=>{
 assert.equal(inline.length,12);
 for(const image of inline){const source='처음 설명입니다.\n\n'+image.afterParagraph+'\n\n## 입력\n\n입력 설명입니다.';const layout=statementIllustrationLayout(source,[image]);assert.equal(layout.remainder.length,0,image.version);assert.equal(layout.placements.size,1,image.version);
  const processor=unified().use(remarkParse).use(remarkGfm).use(remarkStatementIllustrations,layout);const tree=processor.runSync(processor.parse(source));const idx=tree.children.findIndex(n=>n.children?.[0]?.type==='image');assert.equal(tree.children[idx-1].type,'paragraph');assert.equal(tree.children[idx+1].children[0].value,image.explanation);assert.equal(tree.children[idx+2].type,'heading');}
});
test('ambiguous and missing anchors keep their attachment instead of guessing',()=>{
 const i=inline[0];for(const source of ['다른 내용',i.afterParagraph+'\n\n'+i.afterParagraph]){const layout=statementIllustrationLayout(source,[i]);assert.equal(layout.placements.size,0);assert.deepEqual(layout.remainder,[i]);}
});
test('anchors inside code, lists and blockquotes never receive injected narrative images',()=>{
 const i=inline[0],short={...i,afterParagraph:'설명'};
 for(const source of ['```\n설명\n```','- 설명','> 설명']){const layout=statementIllustrationLayout(source,[short]);assert.equal(layout.placements.size,0);assert.equal(layout.remainder.length,1);}
});
test('existing inline and reference Markdown images are not repeated in attachments',()=>{
 const i=inline[0];for(const source of [`![이미지](${i.src})`,`![이미지][그림]\n\n[그림]: ${i.src}`]){const layout=statementIllustrationLayout(source,[i]);assert.equal(layout.placements.size,0);assert.equal(layout.remainder.length,0);}
});
test('two images at one paragraph preserve order and external images cannot be injected',()=>{
 const first=inline[0],second={...first,src:inline[1].src};const layout=statementIllustrationLayout(first.afterParagraph,[first,second,{...first,src:'https://example.invalid/pixel.png'}]);assert.deepEqual([...layout.placements.values()][0],[first,second]);assert.equal(layout.remainder.length,0);
});
