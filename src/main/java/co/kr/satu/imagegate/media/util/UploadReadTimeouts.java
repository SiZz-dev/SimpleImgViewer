package co.kr.satu.imagegate.media.util;
import java.net.SocketTimeoutException;
/** Technical cause classification: bounded traversal, never inspect exception text. */
public final class UploadReadTimeouts {
    private UploadReadTimeouts(){}
    public static boolean causedBySocketTimeout(Throwable failure){
        for(int i=0;i<16&&failure!=null;i++,failure=failure.getCause())if(failure instanceof SocketTimeoutException)return true;
        return false;
    }
}
