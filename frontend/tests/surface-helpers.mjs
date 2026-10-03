import {expect} from '@playwright/test';
export async function surfaceTint(locator){return locator.evaluate(n=>{const c=document.createElement('canvas').getContext('2d');c.fillStyle=getComputedStyle(n).backgroundColor;c.fillRect(0,0,1,1);return [...c.getImageData(0,0,1,1).data];});}
export async function expectSurfaceTint(locator,rgb){
 // CSS transitions and premultiplied alpha can round channels by one unit.
 await expect.poll(async()=>{const tint=await surfaceTint(locator);return Math.max(...rgb.map((channel,i)=>Math.abs(tint[i]-channel)));}).toBeLessThanOrEqual(1);
 const tint=await surfaceTint(locator);expect(tint[3]).toBeGreaterThan(30);expect(tint[3]).toBeLessThan(255);
}
