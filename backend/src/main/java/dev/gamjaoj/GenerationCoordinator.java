package dev.gamjaoj;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
class GenerationCoordinator {
    private final GenerationJobs jobs;
    GenerationCoordinator(GenerationJobs jobs) { this.jobs=jobs; }
    @Scheduled(fixedDelayString="${AI_POLL_MS:5000}",initialDelayString="${AI_POLL_MS:5000}")
    void tick() { jobs.advance(); }
}
