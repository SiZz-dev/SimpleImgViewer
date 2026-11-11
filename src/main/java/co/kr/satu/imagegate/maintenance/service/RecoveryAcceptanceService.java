package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.*;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageScope;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
/** Sequential, fail-closed offline acceptance; all phases share the existing marker locks. */
public final class RecoveryAcceptanceService {
    private final Clock clock;private final Consumer<String> afterStage;
    public RecoveryAcceptanceService(Clock clock){this(clock,stage->{});}
    RecoveryAcceptanceService(Clock clock,Consumer<String> afterStage){this.clock=clock;this.afterStage=afterStage;}
    private static RecoveryAcceptanceReport.Stage stage(String name,boolean limited,int issues,long scanned,long bytes,int passed){
        return new RecoveryAcceptanceReport.Stage(name,limited?"PARTIAL":issues>0?"FINDINGS":"PASSED",scanned,bytes,passed,issues);
    }
    public RecoveryAcceptanceReport run(RecoveryAcceptanceOptions options) throws IOException {
        var generatedAt=clock.instant();var m=options.manifest();
        var phases=new ArrayList<RecoveryAcceptanceReport.Stage>();
        for(String name:List.of("MANIFEST_BEFORE","ORIGINALS","EDITING_REFERENCES","MANIFEST_AFTER"))
            phases.add(new RecoveryAcceptanceReport.Stage(name,"NOT_RUN",0,0,0,0));
        try(var scope=new ReadOnlyStorageScope(m.originalsRoot(),m.annotationsRoot(),m.stampsRoot(),m.workersStopped())){
            var manifestRepository=new BackupManifestRepository();
            for(int i=0;i<phases.size();i++){
                scope.verify();String name=phases.get(i).name();RecoveryAcceptanceReport.Stage summary;
                if(i==0||i==3){
                    var r=manifestRepository.inspectWithinScope(m,generatedAt,scope);
                    summary=stage(name,r.limitReached(),r.issues(),r.scanned(),r.hashedBytes(),r.matched());
                }else if(i==1){
                    var r=new OriginalIntegrityRepository().inspectWithinScope(options.originals(),generatedAt,scope);
                    summary=stage(name,r.limitReached(),r.issues(),r.scanned(),r.hashedBytes(),r.verified());
                }else{
                    var r=new EditingReferenceRepository().inspectWithinScope(options.editing(),generatedAt,scope);
                    summary=stage(name,r.limitReached(),r.issues(),r.scanned(),r.metadataBytesRead(),r.validPages()+r.validStamps());
                }
                phases.set(i,summary);scope.verify();
                if(!summary.status().equals("PASSED"))break;
                if(i<3)afterStage.accept(name);
            }
            scope.verify();
        }
        boolean passed=phases.stream().allMatch(p->p.status().equals("PASSED"));
        return new RecoveryAcceptanceReport(1,generatedAt,passed,List.copyOf(phases));
    }
}
