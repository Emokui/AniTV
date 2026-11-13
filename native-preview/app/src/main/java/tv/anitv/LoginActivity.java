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
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Ui.BG);root.setPadding(Ui.dp(this,50),Ui.dp(this,30),Ui.dp(this,50),Ui.dp(this,24));
        root.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);setContentView(root);
        boolean loggedIn=new AccountSession(this).available(HttpUrl.get(new Library(this).endpoint()));
        root.addView(Ui.text(this,loggedIn ? "AniCh 账号管理" : "登录 AniCh 获取完整线路",24,Ui.TEXT));
        TextView accountState=Ui.text(this,loggedIn ? "当前状态：已登录" : "当前状态：未登录",14,Ui.ACCENT);root.addView(accountState);
        root.addView(Ui.text(this,"登录当前番剧接口。密码不保存，可用遥控器或外接键盘输入。",13,Ui.MUTED));
        EditText user=new EditText(this);user.setHint("用户名或邮箱");user.setSingleLine();user.setTextColor(Ui.TEXT);user.setSaveEnabled(false);root.addView(user);
        EditText password=new EditText(this);password.setHint("密码");password.setSingleLine();password.setTextColor(Ui.TEXT);password.setSaveEnabled(false);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);root.addView(password);
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
        });user.requestFocus();
    }
    @Override protected void onDestroy(){synchronized(sessionLock){destroyed=true;loginGeneration++;}NetworkCleanup.cancel(call);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
