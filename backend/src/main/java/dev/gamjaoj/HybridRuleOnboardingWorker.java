package dev.gamjaoj;

import dev.gamjaoj.ai.OpenAiResponses;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

interface RuleOnboardingProvider {
    OpenAiResponses.Result generate(HybridRuleOnboarding.Call call);
}
@Component
class ResponsesRuleOnboardingProvider implements RuleOnboardingProvider {
    private final AiSettings config;
    ResponsesRuleOnboardingProvider(AiSettings config){this.config=config;}
    public OpenAiResponses.Result generate(HybridRuleOnboarding.Call call) {
        Duration remaining=Duration.between(OffsetDateTime.now(ZoneOffset.UTC),call.deadlineAt());
        if(remaining.isNegative()||remaining.isZero())throw new OpenAiResponses.Failure("DEADLINE_BEFORE_DISPATCH",null,null);
        var m=call.model();
        return new OpenAiResponses(config.key()).generate(m.model(),m.effort(),call.instructions(),call.input(),
                "rule_"+call.role().toLowerCase(java.util.Locale.ROOT)+"_v1",call.schema(),m.maxOutputTokens(),remaining.compareTo(Duration.ofSeconds(280))>0?Duration.ofSeconds(280):remaining);
    }
}
/** One onboarding model call at a time; Runner qualification advances on judge completion wakeups. */
@Component
class HybridRuleOnboardingWorker {
    private final HybridRuleOnboarding onboarding;private final RuleOnboardingProvider provider;private final AiSettings config;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"rule-onboarding");t.setDaemon(true);return t;});
    HybridRuleOnboardingWorker(HybridRuleOnboarding onboarding,RuleOnboardingProvider provider,AiSettings config){this.onboarding=onboarding;this.provider=provider;this.config=config;}
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    void changed(HybridExecution.Wakeup event){wake();}
    @Scheduled(fixedDelayString="${AI_POLL_MS:5000}",initialDelayString="${AI_POLL_MS:5000}")
    void tick(){wake();}
    void wake() {
        if(!Boolean.parseBoolean(config.value("HYBRID_RULE_ONBOARDING_WORKER_ENABLED","true"))||!running.compareAndSet(false,true))return;
        try{executor.submit(()->{try{onboarding.advance();while(runOnce()){onboarding.advance();}}
            catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rule onboarding step failed; retried on the next tick",failure);}
            finally{running.set(false);}});}
        catch(RejectedExecutionException closed){running.set(false);}
    }
    boolean runOnce() {
        var call=onboarding.claimCall();if(call==null)return false;
        OpenAiResponses.Result result=null;OpenAiResponses.Failure failure=null;
        try{result=provider.generate(call);if(result==null)throw new IllegalStateException("Missing provider result");}
        catch(OpenAiResponses.Failure e){failure=e;}
        catch(RuntimeException e){failure=new OpenAiResponses.Failure("PROVIDER_FAILURE_USAGE_UNKNOWN",null,null);}
        onboarding.finishCall(call.attemptId(),result,failure);return true;
    }
    @PreDestroy void close(){executor.shutdownNow();}
}
