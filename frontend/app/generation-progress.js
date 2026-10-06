const scopes={PROSE:'본문',PRESENTATION:'본문·해설',IMPLEMENTATION:'코드·테스트',CORE:'코드',CONTRACT:'문제 규칙',REVIEW:'독립 검수',READER:'독립 검수',FINAL:'최종 검수'};
const resources={QUEUED:'세 언어 정답·최대 입력 준비 대기',GENERATING:'세 언어 정답과 최대 입력을 준비하고 있어요',MEASURING:'Java·C++·Python 실행 시간과 메모리를 측정하고 있어요',REPLAYING:'확정할 제한 안에서 세 언어와 일반 풀이를 다시 실행하고 있어요',PASSED:'세 언어 시간·메모리 측정과 제한 재검증 통과',NEEDS_REVIEW:'자원 검수 중단 · 실패 기록 확인 필요',SUPERSEDED:'이전 자원 검수 기록 보존'};
export default function GenerationProgress({recovery,resource}) {
  return <>{recovery?.attempt>0&&<p className="draft-help">{scopes[recovery.scope]||'실패한 단계'} 자동 보완 {recovery.attempt}/{recovery.limit} · 다른 단계의 산출물과 이전 검증 기록은 보존됩니다.</p>}{resource&&<p className="draft-help" role="status">{resources[resource.status]||'자원 검수 상태 확인 중'}{resource.error&&resource.status==='NEEDS_REVIEW'&&<small> (사유: {resource.error})</small>}</p>}</>;
}
