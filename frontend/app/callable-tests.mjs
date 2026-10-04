// Preserve Java long values as decimal text, including values beyond JS Number's safe range.
const numberToken=/"(?:\\.|[^"\\])*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|[{}]/g;
export function parseWire(text){
 try{return JSON.parse(text.replace(numberToken,token=>{
  if(token==='{'||token==='}')throw new Error('객체 대신 배열과 기본 값을 사용해 주세요.');
  return token.startsWith('"')?token:JSON.stringify({number:token});
 }));}catch(e){if(e instanceof SyntaxError)throw new Error('JSON 배열·따옴표·쉼표를 확인해 주세요.');throw e;}
}
export function wireText(value){
 if(Array.isArray(value))return '['+value.map(wireText).join(',')+']';
 if(value&&typeof value==='object'&&typeof value.number==='string')return value.number;
 return JSON.stringify(value);
}
function checked(value,type){
 if(type.endsWith('[]')){
  if(!Array.isArray(value))throw new Error(`${type}는 [값1, 값2] 형식으로 입력해 주세요.`);
  return '['+value.map(v=>checked(v,type.slice(0,-2))).join(',')+']';
 }
 if(type==='String'){
  if(typeof value!=='string')throw new Error('문자열 값을 입력해 주세요.');
  return JSON.stringify(value);
 }
 if(type==='boolean'){
  if(typeof value!=='boolean')throw new Error('true 또는 false를 입력해 주세요.');
  return String(value);
 }
 if(!value||typeof value.number!=='string'||!/^-(?:0|[1-9]\d*)$|^(?:0|[1-9]\d*)$/.test(value.number))throw new Error('정수를 입력해 주세요.');
 const n=BigInt(value.number),bound=type==='int'?2147483648n:9223372036854775808n;
 if(n< -bound||n>=bound)throw new Error(`${type}의 정수 범위를 벗어났어요.`);
 return n.toString();
}
function field(text,type){return checked(type==='String'?text:parseWire(text),type);}
function display(value,type){return type==='String'?value:wireText(value);}
export function defaultCall(definition,name){
 const m=definition.methods.find(m=>m.name===name)||definition.methods[0];
 return {method:m.name,args:m.parameters.map(p=>p.type.endsWith('[]')?'[]':p.type==='String'?'':p.type==='boolean'?'false':'0'),expected:'',compare:false};
}
export function encodeCalls(rows,definition){
 try{
  if(!rows.length)throw new Error('호출을 하나 이상 추가해 주세요.');
  if(definition.mode==='SINGLE_FUNCTION'&&rows.length!==1)throw new Error('단일 함수는 한 테스트에서 한 번만 호출해요.');
  if(definition.mode==='MULTI_API'&&rows[0].method!=='init')throw new Error('첫 호출은 init이어야 해요.');
  const output=[],calls=[];let comparisons=0,returns=0;
  for(const [i,row] of rows.entries()){
   const m=definition.methods.find(m=>m.name===row.method);
   if(!m||row.args.length!==m.parameters.length)throw new Error(`${i+1}번째 함수와 인자를 확인해 주세요.`);
   try{
    calls.push('['+[JSON.stringify(m.name),...m.parameters.map((p,j)=>field(row.args[j],p.type))].join(',')+']');
    if(m.returns!=='void'){returns++;if(row.compare){comparisons++;output.push(field(row.expected,m.returns));}}
   }catch(e){throw new Error(`${i+1}번째 호출: ${e.message}`);}
  }
  if(comparisons&&comparisons!==returns)throw new Error('반환값이 있는 호출은 모두 비교하거나, 모두 비교를 꺼 주세요.');
  return {input:'[['+calls.join(',')+']]',output:output.join('\n')};
 }catch(e){return {error:e.message};}
}
export function validateCallableInput(input,definition){
 try{
  const groups=parseWire(input);
  if(!Array.isArray(groups)||!groups.length||groups.length>100)throw new Error('입력은 1~100개의 독립 테스트를 담은 배열이어야 해요.');
  for(const [i,calls] of groups.entries()){
   if(!Array.isArray(calls)||!calls.length||calls.length>1000000)throw new Error(`${i+1}번째 테스트에 호출 목록이 필요해요.`);
   const rows=calls.map(call=>{
    if(!Array.isArray(call)||typeof call[0]!=='string')throw new Error('호출은 ["함수 이름", 인자1, ...] 형식이에요.');
    const m=definition.methods.find(m=>m.name===call[0]);
    if(!m||call.length!==m.parameters.length+1)throw new Error('정의된 함수 이름과 인자 개수를 확인해 주세요.');
    return {method:m.name,args:m.parameters.map((p,j)=>{checked(call[j+1],p.type);return display(call[j+1],p.type);}),compare:false,expected:''};
   });
   const encoded=encodeCalls(rows,definition);if(encoded.error)throw new Error(encoded.error);
  }
  return null;
 }catch(e){return '호출 입력을 확인해 주세요. '+e.message;}
}
export function decodeCalls(input,output,definition){
 try{
  if(validateCallableInput(input,definition))return null;
  const groups=parseWire(input);if(groups.length!==1)return null;
  const expected=output.trim()?output.trim().split(/\r?\n/).map(parseWire):[];
  const count=groups[0].filter(call=>definition.methods.find(m=>m.name===call[0]).returns!=='void').length;
  if(expected.length&&expected.length!==count)return null;
  let index=0;
  return groups[0].map(call=>{
   const m=definition.methods.find(m=>m.name===call[0]),compare=m.returns!=='void'&&expected.length>0;
   let value='';if(compare){checked(expected[index],m.returns);value=display(expected[index++],m.returns);}
   return {method:m.name,args:m.parameters.map((p,j)=>display(call[j+1],p.type)),expected:value,compare};
  });
 }catch{return null;}
}
export function sameCallableOutput(actual,expected){
 try{
  const lines=text=>text.trim().split(/\r?\n/).filter(line=>line.trim()).map(line=>wireText(parseWire(line))).join('\n');
  return lines(actual)===lines(expected);
 }catch{return false;}
}
export function validateCallableOutput(input,output,definition){
 if(!output.trim())return null;
 try{
  const returns=parseWire(input).flatMap(calls=>calls.map(call=>definition.methods.find(m=>m.name===call[0]).returns)).filter(type=>type!=='void');
  const lines=output.trim().split(/\r?\n/);
  if(lines.length!==returns.length)throw new Error('반환값이 있는 호출마다 한 줄씩 적어 주세요.');
  lines.forEach((line,i)=>checked(parseWire(line),returns[i]));return null;
 }catch(e){return '기댓값을 확인해 주세요. '+e.message;}
}
