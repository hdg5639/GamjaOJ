import {test} from 'node:test';
import assert from 'node:assert/strict';
import {encodeCalls,decodeCalls,parseWire,wireText,validateCallableInput,validateCallableOutput,sameCallableOutput} from '../app/callable-tests.mjs';
const definition={mode:'MULTI_API',methods:[{name:'init',returns:'void',parameters:[{name:'values',type:'long[]'}]},{name:'query',returns:'String',parameters:[{name:'id',type:'long'}]}]};
const rows=[{method:'init',args:['[9223372036854775807, -9223372036854775808]'],compare:false,expected:''},{method:'query',args:['9223372036854775807'],compare:true,expected:'a b\n한글'}];
test('typed calls preserve long limits, strings and output order through roundtrip',()=>{
 const encoded=encodeCalls(rows,definition);assert.equal(encoded.error,undefined);
 assert.equal(encoded.input,'[[["init",[9223372036854775807,-9223372036854775808]],["query",9223372036854775807]]]');
 assert.equal(encoded.output,'"a b\\n한글"');assert.deepEqual(decodeCalls(encoded.input,encoded.output,definition),rows.map(row=>({...row,args:row.args.map(a=>a.replace(', ',','))})));
 assert.equal(validateCallableInput(encoded.input,definition),null);assert.equal(validateCallableOutput(encoded.input,encoded.output,definition),null);
});
test('invalid signatures and numerical domains stop before executing',()=>{
 for(const input of ['[[["unknown"]]]','[[["query",1]]]','[[["init",[9223372036854775808]]]]','[[["init",[1.5]]]]','[[["init",[null]]]]','[[["init",[{"number":"1"}]]]]','[]','[[["init",[]],["query"]]]'])assert.ok(validateCallableInput(input,definition),input);
});
test('independent cases remain valid raw input and are never flattened by guided editing',()=>{
 const wire='[[["init",[1]],["query",1]],[["init",[2]],["query",2]]]';
 assert.equal(validateCallableInput(wire,definition),null);assert.equal(decodeCalls(wire,'"one"\n"two"',definition),null);
 assert.equal(validateCallableOutput(wire,'"one"\n"two"',definition),null);assert.ok(validateCallableOutput(wire,'"one"',definition));
});
test('single function keeps exactly one call, arrays and bool types',()=>{
 const api={mode:'SINGLE_FUNCTION',methods:[{name:'solution',returns:'boolean[]',parameters:[{name:'a',type:'int[]'},{name:'b',type:'boolean'},{name:'s',type:'String[]'}]}]};
 const encoded=encodeCalls([{method:'solution',args:['[1,2]','true','["a", "b"]'],compare:true,expected:'[true,false]'}],api);
 assert.equal(encoded.input,'[[["solution",[1,2],true,["a","b"]]]]');assert.equal(encoded.output,'[true,false]');
 assert.ok(validateCallableInput('[[["solution",[],true,[]],["solution",[],false,[]]]]',api));
});
test('all or no expected returns; empty String expected is supported',()=>{
 const rows2=[...rows,{method:'query',args:['1'],compare:false,expected:''}];assert.ok(encodeCalls(rows2,definition).error);
 rows2[2].compare=true;assert.equal(encodeCalls(rows2,definition).output,'"a b\\n한글"\n""');
});
test('return comparison decodes driver JSON string escapes and preserves long precision',()=>{
 assert.equal(sameCallableOutput('"a\\u0020b"\n[1, 2]\n9223372036854775807','"a b"\n[1,2]\n9223372036854775807'),true);
 assert.equal(sameCallableOutput('9223372036854775806','9223372036854775807'),false);
 assert.equal(sameCallableOutput('debug\n1','1'),false);
 assert.equal(wireText(parseWire('"{ literal }"')),'"{ literal }"');
});
