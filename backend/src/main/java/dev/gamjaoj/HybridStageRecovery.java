package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import static dev.gamjaoj.HybridGeneration.Role.*;

/** Repair the writer/core DAG with fresh budget reservations, retaining every old receipt and branch. */
final class HybridStageRecovery {
    static HybridGeneration.Role target(HybridGeneration.Role failed,JsonNode receipt) {
        if(failed==CONTENT_REVIEW) {
            var review=receipt.path("payload");
            if(!review.path("implementationAligned").asBoolean(true))return CORE;
            return PRESENTATION;
        }
        return Set.of(CORE,PRESENTATION,READER).contains(failed)?failed:null;
    }
    static void advance(JdbcClient jdbc,HybridGeneration jobs,AiSettings settings,AiTasks ledger) {
        for(var id:jdbc.sql("SELECT id FROM hybrid_generation WHERE resource_validation=true AND status='HELD' AND error_code NOT IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED') AND deadline_at>CURRENT_TIMESTAMP ORDER BY updated_at LIMIT 20 FOR UPDATE").query(UUID.class).list()) {
            if(jdbc.sql("SELECT count(*) FROM diagnostic_practice_plan p WHERE p.hybrid_generation_id=? AND (p.training_session_id IS NOT NULL OR EXISTS (SELECT 1 FROM learning_curriculum_end e WHERE e.evaluation_id=p.evaluation_id AND e.user_id=p.user_id) OR EXISTS (SELECT 1 FROM diagnostic_practice_plan n WHERE n.previous_plan_id=p.id))").param(id).query(Integer.class).single()>0)continue;
            if(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation r JOIN ai_attempt a ON a.id=r.attempt_id WHERE r.generation_id=? AND (a.status IN ('HYBRID_RUNNING','HYBRID_UNKNOWN') OR (a.status IN ('HYBRID_COMPLETED','HYBRID_FAILED') AND a.actual_usd IS NULL))").param(id).query(Integer.class).single()>0)continue;
            UUID owner=jdbc.sql("SELECT owner_id FROM hybrid_generation WHERE id=?").param(id).query(UUID.class).single();
            if(jdbc.sql("SELECT count(*) FROM generation_spec_draft WHERE owner_id=? AND status IN ('QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING')").param(owner).query(Integer.class).single()>0||jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE owner_id=? AND id<>? AND status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING')").param(owner).param(id).query(Integer.class).single()>0)continue;
            var failure=jdbc.sql("SELECT role,error_code,completion_json FROM hybrid_branch WHERE generation_id=? AND status='FAILED' AND late_result=false ORDER BY finished_at DESC LIMIT 1").param(id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)}).optional();
            if(failure.isEmpty()||GenerationDraftRecovery.stopped(failure.get()[1]))continue;
            // Provider errors without a structured result require explicit recovery.
            if(failure.get()[2]==null)continue;var receipt=JudgeJson.parse(failure.get()[2]);if(receipt.hasNonNull("error"))continue;
            var target=target(HybridGeneration.Role.valueOf(failure.get()[0]),receipt);if(target==null)continue;
            int total=jdbc.sql("SELECT count(*) FROM generation_recovery_attempt WHERE pipeline='RULE' AND job_id=?").param(id).query(Integer.class).single();
            int stage=jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND role=?").param(id).param(target.name()).query(Integer.class).single();
            if(total>=6||stage>=3){jdbc.sql("UPDATE hybrid_generation SET error_code='STAGE_RETRY_LIMIT' WHERE id=?").param(id).update();continue;}
            var payable=EnumSet.of(CONTENT_REVIEW);if(target==PRESENTATION){payable.add(PRESENTATION);payable.add(READER);}if(target==READER)payable.add(READER);
            var models=new EnumMap<HybridGeneration.Role,AiSettings.Model>(HybridGeneration.Role.class);BigDecimal amount=BigDecimal.ZERO;
            try{for(var role:payable){var model=HybridModels.slot(settings,role);models.put(role,model);amount=amount.add(HybridExecution.reserve(role,model));}}catch(AccountException unavailable){continue;}
            var budget=ledger.budget();if(budget.spentUsd().add(budget.reservedUsd()).add(amount).compareTo(budget.limitUsd())>0)continue;
            int revision=jdbc.sql("SELECT revision FROM hybrid_generation WHERE id=?").param(id).query(Integer.class).single();
            for(var role:payable) {
                int next=jdbc.sql("SELECT COALESCE(MAX(retry),-1)+1 FROM hybrid_api_reservation WHERE generation_id=? AND revision=? AND role=?").param(id).param(revision).param(role.name()).query(Integer.class).single();
                var model=models.get(role);UUID attempt=UUID.randomUUID();
                jdbc.sql("INSERT INTO ai_attempt(id,month_key,status,reserved_usd,settings_json) VALUES (?,?,'HYBRID_RESERVED',?,?)").param(attempt).param(YearMonth.now(ZoneOffset.UTC).toString()).param(HybridExecution.reserve(role,model)).param(JudgeJson.canonical(JudgeJson.JSON.valueToTree(model))).update();
                jdbc.sql("INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,retry) VALUES (?,?,?,?,?)").param(attempt).param(id).param(revision).param(role.name()).param(next).update();
            }
            jdbc.sql("INSERT INTO generation_recovery_attempt(pipeline,job_id,attempt,scope,error_code,snapshot_json) VALUES ('RULE',?,?,?,?,?)").param(id).param(total+1).param(target.name()).param(failure.get()[1]).param(JudgeJson.canonical(receipt)).update();
            jobs.recoverStage(id,target,receipt.path("payload").path("issues"));
        }
    }
    private HybridStageRecovery(){}
}
