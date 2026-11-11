package co.kr.satu.imagegate.maintenance.controller;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.CacheOrphanRepository;
import co.kr.satu.imagegate.maintenance.service.CacheMaintenanceService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
/** CLI only. No Spring context, web routes, config import or conversion processes. */
public final class MaintenanceCommand {
    private MaintenanceCommand() {}
    public static int run(String[] args,PrintStream out,PrintStream error){
        if(args.length>0&&args[0].equals("--verify-recovery"))return RecoveryAcceptanceCommand.run(Arrays.copyOfRange(args,1,args.length),out,error);
        if(args.length>0&&args[0].equals("--verify-backup-manifest"))return BackupManifestCommand.run(Arrays.copyOfRange(args,1,args.length),out,error);
        if(args.length>0&&args[0].equals("--verify-editing-references"))return EditingReferenceCommand.run(Arrays.copyOfRange(args,1,args.length),out,error);
        if(args.length>0&&args[0].equals("--inspect-upload-residue"))return UploadResidueCommand.run(Arrays.copyOfRange(args,1,args.length),out,error);
        if(args.length>0&&args[0].equals("--verify-originals"))return OriginalIntegrityCommand.run(Arrays.copyOfRange(args,1,args.length),out,error);
        if(Arrays.equals(args,new String[]{"--help"})){
            out.println("ImageGate offline cache maintenance (default: dry-run)");
            out.println("Offline recovery acceptance: --verify-recovery --help (read-only)");
            out.println("Backup manifest: --verify-backup-manifest --help (read-only)");
            out.println("Editing references: --verify-editing-references --help (read-only)");
            out.println("Upload residue: --inspect-upload-residue --help (read-only)");
            out.println("Original checks: --verify-originals --help (read-only)");
            out.println("--converted-root ABSOLUTE_PATH [--min-age-hours 24] [--max-candidates 100] [--max-entries 10000]");
            out.println("[--apply --workers-stopped]  Stop the server and orphan converters first. Never pass the originals directory.");return 0;
        }
        MaintenanceOptions options;
        try{
            var values=new HashMap<String,String>();boolean apply=false,stopped=false;var seen=new HashSet<String>();
            for(int i=0;i<args.length;i++){
                String flag=args[i];if(!seen.add(flag))throw new IllegalArgumentException();
                if(flag.equals("--apply")){apply=true;continue;}if(flag.equals("--workers-stopped")){stopped=true;continue;}
                if(!Set.of("--converted-root","--min-age-hours","--max-candidates","--max-entries").contains(flag)||++i>=args.length)throw new IllegalArgumentException();
                values.put(flag,args[i]);
            }
            options=new MaintenanceOptions(Path.of(Objects.requireNonNull(values.get("--converted-root"))),apply,stopped,
                Integer.parseInt(values.getOrDefault("--min-age-hours","24")),Integer.parseInt(values.getOrDefault("--max-candidates","100")),Integer.parseInt(values.getOrDefault("--max-entries","10000")));
        }catch(RuntimeException invalid){error.println("INVALID_OPTIONS: use --maintenance --help");return 2;}
        try{
            var report=new CacheMaintenanceService(new CacheOrphanRepository(),Clock.systemUTC()).run(options);
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return report.limitReached()||report.skipped()>0?3:0;
        }catch(CacheOrphanRepository.StorageInUseException busy){error.println("STORAGE_IN_USE: stop ImageGate before maintenance");return 4;}
        catch(Exception unavailable){error.println("MAINTENANCE_UNAVAILABLE: check the explicit converted root, existing cache locks, permissions and stopped workers");return 5;}
    }
}
