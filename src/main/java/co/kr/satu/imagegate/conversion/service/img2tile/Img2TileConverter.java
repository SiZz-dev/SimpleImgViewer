package co.kr.satu.imagegate.conversion.service.img2tile;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.util.process.ProcessExecutor;
import co.kr.satu.imagegate.conversion.util.process.NativeModuleSnapshot;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * PNG/JPEG preview adapter. Caller resolves authorized media IDs to paths.
 * No controller, public filesystem route, or automatic fallback is provided.
 */
public final class Img2TileConverter implements ImageConverter, co.kr.satu.imagegate.conversion.service.TileNativeConverter {
    private static final long MAX_INPUT_BYTES = 100L * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_EDGE = 4096;
    private final Path executable;
    private final Path originalsRoot;
    private final Path derivedRoot;
    private final Duration timeout;
    private final ProcessExecutor executor;
    private final NativeModuleSnapshot module;

    public Img2TileConverter(Path executable, Path storageRoot, Duration timeout,
            ProcessExecutor executor) throws IOException {
        this(executable, storageRoot.resolve("originals"), storageRoot.resolve("derived"),
                timeout, executor);
    }

    public Img2TileConverter(Path executable, Path originalsDirectory, Path convertedDirectory,
            Duration timeout, ProcessExecutor executor) throws IOException {
        this.executable = executable.toRealPath();
        if (!Files.isRegularFile(this.executable)) {
            throw new ConversionException(ConversionException.Code.UNAVAILABLE,
                    "Image converter is unavailable");
        }
        this.timeout = timeout;
        this.executor = java.util.Objects.requireNonNull(executor);
        module=NativeModuleSnapshot.capture(List.of(this.executable),true);
        for(String name:List.of("cmyk.icc","libc9i.xml")){
            Path asset=this.executable.getParent().resolve("resource").resolve(name);
            if(!Files.isRegularFile(asset,LinkOption.NOFOLLOW_LINKS)||Files.size(asset)>4L*1024*1024)
                throw new ConversionException(ConversionException.Code.UNAVAILABLE,"Converter resources are unavailable");
        }
        module.verifyAttributes();
        Path originals = originalsDirectory.toAbsolutePath().normalize();
        Path converted = convertedDirectory.toAbsolutePath().normalize();
        requireSeparated(originals, converted);
        Files.createDirectories(originals);
        Files.createDirectories(converted);
        this.originalsRoot = originals.toRealPath();
        this.derivedRoot = converted.toRealPath();
        requireSeparated(this.originalsRoot, this.derivedRoot);
    }

    private static void requireSeparated(Path originals, Path converted) throws IOException {
        if (originals.startsWith(converted) || converted.startsWith(originals)) {
            throw invalidInput();
        }
    }

    @Override public String id() { return "img2tile"; }

