package co.kr.satu.imagegate.conversion.util;
import static org.assertj.core.api.Assertions.*;
import java.io.InterruptedIOException;
import org.junit.jupiter.api.Test;
class PreviewCancellationTests {
    @Test void preservesInterruptAndAllowsLaterIndependentWorkAfterCallerClearsIt() throws Exception {
        PreviewCancellation.check();
        try{
            Thread.currentThread().interrupt();
            assertThatThrownBy(PreviewCancellation::check).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
        PreviewCancellation.check();
    }
}
