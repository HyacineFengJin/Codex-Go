package dev.codex.go.testdriver;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.*;

/** Test-owned content only. Never launches or edits another app's page. */
public final class ReadingFixtureActivity extends Activity {
    private boolean running = true;
    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) { finish(); return; }
        String mode = getIntent().getStringExtra("mode");
        if ("indicator".equals(mode)) {
            float density = getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding((int)(24*density), (int)(8*density), (int)(24*density), (int)(24*density));
            box.setBackgroundColor(0xfff4f7fa);
            int[] taps = {getPreferences(0).getInt("indicatorTaps", 0)};
            Button top = new Button(this); top.setAllCaps(false); top.setText("Touch through badge: " + taps[0]);
            top.setOnClickListener(v -> { top.setText("Touch through badge: " + (++taps[0])); getPreferences(0).edit().putInt("indicatorTaps", taps[0]).apply(); });
            box.addView(top, new LinearLayout.LayoutParams(-1, (int)(100*density)));
            TextView title = new TextView(this); title.setText("Computer Use"); title.setTextSize(28); title.setTextColor(0xff18283b); title.setPadding(0,(int)(48*density),0,(int)(12*density)); box.addView(title);
            TextView subtitle = new TextView(this); subtitle.setText("Operation indicator test\n\nThe blue edges and top label are passive.\nYour app keeps focus and receives touches."); subtitle.setTextSize(15); subtitle.setTextColor(0xff506073); box.addView(subtitle);
            EditText input = new EditText(this); input.setId(0x123451); input.setHint("Focus and typing test"); input.setShowSoftInputOnFocus(false); box.addView(input, new LinearLayout.LayoutParams(-1,(int)(72*density)));
            TextView footer = new TextView(this); footer.setText("Test fixture · No external apps or accounts"); footer.setTextSize(12); footer.setTextColor(0xff506073); footer.setPadding(0,(int)(32*density),0,0); box.addView(footer);
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(box); setContentView(scroll); return;
        }
        if ("actions".equals(mode)) {
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(48, 120, 48, 40);
            TextView heading = new TextView(this); heading.setText("动态点击测试"); box.addView(heading);
            TextView ticker = new TextView(this); ticker.setText("动态内容 0"); box.addView(ticker);
            TextView count = new TextView(this); count.setText("点击次数 0"); box.addView(count);
            Button target = new Button(this); target.setText("点击计数按钮"); int[] clicks = {0};
            target.setOnClickListener(v -> count.setText("点击次数 " + (++clicks[0]))); box.addView(target);
            EditText input = new EditText(this); input.setHint("动态输入测试"); input.setShowSoftInputOnFocus(false); box.addView(input);
            Button start = new Button(this); start.setText("开始持续变化");
            start.setOnClickListener(v -> {
                start.setEnabled(false);
                ticker.post(new Runnable() { int tick = 0; public void run() {
                    if (!running) return; ticker.setText("动态内容 " + (++tick)); ticker.postDelayed(this, 120);
                }});
            }); box.addView(start); setContentView(box); return;
        }
        if ("web".equals(mode)) {
            WebView web = new WebView(this); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setBlockNetworkLoads(true);
            web.loadDataWithBaseURL("https://fixture.invalid/", "<!doctype html><html lang='zh'><meta name='viewport' content='width=device-width,initial-scale=1'><body style='padding:24px;font:20px sans-serif;background:white;color:#18201b'><h1>网页预读测试</h1><p>网页内容 A</p><label>名称<input aria-label='名称' value='示例文字'></label><p><button disabled>不可用按钮</button></p><button onclick=\"document.querySelector('p').textContent='网页内容 B'\">更新网页内容</button></body></html>", "text/html", "UTF-8", null);
            setContentView(web); return;
        }
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(48, 120, 48, 40); column.setBackgroundColor(0xfff8faf5);
        TextView title = new TextView(this); title.setText("结构预读测试"); title.setTextSize(24); column.addView(title);
        title.setAccessibilityHeading(true);
        TextView value = new TextView(this); value.setText("页面内容 A"); value.setTextSize(20); column.addView(value);
        Button update = new Button(this); update.setText("更新测试内容"); update.setOnClickListener(v -> value.setText("页面内容 B")); column.addView(update);
        CheckBox check = new CheckBox(this); check.setText("保留原始状态"); check.setChecked(true); column.addView(check);
        Button disabled = new Button(this); disabled.setText("不可用按钮"); disabled.setEnabled(false); column.addView(disabled);
        if ("visual".equals(mode)) column.addView(new CanvasView(), new LinearLayout.LayoutParams(-1, 700));
        if ("labelled".equals(mode)) {
            ImageView chart = new ImageView(this); chart.setContentDescription("趋势图：本月概览");
            chart.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xff005b46));
            column.addView(chart, new LinearLayout.LayoutParams(-1, 700));
        }
        if ("metadata".equals(mode)) {
            EditText input = new EditText(this) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info); info.setError("至少两个字符"); info.setContentInvalid(true);
                }
            };
            input.setHint("请输入搜索词"); input.setShowSoftInputOnFocus(false); column.addView(input);
            update.setStateDescription("尚未选择");
            SeekBar volume = new SeekBar(this); volume.setMax(100); volume.setProgress(37); volume.setContentDescription("音量"); column.addView(volume);
        }
        if ("keyboard".equals(mode)) {
            EditText input = new EditText(this); input.setHint("输入测试文字"); column.addView(input);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            input.postDelayed(() -> { input.requestFocus(); getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); }, 300);
        }
        setContentView(column);
    }
    private final class CanvasView extends View {
        private final Paint paint = new Paint();
        CanvasView() { super(ReadingFixtureActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(0xffd2ebf5); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(0xff005b46); canvas.drawCircle(getWidth()*.5f, getHeight()*.5f, 100, paint);
        }
    }
}
