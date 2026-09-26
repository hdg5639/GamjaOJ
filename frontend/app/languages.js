export const languageInfo = {
  JAVA:{id:'JAVA',label:'Java 8',file:'Main.java',extension:'.java'},
  CPP:{id:'CPP',label:'C++17',file:'Main.cpp',extension:'.cpp'},
  PYTHON:{id:'PYTHON',label:'Python 3.12',file:'Main.py',extension:'.py'},
};
export const starters={
  JAVA:'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n        // 문제의 입력 형식에 맞춰 읽고 결과를 출력하세요.\n    }\n}\n',
  CPP:'#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    // 입력을 읽고 결과를 출력하세요.\n    return 0;\n}\n',
  PYTHON:'import sys\n\ndef main():\n    # 입력을 읽고 결과를 출력하세요.\n    pass\n\nif __name__ == "__main__":\n    main()\n',
};
export const recordLanguage=item=>item?.language||(item?.runnerPolicy?.startsWith('cpp')?'CPP':item?.runnerPolicy?.startsWith('python')?'PYTHON':'JAVA');
export const recordLanguageLabel=item=>item?.runnerPolicy?.startsWith('java21')?'Java 21 · 이전 기록':item?.execution?.label||languageInfo[recordLanguage(item)]?.label||'Java 8';
export const limitText=profile=>profile?`테스트당 ${profile.timeLimitMs/1000}초 (시작 포함) · 컨테이너 메모리 ${profile.memoryMb} MiB` : '';
