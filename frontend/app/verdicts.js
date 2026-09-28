// One place for judge result codes: the short code stays visible and a Korean name/explanation goes with it.
export const verdictNames={AC:'정답',WA:'오답',CE:'컴파일 에러',RE:'런타임 에러',TLE:'시간 초과',MLE:'메모리 초과',OLE:'출력 초과',IE:'채점 시스템 오류',OK:'실행 완료'};
export const verdictHelp={
  AC:'모든 테스트의 출력이 정답과 같아요.',
  WA:'실행은 끝났지만 어떤 테스트의 출력이 정답과 달라요.',
  CE:'코드를 컴파일하지 못했어요. 문법이나 타입 오류를 확인하세요.',
  RE:'실행 중 예외나 비정상 종료가 일어났어요. 배열 범위, 0으로 나누기, 깊은 재귀 등을 확인하세요.',
  TLE:'제한 시간 안에 끝나지 않았어요. 더 빠른 알고리즘이 필요할 수 있어요.',
  MLE:'메모리 제한을 넘었어요. 큰 배열이나 불필요한 저장을 줄여 보세요.',
  OLE:'출력이 허용된 크기를 넘었어요. 반복 출력이나 디버그 출력을 확인하세요.',
  IE:'채점 시스템 문제로 결과를 확인하지 못했어요. 풀이 실패로 세지 않아요.',
  OK:'직접 실행이 정상 종료됐어요. 정답 여부는 판정하지 않아요.'};
/** "RE · 런타임 에러"; unknown codes are shown as they are. */
export const verdictText=code=>code?(verdictNames[code]?`${code} · ${verdictNames[code]}`:code):'';
