package co.kr.satu.imagegate.playback.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.util.process.ProcessExecutor;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FfmpegDeadlineTests {
    @TempDir Path root;

    @Test void nativeCallsShareOneBudgetAndEachPreparationGetsAFreshBudget() throws Exception {
        var fixture=new FfmpegApprovalTests();fixture.root=root;
        var profile=fixture.profile();var delegate=fixture.approvedProcess();
        var now=new AtomicLong();var budgets=new ArrayList<Duration>();
        ProcessExecutor process=(cmd,work,timeout)->{
            if(!cmd.contains("-L")&&!cmd.contains("-version")){
                budgets.add(timeout);now.addAndGet(Duration.ofSeconds(4).toNanos());
            }
            return delegate.execute(cmd,work,timeout);
        };
        var engine=new FfmpegEngine(profile,process,root,now::get);
        engine.prepare(fixture.audio(),root.resolve("input.wav"),root);
        engine.prepare(fixture.audio(),root.resolve("input.wav"),root);
        assertThat(budgets).containsExactly(Duration.ofSeconds(30),Duration.ofSeconds(26),Duration.ofSeconds(22),
                Duration.ofSeconds(30),Duration.ofSeconds(26),Duration.ofSeconds(22));
    }

    @ParameterizedTest @ValueSource(ints={1,2,3})
    void overrunAtAnyNativeStageStopsSubsequentCallsAndArtifactReturn(int stage) throws Exception {
        var fixture=new FfmpegApprovalTests();fixture.root=root;
        var profile=fixture.profile();var delegate=fixture.approvedProcess();
        var now=new AtomicLong();var calls=new AtomicInteger();
        var engine=new FfmpegEngine(profile,(cmd,work,timeout)->{
            var result=delegate.execute(cmd,work,timeout);
            if(!cmd.contains("-L")&&!cmd.contains("-version")&&calls.incrementAndGet()==stage)
                now.addAndGet(Duration.ofSeconds(30).toNanos());
            return result;
        },root,now::get);
        assertThatThrownBy(()->engine.prepare(fixture.audio(),root.resolve("input.wav"),root))
                .isInstanceOfSatisfying(ConversionException.class,e->assertThat(e.code()).isEqualTo(ConversionException.Code.TIMEOUT));
        assertThat(calls.get()).isEqualTo(stage);
        // A job timeout does not invalidate the approved module or exhaust the next job's budget.
        calls.set(100);
        assertThat(engine.prepare(fixture.audio(),root.resolve("input.wav"),root)).isNotNull();
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void cancellationBeforeOrAfterAnyNativeStagePreservesFlagAndBlocksFurtherWork(int stage) throws Exception {
        var fixture=new FfmpegApprovalTests();fixture.root=root;
        var profile=fixture.profile();var delegate=fixture.approvedProcess();var calls=new AtomicInteger();
        var engine=new FfmpegEngine(profile,(cmd,work,timeout)->{
            var result=delegate.execute(cmd,work,timeout);
            if(!cmd.contains("-L")&&!cmd.contains("-version")&&calls.incrementAndGet()==stage)
                Thread.currentThread().interrupt();
            return result;
        },root);
        try {
            if(stage==0)Thread.currentThread().interrupt();
            assertThatThrownBy(()->engine.prepare(fixture.audio(),root.resolve("input.wav"),root))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls.get()).isEqualTo(stage);
        } finally {Thread.interrupted();}
        calls.set(100);
        assertThat(engine.prepare(fixture.audio(),root.resolve("input.wav"),root)).isNotNull();
    }
}
