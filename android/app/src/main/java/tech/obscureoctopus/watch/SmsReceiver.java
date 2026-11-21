package tech.obscureoctopus.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (!SmsBridge.get().isEnabled()) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Object[] pdus = (Object[]) extras.get("pdus");
        if (pdus == null) return;
        String fmt = extras.getString("format");
        StringBuilder body = new StringBuilder();
        String from = "";
        for (Object pdu : pdus) {
            SmsMessage m = SmsMessage.createFromPdu((byte[]) pdu, fmt);
            if (m == null) continue;
            if (from.isEmpty()) from = m.getDisplayOriginatingAddress();
            body.append(m.getDisplayMessageBody());
        }
        if (from.isEmpty() || body.length() == 0) return;
        WatchBle.get(context).sendNotify(from, body.toString());
    }
}
