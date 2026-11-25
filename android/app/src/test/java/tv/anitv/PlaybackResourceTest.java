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
    @Test public void parentResizeRecalculatesFittedSurfaceLayout()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();
            android.widget.FrameLayout parent=new android.widget.FrameLayout(activity);
            SurfaceView surface=new SurfaceView(activity);
            parent.addView(surface,new android.widget.FrameLayout.LayoutParams(-1,-1));
            activity.setContentView(parent);
            try(NativePlayback engine=new NativePlayback(activity,surface,new OkHttpClient(),
                    new NativePlayback.Listener(){public void ready(){}public void buffering(boolean b){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){}public void changed(){}})){
                parent.layout(0,0,1920,1080);
                java.lang.reflect.Method fit=NativePlayback.class.getDeclaredMethod("fit",int.class,int.class);
                fit.setAccessible(true);
                fit.invoke(engine,640,480);
                assertEquals(1440,surface.getLayoutParams().width);
                assertEquals(1080,surface.getLayoutParams().height);

                parent.layout(0,0,1280,720);
                assertEquals(960,surface.getLayoutParams().width);
                assertEquals(720,surface.getLayoutParams().height);
            }
        }
    }
}
