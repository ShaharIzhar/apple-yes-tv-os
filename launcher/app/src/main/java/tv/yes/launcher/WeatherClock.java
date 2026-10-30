package tv.yes.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Top-left greeting ("Good morning / afternoon / evening", user 29 Oct) and clock with the weather at
 * config.xml's weather location (Tel Aviv by default), wind (knots, compass direction) and
 * wave height (metres) from Open-Meteo (free, no key). Updates only while
 * Home is visible: the clock every minute, the weather every 30 minutes.
 */
final class WeatherClock {

    private static final String TAG = "WeatherClock";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private final String forecastUrl, marineUrl;   // from config.xml weather_* (the place, and its sea for waves)

    final LinearLayout view;
    private final TextView greeting, clock;
    private final LinearLayout info;
    private final ImageView wxIcon, windIcon, wavesIcon;
    private final TextView wxText, windText, wavesText, wavesDot;
    private final Typeface font;
    private final float d;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = Net.CLIENT;
    private long lastFetch;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60000L - now % 60000L + 50L);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            fetch();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    WeatherClock(Context c, Typeface regular) {
        ctx = c;
        android.content.res.Resources r = c.getResources();
        forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + r.getString(R.string.weather_latitude)
                + "&longitude=" + r.getString(R.string.weather_longitude)
                + "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day"
                + "&wind_speed_unit=kn&timezone=auto";
        marineUrl = "https://marine-api.open-meteo.com/v1/marine?latitude=" + r.getString(R.string.weather_sea_latitude)
                + "&longitude=" + r.getString(R.string.weather_sea_longitude) + "&current=wave_height";
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        d = c.getResources().getDisplayMetrics().density;
        font = regular;

        greeting = new TextView(c);
        greeting.setTypeface(regular);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        greeting.setLetterSpacing(0.01f);
        greeting.setTextColor(Spec.APPNAME_COLOR);
        greeting.setIncludeFontPadding(false);
        greeting.setShadowLayer(8 * d, 0, d, 0x66000000);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.bottomMargin = Math.round(4 * d);
        view.addView(greeting, gp);

        clock = new TextView(c);
        clock.setTypeface(regular);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        clock.setLetterSpacing(-0.02f);
        clock.setTextColor(Spec.TEXT);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(8 * d, 0, d, 0x66000000);
        view.addView(clock, new LinearLayout.LayoutParams(-2, -2));

        // [condition] 22° Light rain · [arrow] Wind 3 kts NE · [waves] Waves 0.3 m
        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setVisibility(View.GONE);
        wxIcon = icon(R.drawable.ic_wx_cloud);
        wxText = text();
        windIcon = icon(R.drawable.ic_wind_arrow);
        windText = text();
        wavesIcon = icon(R.drawable.ic_waves);
        wavesText = text();
        info.addView(wxIcon, iconLp());
        info.addView(wxText);
        info.addView(dot());
        info.addView(windIcon, iconLp());
        info.addView(windText);
        wavesDot = dot();
        info.addView(wavesDot);
        info.addView(wavesIcon, iconLp());
        info.addView(wavesText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Math.round(6 * d);
        view.addView(info, lp);
        updateClock();
    }

    private ImageView icon(int res) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setColorFilter(Spec.APPNAME_COLOR);
        return v;
    }

    private LinearLayout.LayoutParams iconLp() {
        int s = Math.round(14 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = Math.round(5 * d);
        return lp;
    }

    private TextView text() {
        TextView t = new TextView(ctx);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Spec.APPNAME_SP);
        t.setLetterSpacing(Spec.APPNAME_TRACKING);
        t.setTextColor(Spec.APPNAME_COLOR);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(6 * d, 0, d, 0x66000000);
        return t;
    }

    private TextView dot() {
        TextView t = text();
        t.setText("\u00B7");
        int m = Math.round(9 * d);
        t.setPadding(m, 0, m, 0);
        return t;
    }

    void resume() {
        if (running) return;
        running = true;
        main.post(tick);
        long wait = Math.max(0L, lastFetch + REFRESH_MS - System.currentTimeMillis());
        main.postDelayed(refresh, wait);
    }

    void pause() {
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(refresh);
    }

    private void updateClock() {
        String pattern = DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm";
        clock.setText(DateFormat.format(pattern, new Date()));
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        greeting.setText(hour >= 5 && hour < 12 ? R.string.good_morning
                : hour >= 12 && hour < 17 ? R.string.good_afternoon : R.string.good_evening);
    }

    private void fetch() {
        lastFetch = System.currentTimeMillis();
        exec.execute(() -> {
            try {
                JSONObject cur = get(forecastUrl).getJSONObject("current");
                double temp = cur.getDouble("temperature_2m");
                int code = cur.getInt("weather_code");
                double wind = cur.getDouble("wind_speed_10m");
                double dir = cur.getDouble("wind_direction_10m");
                boolean day = cur.optInt("is_day", 1) == 1;
                Double waves = null;
                try {
                    JSONObject m = get(marineUrl).getJSONObject("current");
                    if (!m.isNull("wave_height")) waves = m.getDouble("wave_height");
                } catch (Exception e) {
                    Log.w(TAG, "marine: " + e.getMessage());
                }
                final String wx = (Math.round(temp) + "° " + describe(code)).trim();
                final String windS = "Wind " + Math.round(wind) + " kts " + compass(dir);
                final String wavesS = waves == null ? null : "Waves " + String.format(Locale.US, "%.1f", waves) + " m";
                final int wxRes = iconFor(code, day);
                // The arrow points where the wind blows to; the label says where it comes from.
                final float arrow = (float) ((dir + 180) % 360);
                main.post(() -> {
                    wxIcon.setImageResource(wxRes);
                    wxText.setText(wx);
                    windText.setText(windS);
                    windIcon.setRotation(arrow);
                    int wv = wavesS == null ? View.GONE : View.VISIBLE;
                    wavesText.setText(wavesS);
                    wavesText.setVisibility(wv);
                    wavesIcon.setVisibility(wv);
                    wavesDot.setVisibility(wv);
                    info.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e.getMessage());
                lastFetch = System.currentTimeMillis() - REFRESH_MS + 5 * 60 * 1000L;   // retry in 5 min
            }
        });
    }

    private JSONObject get(String url) throws Exception {
        try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
            return new JSONObject(r.body().string());
        }
    }

    /** Where the wind comes from, 8-point compass. */
    static String compass(double degrees) {
        String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int i = (int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8;
        return p[i];
    }

    static int iconFor(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_wx_clear : R.drawable.ic_wx_night;
        if (code == 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_cloud;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 71 && code <= 77) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if (code >= 51) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 61 || code == 66) return "Light rain";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
