package co.kr.satu.imagegate.maintenance.controller;
import co.kr.satu.imagegate.maintenance.domain.BackupManifestOptions;
import co.kr.satu.imagegate.maintenance.repository.BackupManifestRepository;
import co.kr.satu.imagegate.maintenance.service.BackupManifestService;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageLock;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
public final class BackupManifestCommand {
    private BackupManifestCommand() {}
    public static int run(String[] args,PrintStream out,PrintStream error){
        if(Arrays.equals(args,new String[]{"--help"})){
            out.println("ImageGate backup manifest verification (read-only; no repair or deletion)");
            out.println("--originals-root ABS --annotations-root ABS --stamps-root ABS --workers-stopped --manifest ABS --manifest-sha256 HEX [--max-entries 10000] [--max-bytes 1073741824]");return 0;
        }
        BackupManifestOptions options;
        try{
            var values=new HashMap<String,String>();var seen=new HashSet<String>();boolean stopped=false;
            for(int i=0;i<args.length;i++){
                String flag=args[i];if(!seen.add(flag))throw new IllegalArgumentException();
                if(flag.equals("--workers-stopped")){stopped=true;continue;}
                if(!Set.of("--originals-root","--annotations-root","--stamps-root","--manifest","--manifest-sha256","--max-entries","--max-bytes").contains(flag)||++i>=args.length)throw new IllegalArgumentException();
                values.put(flag,args[i]);
            }
            options=new BackupManifestOptions(Path.of(Objects.requireNonNull(values.get("--originals-root"))),
                    Path.of(Objects.requireNonNull(values.get("--annotations-root"))),Path.of(Objects.requireNonNull(values.get("--stamps-root"))),
                    Path.of(Objects.requireNonNull(values.get("--manifest"))),Objects.requireNonNull(values.get("--manifest-sha256")),stopped,
                    Integer.parseInt(values.getOrDefault("--max-entries","10000")),Long.parseLong(values.getOrDefault("--max-bytes","1073741824")));
        }catch(RuntimeException invalid){error.println("INVALID_OPTIONS: use --maintenance --verify-backup-manifest --help");return 2;}
        try{
            var report=new BackupManifestService(new BackupManifestRepository(),Clock.systemUTC()).run(options);
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return report.limitReached()||report.issues()>0?3:0;
        }catch(ReadOnlyStorageLock.StorageInUseException busy){error.println("STORAGE_IN_USE: stop ImageGate and all storage writers");return 4;}
        catch(Exception unavailable){error.println("MANIFEST_VERIFICATION_UNAVAILABLE: check explicit roots, permissions, stable storage and stopped workers");return 5;}
    }
}
