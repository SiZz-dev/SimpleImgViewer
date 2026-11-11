package co.kr.satu.imagegate.conversion.util.process;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Objects;

/** Local canonical paths and generated environment; does not grant OS isolation. */
final class ProcessExecutionBoundary {
    private record Identity(Object key,FileTime created,long size,FileTime modified) {}
    private final Path executable,work;
    private final Identity program,directory;
    ProcessExecutionBoundary(Path executable,Path work) throws IOException {
        this.executable=executable;this.work=work;
        program=identity(executable,false);directory=identity(work,true);
    }
    private static Identity identity(Path path,boolean directory) throws IOException {
        try{
            if(path==null||!path.isAbsolute()||!path.equals(path.normalize()))throw unavailable();
            var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if((directory?!a.isDirectory():!a.isRegularFile())||!path.toRealPath().equals(path))throw unavailable();
            // Converter output legitimately changes directory size/mtime, not its identity.
            return new Identity(a.fileKey(),a.creationTime(),directory?0:a.size(),directory?null:a.lastModifiedTime());
        }catch(IOException|SecurityException failure){throw unavailable();}
    }
    void verify() throws IOException {
        if(!Objects.equals(program,identity(executable,false))||!Objects.equals(directory,identity(work,true)))throw unavailable();
    }
    void configure(ProcessBuilder builder) throws IOException {
        verify();builder.directory(work.toFile()).redirectErrorStream(true);
        var environment=builder.environment();environment.clear();
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        String systemRoot=windows?System.getenv("SystemRoot"):null;
        if(windows&&(systemRoot==null||systemRoot.isBlank()))throw unavailable();
        if(systemRoot!=null){
            environment.put("SystemRoot",systemRoot);environment.put("WINDIR",systemRoot);
            environment.put("PATH",executable.getParent()+File.pathSeparator+Path.of(systemRoot,"System32"));
            environment.put("USERPROFILE",work.toString());
        }else environment.put("PATH",executable.getParent().toString());
        for(String name:java.util.List.of("TEMP","TMP","TMPDIR","HOME"))environment.put(name,work.toString());
    }
    static ConversionException unavailable(){return new ConversionException(ConversionException.Code.UNAVAILABLE,
            "Executable or working directory is unavailable or changed");}
}
