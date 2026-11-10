package pe.gustavo.telefonolite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;

/** Explicit, non-exported notification actions, bound to a particular Telecom call. */
public class CallActions extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        CallService service = CallService.getInstance();
        if (service == null || intent == null) return;
        Call call = service.callForId(intent.getIntExtra("call_id", -1));
        if (call == null) return;
        String action = intent.getAction();
        if ("answer".equals(action)) service.answer(call);
        else if ("reject".equals(action)) service.reject(call);
        else if ("hangup".equals(action)) service.hangUp(call);
    }
}
