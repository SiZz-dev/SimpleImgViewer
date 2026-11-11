package co.kr.satu.imagegate.conversion.util.process;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ProcessExecutor {
    Result execute(List<String> command, Path workingDirectory, Duration timeout) throws IOException;

    record Result(int exitCode, String output, boolean outputTruncated) {}
}
