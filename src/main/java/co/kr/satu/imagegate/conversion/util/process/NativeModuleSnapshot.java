package co.kr.satu.imagegate.conversion.util.process;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable startup baseline of selected EXEs, adjacent native libraries and optional direct resources.
 * Trust in installation/ACL and vendor/license approval remains an operator responsibility. */
public final class NativeModuleSnapshot {
    private static final long MAX_FILE=268435456,MAX_TOTAL=536870912;
    private static final int MAX_FILES=256,MAX_VISITS=4096;
    private record Stamp(boolean directory,Object key,FileTime created,long size,FileTime modified) {}
    private final List<Path> programs,roots;private final boolean resources;
    private final Map<Path,Stamp> baseline;private final Map<Path,String> hashes;
    private final String fingerprint;private final AtomicBoolean invalid=new AtomicBoolean();
    private NativeModuleSnapshot(List<Path> programs,boolean resources) throws IOException {
        if(programs==null||programs.isEmpty()||programs.size()>8||new HashSet<>(programs).size()!=programs.size())throw unavailable();
        this.programs=List.copyOf(programs);this.resources=resources;
        roots=programs.stream().map(Path::getParent).distinct().sorted().toList();
        baseline=inventory();var content=new TreeMap<Path,String>();var identity=digest();
        for(var entry:baseline.entrySet())if(!entry.getValue().directory()){
            String sha=hash(entry.getKey(),entry.getValue());content.put(entry.getKey(),sha);
            int root=0;while(!entry.getKey().startsWith(roots.get(root)))root++;
            String relative=roots.get(root).relativize(entry.getKey()).toString().replace('\\','/');
            identity.update((root+"/"+relative+"\n"+entry.getValue().size()+"\n"+sha+"\n").getBytes(StandardCharsets.UTF_8));
        }
        if(!baseline.equals(inventory()))throw unavailable();
        hashes=Map.copyOf(content);fingerprint="native-module-v1/"+HexFormat.of().formatHex(identity.digest());
    }
    public static NativeModuleSnapshot capture(List<Path> programs,boolean resources) throws IOException {
        try{return new NativeModuleSnapshot(programs,resources);}
        catch(InterruptedIOException interrupted){throw interrupted;}
        catch(IOException|RuntimeException failed){throw unavailable();}
    }
    private static void interrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Native module verification interrupted");
    }
    private static Stamp stamp(Path p,boolean directory) throws IOException {
        interrupted();
        if(p==null||!p.isAbsolute()||!p.equals(p.normalize()))throw unavailable();
        var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((directory?!a.isDirectory():!a.isRegularFile())||!p.toRealPath().equals(p)||(!directory&&a.size()>MAX_FILE))throw unavailable();
        return new Stamp(directory,a.fileKey(),a.creationTime(),directory?0:a.size(),directory?null:a.lastModifiedTime());
    }
    private Map<Path,Stamp> inventory() throws IOException {
        var result=new TreeMap<Path,Stamp>();int visits=0;
        for(Path root:roots){
            result.put(root,stamp(root,true));
            try(var children=Files.newDirectoryStream(root)){
                for(Path child:children){
                    interrupted();if(++visits>MAX_VISITS)throw unavailable();
                    String n=child.getFileName().toString().toLowerCase(Locale.ROOT);
                    if(programs.contains(child)||n.endsWith(".dll")||n.endsWith(".so")||n.contains(".so.")||n.endsWith(".dylib"))
                        result.put(child,stamp(child,false));
                }
            }
            if(resources){
                Path directory=root.resolve("resource");result.put(directory,stamp(directory,true));
                try(var children=Files.newDirectoryStream(directory)){
                    for(Path child:children){if(++visits>MAX_VISITS)throw unavailable();result.put(child,stamp(child,false));}
                }
            }
        }
        for(Path program:programs)if(!result.containsKey(program)||result.get(program).directory())throw unavailable();
        long bytes=0;int files=0;
        for(var value:result.values())if(!value.directory()){
            if(++files>MAX_FILES||(bytes+=value.size())>MAX_TOTAL)throw unavailable();
        }
        return Collections.unmodifiableMap(result);
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failed){throw new IllegalStateException(failed);}
    }
    private static String hash(Path file,Stamp expected) throws IOException {
        if(!expected.equals(stamp(file,false)))throw unavailable();
        var digest=digest();long count=0;
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];int n;
            while(count<expected.size()){
                interrupted();n=in.read(buffer,0,(int)Math.min(buffer.length,expected.size()-count));if(n<0)break;
                digest.update(buffer,0,n);count+=n;
            }
            if(count!=expected.size()||in.read()!=-1)throw unavailable();
        }
        if(!expected.equals(stamp(file,false)))throw unavailable();
        return HexFormat.of().formatHex(digest.digest());
    }
    private void active() throws IOException {if(invalid.get())throw unavailable();}
    public String fingerprint(){return fingerprint;}
    public void verifyAttributes() throws IOException {verify(false);}
    public void verifyContent() throws IOException {verify(true);}
    private void verify(boolean contents) throws IOException {
        active();
        try{
            if(!baseline.equals(inventory()))throw unavailable();
            if(contents){
                for(var entry:hashes.entrySet())if(!entry.getValue().equals(hash(entry.getKey(),baseline.get(entry.getKey()))))throw unavailable();
                if(!baseline.equals(inventory()))throw unavailable();
            }
            active();
        }catch(InterruptedIOException interrupted){throw interrupted;}
        catch(IOException|RuntimeException failed){invalid.set(true);throw unavailable();}
    }
    public void requirePinnedDigest(Path program,String expected) throws IOException {
        active();
        if(!programs.contains(program)||expected==null||!expected.matches("[a-f0-9]{64}")||!expected.equals(hashes.get(program))){invalid.set(true);throw unavailable();}
    }
    public void verifyCopiedResource(Path source,Path copy) throws IOException {
        active();
        try{
            var expected=baseline.get(source);String sha=hashes.get(source);
            if(expected==null||expected.directory()||sha==null||!expected.equals(stamp(source,false)))throw unavailable();
            var copied=stamp(copy,false);
            if(copied.size()!=expected.size()||!sha.equals(hash(copy,copied)))throw unavailable();active();
        }catch(InterruptedIOException interrupted){throw interrupted;}
        catch(IOException|RuntimeException failed){invalid.set(true);throw unavailable();}
    }
    private static ConversionException unavailable(){return new ConversionException(ConversionException.Code.UNAVAILABLE,
            "Native module unavailable or changed; restart after installation approval");}
}
