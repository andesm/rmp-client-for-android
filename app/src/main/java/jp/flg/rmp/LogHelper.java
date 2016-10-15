package jp.flg.rmp;

import android.util.Log;

class LogHelper {

    private static final String LOG_PREFIX = "Rmp_";
    private static final int LOG_PREFIX_LENGTH = LOG_PREFIX.length();
    private static final int MAX_LOG_TAG_LENGTH = 23;

    private static String makeLogTag(String str) {
        if (str.length() > MAX_LOG_TAG_LENGTH - LOG_PREFIX_LENGTH) {
            return LOG_PREFIX + str.substring(0, MAX_LOG_TAG_LENGTH - LOG_PREFIX_LENGTH - 1);
        }

        return LOG_PREFIX + str;
    }

    /**
     * Don't use this when obfuscating class names!
     */
    static String makeLogTag(Class cls) {
        return makeLogTag(cls.getSimpleName());
    }

    static void d(String tag, Object... messages) {
        // Only log DEBUG if build type is DEBUG
        if (BuildConfig.DEBUG) {
            log(tag, Log.DEBUG, null, messages);
        }
    }

    static void w(String tag, Object... messages) {
        log(tag, Log.WARN, null, messages);
    }

    static void e(String tag, Object... messages) {
        log(tag, Log.ERROR, null, messages);
    }

    static void e(String tag, Throwable t, Object... messages) {
        log(tag, Log.ERROR, t, messages);
    }

    private static void log(String tag, int level, Throwable t, Object... messages) {
        String message;
        if (t == null && messages != null && messages.length == 1) {
            message = messages[0].toString();
        } else {
            StringBuilder sb = new StringBuilder();
            if (messages != null) {
                for (Object o : messages) {
                    sb.append(o);
                }
            }
            if (t != null) {
                sb.append("\n").append(Log.getStackTraceString(t));
            }
            message = sb.toString();
        }
        Log.println(level, tag, message);
    }
}

