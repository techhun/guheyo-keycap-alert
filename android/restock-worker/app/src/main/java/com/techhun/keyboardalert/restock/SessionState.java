package com.techhun.keyboardalert.restock;

import android.webkit.CookieManager;

final class SessionState {
    static final String SMARTSTORE_HOME = "https://m.smartstore.naver.com/";

    private SessionState() {}

    static boolean hasNaverSession() {
        CookieManager manager = CookieManager.getInstance();
        String cookie = manager.getCookie("https://naver.com");
        if (cookie == null || cookie.isBlank()) cookie = manager.getCookie(SMARTSTORE_HOME);
        if (cookie == null) return false;
        return cookie.contains("NID_SES=") || cookie.contains("NID_AUT=");
    }
}
