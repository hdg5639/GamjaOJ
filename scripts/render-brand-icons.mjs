// Rasterize the repository's vector symbol and package PNG entries into an ICO.
// Uses the existing frontend Playwright dependency; no image-editing dependency.
import {createRequire} from 'node:module';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../',import.meta.url),assets=new URL('frontend/public/',root);
const require=createRequire(new URL('frontend/package.json',root));
const {chromium}=require('@playwright/test');
const source=await readFile(new URL('gamjaoj-symbol.svg',assets),'utf8');
const browser=await chromium.launch();
try {
 const entries=[];
 for(const [size,name] of [[16,'favicon-16x16.png'],[32,'favicon-32x32.png'],[48,null],[180,'apple-touch-icon.png'],[192,'android-chrome-192x192.png'],[512,'android-chrome-512x512.png']]){
  const page=await browser.newPage({viewport:{width:size,height:size},deviceScaleFactor:1});
  await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block;width:100vw;height:100vh}</style>${source}`);
  const png=await page.screenshot({omitBackground:true});
  if(name)await writeFile(new URL(name,assets),png);
  if(size<=48)entries.push({size,png});
  await page.close();
 }
 const header=Buffer.alloc(6+16*entries.length);header.writeUInt16LE(1,2);header.writeUInt16LE(entries.length,4);
 let offset=header.length;
 for(const [i,{size,png}] of entries.entries()){
  const base=6+16*i;header[base]=size;header[base+1]=size;
  header.writeUInt16LE(1,base+4);header.writeUInt16LE(32,base+6);
  header.writeUInt32LE(png.length,base+8);header.writeUInt32LE(offset,base+12);offset+=png.length;
 }
 await writeFile(new URL('favicon.ico',assets),Buffer.concat([header,...entries.map(e=>e.png)]));
 console.log(`Rendered favicon, touch and Android icons in ${fileURLToPath(assets)}`);
} finally {await browser.close();}
