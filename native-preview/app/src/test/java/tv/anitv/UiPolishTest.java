package tv.anitv;
import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class UiPolishTest {
    private <T> T find(View root,Class<T> type,String hint){
        if(type.isInstance(root) && (hint==null || root instanceof TextView && hint.contentEquals(((TextView)root).getHint())))return type.cast(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type,hint);if(found!=null)return found;}return null;
    }
    @Test public void sidebarSelectedIndicatorSurvivesFocusAndImmediateHideCancelsReveal(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            android.graphics.drawable.StateListDrawable bg=Ui.railBackground(c.get());
            bg.setState(new int[]{android.R.attr.state_selected});assertTrue(bg.getCurrent() instanceof LayerDrawable);
            bg.setState(new int[]{android.R.attr.state_selected,android.R.attr.state_focused});assertTrue(bg.getCurrent() instanceof LayerDrawable);
            View v=new View(c.get());v.setVisibility(View.GONE);Ui.reveal(v,130,8);assertEquals(View.VISIBLE,v.getVisibility());assertEquals(0f,v.getAlpha(),0);assertTrue(v.getTranslationY()>0);Ui.hide(v);assertEquals(View.GONE,v.getVisibility());assertEquals(1f,v.getAlpha(),0);assertEquals(0f,v.getTranslationY(),0);
        }
    }
    @Test public void loginUsesScrollableCardAndKeyboardDoneValidatesWithoutRequest(){
        try(ActivityController<LoginActivity> c=Robolectric.buildActivity(LoginActivity.class).setup().visible()){
            View root=c.get().getWindow().getDecorView();assertNotNull(find(root,ScrollView.class,null));
            EditText password=find(root,EditText.class,"密码");assertNotNull(password);assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_DONE,password.getImeOptions());
            password.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            assertFalse(c.get().isFinishing());
            root.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY));root.layout(0,0,640,220);
            ScrollView scroll=find(root,ScrollView.class,null);assertTrue(scroll.getChildAt(0).getMeasuredHeight()>scroll.getHeight());
        }
    }
    @Test public void compactFiltersPrepareWindowAndKeepCurrentChoiceWithoutApplyingOnFocus(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            java.util.concurrent.atomic.AtomicInteger applied=new java.util.concurrent.atomic.AtomicInteger(-1);
            android.app.AlertDialog dialog=FilterPicker.create(c.get(),"语言",new String[]{"全部","日语","国语","英语","韩语","其他"},2,(d,index)->applied.set(index));
            assertEquals(R.style.AniDialogAnimation,dialog.getWindow().getAttributes().windowAnimations);
            assertTrue(dialog.getWindow().getAttributes().width<=Ui.dp(c.get(),360));dialog.show();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View selected=dialog.getWindow().getDecorView().findViewWithTag("filter-option-2");assertTrue(selected.isSelected());assertTrue(selected.hasFocus());assertEquals(-1,applied.get());
            selected.performClick();assertEquals(2,applied.get());assertFalse(dialog.isShowing());
        }
    }
    @Test public void blankLoginStatusDoesNotReserveSpaceAndPhysicalEnterSubmits(){
        try(ActivityController<LoginActivity> c=Robolectric.buildActivity(LoginActivity.class).setup().visible()){
            View root=c.get().getWindow().getDecorView();EditText password=find(root,EditText.class,"密码");
            android.widget.LinearLayout panel=(android.widget.LinearLayout)password.getParent();TextView status=(TextView)panel.getChildAt(panel.indexOfChild(password)+2);
            assertEquals(View.GONE,status.getVisibility());
            password.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));
            password.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER));
            assertEquals(View.VISIBLE,status.getVisibility());assertEquals("请输入账号和密码",status.getText().toString());
        }
    }
    @Test public void revealCompletesWithoutChangingVisibilityDeadline(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            View v=new View(c.get());c.get().setContentView(v);v.setVisibility(View.GONE);Ui.reveal(v,130,8);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertEquals(View.VISIBLE,v.getVisibility());assertEquals(1f,v.getAlpha(),.01f);assertEquals(0f,v.getTranslationY(),.01f);
        }
    }

}
