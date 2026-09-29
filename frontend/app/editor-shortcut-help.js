import {useEffect,useRef} from 'react';
function fitTools(element) {
  if(element?.open)element.style.setProperty('--tool-panel-height',`${Math.max(60,window.innerHeight-element.getBoundingClientRect().bottom-16)}px`);
}
export function EditorTools({id,children}) {
  const host=useRef(null);
  useEffect(()=>{
    const fit=()=>fitTools(host.current);
    window.addEventListener('resize',fit);window.addEventListener('scroll',fit,true);
    return()=>{window.removeEventListener('resize',fit);window.removeEventListener('scroll',fit,true);};
  },[]);
  return <details id={id} ref={host} className="tool-pop" onToggle={event=>fitTools(event.currentTarget)}>{children}</details>;
}

export default function EditorShortcutHelp({diagnostic=false}) {
  return <>
    <strong>실행 · 저장</strong>
    <span>F5 또는 Ctrl/⌘+Shift+Enter: 예제·추가 테스트 실행<br/>Ctrl/⌘+Enter: 정식 제출<br/>Ctrl/⌘+S: 초안 저장</span>
    <strong>Vim 모드</strong>
    <span>:w 초안 저장 · :run 테스트 실행 · :submit 정식 제출<br/>Esc 일반 모드 · i 입력 · v 선택 · Ctrl+v 블록 선택<br/>hjkl 이동 · ciw 단어 변경 · yy/p 복사/붙여넣기 · u/Ctrl+r 되돌리기/다시 실행<br/>/ 검색 · n 다음 · :%s/이전/새값/g 전체 치환<br/>qa 기록 시작 · q 기록 끝 · @a 매크로 실행</span>
    <span>{diagnostic?'정식 제출은 진단 제출 횟수를 사용합니다. 테스트 실행과 초안 저장은 횟수를 사용하지 않습니다.':'테스트 실행은 정식 제출 기록에 남지 않습니다.'} 초안 저장 상태는 파일명 옆에서 확인하세요.</span>
    <strong>자동완성</strong><span>Ctrl+Space 후보 · Enter 확정 · Tab 들여쓰기 · Esc 다음 Tab으로 나가기</span>
  </>;
}
