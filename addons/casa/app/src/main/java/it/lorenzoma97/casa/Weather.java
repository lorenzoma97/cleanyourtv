package it.lorenzoma97.casa;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Bologna now: temperature, sky, today's max/min and air quality, for the launcher's top bar. Open-Meteo, no key
 * (forecast with the ARPAE ICON-2I model where it covers, air quality from CAMS Europe). Cached 30 minutes.
 */
final class Weather {
    static final String CITY = "Bologna";
    static final double LAT = 44.4949, LON = 11.3426;
    static final long TTL = 30L * 60 * 1000;
    /** Older than this (no network for hours) the reading is not "now" any more: the launcher hides the box. */
    static final long STALE = 3L * 3600 * 1000;
    private static final String TAG = "Casa";

    private Weather() { }

    /** The cached reading, refreshed first when older than TTL; null when there is none younger than STALE. */
    static JSONObject now(Context ctx) {
        File f = Store.file(ctx, "weather.json");
        if (!Store.fresh(f, TTL)) {
            try {
                JSONObject fc = new JSONObject(new String(Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                        + "&longitude=" + LON + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=Europe%2FRome&forecast_days=1"),
                        StandardCharsets.UTF_8));
                JSONObject out = new JSONObject()
                        .put("temp", fc.getJSONObject("current").getDouble("temperature_2m"))
                        .put("code", fc.getJSONObject("current").getInt("weather_code"))
                        .put("day", fc.getJSONObject("current").optInt("is_day", 1))
                        .put("max", fc.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0))
                        .put("min", fc.getJSONObject("daily").getJSONArray("temperature_2m_min").getDouble(0));
                try {
                    JSONObject aq = new JSONObject(new String(Net.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                            + LAT + "&longitude=" + LON + "&current=european_aqi&timezone=Europe%2FRome"), StandardCharsets.UTF_8));
                    out.put("aqi", aq.getJSONObject("current").getInt("european_aqi"));
                } catch (Exception e) {
                    Log.w(TAG, "air quality: " + e);   // the weather still shows without it
                }
                Store.write(f, out.toString());
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
            }
        }
        if (!Store.fresh(f, STALE)) return null;
        String text = Store.read(f);
        try {
            return text == null ? null : new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather code -> Italian words and an icon name the launcher draws (sun, moon, partly, cloud, fog, rain, snow, storm). */
    static String[] describe(int code, boolean day) {
        if (code == 0) return new String[]{"Sereno", day ? "sun" : "moon"};
        if (code == 1) return new String[]{"Prevalentemente sereno", day ? "partly" : "moon"};
        if (code == 2) return new String[]{"Poco nuvoloso", "partly"};
        if (code == 3) return new String[]{"Nuvoloso", "cloud"};
        if (code == 45 || code == 48) return new String[]{"Nebbia", "fog"};
        if (code >= 51 && code <= 57) return new String[]{"Pioviggine", "rain"};
        if (code >= 61 && code <= 67) return new String[]{code >= 65 ? "Pioggia forte" : "Pioggia", "rain"};
        if (code >= 71 && code <= 77) return new String[]{"Neve", "snow"};
        if (code >= 80 && code <= 82) return new String[]{"Rovesci", "rain"};
        if (code == 85 || code == 86) return new String[]{"Rovesci di neve", "snow"};
        if (code >= 95) return new String[]{"Temporale", "storm"};
        return new String[]{"", "cloud"};
    }

    /** European AQI bands (EEA): 0-20 buona ... >100 pessima. */
    static String air(int aqi) {
        if (aqi < 0) return null;
        if (aqi <= 20) return "Aria buona";
        if (aqi <= 40) return "Aria discreta";
        if (aqi <= 60) return "Aria moderata";
        if (aqi <= 80) return "Aria scadente";
        if (aqi <= 100) return "Aria molto scadente";
        return "Aria pessima";
    }
}
