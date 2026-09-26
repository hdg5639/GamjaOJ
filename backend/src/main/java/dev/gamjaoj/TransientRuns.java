package dev.gamjaoj;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keep completed custom results briefly for polling/lost-response recovery, not learning history. */
@Service
class TransientRuns {
    private final JdbcClient jdbc;
    TransientRuns(JdbcClient jdbc){this.jdbc=jdbc;}
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    @Transactional
    public void clean(){
        jdbc.sql("DELETE FROM submission WHERE run_input IS NOT NULL AND generation_job_id IS NULL AND spec_draft_id IS NULL AND id IN (SELECT submission_id FROM judge_job WHERE status='FINISHED' AND finished_at<?) AND id NOT IN (SELECT submission_id FROM generation_execution) AND id NOT IN (SELECT submission_id FROM generation_spec_execution)")
                .param(OffsetDateTime.now(ZoneOffset.UTC).minusHours(24)).update();
    }
}
