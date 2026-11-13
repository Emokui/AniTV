package tv.anitv;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class SourcePreferenceTest {
    private SourcePreference preference(String show){return new SourcePreference(RuntimeEnvironment.getApplication(),Library.DEFAULT_ENDPOINT,show);}
    private PlaySource source(String provider,String caption,String path){return new PlaySource("https://cdn.example.org/"+path,provider+" · "+caption,"",Collections.emptyMap(),provider);}
    @Test public void reorderedEpisodesAndReopeningKeepProviderAndQuality() {
        SourcePreference saved=preference("42");saved.remember(source("mh-6","第01集(日语-4K) · 4K","ep1?token=old"));
        java.util.List<PlaySource> next=Arrays.asList(source("mh-6","第02集(日语-1080P) · 1080P","2-hd"),source("other","第02集 · 4K","2-other"),source("mh-6","第02集(日语-4K) · 4K","2-4k?token=new"));
        assertEquals(2,saved.match(next));assertEquals(2,preference("42").match(next));assertEquals(-1,preference("43").match(next));
    }
    @Test public void missingSourceDoesNotOverwritePreferenceAndManualDefaultDoes() {
        SourcePreference saved=preference("42");PlaySource selected=source("chosen","第1集 · 1080P","1");saved.remember(selected);
        PlaySource fallback=source("default","第2集 · 1080P","2");assertEquals(-1,saved.match(Collections.singletonList(fallback)));
        assertEquals(0,saved.match(Collections.singletonList(source("chosen","第3集 · 1080P","3"))));
        saved.remember(fallback);assertEquals(0,saved.match(Collections.singletonList(source("default","第3集 · 1080P","3"))));
    }
    @Test public void serviceAndQualityDifferencesNeverSelectSameRowByIndex() {
        SourcePreference saved=preference("42");saved.remember(source("same","第01集 · 1080P","1"));
        assertEquals(-1,saved.match(Collections.singletonList(source("same","第02集 · 4K","2"))));
        SourcePreference other=new SourcePreference(RuntimeEnvironment.getApplication(),"https://other.example.org","42");
        assertFalse(other.saved());
    }
}
