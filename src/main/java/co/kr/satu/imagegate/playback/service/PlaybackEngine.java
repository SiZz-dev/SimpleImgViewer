package co.kr.satu.imagegate.playback.service;

import co.kr.satu.imagegate.playback.domain.PlaybackArtifact;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import java.nio.file.Path;
import java.io.IOException;
public interface PlaybackEngine {
    String identity();
    PlaybackArtifact prepare(MediaRecord record,Path original,Path work) throws IOException;
}
