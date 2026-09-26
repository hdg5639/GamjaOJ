package dev.gamjaoj;

import dev.gamjaoj.ai.OpenAiResponses;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.*;

interface HybridApiProvider {
    OpenAiResponses.Result generate(HybridExecution.Work work);
}
@Component
class ResponsesHybridProvider implements HybridApiProvider {
    private final AiSettings config;
    ResponsesHybridProvider(AiSettings config){this.config=config;}
    public OpenAiResponses.Result generate(HybridExecution.Work work) {
        Duration remaining=Duration.between(OffsetDateTime.now(ZoneOffset.UTC),work.deadlineAt());
        if(remaining.isNegative()||remaining.isZero())
            throw new OpenAiResponses.Failure("DEADLINE_BEFORE_DISPATCH",JudgeJson.JSON.createObjectNode().put("input_tokens",0).put("output_tokens",0),null);
        var r=work.request();var m=r.settings();
        return new OpenAiResponses(config.key()).generate(m.model(),m.effort(),r.instructions(),r.input(),r.schemaName(),r.schema(),m.maxOutputTokens(),remaining.compareTo(Duration.ofSeconds(90))>0?Duration.ofSeconds(90):remaining);
    }
}
@Component
class HybridApiWorker {
    private final HybridExecution execution;private final HybridApiProvider provider;private final AiSettings config;
    private final AtomicBoolean running=new AtomicBoolean(),authorRunning=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hybrid-api");t.setDaemon(true);return t;});
    // Codex-quota fallback lane: replaces the concurrent Codex author, one call at a time.
    private final ExecutorService author=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hybrid-api-author");t.setDaemon(true);return t;});
    HybridApiWorker(HybridExecution execution,HybridApiProvider provider,AiSettings config){this.execution=execution;this.provider=provider;this.config=config;}
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    void changed(HybridExecution.Wakeup event){wake();}
    @Scheduled(fixedDelayString="${AI_POLL_MS:5000}",initialDelayString="${AI_POLL_MS:5000}")
    void tick(){execution.recover();wake();}
    void wake() {
        if(!Boolean.parseBoolean(config.value("HYBRID_API_WORKER_ENABLED","false")))return;
        submit(executor,running,this::runOnce);submit(author,authorRunning,this::runAuthorOnce);
    }
    private void submit(ExecutorService lane,AtomicBoolean flag,java.util.function.BooleanSupplier step) {
        if(!flag.compareAndSet(false,true))return;
        try {lane.submit(()->{try {while(step.getAsBoolean()) { /* Reader may become eligible on writer completion. */ }}finally{flag.set(false);}});}
        catch(RejectedExecutionException closed){flag.set(false);}
    }
    boolean runOnce(){return run(execution.claimApi());}
    boolean runAuthorOnce(){return run(execution.claimAuthorApi());}
    private boolean run(HybridExecution.Work work) {
        if(work==null)return false;
        OpenAiResponses.Result result=null;OpenAiResponses.Failure failure=null;
        try{result=provider.generate(work);if(result==null)throw new IllegalStateException("Missing provider result");}
        catch(OpenAiResponses.Failure e){failure=e;}
        catch(RuntimeException e){failure=new OpenAiResponses.Failure("PROVIDER_FAILURE_USAGE_UNKNOWN",null,null);}
        execution.finish(work.attemptId(),result,failure);return true;
    }
    @PreDestroy void close(){executor.shutdownNow();author.shutdownNow();}
}
