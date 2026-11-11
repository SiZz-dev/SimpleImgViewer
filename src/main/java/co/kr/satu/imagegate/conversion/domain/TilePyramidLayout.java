package co.kr.satu.imagegate.conversion.domain;

/** Integer-only Deep Zoom raster layout. Describes geometry; does not approve decoding or resource budgets. */
public record TilePyramidLayout(int width,int height,int tileSize,int overlap) {
    public TilePyramidLayout {
        if(width<1||height<1||tileSize<64||tileSize>1024||(tileSize&(tileSize-1))!=0
                ||overlap<0||overlap>16)throw new IllegalArgumentException("Invalid tile layout");
    }
    public int maxLevel(){return 32-Integer.numberOfLeadingZeros(Math.max(width,height)-1);}
    public Level level(int level){
        if(level<0||level>maxLevel())throw new IllegalArgumentException("Invalid tile level");
        long divisor=1L<<(maxLevel()-level);
        int w=(int)(((long)width+divisor-1)/divisor),h=(int)(((long)height+divisor-1)/divisor);
        return new Level(level,w,h,(int)(((long)w+tileSize-1)/tileSize),(int)(((long)h+tileSize-1)/tileSize));
    }
    public long totalTiles(){long total=0;for(int i=0;i<=maxLevel();i++)total=Math.addExact(total,level(i).tileCount());return total;}
    public Tile tile(int level,int column,int row){
        var grid=level(level);
        if(column<0||row<0||column>=grid.columns()||row>=grid.rows())throw new IllegalArgumentException("Invalid tile coordinate");
        int x=(int)((long)column*tileSize),y=(int)((long)row*tileSize);
        int right=(int)Math.min(grid.width(),(long)x+tileSize),bottom=(int)Math.min(grid.height(),(long)y+tileSize);
        var core=new Rect(x,y,right-x,bottom-y);
        int left=Math.max(0,x-overlap),top=Math.max(0,y-overlap);
        int storedRight=(int)Math.min(grid.width(),(long)right+overlap),storedBottom=(int)Math.min(grid.height(),(long)bottom+overlap);
        var stored=new Rect(left,top,storedRight-left,storedBottom-top);
        long scale=1L<<(maxLevel()-level);
        int sourceX=(int)Math.min(width,(long)x*scale),sourceY=(int)Math.min(height,(long)y*scale);
        int sourceRight=(int)Math.min(width,(long)right*scale),sourceBottom=(int)Math.min(height,(long)bottom*scale);
        return new Tile(core,stored,new Rect(sourceX,sourceY,sourceRight-sourceX,sourceBottom-sourceY));
    }
    public record Level(int index,int width,int height,int columns,int rows){public long tileCount(){return (long)columns*rows;}}
    public record Rect(int x,int y,int width,int height){
        public Rect {if(x<0||y<0||width<1||height<1||(long)x+width>Integer.MAX_VALUE||(long)y+height>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid tile bounds");}
    }
    /** Core bounds, stored image bounds including overlap, and corresponding original-raster core (pixel edges). */
    public record Tile(Rect core,Rect stored,Rect originalCore) {}
}
