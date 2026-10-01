package dev.gamjaoj;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ExportWorker {
    final SolutionExports exports;final ExportSettings settings;
    final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"solution-export");t.setDaemon(true);return t;});
    final AtomicBoolean running=new AtomicBoolean();
    ExportWorker(SolutionExports exports,ExportSettings settings){this.exports=exports;this.settings=settings;}
    @Scheduled(fixedDelayString="${EXPORT_POLL_MS:3000}") void tick(){
        if(!settings.enabled()||settings.value("EXPORT_WORKER_ENABLED").equalsIgnoreCase("false")||!running.compareAndSet(false,true))return;
        executor.execute(()->{try{exports.runOne();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(ExportWorker.class).warn("Solution export worker failed; durable work will be recovered ({})",e.getClass().getSimpleName());}finally{running.set(false);}});
    }
    @PreDestroy void stop(){executor.shutdownNow();}
}
