package tv.anitv;
import static org.junit.Assert.*;
import java.util.Collections;
import org.junit.Test;
public class SourceResolutionTest {
    private String label(String caption){return new PlaySource("https://example.com/video",""+caption,"",Collections.emptyMap()).resolutionLabel();}
    @Test public void recognizesExplicitLabelsAndPixelDimensions(){
        assertEquals("1080P",label("日语-1080p"));
        assertEquals("1920×1080",label("线路 · 1920 x 1080"));
        assertEquals("4K",label("线路 · 4k"));
        assertEquals("720P / 1080P",label("720P / 1080p / 720p"));
    }
    @Test public void doesNotInventDimensionsFromEpisodeOrMarketingLabels(){
        assertEquals("分辨率未知",label("第1080集 · 高清"));
        assertEquals("分辨率未知",label("超清线路 · 原画"));
        assertEquals("分辨率未知",label(""));
    }
}
