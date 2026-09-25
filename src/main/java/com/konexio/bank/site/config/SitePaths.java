package com.konexio.bank.site.config;

/**
 * The paths this module serves, in one place, so the security chain and the
 * templates cannot drift apart about what a page is called.
 */
public final class SitePaths {

    public static final String WELCOME = "/";
    public static final String REGISTER = "/register";
    public static final String LOGIN = "/login";
    public static final String LOGOUT = "/logout";
    public static final String DASHBOARD = "/dashboard";
    public static final String DEPOSITS = "/deposits";
    public static final String WITHDRAWALS = "/withdrawals";

    public static final String TRANSFERS = "/transfers";
    public static final String ACTIVITY = "/activity";
    public static final String ACCOUNTS = "/accounts";
    public static final String LOANS = "/loans";
    static final String[] ASSETS = {"/css/**", "/images/**", "/favicon.ico"};
    static final String[] PUBLIC_PAGES = {WELCOME, REGISTER, REGISTER + "/**", LOGIN};

    /** Pages that need a session. Anything added here is closed until somebody logs in. */
    static final String[] PRIVATE_PAGES = {
        LOGOUT,
        DASHBOARD, DASHBOARD + "/**",
        DEPOSITS, DEPOSITS + "/**",
        WITHDRAWALS, WITHDRAWALS + "/**",
        TRANSFERS, TRANSFERS + "/**",
        ACTIVITY, ACTIVITY + "/**",
        ACCOUNTS, ACCOUNTS + "/**",
        LOANS, LOANS + "/**"
    };

    private SitePaths() {}
}
