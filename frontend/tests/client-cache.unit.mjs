import {test} from 'node:test';
import assert from 'node:assert/strict';
import {cachedApi} from '../app/client-cache.mjs';
test('deduplicates requests, expires, invalidates after mutations and never caches live results',async()=>{
 let calls=0,time=0;
 const api=cachedApi(async()=>({value:++calls}),()=>time);
 const [a,b]=await Promise.all([api('/api/my/summary'),api('/api/my/summary')]);assert.equal(calls,1);assert.deepEqual(a,b);
 a.value=99;assert.equal((await api('/api/my/summary')).value,1);
 time=30001;await api('/api/my/summary');assert.equal(calls,2);
 await api('/api/submissions',{method:'POST'});await api('/api/my/summary');assert.equal(calls,4);
 await api('/api/submissions/42');await api('/api/submissions/42');assert.equal(calls,6);
 api.clear();await api('/api/my/summary');assert.equal(calls,7);
});
test('failed requests are retried and prior-session pending requests cannot repopulate cache',async()=>{
 let calls=0,resolve;const api=cachedApi(()=>{calls++;if(calls===1)return new Promise(r=>resolve=r);return Promise.resolve({value:calls});});
 const pending=api('/api/problems');api.clear();resolve({value:1});await pending;await api('/api/problems');assert.equal(calls,2);
 let attempts=0;const failing=cachedApi(async()=>{if(++attempts===1)throw new Error('offline');return [];});await assert.rejects(failing('/api/problems'));await failing('/api/problems');assert.equal(attempts,2);
});

test('training reads share in-flight work while active sessions always refresh after completion',async()=>{
 let calls=0,resolve;const api=cachedApi(()=>{calls++;return new Promise(r=>resolve=r);});
 const first=api('/api/training-sessions'),second=api('/api/training-sessions');assert.equal(calls,1);resolve([{status:'ACTIVE'}]);await Promise.all([first,second]);
 const next=api('/api/training-sessions');assert.equal(calls,2);resolve([{status:'ENDED'}]);assert.equal((await next)[0].status,'ENDED');
});
test('course entry shares enrollment results briefly and mutations invalidate them',async()=>{
 let calls=0,time=0;const api=cachedApi(async()=>({count:++calls}),()=>time);
 await api('/api/training-courses/enrollments');await api('/api/training-courses/enrollments');assert.equal(calls,1);
 time=5001;await api('/api/training-courses/enrollments');assert.equal(calls,2);
 await api('/api/training-sessions/x/end',{method:'POST'});await api('/api/training-courses/enrollments');assert.equal(calls,4);
});
