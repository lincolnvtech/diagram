package br.com.civicdrive;

import java.net.URI;

/** Regras do atualizador sem dependência do Android (testáveis na JVM). */
final class UpdatePolicy {
    static final long SUCCESS_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    static final long ATTEMPT_INTERVAL_MS = 10L * 60L * 1000L;

    private UpdatePolicy() { }

    static boolean isTrustedApkUrl(String url, String rootUrl) {
        try {
            URI root = new URI(rootUrl);
            URI u = new URI(url).normalize();
            String path = u.getRawPath();
            if (path == null || !path.matches("/[A-Za-z0-9._/-]+")) return false;
            for (String seg : path.split("/")) {
                if (seg.equals("..") || seg.equals(".")) return false;
            }
            return "https".equalsIgnoreCase(u.getScheme())
                && root.getHost() != null
                && root.getHost().equalsIgnoreCase(u.getHost())
                && u.getPort() == -1 && u.getRawUserInfo() == null
                && u.getRawQuery() == null && u.getRawFragment() == null
                && path.startsWith(root.getRawPath()) && path.endsWith(".apk");
        } catch(Exception ignored) { return false; }
    }

    static boolean isAutoCheckDue(long now,long lastSuccess,long lastAttempt) {
        long sinceSuccess=now-lastSuccess, sinceAttempt=now-lastAttempt;
        return (sinceSuccess<0 || sinceSuccess>=SUCCESS_INTERVAL_MS)
            && (sinceAttempt<0 || sinceAttempt>=ATTEMPT_INTERVAL_MS);
    }
}
