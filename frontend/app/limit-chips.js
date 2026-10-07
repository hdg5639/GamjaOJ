/** Time and memory limits as compact chips next to the problem title; they follow the chosen language. */
export default function LimitChips({profile,label}) {
  if(!profile)return null;
  return <span className="limit-chips" title={profile.timeMetric==='CPU'?"CPU 시간은 테스트 하나당 실제 CPU 사용량(프로그램 시작 포함), 메모리는 실행 컨테이너 기준이에요.":"시간은 테스트 하나당(프로그램 시작 포함), 메모리는 실행 컨테이너 기준이에요."}>
    {label&&<span className="limit-chip">{label}</span>}
    <span className="limit-chip"><b>{profile.timeMetric==='CPU'?'CPU 시간':'시간'}</b> {profile.timeLimitMs/1000}초</span>
    <span className="limit-chip"><b>메모리</b> {profile.memoryMb} MB</span>
  </span>;
}
