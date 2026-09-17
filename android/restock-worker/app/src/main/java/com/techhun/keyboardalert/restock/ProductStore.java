package com.techhun.keyboardalert.restock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProductStore {
    static final String KEY_PRODUCTS = "products_v2";
    private static final Pattern PRODUCT_ID = Pattern.compile("/products/(\\d+)");

    private ProductStore() {}

    static JSONArray list(Context context) {
        migrateLegacyIfNeeded(context);
        try {
            return new JSONArray(MonitorPrefs.prefs(context).getString(KEY_PRODUCTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    static void save(Context context, JSONArray products) {
        MonitorPrefs.prefs(context).edit().putString(KEY_PRODUCTS, products.toString()).apply();
    }

    static String idFromUrl(String url) {
        Matcher matcher = PRODUCT_ID.matcher(url == null ? "" : url);
        if (matcher.find()) return matcher.group(1);
        return "url-" + Math.abs((url == null ? "" : url).hashCode());
    }

    static JSONObject find(Context context, String id) {
        JSONArray products = list(context);
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product != null && id.equals(product.optString("id"))) return product;
        }
        return null;
    }

    static void upsert(Context context, JSONObject next) {
        JSONArray products = list(context);
        JSONArray updated = new JSONArray();
        String id = next.optString("id");
        boolean replaced = false;
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null) continue;
            if (!replaced && id.equals(product.optString("id"))) {
                preserveRuntime(product, next);
                updated.put(next);
                replaced = true;
            } else {
                updated.put(product);
            }
        }
        if (!replaced) updated.put(next);
        save(context, updated);
    }

    static void remove(Context context, String id) {
        JSONArray products = list(context);
        JSONArray updated = new JSONArray();
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null || id.equals(product.optString("id"))) continue;
            updated.put(product);
        }
        save(context, updated);
    }

    static int selectedCount(JSONObject product) {
        JSONArray ids = product == null ? null : product.optJSONArray("selectedIds");
        return ids == null ? 0 : ids.length();
    }

    static void migrateLegacyIfNeeded(Context context) {
        SharedPreferences prefs = MonitorPrefs.prefs(context);
        if (prefs.contains(KEY_PRODUCTS)) return;
        String url = prefs.getString(MonitorPrefs.KEY_URL, "");
        JSONArray products = new JSONArray();
        if (!url.isBlank()) {
            try {
                JSONArray ids = new JSONArray(prefs.getString(MonitorPrefs.KEY_SELECTED_IDS, "[]"));
                if (ids.length() > 0) {
                    JSONObject product = new JSONObject();
                    product.put("id", idFromUrl(url));
                    product.put("url", url);
                    product.put("title", prefs.getString(MonitorPrefs.KEY_TITLE, "SmartStore 상품"));
                    product.put("selectedIds", ids);
                    product.put("selectedLabels", new JSONObject(prefs.getString(MonitorPrefs.KEY_SELECTED_LABELS, "{}")));
                    product.put("lastAvailability", new JSONObject(prefs.getString(MonitorPrefs.KEY_LAST_AVAILABILITY, "{}")));
                    product.put("lastStatus", prefs.getString(MonitorPrefs.KEY_LAST_STATUS, "대기 중"));
                    product.put("lastCheck", prefs.getLong(MonitorPrefs.KEY_LAST_CHECK, 0L));
                    products.put(product);
                }
            } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY_PRODUCTS, products.toString()).apply();
    }

    private static void preserveRuntime(JSONObject oldProduct, JSONObject next) {
        try {
            JSONArray oldIds = oldProduct.optJSONArray("selectedIds");
            JSONArray nextIds = next.optJSONArray("selectedIds");
            boolean sameSelection = oldIds != null && nextIds != null && oldIds.toString().equals(nextIds.toString());
            if (sameSelection) {
                next.put("lastAvailability", oldProduct.optJSONObject("lastAvailability") == null ? new JSONObject() : oldProduct.optJSONObject("lastAvailability"));
                next.put("lastStatus", oldProduct.optString("lastStatus", "대기 중"));
                next.put("lastCheck", oldProduct.optLong("lastCheck", 0L));
            } else {
                next.put("lastAvailability", new JSONObject());
                next.put("lastStatus", "대기 중");
                next.put("lastCheck", 0L);
            }
        } catch (Exception ignored) {}
    }
}
