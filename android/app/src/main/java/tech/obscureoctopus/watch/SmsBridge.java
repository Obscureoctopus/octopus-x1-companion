package tech.obscureoctopus.watch;

import android.telephony.SmsManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class SmsBridge {
    private static final String TAG = "SmsBridge";
    private static final Pattern PHONE = Pattern.compile("^[+]?[0-9][0-9\\-() ]{6,}$");
    private static final SmsBridge INST = new SmsBridge();
    private String lastNumber = "";
    private boolean enabled;

    public static SmsBridge get() { return INST; }

    public void setEnabled(boolean v) { enabled = v; }
    public boolean isEnabled() { return enabled; }
    public String lastNumber() { return lastNumber; }

    public void remember(String from) {
        if (from == null) return;
        String n = from.trim();
        if (PHONE.matcher(n).matches()) lastNumber = n.replaceAll("[^0-9+]", "");
        else if (n.startsWith("+") || n.chars().anyMatch(Character::isDigit)) {
            String digits = n.replaceAll("[^0-9+]", "");
            if (digits.length() >= 7) lastNumber = digits;
        }
    }

    public String send(String to, String body) {
        if (!enabled) return "SMS bridge off";
        String dest = to != null ? to.trim() : "";
        if (!PHONE.matcher(dest).matches()) dest = lastNumber;
        if (dest.isEmpty()) return "no phone number for reply";
        if (body == null || body.trim().isEmpty()) return "empty reply";
        SmsManager.getDefault().sendTextMessage(dest, null, body.trim(), null, null);
        Log.i(TAG, "sent to " + dest + ": " + body);
        return "sent to " + dest;
    }

    public void onWatchPacket(byte[] d) {
        if (d.length < 2 || d[0] != 1) return;
        int z = 1;
        while (z < d.length && d[z] != 0) z++;
        String from = new String(d, 1, z - 1, StandardCharsets.UTF_8);
        String body = (z + 1 < d.length)
                ? new String(d, z + 1, d.length - z - 1, StandardCharsets.UTF_8)
                : from;
        String result = send(from, body.isEmpty() ? from : body);
        Log.i(TAG, result);
    }
}
