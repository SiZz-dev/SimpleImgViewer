package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.Jp2InputProfile;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.conversion.util.process.NativeModuleSnapshot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in diagnostic artifacts only; direct native observation never becomes a viewer source. */
@Tag("native-diagnostic") @Tag("img2tile") @Tag("ffmpeg")
@EnabledOnOs(OS.WINDOWS)
class NativeCompatibilityDiagnosticTests {
    record Sample(String name,boolean gray,String format){}
    final BoundedProcessExecutor processes=new BoundedProcessExecutor(1);
    final JsonMapper json=JsonMapper.builder().build();
    @Test @Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
    void createSyntheticModuleHandoffWithoutBinariesSecretsOrApplicationFallback()throws Exception{
        Path ffmpeg=Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffmpeg.exe")).toRealPath();
        Path img2tile=Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe")).toRealPath();
        var ffmpegModule=NativeModuleSnapshot.capture(List.of(ffmpeg),false);
        var imageModule=NativeModuleSnapshot.capture(List.of(img2tile),true);
        String id="run-"+UUID.randomUUID();
        Path reports=boundary("build/reports/native-compatibility"),run=Files.createDirectory(reports.resolve(id));
        Path work=Files.createDirectory(boundary("build/native-diagnostic-work").resolve(id));
        var cases=new ArrayList<Map<String,Object>>();
        for(var sample:List.of(new Sample("rgb-jp2-control",false,"jp2"),new Sample("gray-jp2",true,"jp2"),new Sample("raw-j2k",false,"j2k"))) {
            Path files=Files.createDirectory(run.resolve(sample.name())),job=Files.createDirectory(work.resolve(sample.name()));
            byte[] raw=new byte[128*96*(sample.gray()?1:3)];int at=0;
            var expected=new BufferedImage(128,96,sample.gray()?BufferedImage.TYPE_BYTE_GRAY:BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<96;y++)for(int x=0;x<128;x++){
                int bx=x/32,by=y/24;
                if(sample.gray()){int value=(bx+4*by)*17;raw[at++]=(byte)value;expected.getRaster().setSample(x,y,0,value);}
                else{int r=bx*85,g=by*85,b=(bx+2*by)%4*85;raw[at++]=(byte)r;raw[at++]=(byte)g;raw[at++]=(byte)b;expected.setRGB(x,y,0xff000000|r<<16|g<<8|b);}
            }
            Path source=files.resolve("source.raw"),input=files.resolve("input."+sample.format()),reference=files.resolve("reference.raw");
            Files.write(source,raw,StandardOpenOption.CREATE_NEW);
            try{assertThat(ImageIO.write(expected,"png",files.resolve("expected.png").toFile())).isTrue();}finally{expected.flush();}
            String pixels=sample.gray()?"gray":"rgb24";
            ffmpegModule.verifyContent();
            var encoded=processes.execute(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-n","-threads","1",
                    "-f","rawvideo","-pixel_format",pixels,"-video_size","128x96","-i",source.toString(),"-frames:v","1",
                    "-c:v","libopenjpeg","-pix_fmt",pixels,"-irreversible","0","-format",sample.format(),"-f","image2","-update","1",input.toString()),job,Duration.ofSeconds(30));
            ffmpegModule.verifyContent();assertThat(encoded.exitCode()).as("Synthetic encoder failed").isZero();regular(input,1000000);
            var decoded=processes.execute(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-n","-threads","1",
                    "-i",input.toString(),"-frames:v","1","-pix_fmt",pixels,"-f","rawvideo",reference.toString()),job,Duration.ofSeconds(30));
            ffmpegModule.verifyContent();assertThat(decoded.exitCode()).as("Synthetic reference decode failed").isZero();regular(reference,1000000);
            assertThat(Files.readAllBytes(reference)).containsExactly(raw);
            String inputHash=hash(input);
            Path resources=Files.createDirectory(job.resolve("resource"));
            for(String name:List.of("cmyk.icc","libc9i.xml")){
                Path asset=img2tile.getParent().resolve("resource").resolve(name),copy=Files.copy(asset,resources.resolve(name));imageModule.verifyCopiedResource(asset,copy);
            }
            Path output=Files.createDirectory(job.resolve("output"));
            imageModule.verifyContent();
            var result=processes.execute(List.of(img2tile.toString(),"-page","0","-odir",output.toString(),"-otype","png","-oscalesize","128",
                    "-oscalename","preview.png","-oscaleonly","-f",input.toString()),job,Duration.ofSeconds(30));
            imageModule.verifyContent();assertThat(hash(input)).isEqualTo(inputHash);
            var record=new LinkedHashMap<String,Object>();
            record.put("name",sample.name());record.put("format",sample.format());record.put("components",sample.gray()?1:3);
            record.put("sampleBits",8);record.put("width",128);record.put("height",96);record.put("referenceRoundTripExact",true);
            record.put("inputUnchanged",true);record.put("nativeExit",result.exitCode());
            Path actual=output.resolve("preview.png");record.put("outputPresent",Files.exists(actual,LinkOption.NOFOLLOW_LINKS));
            if(result.exitCode()==0&&Files.exists(actual,LinkOption.NOFOLLOW_LINKS)){
                regular(actual,2097152);var raster=boundedImage(actual);var wanted=boundedImage(files.resolve("expected.png"));
                try{
                    int mismatches=0;
                    if(raster.getWidth()!=128||raster.getHeight()!=96)mismatches=-1;
                    else for(int y=0;y<96;y++)for(int x=0;x<128;x++)if(raster.getRGB(x,y)!=wanted.getRGB(x,y))mismatches++;
                    record.put("actualWidth",raster.getWidth());record.put("actualHeight",raster.getHeight());
                    record.put("displayPixelMismatches",mismatches);record.put("outcome",mismatches==0?"MATCH":"MISMATCH");
                    if(sample.gray()&&raster.getWidth()==128&&raster.getHeight()==96){
                        record.put("probe",Map.of("x",48,"y",12,"expectedComponent0",17,
                                "actualComponent0",raster.getRaster().getSample(48,12,0),"actualBands",raster.getRaster().getNumBands()));
                    }
                }finally{raster.flush();wanted.flush();}
                Files.copy(actual,files.resolve("actual.png"));
            }else record.put("outcome",result.exitCode()!=0?"NATIVE_REJECTED":"OUTPUT_MISSING");
            cases.add(record);
        }
        ffmpegModule.verifyContent();imageModule.verifyContent();
        var readme=new StringBuilder("# img2tile synthetic diagnostic\n\nSynthetic inputs only. No business data, executables, DLLs, secrets or installation ZIP.\n\n");
        readme.append("FFmpeg/libopenjpeg generates and reference-decodes inputs only; img2tile performs the observed conversion.\n");
        readme.append("Direct native observations are test-only, never viewer sources, and do not relax JP2 admission.\n\n");
        readme.append("| Case | Native exit | Observation | Expected | Actual |\n|---|---:|---|---|---|\n");
        for(var row:cases){String name=(String)row.get("name");
            readme.append("| ").append(name).append(" | ").append(row.get("nativeExit")).append(" | ").append(row.get("outcome"))
                    .append(" | [PNG](").append(name).append("/expected.png) | ").append(Files.exists(run.resolve(name).resolve("actual.png"))?"[PNG]("+name+"/actual.png)":"none").append(" |\n");}
        readme.append("\nRecreate from the project root: `.\\gradlew.bat nativeCompatibilityDiagnostic` (JDK21, installed approved modules).\n")
                .append("Native recipe: `-page 0 -odir <private-output> -otype png -oscalesize 128 -oscalename preview.png -oscaleonly -f <input>`.\n")
                .append("Use a fresh private working directory with the approved module's resource/cmyk.icc and resource/libc9i.xml.\n")
                .append("Do not overwrite an existing output or execute an unknown binary. Exact installed paths are intentionally absent.\n\n")
                .append("A completed diagnostic is not a claim of format support or deployment acceptance. HEIC valid-input testing remains pending.\n")
                .append("Review manifest.json, module identities, all file sizes/hashes and the source recipe before forwarding internally. No automatic messaging is performed.\n");
        Files.writeString(run.resolve("README.md"),readme,StandardOpenOption.CREATE_NEW);
        var artifacts=new ArrayList<Map<String,Object>>();
        try(var paths=Files.walk(run)){
            for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList())
                artifacts.add(Map.of("path",run.relativize(file).toString().replace('\\','/'),"bytes",Files.size(file),"sha256",hash(file)));
        }
        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("schemaVersion",1);manifest.put("completed",true);manifest.put("syntheticOnly",true);
        manifest.put("diagnosticDirectNativeOnly",true);manifest.put("distributionReady",false);
        manifest.put("javaVersion",System.getProperty("java.version"));manifest.put("applicationProfileVersion",Jp2InputProfile.VERSION);
        manifest.put("img2tileIdentity",imageModule.fingerprint());manifest.put("ffmpegIdentity",ffmpegModule.fingerprint());
        manifest.put("cases",cases);manifest.put("artifacts",artifacts);
        byte[] encoded=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        String text=new String(encoded,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(img2tile.toString(),ffmpeg.toString(),run.toString(),work.toString(),System.getProperty("user.home"));
        Files.write(run.resolve("manifest.json"),encoded,StandardOpenOption.CREATE_NEW);
        for(var artifact:artifacts){Path file=run.resolve((String)artifact.get("path"));assertThat(hash(file)).isEqualTo(artifact.get("sha256"));}
        Path pointer=reports.resolve("latest-"+UUID.randomUUID()+".tmp");
        try{
            Files.write(pointer,json.writeValueAsBytes(Map.of("schemaVersion",1,"runDirectory",id,"manifestSha256",hash(run.resolve("manifest.json")))),StandardOpenOption.CREATE_NEW);
            Files.move(pointer,reports.resolve("latest.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pointer);}
        System.out.println("Diagnostic report: "+run);
        System.out.println("Observed cases: "+cases.stream().map(c->c.get("name")+"="+c.get("outcome")+"/exit"+c.get("nativeExit")).toList());
        assertThat(cases.get(0).get("outcome")).as("RGB control must match; artifacts remain for investigation").isEqualTo("MATCH");
    }
    private static Path boundary(String value)throws IOException{
        Path path=Path.of(value).toAbsolutePath().normalize();Files.createDirectories(path);
        if(!path.toRealPath().equals(path))throw new IOException("Diagnostic boundary unavailable");return path;
    }
    private static void regular(Path file,long max)throws IOException{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file)||Files.size(file)<1||Files.size(file)>max)
            throw new IOException("Diagnostic artifact unavailable");
    }
    private static BufferedImage boundedImage(Path file)throws IOException{
        try(var stream=new FileImageInputStream(file.toFile())){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Diagnostic PNG unavailable");
            var reader=readers.next();try{reader.setInput(stream,false,true);
                if(!reader.getFormatName().equalsIgnoreCase("png")||reader.getWidth(0)<1||reader.getHeight(0)<1
                        ||reader.getWidth(0)>512||reader.getHeight(0)>512)throw new IOException("Diagnostic raster limit");
                return reader.read(0);
            }finally{reader.dispose();}
        }
    }
    private static String hash(Path file)throws IOException{
        var digest=PreviewKeys.digest();try(var input=Files.newInputStream(file)){byte[] block=new byte[32768];int n;while((n=input.read(block))!=-1)digest.update(block,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
