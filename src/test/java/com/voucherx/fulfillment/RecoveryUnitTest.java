package com.voucherx.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.recovery.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
class RecoveryUnitTest {
    @TempDir Path directory;
    @Test void recoveryKeepsConfirmedAndInFlightQuotasSeparate() {
        assertThat(RecoveryPlan.calculate(100,80,20,5)).isEqualTo(new RecoveryPlan(75,5,20));
        assertThat(RecoveryPlan.calculate(100,0,100,0)).isEqualTo(new RecoveryPlan(0,0,100));
    }
    @Test void inconsistentOrOverReservedSnapshotsCannotResume() {
        assertThatThrownBy(()->RecoveryPlan.calculate(100,80,21,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->RecoveryPlan.calculate(100,80,20,81)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->RecoveryPlan.calculate(100,-1,101,0)).isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledFaultsHaveNoSideEffects() {
        var injector=injector(false);injector.hit(FaultPoint.RESERVE_DONE,"r","e");
        assertThat(directory.resolve("evidence.jsonl")).doesNotExist();
        assertThatThrownBy(()->injector.arm(FaultPoint.RESERVE_DONE,"r","test",FaultInjector.Action.FAIL)).isInstanceOf(IllegalStateException.class);
    }
    @Test void faultIsScopedOneShotAndRecordsActualArrival() throws Exception {
        var injector=injector(true);var barrier=injector.arm(FaultPoint.RESERVE_DONE,"r","run",FaultInjector.Action.FAIL);
        injector.hit(FaultPoint.RESERVE_DONE,"other","e");assertThat(barrier.reached.getCount()).isEqualTo(1);
        assertThatThrownBy(()->injector.hit(FaultPoint.RESERVE_DONE,"r","e")).isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(barrier.reached.getCount()).isZero();injector.hit(FaultPoint.RESERVE_DONE,"r","e");
        assertThat(Files.readAllLines(directory.resolve("evidence.jsonl"))).hasSize(1).first().asString().contains("RESERVE_DONE","run");
    }
    @Test void barrierControlsInterleavingWithoutFixedSleep() throws Exception {
        var injector=injector(true);var barrier=injector.arm(FaultPoint.RECOVERY_BEFORE_RESUME,"1","run",FaultInjector.Action.PAUSE);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->injector.hit(FaultPoint.RECOVERY_BEFORE_RESUME,"1","e"));
            assertThat(barrier.reached.await(2,TimeUnit.SECONDS)).isTrue();assertThat(future.isDone()).isFalse();
            barrier.release.countDown();future.get(2,TimeUnit.SECONDS);
        }
    }
    private FaultInjector injector(boolean enabled) {
        return new FaultInjector(new FaultProperties(enabled,"","*","FAIL","test",3000,directory.resolve("evidence.jsonl").toString()),Clock.systemUTC(),new ObjectMapper());
    }
}
