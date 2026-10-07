export const memoryText=bytes=>Number.isFinite(bytes)&&bytes>=0?`${(bytes/1048576).toFixed(2)} MiB`:'미측정';
export default function ExecutionMetrics({result}) {
 if(result?.status!=='FINISHED')return null;
 const cpu=result.execution?.timeMetric==='CPU';
 return <p className="execution-metrics" title={cpu?"CPU 시간은 프로그램 시작을 포함한 실행 컨테이너의 실제 CPU 사용량입니다. 테스트의 최댓값을 표시합니다.":"실행 시간은 프로그램 시작을 포함한 wall time, 메모리는 Runner 호스트에서 관측한 컨테이너 cgroup 최고 사용량입니다. 런타임과 파일 캐시를 포함하며 측정된 테스트의 최댓값을 표시합니다."}>
  <span>{cpu?'최대 CPU 시간':'최대 실행 시간'} <strong>{(cpu?result.cpuMs:result.wallMs)!=null?`${cpu?result.cpuMs:result.wallMs} ms`:'미측정'}</strong></span>
  <span>최대 메모리 <strong>{memoryText(result.memoryPeakBytes)}</strong></span>
 </p>;
}
