package co.kr.satu.imagegate.conversion.util;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
/** Only known native work names, bounded traversal, no recursive deletion through unknown entries. */
public final class TileWorkCleanup {
    private TileWorkCleanup(){}
    public static void clean(Path work,int maxTiles)throws IOException{
        var owned=inventory(work,maxTiles);
        owned.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for(Path path:owned){if(!work.toRealPath().equals(work)||!path.toRealPath().equals(path))throw new IOException("Tile work changed");Files.delete(path);}
    }
    public static long bytes(Path work,int maxTiles)throws IOException{
        long total=0;for(Path path:inventory(work,maxTiles))if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))total=Math.addExact(total,Files.size(path));return total;
    }
    private static ArrayList<Path> inventory(Path work,int maxTiles)throws IOException{
        if(!Files.exists(work,LinkOption.NOFOLLOW_LINKS))return new ArrayList<>();
        if(!work.getFileName().toString().matches("job-[a-f0-9-]{36}")||!work.toRealPath().equals(work))throw new IOException("Invalid tile work");
        var owned=new ArrayList<Path>();
        try(var walk=Files.walk(work,5)){
            var iterator=walk.iterator();while(iterator.hasNext()){
                PreviewCancellation.check();
                Path path=iterator.next();if(owned.size()>maxTiles+64||!path.toRealPath().equals(path)||Files.isSymbolicLink(path))throw new IOException("Unknown tile work");
                String name=work.relativize(path).toString().replace('\\','/');
                boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
                if(directory?!name.matches("|resource|output|output/mipmap_files|output/mipmap_files/[0-9]{1,2}")
                        :!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!name.matches("resource/(cmyk[.]icc|libc9i[.]xml)|output/mipmap[.]dzi|output/mipmap_files/[0-9]{1,2}/[0-9]{1,8}_[0-9]{1,8}[.]png"))throw new IOException("Unknown tile work");
                owned.add(path);
            }
        }
        return owned;
    }
}
