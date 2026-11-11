package co.kr.satu.imagegate.conversion.util.process;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ProcessExecutionBoundaryTests {
    @TempDir Path temp;
    @Test void normalWorkerOutputDoesNotInvalidateDirectoryIdentity() throws Exception {
        Path program=Files.writeString(temp.resolve("tool.exe"),"synthetic"),work=Files.createDirectory(temp.resolve("work"));
        var boundary=new ProcessExecutionBoundary(program,work);Files.writeString(work.resolve("output.png"),"synthetic output");
        Files.createDirectory(work.resolve("resource"));boundary.verify();
    }
    @Test void changedExecutableCannotPassTheEndCheck() throws Exception {
        Path program=Files.writeString(temp.resolve("tool.exe"),"synthetic"),work=Files.createDirectory(temp.resolve("work"));
        var boundary=new ProcessExecutionBoundary(program,work);Files.writeString(program,"changed executable");
        assertThatThrownBy(boundary::verify).isInstanceOfSatisfying(ConversionException.class,e->{assertThat(e.code()).isEqualTo(ConversionException.Code.UNAVAILABLE);assertThat(e.getMessage()).doesNotContain(temp.toString());});
    }
    @Test void replacedWorkingDirectoryCannotPassEvenAtSamePath() throws Exception {
        Path program=Files.writeString(temp.resolve("tool.exe"),"synthetic"),work=Files.createDirectory(temp.resolve("work"));
        var boundary=new ProcessExecutionBoundary(program,work);Files.move(work,temp.resolve("old-work"));Files.createDirectory(work);
        assertThatThrownBy(boundary::verify).isInstanceOf(ConversionException.class);
    }
    @Test void relativeUnnormalizedMissingAndWrongTypeExecutablesAreRejected() throws Exception {
        Path program=Files.writeString(temp.resolve("tool.exe"),"synthetic"),work=Files.createDirectory(temp.resolve("work"));
        for(Path path:List.of(Path.of("tool.exe"),temp.resolve("nested").resolve("..").resolve("tool.exe"),temp.resolve("absent.exe"),work))
            assertThatThrownBy(()->new ProcessExecutionBoundary(path,work)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(()->new ProcessExecutionBoundary(program,program)).isInstanceOf(ConversionException.class);
    }
    @Test void windowsJunctionWorkAndExecutableAncestorsAreRejectedWithoutFollowingOutput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path module=Files.createDirectory(temp.resolve("module")),program=Files.writeString(module.resolve("tool.exe"),"synthetic");
        Path work=Files.createDirectory(temp.resolve("work")),alias=temp.resolve("alias");
        String prefix=""+((char)92)+((char)92)+"?"+((char)92);
        Process link=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",prefix+alias,prefix+module).redirectErrorStream(true).start();link.getInputStream().readAllBytes();assertThat(link.waitFor()).isZero();
        try{
            assertThatThrownBy(()->new ProcessExecutionBoundary(program,alias)).isInstanceOf(ConversionException.class);
            assertThatThrownBy(()->new ProcessExecutionBoundary(alias.resolve("tool.exe"),work)).isInstanceOf(ConversionException.class);
            assertThat(Files.readString(program)).isEqualTo("synthetic");try(var files=Files.list(work)){assertThat(files.toList()).isEmpty();}
        }finally{Files.delete(alias);}
    }
}
