package co.kr.satu.imagegate.media.util;

import static org.assertj.core.api.Assertions.assertThat;
import co.kr.satu.imagegate.media.domain.MediaType;
import org.junit.jupiter.api.Test;

class MediaHeaderDetectorTests {
    @Test void recognizesRequestedImageVideoAndAudioHeaders() {
        assertThat(MediaHeaderDetector.detect(bytes(73,73,42,0)).mimeType()).isEqualTo("image/tiff");
        assertThat(MediaHeaderDetector.detect(bytes(0,0,0,12,106,80,32,32,13,10,135,10)).mimeType()).isEqualTo("image/jp2");
        assertThat(MediaHeaderDetector.detect(bytes(0,0,0,24,102,116,121,112,104,101,105,99)).type()).isEqualTo(MediaType.IMAGE);
        assertThat(MediaHeaderDetector.detect(bytes(0,0,0,24,102,116,121,112,105,115,111,109)).type()).isEqualTo(MediaType.VIDEO);
        assertThat(MediaHeaderDetector.detect(bytes(0,0,1,186)).mimeType()).isEqualTo("video/mpeg");
        assertThat(MediaHeaderDetector.detect(bytes(73,68,51,4)).mimeType()).isEqualTo("audio/mpeg");
        assertThat(MediaHeaderDetector.detect(bytes(82,73,70,70,0,0,0,0,87,65,86,69)).mimeType()).isEqualTo("audio/wav");
    }

    @Test void distinguishesRiffContainersAndRejectsUnknownData() {
        assertThat(MediaHeaderDetector.detect(bytes(82,73,70,70,0,0,0,0,87,69,66,80)).mimeType()).isEqualTo("image/webp");
        assertThat(MediaHeaderDetector.detect(bytes(82,73,70,70,0,0,0,0,65,86,73,32))).isNull();
        assertThat(MediaHeaderDetector.detect("<svg/>".getBytes())).isNull();
        assertThat(MediaHeaderDetector.detect(new byte[0])).isNull();
    }

    private byte[] bytes(int... values) {
        byte[] bytes=new byte[values.length];
        for(int i=0;i<values.length;i++) bytes[i]=(byte)values[i];
        return bytes;
    }
}
