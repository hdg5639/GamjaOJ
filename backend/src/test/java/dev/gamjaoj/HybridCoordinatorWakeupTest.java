package dev.gamjaoj;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridCoordinatorWakeupTest {
    @Test void wakeupDuringAdvanceIsNotLostAndApiRunsAfterPublication() throws Exception {
        var checks=mock(HybridRunnerChecks.class);var publication=mock(HybridPublication.class);var api=mock(HybridApiWorker.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var twice=new CountDownLatch(2);var count=new AtomicInteger();
        doAnswer(i->{if(count.incrementAndGet()==1){entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}return null;}).when(checks).advance();
        doAnswer(i->{twice.countDown();return null;}).when(api).wake();
        var wake=new HybridCoordinatorWakeup(checks,publication,api,new AiSettings(new MockEnvironment().withProperty("HYBRID_COORDINATOR_EVENTS_ENABLED","true")));
        try {
            wake.changed(new HybridExecution.Wakeup());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            wake.changed(new HybridExecution.Wakeup());release.countDown();assertThat(twice.await(5,TimeUnit.SECONDS)).isTrue();
            var order=inOrder(checks,publication,api);order.verify(checks).advance();order.verify(publication).advance();order.verify(api).wake();
            order.verify(checks).advance();order.verify(publication).advance();order.verify(api).wake();
        }finally{release.countDown();wake.close();}
    }
    @Test void onlyKnownSmallRolesCanUseFunctionalMode() {
        for(String r:new String[]{"domain-valid","domain-invalid","domain-reference","domain-oracle","mutant-unbounded","mutant-strict-fit"})
            assertThat(HybridRunnerChecks.executionMode("FUNCTIONAL_V1",r)).isEqualTo("FUNCTIONAL");
        for(String r:new String[]{"stress-valid","stress-reference-0","stress-reference-1","package-generator","batch-valid","batch-reference","batch-oracle","package-final-0","package-final-1","unknown"})
            assertThat(HybridRunnerChecks.executionMode("FUNCTIONAL_V1",r)).isEqualTo("EXCLUSIVE");
        assertThat(HybridRunnerChecks.executionMode("SERIAL_V1","domain-reference")).isEqualTo("EXCLUSIVE");
    }
}
