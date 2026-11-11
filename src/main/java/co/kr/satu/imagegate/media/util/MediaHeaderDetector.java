package co.kr.satu.imagegate.media.util;

import co.kr.satu.imagegate.media.domain.MediaType;
import java.nio.charset.StandardCharsets;

/** Header classification only. Full codec/decoder validation is a separate processing stage. */
public final class MediaHeaderDetector {
    public record Candidate(MediaType type, String mimeType, String extension) {}
    private MediaHeaderDetector() {}

    public static Candidate detect(byte[] h) {
        if (starts(h, 137,80,78,71,13,10,26,10)) return image("image/png", "png");
        if (starts(h, 255,216,255)) return image("image/jpeg", "jpg");
        if (text(h,0,6).equals("GIF87a") || text(h,0,6).equals("GIF89a")) return image("image/gif","gif");
        if (text(h,0,2).equals("BM")) return image("image/bmp","bmp");
        if (text(h,0,4).equals("RIFF")) {
            if (text(h,8,4).equals("WEBP")) return image("image/webp","webp");
            if (text(h,8,4).equals("WAVE")) return new Candidate(MediaType.AUDIO,"audio/wav","wav");
        }
        if (starts(h,73,73,42,0) || starts(h,77,77,0,42)
                || starts(h,73,73,43,0) || starts(h,77,77,0,43)) return image("image/tiff","tif");
        if (starts(h,0,0,0,12,106,80,32,32,13,10,135,10)) return image("image/jp2","jp2");
        if (starts(h,255,79,255,81)) return image("image/j2c","j2k");
        if (text(h,4,4).equals("ftyp")) {
            String brand = text(h,8,4);
            if (java.util.Set.of("heic","heix","hevc","hevx","mif1","msf1").contains(brand))
                return image("image/heif","heif");
            if (java.util.Set.of("isom","iso2","mp41","mp42","avc1","M4V ","dash").contains(brand))
                return new Candidate(MediaType.VIDEO,"video/mp4","mp4");
        }
        if (starts(h,0,0,1,186) || starts(h,0,0,1,179)
                || (h.length > 376 && h[0] == 71 && h[188] == 71 && h[376] == 71))
            return new Candidate(MediaType.VIDEO,"video/mpeg","mpeg");
        if (text(h,0,3).equals("ID3") || (h.length >= 4 && (h[0]&255)==255
                && (h[1]&224)==224 && (h[1]&6)!=0 && (h[1]&24)!=8
                && (h[2]&240)!=0 && (h[2]&240)!=240 && (h[2]&12)!=12))
            return new Candidate(MediaType.AUDIO,"audio/mpeg","mp3");
        return null;
    }

    private static Candidate image(String mime, String extension) {
        return new Candidate(MediaType.IMAGE,mime,extension);
    }
    private static boolean starts(byte[] header, int... values) {
        if (header.length < values.length) return false;
        for (int i=0;i<values.length;i++) if ((header[i]&255)!=values[i]) return false;
        return true;
    }
    private static String text(byte[] header,int offset,int length) {
        return header.length < offset+length ? "" :
                new String(header,offset,length,StandardCharsets.US_ASCII);
    }
}
