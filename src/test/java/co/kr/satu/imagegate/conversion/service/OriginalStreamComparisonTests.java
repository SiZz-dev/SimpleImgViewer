package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.PreviewCancellation;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic read-only comparison; no production opener selection, server or OS cache/security changes. */
@Tag("original-stream")
class OriginalStreamComparisonTests {
    enum Method {
        FILES, FILE_INPUT, FILE_CHANNEL;
        InputStream open(Path path) throws IOException {
            return switch(this){
                case FILES -> Files.newInputStream(path,StandardOpenOption.READ);
                case FILE_INPUT -> new FileInputStream(path.toFile());
                case FILE_CHANNEL -> Channels.newInputStream(FileChannel.open(path,StandardOpenOption.READ));
            };
        }
    }
    record Result(double openMs,double readMs,double updateMs,double closeMs,double finalMs,
            long bytes,int reads,int updates,double totalMs,String sha256) {}
    static double ms(long nanos){return nanos/1_000_000.0;}
    static BasicFileAttributes boundary(Path path,long expectedSize) throws IOException {
        if(path==null||!path.isAbsolute()||!path.equals(path.normalize())||!path.toRealPath().equals(path)
                ||expectedSize<1||expectedSize>32L*1024*1024)throw new IOException("Invalid comparison boundary");
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()!=expectedSize)throw new IOException("Invalid comparison file");
        return attrs;
    }
    static Result verify(Path path,Method method,long expectedSize,String expectedSha) throws IOException {
        return verify(path,method,expectedSize,expectedSha,null);
    }
    static Result verify(Path path,Method method,long expectedSize,String expectedSha,
            co.kr.satu.imagegate.conversion.util.OpenStackSampler.Marker marker) throws IOException {
        PreviewCancellation.check();
        var before=boundary(path,expectedSize);
        if(expectedSha==null||!expectedSha.matches("[a-f0-9]{64}"))throw new IOException("Invalid expected digest");
        var digest=PreviewKeys.digest();byte[] block=new byte[32768];
        long start=System.nanoTime(),t;InputStream opened;double open;
        if(marker!=null)marker.entering();
        try{t=System.nanoTime();opened=method.open(path);open=ms(System.nanoTime()-t);}
        finally{if(marker!=null)marker.exited();}long read=0,update=0,close=0,bytes=0;int reads=0,updates=0;
        // A local wrapper adds close timing without changing try-with-resources suppression.
        final long[] closed={0};
        try(var stream=new FilterInputStream(opened){
            @Override public int read(byte[] b) throws IOException{return in.read(b);}
            @Override public void close() throws IOException {
                long began=System.nanoTime();try{in.close();}finally{closed[0]+=System.nanoTime()-began;}
            }
        }){
            while(true){
                PreviewCancellation.check();t=System.nanoTime();int n=stream.read(block);read+=System.nanoTime()-t;reads++;
                if(n==-1)break;
                bytes+=n;
                if(bytes>expectedSize)throw new IOException("Original size changed");
                t=System.nanoTime();digest.update(block,0,n);update+=System.nanoTime()-t;updates++;
            }
        }
        close=closed[0];PreviewCancellation.check();t=System.nanoTime();
        String sha=HexFormat.of().formatHex(digest.digest());
        if(!sha.equals(expectedSha)||bytes!=expectedSize)throw new IOException("Original changed");
        double finalMs=ms(System.nanoTime()-t);
        var after=boundary(path,expectedSize);
        if(!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Original identity changed");
        PreviewCancellation.check();
        return new Result(open,ms(read),ms(update),ms(close),finalMs,bytes,reads,updates,ms(System.nanoTime()-start),sha);
    }
    Path directory(String setting,String fallback) throws IOException {
        Path build=Path.of("build").toAbsolutePath().normalize();
        Path path=Path.of(System.getProperty("imagegate.streamComparison."+setting,fallback)).toAbsolutePath().normalize();
        if(!path.startsWith(build)||path.equals(build))throw new IOException("Comparison storage must stay below build");
        Files.createDirectories(path);if(!path.toRealPath().equals(path))throw new IOException("Canonical comparison directory required");
        return path;
    }
    @Test @Timeout(240)
    void comparesFreshFirstAndRepeatOpensWithRotatingOrder() throws Exception {
        Path storage=Files.createTempDirectory(directory("storageDirectory","build/stream-comparison"),"run-");
        Path output=directory("reportDirectory","build/reports/stream-comparison").resolve("latest.json");
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();
        report.put("schemaVersion",1);report.put("createdAt",Instant.now().toString());report.put("storage",storage.toString());
        report.put("scope","Sequential synthetic first application read/repeat; not OS cold cache, HTTP or production P95");
        report.put("javaVersion",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("heapLimitBytes",Runtime.getRuntime().maxMemory());
        report.put("sourceEdge",2048);report.put("rounds",3);report.put("blockBytes",32768);
        boolean passed=false;
        try(var store=new LocalMediaStore(storage.resolve("originals"),32L*1024*1024)){
            var actor=new ViewerPrincipal("synthetic","owner");
            var flags=new ImageGateProperties.Features(
                    new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                    new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
            var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,null,null,null,null,null));
            byte[] png=fixture();var methods=Method.values();
            for(int round=0;round<3;round++){
                for(int position=0;position<3;position++){
                    var first=methods[(round+position)%3];
                    var media=store.register(actor.tenantId(),actor.userId(),"synthetic.png",new ByteArrayInputStream(png));
                    long registered=System.nanoTime();
                    assertThat(policy.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW)).isTrue();
                    assertThat(media.tenantId()).isEqualTo(actor.tenantId());assertThat(media.ownerId()).isEqualTo(actor.userId());
                    Path original=store.resolveOriginal(media);
                    for(int pass=0;pass<4;pass++){
                        var method=pass==0?first:methods[(round+position+pass-1)%3];
                        long age=System.nanoTime()-registered;
                        var result=verify(original,method,media.sizeBytes(),media.sha256());
                        var row=new LinkedHashMap<String,Object>();
                        row.put("round",round+1);row.put("firstOrder",position+1);row.put("firstMethod",first.name());
                        row.put("method",method.name());row.put("kind",pass==0?"first-application-read":"repeat");
                        row.put("repeatOrder",pass);row.put("ageSinceRegistrationMs",ms(age));
                        row.put("timings",result);rows.add(row);
                        assertThat(result.reads()).isEqualTo(result.updates()+1);
                        assertThat(store.find(actor.tenantId(),media.mediaId())).contains(media);
                    }
                }
            }
            assertThat(rows).hasSize(36);
            for(var method:methods){
                assertThat(rows.stream().filter(row->row.get("method").equals(method.name())&&row.get("kind").equals("first-application-read")).count()).isEqualTo(3);
                assertThat(rows.stream().filter(row->row.get("method").equals(method.name())&&row.get("kind").equals("repeat")).count()).isEqualTo(9);
            }
            report.put("originalsUnchanged",true);passed=true;
        }catch(Exception|AssertionError failure){report.put("failureType",failure.getClass().getSimpleName());throw failure;}
        finally{
            report.put("passed",passed);report.put("samples",rows);
            Files.writeString(output,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Original stream comparison: "+output+"; passed="+passed);
        }
    }
    byte[] fixture() throws IOException {
        var image=new BufferedImage(2048,2048,BufferedImage.TYPE_INT_ARGB);var output=new ByteArrayOutputStream();
        try{
            var random=new Random(20261026);int[] row=new int[2048];
            for(int y=0;y<2048;y++){
                for(int x=0;x<2048;x++)row[x]=0x80000000|random.nextInt(0x1000000);
                image.setRGB(0,y,2048,1,row,0,2048);
            }
            if(!ImageIO.write(image,"png",output))throw new IOException("PNG encoder unavailable");
            return output.toByteArray();
        }finally{image.flush();}
    }
}
