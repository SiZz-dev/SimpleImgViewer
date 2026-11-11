package co.kr.satu.imagegate.conversion.util;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bounded admission for the native JP2 profile with verified sample fidelity; not a decoder. */
public final class Jp2InputProfile {
    public static final String VERSION = "jp2-rgb-v2";
    private Jp2InputProfile() {}
    private record Box(int type,long data,long end) {}
    private record Header(long width,long height,int bits) {}

    public static void requireCompatible(Path input) throws IOException {
        try(var channel=FileChannel.open(input,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long size=channel.size();
            if(size<12)return;
            var signature=read(channel,0,12);
            if(signature.getInt()!=12||signature.getInt()!=0x6a502020||signature.getInt()!=0x0d0a870a)return;
            if(size>100L*1024*1024)throw invalid();
            boolean ftyp=false,codestream=false;
            Header header=null;
            long position=12;
            int boxes=0;
            while(position<size) {
                if(++boxes>64)throw invalid();
                var box=box(channel,position,size);
                switch(box.type()) {
                    case 0x66747970 -> {
                        if(ftyp||header!=null||box.end()-box.data()<8||(box.end()-box.data())%4!=0
                                ||read(channel,box.data(),4).getInt()!=0x6a703220)throw invalid();
                        ftyp=true;
                    }
                    case 0x6a703268 -> {
                        if(!ftyp||header!=null||codestream)throw invalid();
                        header=header(channel,box);
                    }
                    case 0x6a703263 -> {
                        if(header==null||codestream)throw invalid();
                        verifySiz(channel,box,header);
                        codestream=true;
                    }
                    case 0x6a502020 -> throw invalid();
                    default -> { /* Skip bounded box extents without allocating their payload. */ }
                }
                position=box.end();
            }
            if(!ftyp||header==null||!codestream)throw invalid();
        }
    }

    private static Header header(FileChannel channel,Box parent)throws IOException {
        if(parent.end()-parent.data()>65536)throw invalid();
        long position=parent.data();
        Header header=null;boolean color=false;int boxes=0;
        while(position<parent.end()) {
            if(++boxes>64)throw invalid();
            var child=box(channel,position,parent.end());
            if(child.type()==0x69686472) {
                if(header!=null||child.end()-child.data()!=14)throw invalid();
                var data=read(channel,child.data(),14);
                long height=Integer.toUnsignedLong(data.getInt()),width=Integer.toUnsignedLong(data.getInt());
                int components=Short.toUnsignedInt(data.getShort()),bits=Byte.toUnsignedInt(data.get());
                int compression=Byte.toUnsignedInt(data.get()),unknown=Byte.toUnsignedInt(data.get());
                int rights=Byte.toUnsignedInt(data.get());
                if(width<1||height<1||width>40000000L/height||components<1||components>16384
                        ||compression!=7||unknown>1||rights>1)throw invalid();
                if(components!=3||unknown!=0||(bits!=7&&bits!=15))throw unsupported();
                header=new Header(width,height,bits);
            } else if(child.type()==0x636f6c72) {
                if(color||child.end()-child.data()<3)throw invalid();
                int method=Byte.toUnsignedInt(read(channel,child.data(),3).get());
                if(method==2||method==3)throw unsupported();
                if(child.end()-child.data()!=7||method!=1)throw invalid();
                var data=read(channel,child.data(),7);
                data.get();
                if(data.get()!=0||data.get()!=0||data.getInt()!=16)throw unsupported();
                color=true;
            } else {
                // Palette/channel maps, ICC, variable precision and unapproved extension profiles are not inferred.
                if(java.util.Set.of(0x70636c72,0x636d6170,0x63646566,0x62706363,0x72657320).contains(child.type()))throw unsupported();
                throw invalid();
            }
            position=child.end();
        }
        if(header==null||!color)throw invalid();
        return header;
    }

    private static void verifySiz(FileChannel channel,Box box,Header header)throws IOException {
        if(box.end()-box.data()<51)throw invalid();
        var data=read(channel,box.data(),51);
        if(Short.toUnsignedInt(data.getShort())!=0xff4f||Short.toUnsignedInt(data.getShort())!=0xff51
                ||Short.toUnsignedInt(data.getShort())!=47)throw invalid();
        data.getShort(); // Rsiz; actual codec capability is still checked by native conversion.
        long width=Integer.toUnsignedLong(data.getInt()),height=Integer.toUnsignedLong(data.getInt());
        long xOrigin=Integer.toUnsignedLong(data.getInt()),yOrigin=Integer.toUnsignedLong(data.getInt());
        data.position(40);
        if(width!=header.width()||height!=header.height()||xOrigin!=0||yOrigin!=0
                ||Short.toUnsignedInt(data.getShort())!=3)throw invalid();
        for(int component=0;component<3;component++)
            if(Byte.toUnsignedInt(data.get())!=header.bits()||data.get()!=1||data.get()!=1)throw invalid();
    }

    private static Box box(FileChannel channel,long position,long end)throws IOException {
        if(position<0||end-position<8)throw invalid();
        var data=read(channel,position,8);
        long size=Integer.toUnsignedLong(data.getInt());int type=data.getInt();int header=8;
        if(size==1) {
            if(end-position<16)throw invalid();
            size=read(channel,position+8,8).getLong();header=16;
        } else if(size==0)size=end-position;
        if(size<header||size>end-position)throw invalid();
        return new Box(type,position+header,position+size);
    }
    private static ByteBuffer read(FileChannel channel,long position,int length)throws IOException {
        var data=ByteBuffer.allocate(length);
        while(data.hasRemaining()) {
            int count=channel.read(data,position+data.position());
            if(count<=0)throw invalid();
        }
        return data.flip();
    }
    private static ConversionException invalid(){
        return new ConversionException(ConversionException.Code.INVALID_INPUT,"JP2 input profile is unavailable");
    }
    private static ConversionException unsupported(){
        return new ConversionException(ConversionException.Code.UNSUPPORTED_PROFILE,"JP2 input profile is not supported");
    }
}
