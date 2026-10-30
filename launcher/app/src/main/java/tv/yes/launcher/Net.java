package tv.yes.launcher;

import okhttp3.OkHttpClient;

/** One HTTP client for the process (Top Shelf art, weather): one connection pool and one set of threads. */
final class Net {
    private Net() {}

    /** OkHttp's defaults: 10 s connect / read / write, which both callers used. */
    static final OkHttpClient CLIENT = new OkHttpClient();
}
