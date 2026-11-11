package co.kr.satu.imagegate.conversion.util.process;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
/** Runs only in tests, with synthetic parent credentials/injection flags set by the test. */
public final class ProcessEnvironmentParentFixture {
    public static void main(String[] args) throws Exception {
        var result=new BoundedProcessExecutor(1).execute(List.of(args[0],"-cp",args[1],ProcessFixture.class.getName(),"environment"),Path.of(args[2]),Duration.ofSeconds(10));
        if(result.exitCode()!=0||result.outputTruncated())throw new IllegalStateException("Synthetic worker failed");
        System.out.print(result.output());
    }
}
