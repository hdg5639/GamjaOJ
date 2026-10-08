package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.gamjaoj.generation.service.GenerationJobs;
import dev.gamjaoj.generation.service.GenerationTemplate;
import dev.gamjaoj.generation.service.HybridRuleOnboarding;
import dev.gamjaoj.judge.service.JudgeQueue;
import dev.gamjaoj.learning.service.PracticeFollowups;
import dev.gamjaoj.shared.support.JudgeJson;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

@SpringBootTest(
    properties = {
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.invite-code=test",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000",
      "HYBRID_RULE_ONBOARDING_WORKER_ENABLED=false"
    })
class ConnectionPoolReadIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired GenerationJobs jobs;
  @Autowired HybridRuleOnboarding rules;
  @Autowired GatedDataSource dataSource;
  @Autowired PracticeFollowups followups;
  @Autowired JudgeQueue queue;

  @TestConfiguration
  static class Config {
    @Bean(destroyMethod = "close")
    GatedDataSource dataSource() {
      return new GatedDataSource();
    }
  }

  // Pause five real result-set readers while each holds one of the five production-sized pool
  // connections.
  static class GatedDataSource extends AbstractDataSource implements AutoCloseable {
    final HikariDataSource pool;
    volatile String prefix;
    volatile CountDownLatch entered, release;

    GatedDataSource() {
      var c = new HikariConfig();
      c.setJdbcUrl("jdbc:h2:mem:poolreads;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
      c.setUsername("sa");
      c.setPassword("");
      c.setMaximumPoolSize(5);
      c.setConnectionTimeout(1000);
      pool = new HikariDataSource(c);
    }

    public Connection getConnection() throws SQLException {
      return wrap(pool.getConnection());
    }

    public Connection getConnection(String u, String p) throws SQLException {
      return wrap(pool.getConnection(u, p));
    }

    public void close() {
      pool.close();
    }

    Connection wrap(Connection c) {
      return proxy(
          Connection.class,
          c,
          (m, a, result) -> {
            if (m.getName().equals("prepareStatement")
                && prefix != null
                && ((String) a[0]).startsWith(prefix))
              return proxy(
                  PreparedStatement.class,
                  (PreparedStatement) result,
                  (pm, pa, pr) -> {
                    if (pm.getName().equals("executeQuery"))
                      return proxy(
                          ResultSet.class,
                          (ResultSet) pr,
                          (rm, ra, rr) -> {
                            if (rm.getName().equals("next") && Boolean.TRUE.equals(rr)) {
                              entered.countDown();
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("readers did not assemble");
                            }
                            return rr;
                          });
                    return pr;
                  });
            return result;
          });
    }
  }

  interface After {
    Object apply(Method method, Object[] args, Object result) throws Throwable;
  }

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, T target, After after) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              try {
                return after.apply(m, a, m.invoke(target, a));
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  UUID owner(String name) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,'unused',?)")
        .param(id)
        .param(name)
        .param(name)
        .update();
    return id;
  }

  void concurrentReads(String prefix, Callable<?> read) throws Exception {
    dataSource.entered = new CountDownLatch(5);
    dataSource.release = new CountDownLatch(1);
    dataSource.prefix = prefix;
    var executor = Executors.newFixedThreadPool(6);
    var futures = new ArrayList<Future<?>>();
    try {
      for (int i = 0; i < 5; i++) futures.add(executor.submit(read));
      assertThat(dataSource.entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(dataSource.pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(5);
      dataSource.release.countDown();
      var independent =
          executor.submit(
              () -> {
                assertThat(queue.claim(UUID.randomUUID())).isEmpty();
                assertThat(rules.claimCodexAuthor()).isNull();
                assertThat(jobs.claim()).isNull();
                return jdbc.sql("SELECT 1").query(Integer.class).single();
              });
      Thread.sleep(150);
      if (dataSource.pool.getHikariPoolMXBean().getThreadsAwaitingConnection() > 0) {
        System.out.println(
            "POOL REPRO active="
                + dataSource.pool.getHikariPoolMXBean().getActiveConnections()
                + " pending="
                + dataSource.pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        Thread.getAllStackTraces()
            .forEach(
                (thread, stack) -> {
                  if (thread.getName().startsWith("pool-")) {
                    System.out.println(thread.getName());
                    for (var frame : stack) System.out.println("  " + frame);
                  }
                });
      }
      for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThat(independent.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      dataSource.prefix = null;
      dataSource.release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void generationDetailAndListReleaseRowsBeforeEnrichment() throws Exception {
    UUID owner = owner("pool-generation"), id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO generation_job(id,owner_id,template_id,status,model,effort,focus) VALUES"
                + " (?,?,?,'NEEDS_REVIEW','fixture','medium','basics')")
        .param(id)
        .param(owner)
        .param(GenerationTemplate.ID)
        .update();
    concurrentReads("SELECT g.*,COALESCE", () -> jobs.view("pool-generation", id));
    concurrentReads("SELECT g.*,COALESCE", () -> jobs.list("pool-generation"));
  }

  @Test
  void onboardingListReleasesRowsBeforeChecksCostAndCodexStages() throws Exception {
    UUID owner = owner("pool-rules"), id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO"
                + " hybrid_rule_onboarding(id,owner_id,request_json,request_sha256,status,budget_usd,created_at,updated_at,deadline_at)"
                + " VALUES"
                + " (?,?,?,?,'HELD',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
        .param(id)
        .param(owner)
        .param("{\"request\":\"test\",\"difficulty\":\"HARD\"}")
        .param("0".repeat(64))
        .update();
    concurrentReads("SELECT o.*,v.catalog_json", () -> rules.list("pool-rules"));
  }

  @Test
  void followupListReleasesRowsBeforeCandidateAndAttemptQueries() throws Exception {
    UUID owner = owner("pool-followup"), analysis = UUID.randomUUID(), id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO"
                + " ai_task(id,user_id,kind,cache_key,settings_json,input_json,status,result_json)"
                + " VALUES (?,?,'ANALYSIS',?,'{}','{}','COMPLETED','{}')")
        .param(analysis)
        .param(owner)
        .param(JudgeJson.hash(analysis.toString()))
        .update();
    jdbc.sql(
            "INSERT INTO"
                + " practice_followup(id,user_id,analysis_id,step_index,goal,focus,source_version,round_id)"
                + " VALUES (?,?,?,0,'goal','basics','total-v1',?)")
        .param(id)
        .param(owner)
        .param(analysis)
        .param(UUID.randomUUID())
        .update();
    concurrentReads("SELECT f.*,p.review_hold", () -> followups.list("pool-followup"));
  }
}
