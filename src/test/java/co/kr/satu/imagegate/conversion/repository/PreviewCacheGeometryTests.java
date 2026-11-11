package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewCacheGeometryTests {
    @TempDir Path directory;
    @Test void persistsOriginalGeometryAcrossCacheInstancesAndRejectsInvalidStoredValues() throws Exception {
        String key="a".repeat(64);var cache=new PreviewCache(directory);
        Path work=Files.createDirectory(cache.entry(key).resolve("work-test"));
        Path png=work.resolve("preview.png");
        ImageIO.write(new BufferedImage(20,30,BufferedImage.TYPE_INT_ARGB),"png",png.toFile());
        var geometry=new ImageGeometry(60,40,6);
        cache.publish(key,2,png,20,30,geometry);
        assertThat(new PreviewCache(directory).read(key,2).geometry()).isEqualTo(geometry);
        Path metadata=cache.entry(key).resolve("state.properties");
        var values=new Properties();
        try(var in=Files.newInputStream(metadata)) { values.load(in); }
        values.setProperty("orientation","9");
        try(var out=Files.newOutputStream(metadata)) { values.store(out,"invalid private geometry"); }
        assertThatThrownBy(()->cache.read(key,2)).isInstanceOf(IOException.class);
    }
}
