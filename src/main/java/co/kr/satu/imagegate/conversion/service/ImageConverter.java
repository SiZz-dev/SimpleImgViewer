package co.kr.satu.imagegate.conversion.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Server-side adapter boundary. Files must be resolved from authorized media IDs,
 * not client-provided paths. Implementations run in a restricted worker.
 */
public interface ImageConverter {

    String id();

    default String cacheIdentity() throws IOException { return id(); }

    Set<String> supportedMimeTypes();

    ConversionResult convert(ConversionRequest request) throws IOException;

    record ConversionRequest(
            Path input,
            Path outputDirectory,
            int page,
            int maxWidth,
            int maxHeight,
            OutputFormat format) {

        public ConversionRequest {
            if (input == null || outputDirectory == null || format == null) {
                throw new IllegalArgumentException("Paths and output format are required");
            }
            if (page < 1 || maxWidth < 1 || maxHeight < 1) {
                throw new IllegalArgumentException("Page and dimensions must be positive");
            }
        }
    }

    record ConversionResult(Path output, int width, int height, String mimeType) {}

    enum OutputFormat {
        PNG,
        JPEG
    }
}
