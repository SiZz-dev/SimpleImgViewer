package co.kr.satu.imagegate.conversion.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TilePyramidLayoutTests {
    @ParameterizedTest @CsvSource({"1,1,0","256,256,8","257,1,9","513,257,10","100000,4000,17","2147483647,2147483647,31"})
    void levelsUseCeilingDivisionWithoutFloatingRounding(int width,int height,int max){
        var layout=new TilePyramidLayout(width,height,256,0);assertThat(layout.maxLevel()).isEqualTo(max);
        assertThat(layout.level(0).width()).isEqualTo(1);assertThat(layout.level(0).height()).isEqualTo(1);
        assertThat(layout.level(max).width()).isEqualTo(width);assertThat(layout.level(max).height()).isEqualTo(height);
    }
    @Test void nonPowerOfTwoRasterHasExactLastRowAndColumn(){
        var layout=new TilePyramidLayout(513,257,256,0);assertThat(layout.totalTiles()).isEqualTo(17);
        var grid=layout.level(10);assertThat(grid.columns()).isEqualTo(3);assertThat(grid.rows()).isEqualTo(2);
        var last=layout.tile(10,2,1);assertThat(last.core()).isEqualTo(new TilePyramidLayout.Rect(512,256,1,1));
        assertThat(last.stored()).isEqualTo(last.core());assertThat(last.originalCore()).isEqualTo(last.core());
        assertThat(layout.level(9).width()).isEqualTo(257);assertThat(layout.level(9).height()).isEqualTo(129);
        assertThat(layout.tile(9,1,0).originalCore()).isEqualTo(new TilePyramidLayout.Rect(512,0,1,257));
    }
    @Test void overlapIsIncludedOnlyWhereAdjacentPixelsExist(){
        var layout=new TilePyramidLayout(513,257,256,1);
        assertThat(layout.tile(10,0,0).stored()).isEqualTo(new TilePyramidLayout.Rect(0,0,257,257));
        assertThat(layout.tile(10,1,0).stored()).isEqualTo(new TilePyramidLayout.Rect(255,0,258,257));
        assertThat(layout.tile(10,2,1).stored()).isEqualTo(new TilePyramidLayout.Rect(511,255,2,2));
        assertThat(layout.tile(0,0,0).originalCore()).isEqualTo(new TilePyramidLayout.Rect(0,0,513,257));
    }
    @Test void tileCoresCoverEachLevelAndOriginalExactlyOnce(){
        for(int size:new int[]{64,256,512})for(int overlap:new int[]{0,1,16}){
            var layout=new TilePyramidLayout(1025,513,size,overlap);
            for(int z=0;z<=layout.maxLevel();z++){
                var level=layout.level(z);long area=0,originalArea=0;
                for(int row=0;row<level.rows();row++)for(int col=0;col<level.columns();col++){
                    var tile=layout.tile(z,col,row);area+=(long)tile.core().width()*tile.core().height();
                    originalArea+=(long)tile.originalCore().width()*tile.originalCore().height();
                    assertThat(tile.stored().width()).isLessThanOrEqualTo(size+overlap*2);
                    assertThat(tile.stored().height()).isLessThanOrEqualTo(size+overlap*2);
                }
                assertThat(area).isEqualTo((long)level.width()*level.height());assertThat(originalArea).isEqualTo(1025L*513);
            }
        }
    }
    @Test void largeGeometryCountsRemainLongAndNeverAuthorizeHugeAllocation(){
        var layout=new TilePyramidLayout(Integer.MAX_VALUE,Integer.MAX_VALUE,64,16);
        assertThat(layout.totalTiles()).isGreaterThan(Integer.MAX_VALUE);
        var grid=layout.level(31);var last=layout.tile(31,grid.columns()-1,grid.rows()-1);
        assertThat((long)last.stored().x()+last.stored().width()).isEqualTo(Integer.MAX_VALUE);
        assertThat(last.core().width()).isEqualTo(63);assertThat(last.core().height()).isEqualTo(63);
    }
    @Test void invalidDimensionsParametersLevelsAndCoordinatesAreRejected(){
        for(int[] values:new int[][]{{0,1,256,0},{1,-1,256,0},{1,1,63,0},{1,1,255,0},{1,1,2048,0},{1,1,256,-1},{1,1,256,17}})
            assertThatThrownBy(()->new TilePyramidLayout(values[0],values[1],values[2],values[3])).isInstanceOf(IllegalArgumentException.class);
        var layout=new TilePyramidLayout(513,257,256,0);
        for(int level:new int[]{-1,11,Integer.MAX_VALUE})assertThatThrownBy(()->layout.level(level)).isInstanceOf(IllegalArgumentException.class);
        for(int[] coordinate:new int[][]{{-1,0},{3,0},{0,-1},{0,2},{Integer.MAX_VALUE,0}})
            assertThatThrownBy(()->layout.tile(10,coordinate[0],coordinate[1])).isInstanceOf(IllegalArgumentException.class);
    }
}
