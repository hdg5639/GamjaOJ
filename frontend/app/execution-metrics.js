export const memoryText=bytes=>Number.isFinite(bytes)&&bytes>=0?`${(bytes/1048576).toFixed(2)} MiB`:'미측정';
export default function ExecutionMetrics({result}) {
 if(result?.status!=='FINISHED')return null;
 return <p className="execution-metrics" title="실행 시간은 프로그램 시작을 포함한 wall time, 메모리는 Runner 호스트에서 관측한 컨테이너 cgroup 최고 사용량입니다. 런타임과 파일 캐시를 포함하며 측정된 테스트의 최댓값을 표시합니다.">
  <span>최대 실행 시간 <strong>{result.wallMs!=null?`${result.wallMs} ms`:'미측정'}</strong></span>
  <span>최대 메모리 <strong>{memoryText(result.memoryPeakBytes)}</strong></span>
 </p>;
}
