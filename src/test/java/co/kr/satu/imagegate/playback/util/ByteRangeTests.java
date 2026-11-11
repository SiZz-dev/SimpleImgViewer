package co.kr.satu.imagegate.playback.util;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ByteRangeTests {
    @Test void resolvesFullOpenEndedSuffixAndClampedRanges() {
        assertThat(ByteRange.parse(null,100)).isEqualTo(new ByteRange(0,99,false));
        assertThat(ByteRange.parse("bytes=20-",100)).isEqualTo(new ByteRange(20,99,true));
        assertThat(ByteRange.parse("bytes=-20",100)).isEqualTo(new ByteRange(80,99,true));
        assertThat(ByteRange.parse("bytes=2-999",100)).isEqualTo(new ByteRange(2,99,true));
        assertThat(ByteRange.parse("bytes=-999",100)).isEqualTo(new ByteRange(0,99,true));
    }
    @Test void rejectsMalformedMultipleOverflowAndUnsatisfiableRequests() {
        for(String header:new String[]{"bytes=100-","bytes=3-2","bytes=-0","bytes=-","bytes=0-1,5-6",
                "bytes=999999999999999999999999-","items=1-2","bytes= 1-2"})
            assertThatThrownBy(()->ByteRange.parse(header,100)).isInstanceOf(IllegalArgumentException.class);
    }
}
