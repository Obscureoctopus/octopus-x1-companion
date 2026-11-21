package tech.obscureoctopus.watch;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

public final class NotifService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!SmsBridge.get().isEnabled()) return;
        if (sbn.isOngoing()) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;
        if (pkg.equals(getPackageName())) return;
        boolean smsish = pkg.contains("mms") || pkg.contains("sms") || pkg.contains("messaging")
                || pkg.contains("talk") || pkg.contains("whatsapp") || pkg.contains("telegram")
                || pkg.contains("signal") || pkg.contains("facebook.orca") || pkg.contains("discord");
        Notification n = sbn.getNotification();
        if (n == null) return;
        Bundle e = n.extras;
        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = e.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(title)) return;
        String from = title != null ? title.toString() : pkg;
        String body = text != null ? text.toString() : "";
        if (!smsish && (n.category == null || !Notification.CATEGORY_MESSAGE.equals(n.category))) {
            return;
        }
        WatchBle.get(this).sendNotify(from, body);
    }
}
