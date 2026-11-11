package co.kr.satu.imagegate.media.controller;
import static org.assertj.core.api.Assertions.*;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.*;
class MultipartExceptionHandlerTests {
    @Test void nestedSocketTimeoutReturnsSanitized408(){
        var handler=new MultipartExceptionHandler();var failure=new MultipartException("secret path",new java.io.IOException("private detail",new SocketTimeoutException("socket detail")));
        var response=handler.readTimeout(failure);assertThat(response.getStatusCode().value()).isEqualTo(408);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().getProperties()).containsEntry("code","UPLOAD_READ_TIMEOUT");assertThat(response.getBody().toString()).doesNotContain("secret","private","socket detail");
    }
    @Test void unrelatedMultipartFailureKeepsItsOriginalResolverPath(){
        var failure=new MultipartException("synthetic");assertThatThrownBy(()->new MultipartExceptionHandler().readTimeout(failure)).isSameAs(failure);
    }
    @Test void sizeFailureRetains413Contract(){var response=new MultipartExceptionHandler().tooLarge();assertThat(response.getStatusCode().value()).isEqualTo(413);assertThat(response.getBody().getProperties()).containsEntry("code","FILE_TOO_LARGE");}
}
