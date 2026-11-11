package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.TilePyramidLayout;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Img2TilePyramidValidatorTests {
    @TempDir Path temporary;
    final Img2TilePyramidValidator validator=new Img2TilePyramidValidator();
    final Img2TilePyramidValidator.Source source=new Img2TilePyramidValidator.Source(257,65,1,"TIFF_RGB",1,8,3,Img2TilePyramidValidator.MODULE);
    Path fixture() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("out"));
        Files.writeString(root.resolve("mipmap.dzi"),xml());
        var layout=new TilePyramidLayout(source.width(),source.height(),256,0);
        for(int z=0;z<=layout.maxLevel();z++) {
            Path level=Files.createDirectories(root.resolve("mipmap_files/"+z)); var grid=layout.level(z);
            for(int col=0;col<grid.columns();col++) for(int row=0;row<grid.rows();row++) {
                var bounds=layout.tile(z,col,row).stored();
                png(level.resolve(col+"_"+row+".png"),bounds.width(),bounds.height());
            }
        }
        return root;
    }
    static void png(Path path,int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        try { ImageIO.write(image,"PNG",path.toFile()); } finally { image.flush(); }
    }
    String xml() { return "<?xml version=\"1.0\"?><Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"png\" Overlap=\"1\" TileSize=\"256\"><Size Width=\"257\" Height=\"65\"/></Image>"; }
    Path tile(Path root) { return root.resolve("mipmap_files/9/0_0.png"); }
    void rejected(Path root) { assertThatThrownBy(()->validator.validate(root,source)).isInstanceOf(ConversionException.class)
            .extracting(e->((ConversionException)e).code()).isEqualTo(ConversionException.Code.INVALID_OUTPUT); }

    @Test void normalizesKnownOverlapWithoutWritingAndCopiesInventory() throws Exception {
        Path root=fixture(); byte[] before=Files.readAllBytes(root.resolve("mipmap.dzi"));
        var manifest=validator.validate(root,source);
        assertThat(manifest.layout()).isEqualTo(new TilePyramidLayout(257,65,256,0));
        assertThat(manifest.tiles()).hasSize(11); assertThat(manifest.coordinateSpace()).isEqualTo("original-upright-pixel-edges");
        assertThat(manifest.tiles()).allSatisfy(t->assertThat(t.sha256()).matches("[a-f0-9]{64}"));
        assertThatThrownBy(()->manifest.tiles().clear()).isInstanceOf(UnsupportedOperationException.class);
        validator.verifyUnchanged(root,source,manifest);
        assertThat(Files.readAllBytes(root.resolve("mipmap.dzi"))).isEqualTo(before);
    }
    @Test void publicationProofIsBoundToValidatorDirectoryAndSource()throws Exception{
        Path root=fixture();var proof=validator.validateForPublication(root,source);
        validator.verifyForPublication(root,source,proof);
        assertThat(proof.manifest()).isEqualTo(validator.validate(root,source));
        assertThatThrownBy(()->new Img2TilePyramidValidator().verifyForPublication(root,source,proof)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->validator.verifyForPublication(root.getParent(),source,proof)).isInstanceOf(java.io.IOException.class);
        var other=new Img2TilePyramidValidator.Source(257,65,2,"TIFF_RGB",1,8,3,Img2TilePyramidValidator.MODULE);
        assertThatThrownBy(()->validator.verifyForPublication(root,other,proof)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->validator.verifyForPublication(root,source,null)).isInstanceOf(java.io.IOException.class);
    }
    @Test void publicationProofRejectsDirectoryJunctionAfterInitialValidation()throws Exception{
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root=fixture();var proof=validator.validateForPublication(root,source);Path original=root.resolve("mipmap_files/9"),target=temporary.resolve("moved-level");
        Files.move(original,target);var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",original.toString(),target.toString()).redirectErrorStream(true).start();
        assertThat(process.waitFor()).isZero();try{assertThatThrownBy(()->validator.verifyForPublication(root,source,proof)).isInstanceOf(java.io.IOException.class);}
        finally{Files.delete(original);}
        assertThat(Files.exists(target.resolve("0_0.png"))).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"tile","descriptor","extra","replace","timestamp","missing"})
    void publicationProofRejectsChangedBytesEvenWithRestoredTimestamp(String kind)throws Exception{
        Path root=fixture();var proof=validator.validateForPublication(root,source);Path target=kind.equals("descriptor")?root.resolve("mipmap.dzi"):tile(root);
        var time=Files.getLastModifiedTime(target);byte[] original=Files.readAllBytes(target);
        switch(kind){
            case "tile","descriptor"->{byte[] changed=original.clone();changed[changed.length-1]^=1;Files.write(target,changed);Files.setLastModifiedTime(target,time);}
            case "extra"->Files.write(root.resolve("unexpected.bin"),new byte[]{1});
            case "replace"->{Path detached=root.resolve("old.png");Files.move(target,detached);Files.write(target,original);Files.delete(detached);Files.setLastModifiedTime(target,time);}
            case "timestamp"->Files.setLastModifiedTime(target,java.nio.file.attribute.FileTime.fromMillis(time.toMillis()+60000));
            case "missing"->Files.delete(target);
        }
        assertThatThrownBy(()->validator.verifyForPublication(root,source,proof)).isInstanceOf(java.io.IOException.class);
    }
    @ParameterizedTest @ValueSource(strings={"namespace","overlap","dimensions","extraAttribute","duplicateSize","nested","doctype","xxe","comment","instruction","text","oversize","leadingZero","prefix","xinclude"})
    void rejectsUntrustedDescriptors(String kind) throws Exception {
        Path root=fixture(); String xml=switch(kind) {
            case "namespace" -> xml().replace("deepzoom/2008","deepzoom/2009");
            case "overlap" -> xml().replace("Overlap=\"1\"","Overlap=\"0\"");
            case "dimensions" -> xml().replace("Width=\"257\"","Width=\"258\"");
            case "extraAttribute" -> xml().replace("Format=", "Url=\"https://example.invalid/\" Format=");
            case "duplicateSize" -> xml().replace("</Image>","<Size Width=\"257\" Height=\"65\"/></Image>");
            case "nested" -> xml().replace("</Image>","<a><b><c><d><e/></d></c></b></a></Image>");
            case "doctype" -> xml().replace("?><Image","?><!DOCTYPE Image [<!ENTITY a 'x'>]><Image");
            case "xxe" -> xml().replace("?><Image","?><!DOCTYPE Image SYSTEM 'file:///should-never-be-read'><Image");
            case "comment" -> xml().replace("</Image>","<!-- hidden --></Image>");
            case "instruction" -> xml()+"<?unexpected data?>";
            case "text" -> xml().replace("</Image>","unexpected</Image>");
            case "oversize" -> xml()+" ".repeat(8192);
            case "leadingZero" -> xml().replace("Width=\"257\"","Width=\"0257\"");
            case "prefix" -> xml().replace("<Size ","<s:Size xmlns:s=\"http://schemas.microsoft.com/deepzoom/2008\" ");
            case "xinclude" -> xml().replace("</Image>","<xi:include xmlns:xi=\"http://www.w3.org/2001/XInclude\" href=\"file:///never\"/></Image>");
            default -> throw new AssertionError();
        };
        Files.writeString(root.resolve("mipmap.dzi"),xml); rejected(root);
    }
    @ParameterizedTest @ValueSource(strings={"missing","extra","nonCanonical","extraDirectory","wrongDimensions","truncated","crc","trailing","fake","oversize","incompatibleAlias"})
    void rejectsInvalidInventoryOrPng(String kind) throws Exception {
        Path root=fixture(); Path tile=tile(root);
        switch(kind) {
            case "missing" -> Files.delete(tile);
            case "extra" -> Files.writeString(root.resolve("secret.txt"),"secret");
            case "nonCanonical" -> Files.move(tile,tile.resolveSibling("00_0.png"));
            case "extraDirectory" -> Files.createDirectory(root.resolve("mipmap_files/99"));
            case "wrongDimensions" -> png(tile,257,65);
            case "truncated" -> { byte[] bytes=Files.readAllBytes(tile); Files.write(tile,Arrays.copyOf(bytes,bytes.length-12)); }
            case "crc" -> { byte[] bytes=Files.readAllBytes(tile); bytes[29]^=1; Files.write(tile,bytes); }
            case "trailing" -> Files.write(tile,new byte[]{0},StandardOpenOption.APPEND);
            case "fake" -> Files.writeString(tile,"not a PNG");
            case "oversize" -> Files.write(tile,new byte[2_097_153]);
            case "incompatibleAlias" -> { Path other=root.resolve("mipmap_files/9/1_0.png"); Files.delete(other); Files.createLink(other,tile); }
            default -> throw new AssertionError();
        }
        rejected(root);
    }
    @Test void rejectsValidCrcWithInvalidCompressedRaster() throws Exception {
        Path root=fixture(); byte[] bytes=Files.readAllBytes(tile(root)); var buffer=java.nio.ByteBuffer.wrap(bytes); buffer.position(8);
        while(buffer.hasRemaining()) {
            int length=buffer.getInt(),typeStart=buffer.position(),type=buffer.getInt(),content=buffer.position();
            if(type==0x49444154) { Arrays.fill(bytes,content,content+length,(byte)0); var crc=new CRC32(); crc.update(bytes,typeStart,length+4); buffer.putInt(content+length,(int)crc.getValue()); break; }
            buffer.position(content+length+4);
        }
        Files.write(tile(root),bytes); rejected(root);
    }
    @Test void rejectsUnsupportedSourceBeforeReadingOutput() {
        for(var untrusted:List.of(
                new Img2TilePyramidValidator.Source(257,65,1,"TIFF_RGB",6,8,3,Img2TilePyramidValidator.MODULE),
                new Img2TilePyramidValidator.Source(257,65,1,"TIFF_RGB",1,8,3,"changed-module"),
                new Img2TilePyramidValidator.Source(257,65,1,"HEIC",1,8,3,Img2TilePyramidValidator.MODULE),
                new Img2TilePyramidValidator.Source(257,65,1,"TIFF_RGB",1,16,3,Img2TilePyramidValidator.MODULE)))
            assertThatThrownBy(()->validator.validate(temporary.resolve("absent"),untrusted)).isInstanceOf(ConversionException.class)
                    .extracting(e->((ConversionException)e).code()).isEqualTo(ConversionException.Code.UNAVAILABLE);
    }
    @Test void rejectsSourceAndOutputBudgets() throws Exception {
        Path root=fixture();
        for(var limits:List.of(new Img2TilePyramidValidator.Limits(1,4096,2097152,268435456),
                new Img2TilePyramidValidator.Limits(40000000,1,2097152,268435456),
                new Img2TilePyramidValidator.Limits(40000000,4096,64,268435456),
                new Img2TilePyramidValidator.Limits(40000000,4096,2097152,64)))
            assertThatThrownBy(()->new Img2TilePyramidValidator(limits).validate(root,source)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(()->new Img2TilePyramidValidator.Limits(Long.MAX_VALUE,1,64,64)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void detectsChangedPixelContentWithSameDimensions() throws Exception {
        Path root=fixture(); var manifest=validator.validate(root,source);
        var image=new BufferedImage(256,65,BufferedImage.TYPE_INT_RGB); image.setRGB(0,0,0xffffff);
        try { ImageIO.write(image,"PNG",tile(root).toFile()); } finally { image.flush(); }
        assertThatThrownBy(()->validator.verifyUnchanged(root,source,manifest)).isInstanceOf(ConversionException.class);
    }
    @Test void preservesInterruption() throws Exception {
        Path root=fixture(); Thread.currentThread().interrupt();
        try { assertThatThrownBy(()->validator.validate(root,source)).isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
    @Test void rejectsWindowsDirectoryJunction() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root=fixture(), original=root.resolve("mipmap_files/0"), target=temporary.resolve("outside"); Files.move(original,target);
        var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",original.toString(),target.toString()).redirectErrorStream(true).start();
        assertThat(process.waitFor()).isZero();
        try { rejected(root); } finally { Files.delete(original); }
    }
}
