package tv.anitv;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import okhttp3.*;

public final class LoginActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile Call call;private boolean destroyed;
    private final Object sessionLock=new Object();private int loginGeneration;
    @Override public void onCreate(Bundle state){super.onCreate(state);Ui.fullscreen(this);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);scroll.setClipToPadding(false);
        android.widget.FrameLayout outer=new android.widget.FrameLayout(this);outer.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));scroll.addView(outer,new android.widget.ScrollView.LayoutParams(-1,-1));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(Ui.shape(this,Ui.PANEL,16,0));root.setPadding(Ui.dp(this,26),Ui.dp(this,24),Ui.dp(this,26),Ui.dp(this,24));
        outer.addView(root,new android.widget.FrameLayout.LayoutParams(Math.min(Ui.dp(this,460),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,48)),-2,android.view.Gravity.CENTER));
        root.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);setContentView(scroll);
        boolean loggedIn=new AccountSession(this).available(HttpUrl.get(new Library(this).endpoint()));
        root.addView(Ui.text(this,loggedIn ? "AniCh 账号管理" : "登录 AniCh 获取完整线路",24,Ui.TEXT));
        TextView accountState=Ui.text(this,loggedIn ? "当前状态：已登录" : "当前状态：未登录",14,Ui.ACCENT);root.addView(accountState);
        root.addView(Ui.text(this,"登录当前番剧接口。密码不保存，可用遥控器或外接键盘输入。",13,Ui.MUTED));
        EditText user=TvDialogs.input(this,"用户名或邮箱","");user.setHint("用户名或邮箱");user.setSingleLine();user.setTextColor(Ui.TEXT);user.setSaveEnabled(false);root.addView(user);
        EditText password=TvDialogs.input(this,"密码","");password.setHint("密码");password.setSingleLine();password.setTextColor(Ui.TEXT);password.setSaveEnabled(false);
        password.setId(android.view.View.generateViewId());password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);root.addView(password);
        TextView status=Ui.text(this,"",13,Ui.ACCENT);TextView login=Ui.button(this,loggedIn ? "重新登录" : "登录",null);root.addView(login);root.addView(status);
        root.addView(Ui.button(this,"返回",v->finish()));
        if(loggedIn)root.addView(Ui.button(this,"退出登录",v->{
            synchronized(sessionLock){loginGeneration++;new AccountSession(this).clear();}NetworkCleanup.cancel(call);accountState.setText("当前状态：未登录");
            login.setText("登录");v.setVisibility(android.view.View.GONE);status.setText("已退出登录");login.requestFocus();
        }));
        login.setOnClickListener(v->{String name=user.getText().toString().trim(),secret=password.getText().toString();
            if(name.isEmpty()||secret.isEmpty()){status.setText("请输入账号和密码");return;}
            password.setText("");login.setEnabled(false);status.setText("正在登录…");HttpUrl base=HttpUrl.get(new Library(this).endpoint());
            final int attempt=++loginGeneration;
            worker.submit(()->{try {
                synchronized(sessionLock){if(destroyed || attempt!=loginGeneration)return;}
                Request request=new Request.Builder().url(base.newBuilder().addPathSegment("login").build())
                        .post(new FormBody.Builder().add("data",AccountCodec.csv(AccountCodec.fields(name,secret))).build()).build();
                call=((AniApp)getApplication()).http().newCall(request);byte[] bytes;
                try(Response response=call.execute()){bytes=CatalogRepository.body(response);}
                String token=AccountCodec.token(bytes);
                synchronized(sessionLock){if(destroyed || attempt!=loginGeneration)return;new AccountSession(this).save(base,token);}
                main.post(()->{if(!destroyed){android.widget.Toast.makeText(this,"登录成功，请重新打开番剧获取完整线路",android.widget.Toast.LENGTH_LONG).show();finish();}});
            }catch(Exception error){main.post(()->{if(!destroyed){login.setEnabled(true);status.setText(error instanceof java.io.IOException?error.getMessage():"无法保存登录，请重试");}});}});
        });
        status.setVisibility(android.view.View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){status.setVisibility(s.length()==0?android.view.View.GONE:android.view.View.VISIBLE);}public void afterTextChanged(android.text.Editable e){}});
        for(int i=0;i<root.getChildCount();i++)if(root.getChildAt(i).isClickable())root.getChildAt(i).setBackground(Ui.formBackground(this));
        for(int i=1;i<root.getChildCount();i++){android.view.ViewGroup.MarginLayoutParams lp=(android.view.ViewGroup.MarginLayoutParams)root.getChildAt(i).getLayoutParams();lp.topMargin=Ui.dp(this,12);root.getChildAt(i).setLayoutParams(lp);}
        user.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);user.setNextFocusForwardId(password.getId());
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);password.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE || event!=null && (event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER || event.getKeyCode()==android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)){
            if((event==null || event.getAction()==android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount()==0 && !event.isCanceled()) && login.isEnabled())login.performClick();return true;}return false;});user.requestFocus();
    }
    @Override protected void onDestroy(){synchronized(sessionLock){destroyed=true;loginGeneration++;}NetworkCleanup.cancel(call);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
