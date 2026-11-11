package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** Test-only candidates; no alternate encoder or compression setting is exposed in production. */
@Tag("png-codec-profile")
class PngCodecProfileTests {
    enum Candidate { REFERENCE_COPY, OWNED_BUFFER, LEVEL_3, LEVEL_1, LEVEL_0 }
    record Fixture(String name,int width,int height,int[] pixels,byte[] input){}
    static byte[] png(BufferedImage image,boolean metadata)throws IOException{
        var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("PNG").next();
        try(var output=new MemoryCacheImageOutputStream(bytes)){
            writer.setOutput(output);var param=writer.getDefaultWriteParam();var meta=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
            if(metadata){var tree=(IIOMetadataNode)meta.getAsTree("javax_imageio_png_1.0");var text=new IIOMetadataNode("tEXt");var entry=new IIOMetadataNode("tEXtEntry");entry.setAttribute("keyword","Comment");entry.setAttribute("value","synthetic-private-note");text.appendChild(entry);tree.appendChild(text);meta.setFromTree("javax_imageio_png_1.0",tree);}
            writer.write(null,new IIOImage(image,null,meta),param);
        }finally{writer.dispose();}return bytes.toByteArray();
    }
    static Fixture fixture(String pattern,int width,int height)throws IOException{
        boolean alpha=pattern.equals("rgba-noise");var image=new BufferedImage(width,height,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);var random=new Random(314159);int[] pixels=new int[width*height];
        try{for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=switch(pattern){
            case "flat"->0xffbedbff;case "checker"->((x/16+y/16)%2==0)?0xffbedbff:0xffdbeafe;
            case "gradient"->0xff000000|((x&255)<<16)|((y&255)<<8)|((x+y)&255);
            case "rgba-noise"->random.nextInt();default->0xff000000|(random.nextInt()&0xffffff);
        };image.setRGB(0,0,width,height,pixels,0,width);return new Fixture(pattern+"-"+width+"x"+height,width,height,pixels,png(image,true));}finally{image.flush();}
    }
    static List<Fixture> fixtures()throws IOException{
        var list=new ArrayList<Fixture>();for(String pattern:List.of("flat","checker","gradient","rgb-noise","rgba-noise"))list.add(fixture(pattern,256,256));
        for(int[] size:List.of(new int[]{1,1},new int[]{1,256},new int[]{256,1},new int[]{13,17},new int[]{256,65}))list.add(fixture("rgba-noise",size[0],size[1]));return list;
    }
    static byte[] encode(Fixture f,Candidate candidate)throws IOException{
        return encode(f.input(),f.width(),f.height(),candidate);
    }
    static byte[] encode(byte[] input,int width,int height,Candidate candidate)throws IOException{
        BufferedImage decoded=null,clean=null;
        try{
            decoded=TilePngVerifier.decode(input,width,height);clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            if(candidate==Candidate.REFERENCE_COPY)clean.setRGB(0,0,width,height,decoded.getRGB(0,0,width,height,null,0,width),0,width);
            else decoded.getRGB(0,0,width,height,((DataBufferInt)clean.getRaster().getDataBuffer()).getData(),0,width);
            var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("PNG").next();
            try(var output=new MemoryCacheImageOutputStream(new FilterOutputStream(bytes){int count;
                void reserve(int n)throws IOException{if(n>2097152-count)throw new IOException("Output bound");count+=n;}
                public void write(int b)throws IOException{reserve(1);out.write(b);}public void write(byte[] b,int off,int len)throws IOException{reserve(len);out.write(b,off,len);}
            })){
                var param=writer.getDefaultWriteParam();if(candidate==Candidate.LEVEL_3||candidate==Candidate.LEVEL_1||candidate==Candidate.LEVEL_0){int level=candidate==Candidate.LEVEL_3?3:candidate==Candidate.LEVEL_1?1:0;param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);param.setCompressionQuality((9-level)/9f);}
                writer.setOutput(output);writer.write(null,new IIOImage(clean,null,null),param);
            }finally{writer.dispose();}
            byte[] result=bytes.toByteArray();TilePngVerifier.verify(result,width,height);TilePngSanitizer.verifyClean(result);return result;
        }finally{if(decoded!=null)decoded.flush();if(clean!=null)clean.flush();}
    }
    static void verifyPixels(Fixture f,byte[] output)throws IOException{
        assertThat(output.length).isLessThanOrEqualTo(2097152);assertThat(output[25]).isEqualTo((byte)6);
        var decoded=TilePngVerifier.decode(output,f.width(),f.height());try{assertThat(decoded.getRGB(0,0,f.width(),f.height(),null,0,f.width())).containsExactly(f.pixels());}finally{decoded.flush();}
        assertThat(new String(output,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-private-note");
    }
    @Test @Timeout(120) void compareCopyAndCompressionWithoutChangingPixelsOrMetadataPolicy()throws Exception{
        var fixtures=fixtures();var results=new ArrayList<Map<String,Object>>();boolean passed=false;
        try{
            for(var f:fixtures){byte[] reference=encode(f,Candidate.REFERENCE_COPY);assertThat(TilePngSanitizer.normalize(f.input(),f.width(),f.height())).isEqualTo(reference);
                for(var candidate:Candidate.values()){byte[] bytes=encode(f,candidate);verifyPixels(f,bytes);if(candidate==Candidate.OWNED_BUFFER)assertThat(bytes).isEqualTo(reference);}
                var timings=new EnumMap<Candidate,List<Double>>(Candidate.class);var sizes=new EnumMap<Candidate,Integer>(Candidate.class);
                for(var c:Candidate.values()){timings.put(c,new ArrayList<>());for(int i=0;i<3;i++)encode(f,c);}
                for(int round=0;round<9;round++)for(int index=0;index<Candidate.values().length;index++){
                    var c=Candidate.values()[(index+round)%Candidate.values().length];long started=System.nanoTime();byte[] bytes=encode(f,c);double elapsed=(System.nanoTime()-started)/1e6;
                    verifyPixels(f,bytes);timings.get(c).add(elapsed);sizes.put(c,bytes.length);
                }
                for(var c:Candidate.values()){var sorted=timings.get(c).stream().sorted().toList();results.add(Map.of("fixture",f.name(),"candidate",c.name(),"samples",sorted.size(),"medianMs",sorted.get(4),"outputBytes",sizes.get(c),"referenceBytes",reference.length,"temporaryCopyArrayPayloadBytes",c==Candidate.REFERENCE_COPY?(long)f.width()*f.height()*4:0,"latenciesMs",timings.get(c)));}
            }passed=true;
        }finally{
            Path report=Files.createDirectories(Path.of("build/reports/png-codec"));new JsonMapper().writerWithDefaultPrettyPrinter().writeValue(report.resolve("candidates.json").toFile(),Map.of(
                "passed",passed,"java",System.getProperty("java.version"),"fixtures",fixtures.size(),"rows",results,"scope","Test-only bounded tile pipelines, nine samples after warmup with rotated candidate order. Full decode/copy/encode/output validation measured; independent pixel verification outside timing. PNG levels mapped from installed JDK21 source. No HTTP/IO/native timing or production SLA."));
        }
    }
}