    @Override public String tileIdentity() throws IOException {
        module.verifyContent();
        if(!Img2TilePyramidValidator.MODULE.equals(module.fingerprint()))throw new ConversionException(ConversionException.Code.UNAVAILABLE,"Tile module profile unavailable");
        return module.fingerprint();
    }
    @Override public Path generateTiles(Path source,int page,Path work) throws IOException {
        tileIdentity();Path input=source.toAbsolutePath().normalize(),job=work.toAbsolutePath().normalize();
        if(page<1||page>10000||!input.toRealPath().equals(input)||!input.startsWith(originalsRoot)
                ||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)||Files.size(input)<8||Files.size(input)>MAX_INPUT_BYTES
                ||!job.startsWith(derivedRoot)||job.equals(derivedRoot)||!job.getParent().toRealPath().equals(job.getParent())
                ||Files.exists(job,LinkOption.NOFOLLOW_LINKS))throw invalidInput();
        try(var stream=Files.newInputStream(input,LinkOption.NOFOLLOW_LINKS)){
            byte[] h=stream.readNBytes(4);
            if(!java.util.Arrays.equals(h,new byte[]{73,73,42,0})&&!java.util.Arrays.equals(h,new byte[]{77,77,0,42}))throw invalidInput();
        }
        Files.createDirectory(job);prepareResources(job);Path output=Files.createDirectory(job.resolve("output"));
        var command=List.of(executable.toString(),"-page",Integer.toString(page-1),"-odir",output.toString(),
                "-otype","png","-otype_tile","png","-otilesize","256","-oname","%c_%r.%e","-f",input.toString());
        ProcessExecutor.Result result;
        try{result=executor.execute(command,job,timeout);}finally{module.verifyAttributes();}
        tileIdentity();if(result.exitCode()!=0)throw new ConversionException(ConversionException.Code.PROCESS_FAILED,"Tile generation failed",result.exitCode());
        return output;
    }

    @Override public String cacheIdentity() throws IOException {
        module.verifyContent();return id()+"/"+module.fingerprint()+"/"+co.kr.satu.imagegate.conversion.util.Jp2InputProfile.VERSION;
    }

    @Override public Set<String> supportedMimeTypes() {
        return Set.of("image/tiff", "image/heic", "image/heif", "image/jp2", "image/j2c");
    }

    @Override
    public ConversionResult convert(ConversionRequest request) throws IOException {
        if (request.maxWidth() > MAX_EDGE || request.maxHeight() > MAX_EDGE) {
            throw invalidInput();
        }
        Path input = request.input().toRealPath();
        if (!input.startsWith(originalsRoot) || !Files.isRegularFile(input)
                || Files.size(input) == 0 || Files.size(input) > MAX_INPUT_BYTES
                || !hasSupportedHeader(input)) {
            throw invalidInput();
        }
        module.verifyContent();
        co.kr.satu.imagegate.conversion.util.Jp2InputProfile.requireCompatible(input);
        // Require an existing trusted parent and an unused output directory:
        // never overwrite a previous result or let stale output signal success.
        Path outputDirectory = request.outputDirectory().toAbsolutePath().normalize();
        if (!outputDirectory.startsWith(derivedRoot) || outputDirectory.equals(derivedRoot)
                || outputDirectory.getParent() == null
                || !outputDirectory.getParent().toRealPath().startsWith(derivedRoot)
                || Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidInput();
        }
        Files.createDirectory(outputDirectory);
        Path job = outputDirectory.toRealPath();
        if (!job.startsWith(derivedRoot)) {
            throw invalidInput();
        }
        prepareResources(job);
        String extension = request.format() == OutputFormat.PNG ? "png" : "jpg";
        Path output = job.resolve("preview." + extension);
        int edge = Math.max(request.maxWidth(), request.maxHeight());
        var command = List.of(executable.toString(), "-page", Integer.toString(request.page() - 1),
                "-odir", job.toString(), "-otype", extension,
                "-oscalesize", Integer.toString(edge), "-oscalename", output.getFileName().toString(),
                "-oscaleonly", "-f", input.toString());
        module.verifyAttributes();
        ProcessExecutor.Result result;
        try{result=executor.execute(command,job,timeout);}finally{module.verifyAttributes();}
        if (result.exitCode() != 0) {
            throw new ConversionException(ConversionException.Code.PROCESS_FAILED,
                    "Image converter rejected the job", result.exitCode());
        }
        var inspected=inspectAndFit(output,request);module.verifyContent();return inspected;
    }

    private void prepareResources(Path job) throws IOException {
        Path resources = executable.getParent().resolve("resource").toRealPath();
        Path destination = Files.createDirectory(job.resolve("resource"));
        for (String name : List.of("cmyk.icc", "libc9i.xml")) {
            Path source = resources.resolve(name).toRealPath();
            if (!source.startsWith(resources) || !Files.isRegularFile(source)
                    || Files.size(source) > 4L * 1024 * 1024) {
                throw new ConversionException(ConversionException.Code.UNAVAILABLE,
                        "Converter resources are unavailable");
            }
            Path copied=destination.resolve(name);Files.copy(source,copied);
            module.verifyCopiedResource(source,copied);
        }
    }

    private ConversionResult inspectAndFit(Path output, ConversionRequest request) throws IOException {
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || !output.toRealPath().startsWith(derivedRoot)
                || Files.size(output) == 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
            throw invalidOutput();
        }
        BufferedImage image;
        try (var stream = ImageIO.createImageInputStream(output.toFile())) {
            if (stream == null) throw invalidOutput();
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalidOutput();
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName();
                if (!(request.format() == OutputFormat.PNG ? format.equalsIgnoreCase("png")
                        : format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("jpg"))) {
                    throw invalidOutput();
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE) {
                    throw invalidOutput();
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) throw invalidOutput();
        double ratio = Math.min(1, Math.min((double) request.maxWidth() / image.getWidth(),
                (double) request.maxHeight() / image.getHeight()));
        if (ratio < 1) {
            int width = Math.max(1, (int) Math.floor(image.getWidth() * ratio));
            int height = Math.max(1, (int) Math.floor(image.getHeight() * ratio));
            var resized = new BufferedImage(width, height, request.format() == OutputFormat.JPEG
                    ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
            var graphics = resized.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                if (request.format() == OutputFormat.JPEG) graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(image, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            Path temporary = output.resolveSibling("resized.tmp");
            if (!ImageIO.write(resized, request.format() == OutputFormat.PNG ? "png" : "jpeg",
                    temporary.toFile())) {
                throw invalidOutput();
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            image = resized;
        }
        return new ConversionResult(output, image.getWidth(), image.getHeight(),
                request.format() == OutputFormat.PNG ? "image/png" : "image/jpeg");
    }

    private static boolean hasSupportedHeader(Path path) throws IOException {
        byte[] header;
        try (var input = Files.newInputStream(path)) { header = input.readNBytes(16); }
        if (header.length < 4) return false;
        boolean tiff = (header[0] == 'I' && header[1] == 'I'
                && (header[2] == 42 || header[2] == 43) && header[3] == 0)
                || (header[0] == 'M' && header[1] == 'M' && header[2] == 0
                && (header[3] == 42 || header[3] == 43));
        boolean codestream = (header[0] & 255) == 255 && (header[1] & 255) == 79
                && (header[2] & 255) == 255 && (header[3] & 255) == 81;
        boolean jp2 = header.length >= 12 && header[0] == 0 && header[1] == 0
                && header[2] == 0 && header[3] == 12 && header[4] == 'j' && header[5] == 'P'
                && header[6] == 32 && header[7] == 32 && header[8] == 13
                && header[9] == 10 && (header[10] & 255) == 135 && header[11] == 10;
        boolean heif = false;
        if (header.length >= 12 && new String(header, 4, 4,
                java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) {
            String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            heif = Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1").contains(brand);
        }
        return tiff || jp2 || codestream || heif;
    }

    private static ConversionException invalidInput() {
        return new ConversionException(ConversionException.Code.INVALID_INPUT,
                "Invalid conversion input or storage boundary");
    }

    private static ConversionException invalidOutput() {
        return new ConversionException(ConversionException.Code.INVALID_OUTPUT,
                "Converter did not produce a valid bounded image");
    }
}
