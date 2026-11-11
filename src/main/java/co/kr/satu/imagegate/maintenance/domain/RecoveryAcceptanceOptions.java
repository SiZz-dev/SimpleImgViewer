package co.kr.satu.imagegate.maintenance.domain;
import java.util.Objects;
public record RecoveryAcceptanceOptions(BackupManifestOptions manifest,int maxOriginals,int maxEditingItems) {
    public RecoveryAcceptanceOptions {
        Objects.requireNonNull(manifest);
        if(maxOriginals<1||maxOriginals>10000||maxEditingItems<1||maxEditingItems>10000)throw new IllegalArgumentException("Invalid acceptance limits");
    }
    public OriginalIntegrityOptions originals(){return new OriginalIntegrityOptions(manifest.originalsRoot(),true,maxOriginals,manifest.maxEntries(),manifest.maxBytes());}
    public EditingReferenceOptions editing(){return new EditingReferenceOptions(manifest.originalsRoot(),manifest.annotationsRoot(),manifest.stampsRoot(),true,maxEditingItems,manifest.maxEntries(),manifest.maxBytes());}
}
