package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Header admission fixtures contain no entropy payload; actual decoding belongs to the native tests. */
class Jp2InputProfileTests {
    @TempDir Path root;
    @ParameterizedTest
    @ValueSource(ints={7,15})
    void acceptsConsistentRgbHeadersWithoutModifyingInput(int bits)throws Exception{
        byte[] bytes=file(ihdr(bits),color(),siz(bits));
        Path input=Files.write(root.resolve("rgb.jp2"),bytes);
        Jp2InputProfile.requireCompatible(input);
        assertThat(Files.readAllBytes(input)).containsExactly(bytes);
    }
    @Test void acceptsExtendedBoxLengthsAndFinalZeroLengthCodestream()throws Exception{
        byte[] stream=box("jp2c",siz(7));ByteBuffer.wrap(stream).putInt(0);
        byte[] brand=ByteBuffer.allocate(28).putInt(1).putInt(type("ftyp")).putLong(28)
                .putInt(type("jp2 ")).putInt(0).putInt(type("jp2 ")).array();
        Path input=Files.write(root.resolve("extended.jp2"),join(signature(),brand,box("jp2h",join(ihdr(7),color())),stream));
        Jp2InputProfile.requireCompatible(input);
    }
    @Test void leavesOtherExistingModuleFormatsToTheirOwnValidation()throws Exception{
        for(byte[] bytes:new byte[][]{new byte[]{73,73,42,0},new byte[]{(byte)255,79,(byte)255,81},new byte[20]}){
            Path input=root.resolve("other");Files.write(input,bytes);Jp2InputProfile.requireCompatible(input);
            assertThat(Files.readAllBytes(input)).containsExactly(bytes);
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"gray-header","gray-codestream","depth-mismatch","header-bit-off-by-one","signed-depth","dimension-mismatch",
            "large-dimensions","subsampled","gray-color","icc-color","duplicate-color","palette",
            "missing-header","duplicate-header","missing-brand","stream-first","duplicate-stream",
            "short-stream","child-overrun","extended-overflow","box-budget","header-budget","signature-only"})
    void rejectsUnsupportedAmbiguousOrOutOfBoundsInput(String kind)throws Exception{
        byte[] header=ihdr(7),color=color(),stream=siz(7),bytes;
        switch(kind){
            case "gray-header" -> {ByteBuffer.wrap(header).putShort(16,(short)1);bytes=file(header,color,stream);}
            case "gray-codestream" -> {ByteBuffer.wrap(stream).putShort(40,(short)1);bytes=file(header,color,stream);}
            case "depth-mismatch" -> {stream[42]=15;bytes=file(header,color,stream);}
            case "header-bit-off-by-one" -> {header[18]=8;bytes=file(header,color,stream);}
            case "signed-depth" -> {header[18]=(byte)135;bytes=file(header,color,stream);}
            case "dimension-mismatch" -> {ByteBuffer.wrap(stream).putInt(8,129);bytes=file(header,color,stream);}
            case "large-dimensions" -> {ByteBuffer.wrap(header).putInt(8,-1).putInt(12,-1);bytes=file(header,color,stream);}
            case "subsampled" -> {stream[43]=2;bytes=file(header,color,stream);}
            case "gray-color" -> {ByteBuffer.wrap(color).putInt(11,17);bytes=file(header,color,stream);}
            case "icc-color" -> {color[8]=2;bytes=file(header,color,stream);}
            case "duplicate-color" -> bytes=file(header,join(color,color),stream);
            case "palette" -> bytes=file(join(header,box("pclr",new byte[4])),color,stream);
            case "missing-header" -> bytes=join(signature(),brand(),box("jp2c",stream));
            case "duplicate-header" -> bytes=file(join(header,header),color,stream);
            case "missing-brand" -> bytes=join(signature(),box("jp2h",join(header,color)),box("jp2c",stream));
            case "stream-first" -> bytes=join(signature(),brand(),box("jp2c",stream),box("jp2h",join(header,color)));
            case "duplicate-stream" -> bytes=join(file(header,color,stream),box("jp2c",stream));
            case "short-stream" -> bytes=file(header,color,Arrays.copyOf(stream,42));
            case "child-overrun" -> {ByteBuffer.wrap(header).putInt(10000);bytes=file(header,color,stream);}
            case "extended-overflow" -> bytes=join(signature(),ByteBuffer.allocate(16).putInt(1).putInt(type("free")).putLong(Long.MAX_VALUE).array());
            case "box-budget" -> {var out=new ByteArrayOutputStream();out.write(signature());out.write(brand());
                for(int i=0;i<65;i++)out.write(box("free",new byte[0]));out.write(box("jp2h",join(header,color)));out.write(box("jp2c",stream));bytes=out.toByteArray();}
            case "header-budget" -> bytes=join(signature(),brand(),box("jp2h",new byte[65537]),box("jp2c",stream));
            case "signature-only" -> bytes=signature();
            default -> throw new AssertionError(kind);
        }
        Path input=Files.write(root.resolve("input.jp2"),bytes);
        assertThatThrownBy(()->Jp2InputProfile.requireCompatible(input))
                .isInstanceOfSatisfying(ConversionException.class,e->{
                    assertThat(e.code()).isEqualTo(java.util.Set.of("gray-header","header-bit-off-by-one","signed-depth","gray-color","icc-color","palette").contains(kind)
                            ?ConversionException.Code.UNSUPPORTED_PROFILE:ConversionException.Code.INVALID_INPUT);
                    assertThat(e.getMessage()).doesNotContain(input.toString());
                });
        assertThat(Files.readAllBytes(input)).containsExactly(bytes);
    }
    private byte[] ihdr(int bits){return box("ihdr",ByteBuffer.allocate(14).putInt(96).putInt(128).putShort((short)3)
            .put((byte)bits).put((byte)7).put((byte)0).put((byte)0).array());}
    private byte[] color(){return box("colr",new byte[]{1,0,0,0,0,0,16});}
    private byte[] siz(int bits){return ByteBuffer.allocate(51).putShort((short)0xff4f).putShort((short)0xff51)
            .putShort((short)47).putShort((short)0).putInt(128).putInt(96).putInt(0).putInt(0)
            .putInt(128).putInt(96).putInt(0).putInt(0).putShort((short)3)
            .put(new byte[]{(byte)bits,1,1,(byte)bits,1,1,(byte)bits,1,1}).array();}
    private byte[] signature(){return box("jP  ",new byte[]{13,10,(byte)135,10});}
    private byte[] brand(){return box("ftyp",ByteBuffer.allocate(12).putInt(type("jp2 ")).putInt(0).putInt(type("jp2 ")).array());}
    private byte[] file(byte[] header,byte[] color,byte[] stream)throws Exception{return join(signature(),brand(),box("jp2h",join(header,color)),box("jp2c",stream));}
    private byte[] box(String name,byte[] payload){return ByteBuffer.allocate(8+payload.length).putInt(8+payload.length).putInt(type(name)).put(payload).array();}
    private int type(String name){return ByteBuffer.wrap(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).getInt();}
    private byte[] join(byte[]... arrays)throws java.io.IOException{
        var out=new ByteArrayOutputStream();for(var array:arrays)out.write(array);return out.toByteArray();
    }
}
