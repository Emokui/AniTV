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
    @Test public void retainsResolutionFromProviderEvenWhenDuplicateCaptionIsStripped(){
        assertEquals("1080P", new PlaySource("https://example.com/video", "1080P", "", Collections.emptyMap(), "1080P").resolutionLabel());
        assertEquals("4K", new PlaySource("https://example.com/video", "第01集", "", Collections.emptyMap(), "4K源").resolutionLabel());
        assertEquals("4K", new PlaySource("https://example.com/video", "第01集 · 4K", "", Collections.emptyMap(), "4K源").resolutionLabel());
        PlaySource dotted = new PlaySource("https://example.com/video", "云播 · 专线 · 第01集 · １０８０Ｐ", "", Collections.emptyMap(), "云播 · 专线");
        assertEquals("云播 · 专线 · 第01集 · １０８０Ｐ", dotted.displayCaption);
        assertEquals("1080P", dotted.resolutionLabel());
        PlaySource compactDots = new PlaySource("https://example.com/video", "云播·专线·第01集 · 2K", "", Collections.emptyMap(), "云播 · 专线");
        assertEquals("云播 · 专线 · 第01集 · 2K", compactDots.displayCaption);
        assertEquals("2K", compactDots.resolutionLabel());
        PlaySource fullwidth = new PlaySource("https://example.com/video", "1080p", "", Collections.emptyMap(), "１０８０Ｐ");
        assertEquals("１０８０Ｐ", fullwidth.displayCaption);
        assertEquals("1080P", fullwidth.resolutionLabel());
    }
}
