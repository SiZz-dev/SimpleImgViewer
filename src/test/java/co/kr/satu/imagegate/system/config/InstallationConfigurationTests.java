package co.kr.satu.imagegate.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import co.kr.satu.imagegate.conversion.config.ConversionConfiguration;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

class InstallationConfigurationTests {
    @TempDir Path directory;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            var loader = new YamlPropertySourceLoader();
            try {
                for (var source : loader.load("defaults", new ClassPathResource("application.yml"))) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
                for (var source : loader.load("installation",
                        new FileSystemResource("config/imagegate.example.yml"))) {
                    context.getEnvironment().getPropertySources().addBefore("defaults", source);
                }
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }).withUserConfiguration(TestConfiguration.class);
    }

    @Test
    void loadsRealInstallerYamlAndKeepsConversionDisabled() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ImageConverter.class);
            var properties = context.getBean(ImageGateProperties.class);
            assertThat(properties.preview().enabled()).isTrue();
            assertThat(properties.tiles().enabled()).isFalse();
            assertThat(properties.tiles().workers()).isEqualTo(1);
            assertThat(properties.tiles().queueCapacity()).isEqualTo(8);
            assertThat(properties.preview().metricsEnabled()).isFalse();
            assertThat(properties.preview().originalOpen().enabled()).isFalse();
            assertThat(properties.preview().originalOpen().maxConcurrent()).isEqualTo(1);
            assertThat(properties.preview().originalOpen().waitTimeout()).isEqualTo(java.time.Duration.ofSeconds(10));
            assertThat(properties.audit().enabled()).isTrue();
            assertThat(properties.upload().maxConcurrent()).isEqualTo(4);
            assertThat(properties.upload().tenantOriginalMaxBytes()).isZero();
            assertThat(properties.upload().minFreeBytes()).isEqualTo(536870912);
            assertThat(properties.upload().multipartOverheadBytes()).isEqualTo(65536);
            assertThat(properties.upload().multipartMaxConcurrent()).isEqualTo(4);
            assertThat(properties.upload().bodyReadIdleTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
            assertThat(properties.upload().multipartDirectory().toAbsolutePath().normalize()).isEqualTo(Path.of("./.runtime/multipart").toAbsolutePath().normalize());
            assertThat(properties.editing().history().enabled()).isTrue();
            assertThat(properties.editing().history().maxSteps()).isEqualTo(20);
            assertThat(properties.preview().thumbnailEnabled()).isTrue();
            assertThat(properties.preview().thumbnailMaxEdge()).isEqualTo(256);
            assertThat(properties.preview().cache().enabled()).isTrue();
            assertThat(properties.preview().cache().maxBytes()).isEqualTo(5368709120L);
            assertThat(properties.playback().cache().enabled()).isTrue();
            assertThat(properties.playback().cache().maxBytes()).isEqualTo(10737418240L);
            assertThat(properties.preview().queueCapacity()).isEqualTo(32);
            assertThat(properties.conversion().module()).isEqualTo(ImageGateProperties.ImageModule.IMG2TILE);
            assertThat(properties.storage().convertedPath()).isEqualTo(Path.of("data/converted").toAbsolutePath());
            assertThat(context.getBean(FeaturePolicy.class).isEnabled(FeaturePolicy.Feature.IMAGE_VIEW)).isTrue();
        });
    }

    @Test
    void olderPreviewYamlWithoutAdmissionKeepsDisabledDefault(){
        var values=new java.util.HashMap<String,Object>();
        values.put("preview.enabled","true");values.put("preview.workers","2");values.put("preview.queue-capacity","32");
        values.put("preview.max-width","1600");values.put("preview.max-height","1600");values.put("preview.max-source-pixels","16000000");
        values.put("preview.cache-version","v1");values.put("preview.thumbnail-enabled","true");values.put("preview.thumbnail-max-edge","256");
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values);
        var bound=new org.springframework.boot.context.properties.bind.Binder(source).bind("preview",org.springframework.boot.context.properties.bind.Bindable.of(ImageGateProperties.Preview.class)).get();
        assertThat(bound.originalOpen()).isEqualTo(ImageGateProperties.OriginalOpenPolicy.defaults());
        assertThat(bound.metricsEnabled()).isFalse();
    }

    @Test
    void previewMetricsIsAnExplicitBooleanInstallationChoice(){
        runner().withPropertyValues("imagegate.preview.metrics-enabled=true").run(context->{
            assertThat(context).hasNotFailed();assertThat(context.getBean(ImageGateProperties.class).preview().metricsEnabled()).isTrue();
        });
        runner().withPropertyValues("imagegate.preview.metrics-enabled=invalid").run(context->assertThat(context).hasFailed());
    }

    @Test
    void originalOpenPolicyCanBeSelectedAtInstallation(){
        runner().withPropertyValues("imagegate.preview.original-open.enabled=true","imagegate.preview.original-open.max-concurrent=2",
                "imagegate.preview.original-open.wait-timeout=1500ms").run(context->{
            assertThat(context).hasNotFailed();var policy=context.getBean(ImageGateProperties.class).preview().originalOpen();
            assertThat(policy.enabled()).isTrue();assertThat(policy.maxConcurrent()).isEqualTo(2);
            assertThat(policy.waitTimeout()).isEqualTo(java.time.Duration.ofMillis(1500));
        });
        for(String invalid:new String[]{"max-concurrent=0","max-concurrent=5","wait-timeout=0ms","wait-timeout=-1s","wait-timeout=61s","wait-timeout=1ns","unknown=true"})
            runner().withPropertyValues("imagegate.preview.original-open."+invalid).run(context->assertThat(context).hasFailed());
    }

    @Test
    void auditCanBeDisabledAtInstallation() {
        runner().withPropertyValues("imagegate.audit.enabled=false").run(context->{
            assertThat(context).hasNotFailed();assertThat(context.getBean(ImageGateProperties.class).audit().enabled()).isFalse();
        });
    }

    @Test
    void parentSwitchDisablesChildFeatures() {
        runner().withPropertyValues("imagegate.features.image.enabled=false",
                "imagegate.features.video.enabled=false", "imagegate.features.audio.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var policy = context.getBean(FeaturePolicy.class);
                    for (var feature : FeaturePolicy.Feature.values()) {
                        assertThat(policy.isEnabled(feature)).as(feature.name()).isFalse();
                    }
                });
    }

    @Test
    void disablingStampsAlsoDisablesUploadWhileOtherToolsRemainAvailable() {
        runner().withPropertyValues("imagegate.features.image.stamps=false",
                "imagegate.features.image.annotations=false").run(context -> {
                    var policy = context.getBean(FeaturePolicy.class);
                    assertThat(policy.isEnabled(FeaturePolicy.Feature.STAMP_UPLOAD)).isFalse();
                    assertThat(policy.isEnabled(FeaturePolicy.Feature.ANNOTATIONS)).isFalse();
                    assertThat(policy.isEnabled(FeaturePolicy.Feature.ZOOM)).isTrue();
                });
    }

    @Test
    void invalidLimitsUnknownKeysAndOverlappingPathsFailStartup() {
        for (String invalid : new String[]{"imagegate.upload.tenant-original-max-bytes=-1", "imagegate.upload.tenant-original-max-bytes=1125899906842625", "imagegate.upload.unknown=true", "imagegate.upload.max-file-bytes=0", "imagegate.upload.max-file-bytes=104857601",
                "imagegate.upload.max-concurrent=0", "imagegate.upload.max-concurrent=17", "imagegate.upload.max-concurrent-per-user=0",
                "imagegate.upload.max-concurrent-per-user=9", "imagegate.upload.max-concurrent=1", "imagegate.upload.min-free-bytes=-1",
                "imagegate.upload.min-free-bytes=1099511627777", "imagegate.audit.unknown=true", "imagegate.conversion.timeout=0s",
                "imagegate.conversion.max-concurrent-jobs=9",
                "imagegate.features.image.annotatons=true",
                "imagegate.storage.converted-directory=originals/nested",
                "imagegate.conversion.module=unknown",
                "imagegate.preview.cache.retention=30m", "imagegate.preview.cache.retention=366d",
                "imagegate.preview.cache.cleanup-interval=30s", "imagegate.preview.cache.cleanup-interval=2d",
                "imagegate.preview.cache.max-bytes=0", "imagegate.preview.cache.max-bytes=1099511627777",
                "imagegate.preview.cache.unknown=true", "imagegate.preview.thumbnail-max-edge=63", "imagegate.preview.thumbnail-max-edge=513",
                "imagegate.preview.workers=5", "imagegate.preview.queue-capacity=0",
                "imagegate.preview.max-width=4097", "imagegate.preview.max-source-pixels=40000001",
                "imagegate.preview.cache-version=../unsafe",
                "imagegate.viewer.session-ttl=0s", "imagegate.viewer.session-ttl=3h",
                "imagegate.viewer.max-sessions=0", "imagegate.viewer.max-sessions-per-user=101",
                "imagegate.viewer.max-sessions=10",
                "imagegate.editing.history.max-steps=0", "imagegate.editing.history.max-steps=51",
                "imagegate.editing.history.max-contexts=0", "imagegate.editing.history.max-contexts=257",
                "imagegate.editing.history.max-contexts-per-user=33", "imagegate.editing.history.max-contexts=1",
                "imagegate.editing.history.ttl=30s", "imagegate.editing.history.ttl=3h",
                "imagegate.editing.history.unknown=true", "imagegate.editing.max-annotations-per-page=101", "imagegate.editing.max-note-length=2001",
                "imagegate.editing.max-stamps-per-user=0", "imagegate.editing.max-stamp-upload-bytes=5242881",
                "imagegate.editing.max-stamp-pixels=4194305",
                "imagegate.storage.annotations-directory=converted/nested",
                "imagegate.storage.stamps-directory=annotations",
                "imagegate.crop.max-source-pixels=40000001", "imagegate.crop.max-output-pixels=8000001",
                "imagegate.playback.timeout=0s", "imagegate.playback.workers=3",
                "imagegate.playback.queue-capacity=0", "imagegate.playback.max-duration-seconds=7201",
                "imagegate.playback.max-source-pixels=8294401", "imagegate.playback.max-output-bytes=536870913",
                "imagegate.playback.cache.retention=30m", "imagegate.playback.cache.retention=366d",
                "imagegate.playback.cache.cleanup-interval=30s", "imagegate.playback.cache.cleanup-interval=2d",
                "imagegate.playback.cache.max-bytes=0", "imagegate.playback.cache.max-bytes=1099511627777",
                "imagegate.playback.cache.unknown=true", "imagegate.playback.max-streams=0", "imagegate.playback.ffmpeg-sha256=wrong",
                "imagegate.crop.max-output-bytes=16777217", "imagegate.crop.max-concurrent-jobs=3"}) {
            runner().withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void refusesEnabledUnimplementedModules() {
        for (String module : new String[]{"none", "libvips", "imagemagick"}) {
            runner().withPropertyValues("imagegate.conversion.enabled=true",
                    "imagegate.conversion.module=" + module)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void disabledReservedModuleDoesNotCreateConverter() {
        runner().withPropertyValues("imagegate.conversion.module=imagemagick")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ImageConverter.class));
    }

    @Test
    void enabledImg2tileUsesConfiguredIndependentDirectories() throws Exception {
        Path executable = Files.createFile(directory.resolve("converter.exe"));
        Path resources=Files.createDirectory(directory.resolve("resource"));
        Files.write(resources.resolve("cmyk.icc"),new byte[]{1});
        Files.writeString(resources.resolve("libc9i.xml"),"<root/>");
        Path originals = directory.resolve("original-volume");
        Path converted = directory.resolve("converted-volume");
        runner().withPropertyValues("imagegate.conversion.enabled=true",
                "imagegate.conversion.module=img2tile",
                "imagegate.conversion.modules.img2tile.executable=" + executable,
                "imagegate.storage.originals-directory=" + originals,
                "imagegate.storage.converted-directory=" + converted)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ImageConverter.class);
                    assertThat(context.getBean(ImageConverter.class).id()).isEqualTo("img2tile");
                    assertThat(originals).isDirectory();
                    assertThat(converted).isDirectory();
                });
    }


    @Test
    void enabledImg2tileRejectsIncompleteResourceInstallation() throws Exception {
        Path executable=Files.createFile(directory.resolve("converter.exe"));Path resources=Files.createDirectory(directory.resolve("resource"));
        Files.write(resources.resolve("cmyk.icc"),new byte[]{1});
        runner().withPropertyValues("imagegate.conversion.enabled=true","imagegate.conversion.module=img2tile",
                "imagegate.conversion.modules.img2tile.executable="+executable,
                "imagegate.storage.originals-directory="+directory.resolve("rejected-originals"),
                "imagegate.storage.converted-directory="+directory.resolve("rejected-converted"))
                .run(context->assertThat(context).hasFailed());
        assertThat(directory.resolve("rejected-originals")).doesNotExist();
        assertThat(directory.resolve("rejected-converted")).doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"imagegate.tiles.workers=0","imagegate.tiles.timeout=0s","imagegate.tiles.max-tile-bytes=2097153","imagegate.tiles.max-source-pixels=40000001","imagegate.tiles.max-tracked-jobs=1","imagegate.tiles.enabled=true"})
    void rejectsUnsafeTileInstallationPolicy(String option){runner().withPropertyValues(option).run(context->assertThat(context).hasFailed());}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"imagegate.tiles.maintenance.retention=0s","imagegate.tiles.maintenance.interval=1ms","imagegate.tiles.maintenance.history-retention=366d","imagegate.tiles.maintenance.work-retention=31d","imagegate.tiles.maintenance.max-bytes=0","imagegate.tiles.maintenance.max-scan-entries=4097"})
    void rejectsUnsafeTileMaintenancePolicy(String option){runner().withPropertyValues(option).run(context->assertThat(context).hasFailed());}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImageGateProperties.class)
    @Import({ConversionConfiguration.class, FeaturePolicy.class})
    static class TestConfiguration {}
}
