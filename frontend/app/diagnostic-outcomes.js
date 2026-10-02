export const skipReasons={NOT_SURE:'접근 방법을 모르겠어요',NO_TIME:'시간이 부족해요',OTHER:'이번에는 풀지 않을게요',SESSION_ENDED:'진단 종료로 미완료',UNSPECIFIED:'사유를 남기지 않았어요'};
export function diagnosticOutcome(item){
 if(item.externallySeen)return '본 적 있음 · 평가 근거에서 제외';
 if(item.status==='SKIPPED')return item.skipReason==='NOT_SURE'?'접근 어려움 · 본인 보고':item.skipReason==='NO_TIME'?'시간 부족 · 미확인':item.skipReason==='SESSION_ENDED'?'종료로 미완료':item.skipReason==='OTHER'?'풀지 않음 · 미확인':'건너뜀 · 사유 미상';
 return {PASSED:'통과',EXHAUSTED:'5회 소진',OPEN:'미완료'}[item.status]||item.status;
}
