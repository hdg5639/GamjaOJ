'use client';
import {useEffect,useState} from 'react';
import Modal from './modal';

function ColorField({name,value,onChange}) {
  const [text,setText]=useState(value);
  useEffect(()=>setText(value),[value]);
  const valid=/^#[\da-f]{6}$/i.test(text);
  return <div className="appearance-color"><label>{name}<input type="color" aria-label={`${name} 색상 선택`} value={value} onChange={e=>onChange(e.target.value)}/></label>
    <input aria-label={`${name} HEX`} value={text} maxLength={7} spellCheck={false} aria-invalid={!valid} onChange={e=>{setText(e.target.value);if(/^#[\da-f]{6}$/i.test(e.target.value))onChange(e.target.value);}}/></div>;
}

export default function AppearanceSettings() {
  const [open,setOpen]=useState(false),[prefs,setPrefs]=useState(null),[mode,setMode]=useState('light'),[tab,setTab]=useState('ui');
  useEffect(()=>{
    const update=()=>{setPrefs(window.GamjaAppearance.get());setMode(document.documentElement.getAttribute('data-theme')==='dark'?'dark':'light');};
    update();window.addEventListener('gamjaoj-appearance',update);
    return ()=>window.removeEventListener('gamjaoj-appearance',update);
  },[]);
  const api=typeof window==='undefined'?null:window.GamjaAppearance;
  function save(next) { api.save(next); }
  function preset(name) {
    if(name==='default'){api.reset();return;}
    const next={...prefs,editor:{},console:{},[mode]:{}};
    if(name==='paper'){
      next.editor={'editor-background':'#fafafa','editor-ink':'#25352f','editor-gutter':'#f0f1f3','editor-gutter-ink':'#6b7280','editor-active-line':'#eef2f6','editor-caret':'#25352f','editor-keyword':'#8b2599','editor-type':'#22666b','editor-function':'#805000','editor-string':'#397328','editor-number':'#245b99','editor-comment':'#697268','editor-meta':'#805000'};
      next.console={'console-background':'#f5f7fa','console-header':'#e8edf3','console-ink':'#25352f','console-muted':'#606c64','console-line':'#d2d9e2','console-input':'#ffffff','console-success':'#26744b','console-error':'#a1271b'};
    } else {
      next[mode]={canvas:'#101827',surface:'#18243a',subtle:'#22334d',ink:'#e6eefb',muted:'#a7b8d1',line:'#354760',green:'#568bc9','sidebar-background':'#101f35','sidebar-ink':'#c5d8f0','sidebar-active':'#315884','sidebar-active-ink':'#ffffff'};
      next.editor={'editor-background':'#122035','editor-ink':'#d0e2f5','editor-gutter':'#192a42','editor-active-line':'#203650','editor-keyword':'#c5a4ff','editor-string':'#9ddba3','editor-function':'#ffd68d'};
      next.console={'console-background':'#14263a','console-header':'#203650','console-ink':'#d0e2f5','console-line':'#354760'};
    }
    save(next);
  }
  const group=tab==='ui'?mode:tab;
  return <><button type="button" className="secondary appearance-toggle" aria-label="화면 설정" title="색상·에디터 글꼴 설정" onClick={()=>setOpen(true)}>
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1-3.7 1.5 1.5 0 0 1 .7-2.8H17a4 4 0 0 0 4-4C21 6.4 17 3 12 3Z"/><circle cx="7.5" cy="10" r=".7"/><circle cx="10" cy="6.8" r=".7"/><circle cx="14" cy="6.8" r=".7"/><circle cx="17" cy="9.5" r=".7"/></svg>
  </button><Modal open={open} title="화면 설정" wide onClose={()=>setOpen(false)}>{prefs&&<div className="appearance-settings">
    <p className="muted">변경은 바로 적용되고 이 브라우저에 저장돼요. 화면 색상은 라이트·다크 모드별로, 에디터와 터미널 설정은 공통으로 사용해요.</p>
    <div className="appearance-presets" role="group" aria-label="색상 프리셋"><span>프리셋</span><button type="button" className="secondary" onClick={()=>preset('paper')}>밝은 코드</button><button type="button" className="secondary" onClick={()=>preset('ocean')}>밤바다</button><button type="button" className="secondary" onClick={()=>preset('default')}>전체 기본값</button></div>
    <div className="appearance-tabs" role="group" aria-label="설정 영역">{[['ui','화면 색상'],['editor','에디터'],['console','터미널']].map(([id,label])=><button key={id} type="button" className="secondary" aria-pressed={tab===id} onClick={()=>setTab(id)}>{label}</button>)}</div>
    {tab==='ui'&&<div className="appearance-mode" role="group" aria-label="색상 모드">{[['light','라이트'],['dark','다크']].map(([id,label])=><button key={id} type="button" className="secondary" aria-pressed={mode===id} onClick={()=>{document.documentElement.setAttribute('data-theme',id);try{localStorage.setItem('gamjaoj-theme',id);}catch{}}}>{label}</button>)}</div>}
    {tab==='editor'&&<div className="appearance-fonts">
      <label>에디터 글꼴<select aria-label="에디터 글꼴" value={prefs.font} onChange={e=>save({...prefs,font:e.target.value})}>{Object.entries(api.fonts).map(([id,[label]])=><option key={id} value={id}>{label}</option>)}</select></label>
      {prefs.font==='custom'&&<label>설치된 글꼴 이름<input aria-label="설치된 글꼴 이름" maxLength={80} value={prefs.customFont} onChange={e=>save({...prefs,customFont:e.target.value})}/></label>}
      <label>글자 크기 · {prefs.fontSize}px<input type="range" aria-label="에디터 글자 크기" min="10" max="28" value={prefs.fontSize} onChange={e=>save({...prefs,fontSize:Number(e.target.value)})}/></label>
      <small>기기에 설치된 글꼴을 사용해요. 없으면 시스템 고정폭 글꼴로 표시돼요.</small>
    </div>}
    <div className="appearance-colors">{api.groups[tab].map(([id,label,light,dark])=><ColorField key={group+id} name={label} value={prefs[group][id]||(mode==='dark'&&dark?dark:light)} onChange={value=>save({...prefs,[group]:{...prefs[group],[id]:value}})}/>)}</div>
    <button type="button" className="secondary" onClick={()=>save({...prefs,[group]:{}})}>이 영역 색상 초기화</button>
  </div>}</Modal></>;
}
