package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Readable problem folders with stable IDs, safe path components and public submission metadata. */
final class GitHubSolutionLayout {
    static String difficulty(JsonNode payload){return switch(payload.path("difficulty").asText()){
        case "EASY" -> "Easy";case "MEDIUM" -> "Medium";case "HARD" -> "Hard";case "EXPERT" -> "Expert";default -> "Unrated";
    };}
    static String folder(JsonNode target,JsonNode payload){
        if(!target.path("layout").asText().equals("problem-v1"))return target.path("prefix").asText()+"/"+payload.path("username").asText()+"/"+payload.path("problemVersion").asText()+"/"+payload.path("language").asText();
        String version=payload.path("problemVersion").asText();
        if(!version.matches("[A-Za-z0-9_.-]{1,80}")||version.equals(".")||version.equals(".."))throw new ExportRemote.Failure("INVALID_TARGET",false);
        return target.path("prefix").asText()+"/"+difficulty(payload)+"/"+version+". "+safeTitle(payload.path("title").asText(),238-version.length());
    }
    static String safeTitle(String title,int budget){
        String normalized=Normalizer.normalize(title,Normalizer.Form.NFC);
        StringBuilder b=new StringBuilder();int used=0;
        String unsafe="/\\:*?\"<>|",replacement="／＼：＊？＂＜＞｜";
        for(int cp:normalized.codePoints().toArray()){
            if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT)continue;
            if(Character.isWhitespace(cp))cp=' ';
            int index=unsafe.indexOf(cp);if(index>=0)cp=replacement.charAt(index);
            String piece=new String(Character.toChars(cp));int bytes=piece.getBytes(StandardCharsets.UTF_8).length;
            if(used+bytes>budget)break;b.append(piece);used+=bytes;
        }
        String result=b.toString().strip().replaceAll(" +"," ").replaceAll("[. ]+$","");
        return result.isBlank()?"제목 없음":result;
    }
    static String path(String raw){return Arrays.stream(raw.split("/",-1)).map(ExportRemote::enc).collect(Collectors.joining("/"));}
    static String markdown(String raw){return raw.replaceAll("[\\p{Cntrl}]"," ").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace("\\","\\\\").replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("#","\\#").replace("|","\\|");}
    static String marker(JsonNode payload){return "<!-- GamjaOJ solution: "+JudgeJson.hash(payload.path("username").asText()+"\n"+payload.path("problemVersion").asText())+" -->";}
    static String language(JsonNode payload){return switch(payload.path("language").asText()){case "JAVA" -> "Java";case "CPP" -> "C++";default -> "Python";};}
    static String date(JsonNode payload){
        try{return OffsetDateTime.parse(payload.path("createdAt").asText(payload.path("finishedAt").asText())).withOffsetSameInstant(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm:ss 'KST'"));}
        catch(RuntimeException e){return "기록 없음";}
    }
    static String readme(JsonNode payload){
        StringBuilder b=new StringBuilder(marker(payload)).append("\n\n# [").append(difficulty(payload)).append("] ").append(markdown(payload.path("title").asText()))
            .append(" - ").append(markdown(payload.path("problemVersion").asText())).append("\n\n[문제 링크](").append(payload.path("problemUrl").asText()).append(")\n\n")
            .append("### 성능 요약\n\n").append("| 언어 | 결과 | 테스트별 최대 실행 시간 | 최대 메모리 |\n| --- | --- | --- | --- |\n| ")
            .append(language(payload)).append(" | AC | ").append(ExecutionMetrics.time(payload)).append(" | ").append(ExecutionMetrics.memory(payload)).append(" |\n\n")
            .append("실행 시간은 Runner가 측정한 wall time입니다. 메모리는 Runner 호스트에서 관측한 컨테이너 cgroup 최고 사용량이며 JVM·런타임과 파일 캐시를 포함합니다. 측정된 테스트별 최댓값입니다.\n\n### 분류\n\n");
        var tags=payload.path("tags");String category=payload.path("category").asText("미분류");
        b.append(markdown(category));for(var tag:tags)b.append(" · ").append(markdown(tag.asText()));
        b.append("\n\n### 난이도\n\n").append(difficulty(payload)).append(switch(payload.path("difficultySource").asText()){
            case "TEMPLATE_ESTIMATE" -> " (템플릿 추정)";case "AUTHOR_ESTIMATE" -> " (출제자 설정)";default -> "";
        });
        return b.append("\n\n### 제출 일자\n\n").append(date(payload)).append("\n\n### 풀이 코드\n\n[").append(markdown(payload.path("filename").asText())).append("](")
            .append(ExportRemote.enc(payload.path("filename").asText())).append(")\n\n---\nGamjaOJ에서 자동으로 기록한 정답 풀이입니다.\n").toString();
    }
}
