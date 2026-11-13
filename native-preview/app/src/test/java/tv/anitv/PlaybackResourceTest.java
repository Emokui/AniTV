package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.SurfaceView;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class PlaybackResourceTest {
    @Test public void fitCropAndStretchPreserveTheirIntendedGeometry(){
        assertArrayEquals(new int[]{1440,1080},NativePlayback.displaySize(1920,1080,640,480,0));
        assertArrayEquals(new int[]{1920,1440},NativePlayback.displaySize(1920,1080,640,480,1));
        assertArrayEquals(new int[]{1920,1080},NativePlayback.displaySize(1920,1080,640,480,2));
        assertArrayEquals(new int[]{1920,1080},NativePlayback.displaySize(1920,1080,1920,1080,0));
    }
}
