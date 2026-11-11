package co.kr.satu.imagegate.conversion.service;
import java.io.IOException;
import java.nio.file.Path;
public interface TileNativeConverter {
    String tileIdentity() throws IOException;
    Path generateTiles(Path input,int page,Path job) throws IOException;
}
