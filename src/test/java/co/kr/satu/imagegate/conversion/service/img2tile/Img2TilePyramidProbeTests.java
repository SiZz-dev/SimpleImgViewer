package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.domain.TilePyramidLayout;
import co.kr.satu.imagegate.conversion.util.process.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.plugins.tiff.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("img2tile") @EnabledOnOs(OS.WINDOWS)
class Img2TilePyramidProbeTests {
    static int pixel(int page,int x,int y){return 0xff000000|((page+1)<<20)|(y<<10)|x;}
    static int attribute(String text,String name){
        var matcher=java.util.regex.Pattern.compile("\\b"+name+"=\"(\\d+)\"").matcher(text);
        if(!matcher.find())throw new AssertionError("Missing native attribute");return Integer.parseInt(matcher.group(1));
    }
    @Test @Timeout(120)
    void recordsRealPyramidOutputFromDocumentedEmbeddedOptions()throws Exception {
        Path base=Path.of("build/tile-contract").toAbsolutePath().normalize();Files.createDirectories(base);
        assertThat(base.toRealPath()).isEqualTo(base);
        Path root=Files.createTempDirectory(base,"run-");Path input=root.resolve("synthetic.tif");
        var writer=ImageIO.getImageWritersByFormatName("TIFF").next();
        try(var output=ImageIO.createImageOutputStream(input.toFile())){
            writer.setOutput(output);writer.prepareWriteSequence(null);
            for(int orientation:new int[]{1,6}){
                var image=new BufferedImage(513,257,BufferedImage.TYPE_INT_RGB);
                try{
                    for(int y=0;y<257;y++)for(int x=0;x<513;x++)image.setRGB(x,y,pixel(orientation==1?0:1,x,y));
                    var param=writer.getDefaultWriteParam();var directory=TIFFDirectory.createFromMetadata(writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param));
                    directory.addTIFFField(new TIFFField(BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ORIENTATION),TIFFTag.TIFF_SHORT,1,new char[]{(char)orientation}));
                    writer.writeToSequence(new IIOImage(image,null,directory.getAsMetadata()),param);
                }finally{image.flush();}
            }
            writer.endWriteSequence();
        }finally{writer.dispose();}
        String original=PreviewKeys.sha256(Files.readAllBytes(input));
        Path executable=Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe")).toRealPath();
        var module=NativeModuleSnapshot.capture(List.of(executable),true);var rows=new ArrayList<Map<String,Object>>();
        for(int page=0;page<2;page++){
            Path job=Files.createDirectory(root.resolve("page"+page));Path resource=Files.createDirectory(job.resolve("resource"));Path outputDirectory=Files.createDirectory(job.resolve("output"));
            for(String name:List.of("cmyk.icc","libc9i.xml")){
                Path source=executable.getParent().resolve("resource").resolve(name);Path copied=resource.resolve(name);Files.copy(source,copied);module.verifyCopiedResource(source,copied);
            }
            module.verifyContent();var command=List.of(executable.toString(),"-page",""+page,"-odir",outputDirectory.toString(),"-otype","png","-otype_tile","png",
                    "-otilesize","256","-oname","%c_%r.%e","-f",input.toString());
            var result=new BoundedProcessExecutor(1).execute(command,job,Duration.ofSeconds(30));module.verifyContent();
            var files=new ArrayList<Map<String,Object>>();
            try(var paths=Files.walk(outputDirectory,5)){
                for(var path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).limit(257).toList()){
                    String relative=outputDirectory.relativize(path).toString().replace('\\','/');if(relative.startsWith("resource/"))continue;
                    assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.size(path)).isLessThan(8388608);
                    var row=new LinkedHashMap<String,Object>();row.put("file",relative);row.put("bytes",Files.size(path));
                    if(relative.endsWith(".dzi")||relative.endsWith(".inf"))row.put("text",Files.readString(path));
                    if(relative.endsWith(".png")){
                        var image=ImageIO.read(path.toFile());assertThat(image).isNotNull();try{row.put("width",image.getWidth());row.put("height",image.getHeight());row.put("firstPixel",image.getRGB(0,0));row.put("lastPixel",image.getRGB(image.getWidth()-1,image.getHeight()-1));}finally{image.flush();}
                    }
                    files.add(row);assertThat(files.size()).isLessThanOrEqualTo(128);
                }
            }
            var declared=Files.readString(outputDirectory.resolve("mipmap.dzi"));
            var advertised=new TilePyramidLayout(attribute(declared,"Width"),attribute(declared,"Height"),attribute(declared,"TileSize"),attribute(declared,"Overlap"));
            var actual=new TilePyramidLayout(513,257,256,0);
            assertThat(advertised.width()).isEqualTo(513);assertThat(advertised.height()).isEqualTo(257);
            var tiles=files.stream().filter(f->f.get("file").toString().endsWith(".png")).toList();
            assertThat(tiles.size()).isEqualTo(actual.totalTiles());
            boolean advertisedMatches=true;long mismatchedPixels=0;
            for(var tile:tiles){
                String relative=tile.get("file").toString();
                var match=java.util.regex.Pattern.compile("mipmap_files/(\\d+)/(\\d+)_(\\d+)[.]png").matcher(relative);
                assertThat(match.matches()).isTrue();int z=Integer.parseInt(match.group(1)),col=Integer.parseInt(match.group(2)),row=Integer.parseInt(match.group(3));
                var bounds=actual.tile(z,col,row);var claimed=advertised.tile(z,col,row).stored();
                assertThat(tile.get("width")).isEqualTo(bounds.stored().width());assertThat(tile.get("height")).isEqualTo(bounds.stored().height());
                if(!tile.get("width").equals(claimed.width())||!tile.get("height").equals(claimed.height()))advertisedMatches=false;
                if(z==actual.maxLevel()){
                    var image=ImageIO.read(outputDirectory.resolve(relative).toFile());
                    try{for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
                        if(image.getRGB(x,y)!=pixel(page,bounds.core().x()+x,bounds.core().y()+y))mismatchedPixels++;}
                    finally{image.flush();}
                }
            }
            assertThat(mismatchedPixels).as("Selected TIFF page raw-raster pixels").isZero();
            var observed=new LinkedHashMap<String,Object>();observed.put("page",page+1);observed.put("orientation",page==0?1:6);
            observed.put("exit",result.exitCode());observed.put("files",files);observed.put("diagnostic",result.output());
            observed.put("declaredOverlap",advertised.overlap());observed.put("observedOverlap",0);observed.put("advertisedGeometryMatches",advertisedMatches);
            observed.put("rawRasterPixelsVerified",513L*257);observed.put("uprightOrientationApplied",page==0);rows.add(observed);
            assertThat(result.exitCode()).isZero();
            var source=new Img2TilePyramidValidator.Source(513,257,page+1,"TIFF_RGB",page==0?1:6,8,3,module.fingerprint());
            var validator=new Img2TilePyramidValidator();
            if(page==0){
                var manifest=validator.validate(outputDirectory,source);
                assertThat(manifest.layout()).isEqualTo(actual);assertThat(manifest.tiles()).hasSize(17);
                validator.verifyUnchanged(outputDirectory,source,manifest);
                observed.put("normalizedManifestValidated",true);
                try(var cache=new co.kr.satu.imagegate.conversion.repository.TileCache(root.resolve("normalized-cache"))){
                    var publisher=new co.kr.satu.imagegate.conversion.service.TilePyramidPublisher(validator,cache);
                    String key="d".repeat(64);var clean=publisher.publish(key,outputDirectory,source);
                    assertThat(clean.tiles()).hasSize(17);long[] verified={0};
                    for(var tile:clean.tiles()){
                        cache.withTile(key,clean.generation(),tile.level(),tile.column(),tile.row(),(stream,meta)->{
                            byte[] bytes=stream.readAllBytes();co.kr.satu.imagegate.conversion.util.TilePngSanitizer.verifyClean(bytes);
                            var image=ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                            try{if(tile.level()==actual.maxLevel()){
                                var bounds=actual.tile(tile.level(),tile.column(),tile.row()).core();
                                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
                                    assertThat(image.getRGB(x,y)).isEqualTo(pixel(0,bounds.x()+x,bounds.y()+y));verified[0]++;
                                }
                            }}finally{image.flush();}
                        });
                    }
                    assertThat(verified[0]).isEqualTo(513L*257);
                    observed.put("sanitizedCachePixelsVerified",verified[0]);observed.put("sanitizedCacheTiles",clean.tiles().size());
                }
            }else{
                assertThatThrownBy(()->validator.validate(outputDirectory,source)).isInstanceOf(co.kr.satu.imagegate.conversion.exception.ConversionException.class)
                    .extracting(e->((co.kr.satu.imagegate.conversion.exception.ConversionException)e).code())
                    .isEqualTo(co.kr.satu.imagegate.conversion.exception.ConversionException.Code.UNAVAILABLE);
                observed.put("unsupportedOrientationRejected",true);
            }
        }
        assertThat(PreviewKeys.sha256(Files.readAllBytes(input))).isEqualTo(original);
        var report=Map.of("createdAt",Instant.now().toString(),"storage",root.toString(),"moduleIdentity",module.fingerprint(),"rows",rows,"originalUnchanged",true);
        Path reportDirectory=Path.of("build/reports/tile-contract").toAbsolutePath().normalize();Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("latest.json"),JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Tile output probe: "+reportDirectory.resolve("latest.json"));
    }
}
