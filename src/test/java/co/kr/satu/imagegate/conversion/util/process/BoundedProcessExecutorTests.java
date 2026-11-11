package co.kr.satu.imagegate.conversion.util.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessExecutorTests {
    @TempDir Path directory;

    @Test
    void preservesArgumentsWithoutShellInterpretation() throws Exception {
        String text = "space ; & | $ (literal)";
        var result = new BoundedProcessExecutor(1).execute(command("echo", text),
                directory, Duration.ofSeconds(10));
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(text);
    }

    @Test
    void boundsOutputWithoutBlockingAndReturnsExitCode() throws Exception {
        var runner = new BoundedProcessExecutor(1);
        var spam = runner.execute(command("spam"), directory, Duration.ofSeconds(10));
        assertThat(spam.output()).hasSize(8192);
        assertThat(spam.outputTruncated()).isTrue();
        assertThat(runner.execute(command("fail"), directory, Duration.ofSeconds(10)).exitCode())
                .isEqualTo(7);
    }

    @Test
    void timesOutTerminatesProcessAndReleasesCapacity() throws Exception {
        var runner = new BoundedProcessExecutor(1);
        Path pidFile = directory.resolve("parent.pid");
        assertThatThrownBy(() -> runner.execute(command("sleep", pidFile.toString()),
                directory, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(ConversionException.class, error ->
                        assertThat(error.code()).isEqualTo(ConversionException.Code.TIMEOUT));
        assertStopped(pidFile);
        assertThat(runner.execute(command("echo", "reused"), directory,
                Duration.ofSeconds(10)).output()).isEqualTo("reused");
    }

    @Test
    void rejectsExcessConcurrentJobsInsteadOfBuildingUnboundedQueue() throws Exception {
        var runner = new BoundedProcessExecutor(1);
        Path pidFile = directory.resolve("busy.pid");
        var first = CompletableFuture.runAsync(() -> {
            try {
                runner.execute(command("sleep", pidFile.toString()), directory, Duration.ofSeconds(3));
            } catch (Exception expectedTimeout) {
                if (!(expectedTimeout instanceof ConversionException error)
                        || error.code() != ConversionException.Code.TIMEOUT) {
                    throw new RuntimeException(expectedTimeout);
                }
            }
        });
        awaitFile(pidFile);
        assertThatThrownBy(() -> runner.execute(command("echo", "blocked"), directory,
                Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ConversionException.class, error ->
                        assertThat(error.code()).isEqualTo(ConversionException.Code.BUSY));
        first.get(10, TimeUnit.SECONDS);
        assertStopped(pidFile);
    }

    @Test
    void terminatesObservedChildOnTimeout() throws Exception {
        Path childPid = directory.resolve("child.pid");
        var args = command("child", javaExecutable(), classPath(), childPid.toString());
        assertThatThrownBy(() -> new BoundedProcessExecutor(1).execute(args,
                directory, Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(ConversionException.class, error ->
                        assertThat(error.code()).isEqualTo(ConversionException.Code.TIMEOUT));
        assertStopped(childPid);
    }


    @Test
    void stripsSyntheticParentCredentialsAndJvmInjectionAndUsesPrivateWorkPaths() throws Exception {
        Path outer=Files.createDirectory(directory.resolve("parent")),work=Files.createDirectory(directory.resolve("job"));
        String mainClasses=Path.of(BoundedProcessExecutor.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String parentClasspath=classPath()+java.io.File.pathSeparator+mainClasses;
        var builder=new ProcessBuilder(javaExecutable(),"-cp",parentClasspath,ProcessEnvironmentParentFixture.class.getName(),javaExecutable(),classPath(),work.toString());
        var env=builder.environment();env.clear();String systemRoot=System.getenv("SystemRoot");if(systemRoot!=null)env.put("SystemRoot",systemRoot);
        for(String key:List.of("HOME","USERPROFILE","TEMP","TMP","TMPDIR"))env.put(key,outer.toString());
        env.put("PATH",outer.toString());env.put("AWS_SECRET_ACCESS_KEY","synthetic-not-a-real-key");
        env.put("IMAGEGATE_TEST_SECRET","synthetic-secret");env.put("FFREPORT","synthetic-ffreport");
        env.put("JAVA_TOOL_OPTIONS","-Dimagegate.fixture.inherited=true");
        Process parent=builder.start();
        try{
            assertThat(parent.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(parent.exitValue()).isZero();
            var properties=new java.util.Properties();properties.load(parent.getInputStream());
            for(String key:List.of("AWS_SECRET_ACCESS_KEY","IMAGEGATE_TEST_SECRET","JAVA_TOOL_OPTIONS","FFREPORT"))assertThat(properties.getProperty("env."+key)).isNull();
            assertThat(properties.getProperty("property.inherited")).isEqualTo("absent");
            for(String key:List.of("HOME","TEMP","TMP","TMPDIR"))assertThat(Path.of(properties.getProperty("env."+key))).isEqualTo(work);
            if(System.getProperty("os.name").startsWith("Windows"))assertThat(Path.of(properties.getProperty("env.USERPROFILE"))).isEqualTo(work);
            assertThat(Path.of(properties.getProperty("cwd"))).isEqualTo(work);
            assertThat(properties.getProperty("stdin")).isEqualTo("-1");
            assertThat(properties.getProperty("env.PATH")).doesNotContain(outer.toString());
            assertThat(Files.readString(work.resolve("relative.txt"))).isEqualTo("fixture output");
            // The synthetic parent JVM may create its own hsperfdata directory here.
            assertThat(Files.exists(outer.resolve("relative.txt"))).isFalse();
        }finally{if(parent.isAlive()){parent.destroyForcibly();parent.waitFor(5,TimeUnit.SECONDS);}parent.getInputStream().close();parent.getErrorStream().close();parent.getOutputStream().close();}
    }
    @Test
    void rejectsUntrustedWorkingPathsWithoutCreatingThemAndPreservesCapacity() throws Exception {
        var runner=new BoundedProcessExecutor(1);Path missing=directory.resolve("missing");Path file=Files.writeString(directory.resolve("not-directory"),"keep");
        for(Path work:java.util.Arrays.asList(missing,file,Path.of("relative"),directory.resolve("x").resolve(".."),null)){
            assertThatThrownBy(()->runner.execute(command("echo","no"),work,Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(ConversionException.class,error->{assertThat(error.code()).isEqualTo(ConversionException.Code.UNAVAILABLE);assertThat(error.getMessage()).doesNotContain(directory.toString());});
        }
        assertThat(Files.exists(missing)).isFalse();assertThat(Files.readString(file)).isEqualTo("keep");
        assertThat(runner.execute(command("echo","reused"),directory,Duration.ofSeconds(10)).output()).isEqualTo("reused");
    }
    @Test
    void rejectsOversizedCommandsAndInvalidTimeoutsBeforeLaunching() throws Exception {
        var runner=new BoundedProcessExecutor(1);
        for(var values:List.of(java.util.Collections.nCopies(257,"x"),List.of(javaExecutable(),"x".repeat(32769)),List.of(javaExecutable(),"bad"+((char)0))))
            assertThatThrownBy(()->runner.execute(values,directory,Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        for(Duration timeout:List.of(Duration.ZERO,Duration.ofSeconds(-1),Duration.ofMinutes(6)))
            assertThatThrownBy(()->runner.execute(command("echo","no"),directory,timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.execute(command("echo","reused"),directory,Duration.ofSeconds(10)).output()).isEqualTo("reused");
    }
    @Test
    void failedNativeLaunchHasFixedMessageAndReleasesSlot() throws Exception {
        var runner=new BoundedProcessExecutor(1);Path invalid=Files.writeString(directory.resolve("invalid.exe"),"not an executable");
        assertThatThrownBy(()->runner.execute(List.of(invalid.toString()),directory,Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ConversionException.class,error->{assertThat(error.code()).isEqualTo(ConversionException.Code.UNAVAILABLE);assertThat(error.getMessage()).doesNotContain(directory.toString(),"invalid.exe");});
        assertThat(runner.execute(command("echo","reused"),directory,Duration.ofSeconds(10)).output()).isEqualTo("reused");
    }
    @Test
    void interruptionStopsProcessPreservesInterruptAndReleasesSlot() throws Exception {
        var runner=new BoundedProcessExecutor(1);Path pid=directory.resolve("interrupted.pid");
        var failed=new java.util.concurrent.atomic.AtomicReference<Throwable>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var args=command("sleep",pid.toString());
        Thread thread=Thread.ofPlatform().start(()->{
            try{runner.execute(args,directory,Duration.ofSeconds(10));failed.set(new AssertionError("Expected interruption"));}
            catch(java.io.IOException expected){interrupted.set(Thread.currentThread().isInterrupted());}
            catch(Throwable unexpected){failed.set(unexpected);}
        });
        try{awaitFile(pid);thread.interrupt();thread.join(5000);assertThat(thread.isAlive()).isFalse();assertThat(failed.get()).isNull();assertThat(interrupted.get()).isTrue();assertStopped(pid);}
        finally{thread.interrupt();thread.join(5000);}
        assertThat(runner.execute(command("echo","reused"),directory,Duration.ofSeconds(10)).output()).isEqualTo("reused");
    }
    @Test
    void successfulParentExitAlsoTerminatesObservedChild() throws Exception {
        Path pid=directory.resolve("orphan.pid");var result=new BoundedProcessExecutor(1).execute(
                command("child-exit",javaExecutable(),classPath(),pid.toString()),directory,Duration.ofSeconds(10));
        assertThat(result.exitCode()).isZero();assertThat(result.output()).isEqualTo("parent-finished");assertStopped(pid);
    }

    private List<String> command(String... args) throws Exception {
        var command = new ArrayList<>(List.of(javaExecutable(), "-cp",
                classPath(), ProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    private static String classPath() throws Exception {
        return Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().toString();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(path).exists();
    }

    private static void assertStopped(Path pidFile) throws Exception {
        assertThat(pidFile).exists();
        long pid = Long.parseLong(Files.readString(pidFile));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
