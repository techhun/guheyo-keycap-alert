package com.techhun.keyboardalert.restock;

import android.webkit.CookieManager;

final class SessionState {
    static final String SMARTSTORE_HOME = "https://m.smartstore.naver.com/";

    private SessionState() {}

    static boolean hasNaverSession() {
        CookieManager manager = CookieManager.getInstance();
        String cookie = manager.getCookie("https://naver.com");
        if (cookie == null || cookie.isBlank()) cookie = manager.getCookie(SMARTSTORE_HOME);
        if (cookie == null || cookie.isBlank()) return false;
        return hasCookieValue(cookie, "NID_SES") || hasCookieValue(cookie, "NID_AUT");
    }

    private static boolean hasCookieValue(String cookie, String name) {
        String prefix = name + "=";
        for (String token : cookie.split(";")) {
            String value = token.trim();
            if (!value.startsWith(prefix)) continue;
            String content = value.substring(prefix.length()).trim();
            return !content.isBlank() && !"deleted".equalsIgnoreCase(content);
        }
        return false;
    }
}
