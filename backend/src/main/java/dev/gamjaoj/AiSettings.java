package dev.gamjaoj;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiSettings {
    private final Environment env;
    public AiSettings(Environment env) { this.env=env; }
    String value(String name, String fallback) { String value=env.getProperty(name);return value==null||value.isBlank()?fallback:value; }
    boolean enabled() { return Boolean.parseBoolean(value("AI_API_ENABLED","false")); }
    String key() { return value("OPENAI_API_KEY", ""); }
    BigDecimal budget() { return new BigDecimal(value("AI_MONTHLY_BUDGET_USD","10")); }
    /** "*" opens operator features (stronger re-analysis, service budget view) to every signed-in user. */
    boolean operator(String name) {
        return Arrays.stream(value("AI_OPERATOR_USERS", "").split(",")).map(String::trim).filter(s->!s.isEmpty()).anyMatch(entry->entry.equals("*")||entry.equals(name));
    }
    void requireOperator(String name) { if (!operator(name)) throw new AccountException(403,"운영자만 요청할 수 있어요."); }
    record Model(String model, String effort, BigDecimal inputRate, BigDecimal cachedRate, BigDecimal outputRate,
                 String pricingVersion, int maxOutputTokens, String promptVersion, String schemaVersion) {}
    Model model(boolean strong) {
        String prefix=strong?"AI_STRONG_":"AI_DEFAULT_";
        String model=value(prefix+"MODEL", strong?"gpt-6.1-sol":"gpt-6-luna");
        String effort=value(prefix+"REASONING",strong?"medium":"low");
        if (!Set.of("none","low","medium","high","xhigh","max").contains(effort))
            throw new AccountException(503,"AI reasoning 설정을 확인해 주세요.");
        // Rates belong to the exact configured model, not the slot. Unknown models fail closed.
        String[] rates=switch(model) {
            case "gpt-6-luna" -> new String[]{"0.10","0.01","0.50"};
            case "gpt-6.1-sol" -> new String[]{"2.00","0.10","10.00"};
            default -> new String[]{"", "", ""};
        };
        try {
            BigDecimal input=new BigDecimal(value(prefix+"INPUT_USD_PER_M",rates[0]));
            BigDecimal cached=new BigDecimal(value(prefix+"CACHED_USD_PER_M",rates[1]));
            BigDecimal output=new BigDecimal(value(prefix+"OUTPUT_USD_PER_M",rates[2]));
            if (input.signum()<=0 || output.signum()<=0 || cached.signum()<0 || cached.compareTo(input)>0) throw new IllegalArgumentException();
            return new Model(model,effort,input,cached,output,value(prefix+"PRICING_VERSION","openai-standard-2026-10-01"),
                    2048,"feedback-v2","feedback-v1");
        } catch (IllegalArgumentException e) { throw new AccountException(503,"선택 모델의 API 단가 설정이 필요해요. 다른 모델로 전환하지 않았어요."); }
    }
}
