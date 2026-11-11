package co.kr.satu.imagegate.maintenance.domain;
import java.nio.file.Path;
public record MaintenanceOptions(Path convertedRoot,boolean apply,boolean workersStopped,int minAgeHours,int maxCandidates,int maxEntries) {
    public MaintenanceOptions {
        if(convertedRoot==null||!convertedRoot.isAbsolute()||!convertedRoot.equals(convertedRoot.normalize())
                ||(apply&&!workersStopped)||minAgeHours<24||minAgeHours>8760||maxCandidates<1||maxCandidates>1000||maxEntries<1||maxEntries>100000)
            throw new IllegalArgumentException("Invalid maintenance options");
    }
}
