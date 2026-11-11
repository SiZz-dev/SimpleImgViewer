package co.kr.satu.imagegate.playback.util;

/** A bounded single byte range. Multiple ranges are intentionally rejected. */
public record ByteRange(long start,long end,boolean partial) {
    public long length(){return end-start+1;}
    public static ByteRange parse(String header,long size) {
        if(size<1)throw new IllegalArgumentException("Empty representation");
        if(header==null)return new ByteRange(0,size-1,false);
        if(header.length()>128)throw new IllegalArgumentException("Invalid byte range");
        header=header.trim();
        if(!header.matches("(?i)bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException("Invalid byte range");
        var parts=header.substring(6).split("-",2);
        try {
            if(parts[0].isEmpty()) {
                long suffix=Long.parseLong(parts[1]);
                if(suffix<1)throw new IllegalArgumentException("Invalid suffix");
                return new ByteRange(Math.max(0,size-suffix),size-1,true);
            }
            long start=Long.parseLong(parts[0]),end=parts[1].isEmpty()?size-1:Long.parseLong(parts[1]);
            if(start>=size||end<start)throw new IllegalArgumentException("Unsatisfiable range");
            return new ByteRange(start,Math.min(end,size-1),true);
        } catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid byte range",invalid);}
    }
}
