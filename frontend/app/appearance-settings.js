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

function SettingIcon({name}) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {name==='ui'?<><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M3 10h18M9 10v10"/></>:name==='editor'?<><path d="m8 7-5 5 5 5m8-10 5 5-5 5M14 4l-4 16"/></>:<><rect x="3" y="4" width="18" height="16" rx="3"/><path d="m7 9 3 3-3 3m6 0h4"/></>}
  </svg>;
}

const colorGroups={
  ui:[['배경과 경계',['canvas','surface','subtle','line']],['텍스트와 강조',['ink','muted','green','button-ink']],['사이드바',['sidebar-background','sidebar-ink','sidebar-active','sidebar-active-ink']],['상태 표시',['success','danger']]],
  editor:[['편집 영역',['editor-background','editor-ink','editor-gutter','editor-gutter-ink','editor-active-line','editor-caret','editor-selection','editor-selection-ink']],['코드 강조',['editor-keyword','editor-type','editor-function','editor-string','editor-number','editor-comment','editor-meta']],['괄호와 검색',['editor-border','editor-bracket','editor-bracket-line','editor-search','editor-search-line']]],
  console:[['실행 결과',['console-background','console-header','console-ink','console-muted','console-line','console-input']],['상태 표시',['console-success','console-error']]]
};

function LivePreview({fontSize}) {
  return <aside className="appearance-preview" aria-label="테마 미리보기">
    <div className="appearance-preview-caption"><span>LIVE PREVIEW</span><span className="preview-live-dot">실시간</span></div>
    <div className="appearance-preview-window">
      <div className="preview-window-bar"><span className="preview-window-dots" aria-hidden="true"><i/><i/><i/></span><span>GamjaOJ</span></div>
      <div className="preview-workspace"><div className="preview-sidebar" aria-hidden="true"><span/><span/><span/></div><div className="preview-panels">
        <div className="preview-editor"><div className="preview-file">Main.java <span>{fontSize}px</span></div><pre style={{fontSize:`${fontSize}px`}}><span className="preview-keyword">public class</span>{' Main {\n  '}<span className="preview-keyword">int</span>{' '}<span className="preview-function">solve</span>{'() {\n    '}<span className="preview-comment">// 한 문제씩, 내 것으로.</span>{'\n    '}<span className="preview-keyword">return</span>{' '}<span className="preview-number">42</span>{';\n  }\n}'}</pre></div>
        <div className="preview-terminal"><strong>실행 결과</strong><pre>Hello, GamjaOJ</pre><span>✓ 테스트를 통과했어요.</span></div>
      </div></div>
    </div>
    <p>마음에 드는 색과 글꼴을 찾아보세요.<br/>변경은 바로 적용됩니다.</p>
  </aside>;
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
  </button><Modal open={open} title="화면 설정" description="나에게 편안한 작업 공간을 만들어 보세요." className="appearance-dialog" wide onClose={()=>setOpen(false)}>{prefs&&<div className="appearance-settings">
    <nav className="appearance-nav" aria-label="설정 영역">
      <span className="appearance-nav-caption">꾸미기</span>
      {[["ui","화면 색상"],["editor","에디터"],["console","터미널"]].map(([id,label])=><button key={id} type="button" aria-pressed={tab===id} onClick={()=>setTab(id)}><SettingIcon name={id}/><span>{label}</span></button>)}
      <div className="appearance-nav-bottom"><span className="appearance-saved">자동 저장</span><button type="button" onClick={()=>preset('default')}>전체 기본값</button></div>
    </nav>
    <div className="appearance-content">
      <div className="appearance-section-heading"><div><h4>{{ui:'화면 색상',editor:'코드 에디터',console:'실행 터미널'}[tab]}</h4><p>{{ui:'배경부터 작은 강조색까지, 원하는 분위기로.',editor:'글꼴과 코드 색상을 읽기 편하게 조절하세요.',console:'입력과 실행 결과를 또렷하게 구분하세요.'}[tab]}</p></div>
        {tab==='ui'&&<div className="appearance-mode" role="group" aria-label="색상 모드">{[['light','라이트'],['dark','다크']].map(([id,label])=><button key={id} type="button" aria-pressed={mode===id} onClick={()=>{document.documentElement.setAttribute('data-theme',id);try{localStorage.setItem('gamjaoj-theme',id);}catch{}}}>{label}</button>)}</div>}
      </div>
      <div className="appearance-presets" role="group" aria-label="색상 프리셋">{[['default','기본 테마','매일 편안하게'],['paper','밝은 코드','가볍고 선명하게'],['ocean','밤바다','차분한 푸른빛']].map(([id,label,description])=><button key={id} type="button" className="appearance-preset" data-preset={id} onClick={()=>preset(id)}>
        <span className="preset-thumbnail" aria-hidden="true"><span className="preset-side"/><span className="preset-code"><i/><i/><i/></span><span className="preset-terminal"/></span><strong>{label}</strong><small>{description}</small>
      </button>)}</div>
      <div className="appearance-editor-layout"><div className="appearance-controls">
        {tab==='editor'&&<section className="appearance-fonts"><h5>글꼴과 크기</h5>
          <label>에디터 글꼴<select aria-label="에디터 글꼴" value={prefs.font} onChange={e=>save({...prefs,font:e.target.value})}>{Object.entries(api.fonts).map(([id,[label]])=><option key={id} value={id}>{label}</option>)}</select></label>
          {prefs.font==='custom'&&<label>설치된 글꼴 이름<input aria-label="설치된 글꼴 이름" maxLength={80} value={prefs.customFont} onChange={e=>save({...prefs,customFont:e.target.value})}/></label>}
          <label className="appearance-size-label"><span>글자 크기 <output>{prefs.fontSize}px</output></span><input type="range" aria-label="에디터 글자 크기" min="10" max="28" value={prefs.fontSize} onChange={e=>save({...prefs,fontSize:Number(e.target.value)})}/></label>
          <small>기기에 설치된 글꼴을 사용해요.</small>
        </section>}
        {colorGroups[tab].map(([title,ids])=><section className="appearance-color-group" key={title}><h5>{title}</h5><div className="appearance-colors">{ids.map(id=>{
          const [,label,light,dark]=api.groups[tab].find(field=>field[0]===id);
          return <ColorField key={group+id} name={label} value={prefs[group][id]||(mode==='dark'&&dark?dark:light)} onChange={value=>save({...prefs,[group]:{...prefs[group],[id]:value}})}/>;
        })}</div></section>)}
        <button type="button" className="appearance-reset-section" onClick={()=>save({...prefs,[group]:{}})}>이 영역 색상 초기화</button>
        <p className="appearance-storage-note">이 브라우저에 저장돼요. 화면 색상은 라이트·다크별로, 에디터와 터미널은 공통으로 적용됩니다.</p>
      </div><LivePreview fontSize={prefs.fontSize}/></div>
    </div>
  </div>}</Modal></>;
}
