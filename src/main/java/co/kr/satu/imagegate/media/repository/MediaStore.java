package co.kr.satu.imagegate.media.repository;

import co.kr.satu.imagegate.media.domain.MediaRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public interface MediaStore {
    MediaRecord register(String tenantId, String ownerId, String fileName, InputStream input)
            throws IOException;
    java.nio.file.Path resolveOriginal(MediaRecord media) throws IOException;
    Optional<MediaRecord> find(String tenantId, String mediaId) throws IOException;
}
