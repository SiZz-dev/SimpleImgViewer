package co.kr.satu.imagegate.maintenance.controller;
import co.kr.satu.imagegate.maintenance.domain.BackupManifestOptions;
import co.kr.satu.imagegate.maintenance.domain.RecoveryAcceptanceOptions;
import co.kr.satu.imagegate.maintenance.service.RecoveryAcceptanceService;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageLock;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
public final class RecoveryAcceptanceCommand {
    private RecoveryAcceptanceCommand() {}
    public static int run(String[] args,PrintStream out,PrintStream error){
        if(Arrays.equals(args,new String[]{"--help"})){
            out.println("ImageGate offline recovery acceptance checks (read-only; no repair or deletion)");
            out.println("--originals-root ABS --annotations-root ABS --stamps-root ABS --workers-stopped --manifest ABS --manifest-sha256 HEX [--max-entries 10000] [--max-bytes 1073741824] [--max-originals 1000] [--max-editing-items 1000]");return 0;
        }
        RecoveryAcceptanceOptions options;
        try{
            var values=new HashMap<String,String>();var seen=new HashSet<String>();boolean stopped=false;
            for(int i=0;i<args.length;i++){
                String flag=args[i];if(!seen.add(flag))throw new IllegalArgumentException();
                if(flag.equals("--workers-stopped")){stopped=true;continue;}
                if(!Set.of("--originals-root","--annotations-root","--stamps-root","--manifest","--manifest-sha256","--max-entries","--max-bytes","--max-originals","--max-editing-items").contains(flag)||++i>=args.length)throw new IllegalArgumentException();
                values.put(flag,args[i]);
            }
            var manifest=new BackupManifestOptions(Path.of(Objects.requireNonNull(values.get("--originals-root"))),
                    Path.of(Objects.requireNonNull(values.get("--annotations-root"))),Path.of(Objects.requireNonNull(values.get("--stamps-root"))),
                    Path.of(Objects.requireNonNull(values.get("--manifest"))),Objects.requireNonNull(values.get("--manifest-sha256")),stopped,
                    Integer.parseInt(values.getOrDefault("--max-entries","10000")),Long.parseLong(values.getOrDefault("--max-bytes","1073741824")));
            options=new RecoveryAcceptanceOptions(manifest,Integer.parseInt(values.getOrDefault("--max-originals","1000")),
                    Integer.parseInt(values.getOrDefault("--max-editing-items","1000")));
        }catch(RuntimeException invalid){error.println("INVALID_OPTIONS: use --maintenance --verify-recovery --help");return 2;}
        try{
            var report=new RecoveryAcceptanceService(Clock.systemUTC()).run(options);
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return report.offlineChecksPassed()?0:3;
        }catch(ReadOnlyStorageLock.StorageInUseException busy){error.println("STORAGE_IN_USE: stop ImageGate and all storage writers");return 4;}
        catch(Exception unavailable){error.println("RECOVERY_INSPECTION_UNAVAILABLE: check explicit roots, permissions, stable storage and stopped workers");return 5;}
    }
}
