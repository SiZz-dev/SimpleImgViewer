package co.kr.satu.imagegate.conversion.util.process;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds process count, execution duration and captured output. This is not an OS
 * sandbox: production workers must additionally restrict filesystem, network,
 * CPU/memory and child process creation at the operating-system/container level.
 */
public final class BoundedProcessExecutor implements ProcessExecutor {
    private static final int CAPTURE_BYTES = 8192;
    private final Semaphore slots;

    public BoundedProcessExecutor(int maxConcurrentJobs) {
        if (maxConcurrentJobs < 1 || maxConcurrentJobs > 8) {
            throw new IllegalArgumentException("Concurrent job limit must be between 1 and 8");
        }
        slots = new Semaphore(maxConcurrentJobs);
    }

    @Override
    public Result execute(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException {
        if(command==null||command.isEmpty()||command.size()>256||command.stream().anyMatch(java.util.Objects::isNull)
                ||timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Valid bounded command and timeout are required");
        var arguments=List.copyOf(command);long characters=0;
        for(String value:arguments){
            characters+=value.length();
            if(value.indexOf(0)>=0||characters>32768)throw new IllegalArgumentException("Command size limit exceeded");
        }
        Path executable;
        try{executable=Path.of(arguments.getFirst());}catch(java.nio.file.InvalidPathException invalid){throw ProcessExecutionBoundary.unavailable();}
        var boundary=new ProcessExecutionBoundary(executable,workingDirectory);
        if (!slots.tryAcquire()) {
            throw new ConversionException(ConversionException.Code.BUSY, "Converter capacity exceeded");
        }
        Process process = null;
        Thread drainer = null;
        var descendants = new HashSet<ProcessHandle>();
        var capture = new Capture();
        try {
            var builder = new ProcessBuilder(arguments);
            boundary.configure(builder);
            boundary.verify();
            long deadline = System.nanoTime() + timeout.toNanos();
            try{process=builder.start();}catch(IOException|SecurityException failure){throw ProcessExecutionBoundary.unavailable();}
            process.getOutputStream().close();
            var stream = process.getInputStream();
            drainer = Thread.ofVirtual().name("converter-output").start(() -> {
                try (stream) {
                    byte[] buffer = new byte[2048];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        capture.append(buffer, read);
                    }
                } catch (IOException ignored) {
                    // A terminating process can close its pipe while the reader is draining it.
                }
            });
            boolean finished = false;
            while (!finished) {
                process.descendants().forEach(descendants::add);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new ConversionException(ConversionException.Code.TIMEOUT,
                            "Converter exceeded its execution deadline");
                }
                finished = process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(40)),
                        TimeUnit.NANOSECONDS);
            }
            // A parent may leave children holding the output pipe open.
            terminate(descendants);
            drainer.join(1000);
            boundary.verify();
            return new Result(process.exitValue(), capture.text(), capture.truncated());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion interrupted", interrupted);
        } finally {
            try {
            if (process != null) {
                process.descendants().forEach(descendants::add);
                terminate(descendants);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // Cleanup must not mask the original error or leak a permit.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (drainer != null && drainer.isAlive()) {
                drainer.interrupt();
            }
            } finally {
                slots.release();
            }
        }
    }

    private static void terminate(Set<ProcessHandle> processes) {
        for (var process : processes) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static final class Capture {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;

        synchronized void append(byte[] buffer, int count) {
            int accepted = Math.min(count, CAPTURE_BYTES - bytes.size());
            bytes.write(buffer, 0, accepted);
            truncated |= accepted < count;
        }

        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
        synchronized boolean truncated() { return truncated; }
    }
}
