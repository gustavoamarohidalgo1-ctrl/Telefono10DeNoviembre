package pe.gustavo.telefonolite;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.CallEndpointException;
import android.telecom.InCallService;
import android.telecom.VideoProfile;
import android.os.OutcomeReceiver;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Telecom owns the calls and ringtone; this service owns their in-call presentation. */
public class CallService extends InCallService {
    private static final String CHANNEL = "calls_silent_v1";
    private static CallService instance;
    // Only foreground dialer activities register here, even before Telecom binds the service.
    private static final List<Runnable> dialerListeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Call> calls = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<Call, Integer> ids = new IdentityHashMap<>();
    private final Map<Call, Call.Callback> callbacks = new IdentityHashMap<>();
    private final Map<Call, Integer> notifiedStates = new IdentityHashMap<>();
    private static int nextId = 1;
    private final Map<Call, String> contactNames = new IdentityHashMap<>();
    private final Map<Call, Uri> contactLookups = new IdentityHashMap<>();
    private final ExecutorService contactsExecutor = Executors.newSingleThreadExecutor();
    private boolean muted;
    private boolean speaker;
    private int supportedRoutes;
    private int currentRoute;
    private List<CallEndpoint> endpoints = new ArrayList<>();
    private CallEndpoint currentEndpoint;

    public static CallService getInstance() { return instance; }
    public static void addDialerListener(Runnable listener) { if (!dialerListeners.contains(listener)) dialerListeners.add(listener); }
    public static void removeDialerListener(Runnable listener) { dialerListeners.remove(listener); }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Llamadas", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public void onDestroy() {
        for (Call call : new ArrayList<>(calls)) {
            Call.Callback callback = callbacks.get(call);
            if (callback != null) call.unregisterCallback(callback);
            Integer id = ids.get(call);
            if (id != null) getSystemService(NotificationManager.class).cancel(id);
        }
        calls.clear(); callbacks.clear(); ids.clear(); notifiedStates.clear(); contactNames.clear(); contactLookups.clear();
        contactsExecutor.shutdownNow();
        if (instance == this) instance = null;
        notifyListeners();
        listeners.clear();
        super.onDestroy();
    }
    @Override public void onCallAdded(Call call) {
        super.onCallAdded(call);
        calls.add(call);
        ids.put(call, nextId++);
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call changed, int state) { refresh(changed); }
            @Override public void onDetailsChanged(Call changed, Call.Details details) { lookupContact(changed); refresh(changed); }
            @Override public void onParentChanged(Call changed, Call parent) { refresh(changed); }
            @Override public void onChildrenChanged(Call changed, List<Call> children) { refresh(changed); }
        };
        callbacks.put(call, callback);
        call.registerCallback(callback, main);
        refresh(call);
        lookupContact(call);
        if (getCallState(call) != Call.STATE_RINGING) showCallActivity(false);
    }
    @Override public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Call.Callback callback = callbacks.remove(call);
        if (callback != null) call.unregisterCallback(callback);
        Integer id = ids.remove(call);
        if (id != null) getSystemService(NotificationManager.class).cancel(id);
        calls.remove(call);
        notifiedStates.remove(call);
        contactNames.remove(call);
        contactLookups.remove(call);
        notifyListeners();
    }
    @Override public void onBringToForeground(boolean showDialpad) { showCallActivity(showDialpad); }
    private void showCallActivity(boolean showDialpad) {
        Intent intent = new Intent(this, InCallActivity.class).putExtra("show_dialpad", showDialpad).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    public Call primaryCall() {
        for (Call c : calls) if (getCallState(c) == Call.STATE_RINGING) return c;
        for (Call c : calls) { int state = getCallState(c); if (state == Call.STATE_SELECT_PHONE_ACCOUNT || state == Call.STATE_CONNECTING || state == Call.STATE_DIALING) return c; }
        for (Call c : calls) if (getCallState(c) == Call.STATE_ACTIVE) return c;
        for (Call c : calls) if (getCallState(c) == Call.STATE_HOLDING) return c;
        return calls.isEmpty() ? null : calls.get(0);
    }
    public List<Call> currentCalls() { return new ArrayList<>(calls); }
    public void addListener(Runnable listener) { if (listener != null && !listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void notifyListeners() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::notifyListeners); return; }
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        for (Runnable listener : new ArrayList<>(dialerListeners)) listener.run();
    }
    private void refresh(Call call) { updateNotification(call); notifyListeners(); }
    public static int getCallState(Call call) {
        if (call == null) return Call.STATE_DISCONNECTED;
        return Build.VERSION.SDK_INT >= 31 ? call.getDetails().getState() : call.getState();
    }
    public boolean isMuted() { return muted; }
    public boolean isSpeaker() { return speaker; }
    public void toggleMute() { setMuted(!muted); }
    public void toggleSpeaker() {
        if (Build.VERSION.SDK_INT >= 34) {
            CallEndpoint target = null;
            if (!speaker) {
                for (CallEndpoint e : endpoints) if (e.getEndpointType() == CallEndpoint.TYPE_SPEAKER) { target = e; break; }
            } else {
                for (CallEndpoint e : endpoints) if (e.getEndpointType() == CallEndpoint.TYPE_BLUETOOTH || e.getEndpointType() == CallEndpoint.TYPE_WIRED_HEADSET) { target = e; break; }
                if (target == null) for (CallEndpoint e : endpoints) if (e.getEndpointType() == CallEndpoint.TYPE_EARPIECE) { target = e; break; }
            }
            if (target != null) requestCallEndpointChange(target, getMainExecutor(), new OutcomeReceiver<Void, CallEndpointException>() {
                @Override public void onResult(Void result) { }
                @Override public void onError(CallEndpointException error) { Toast.makeText(CallService.this, "No se pudo cambiar el audio", Toast.LENGTH_SHORT).show(); notifyListeners(); }
            });
            else Toast.makeText(this, "Esa salida de audio no está disponible", Toast.LENGTH_SHORT).show();
        } else {
            int route = CallAudioState.ROUTE_SPEAKER;
            if (speaker) {
                if ((supportedRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0) route = CallAudioState.ROUTE_BLUETOOTH;
                else if ((supportedRoutes & CallAudioState.ROUTE_WIRED_HEADSET) != 0) route = CallAudioState.ROUTE_WIRED_HEADSET;
                else route = CallAudioState.ROUTE_EARPIECE;
            }
            if ((supportedRoutes & route) != 0) setAudioRoute(route);
        }
    }
    @Override public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        muted = state.isMuted(); currentRoute = state.getRoute(); supportedRoutes = state.getSupportedRouteMask();
        if (Build.VERSION.SDK_INT < 34) speaker = currentRoute == CallAudioState.ROUTE_SPEAKER;
        notifyListeners();
    }
    @Override public void onMuteStateChanged(boolean isMuted) { muted = isMuted; notifyListeners(); }
    @Override public void onAvailableCallEndpointsChanged(List<CallEndpoint> available) { endpoints = new ArrayList<>(available); notifyListeners(); }
    @Override public void onCallEndpointChanged(CallEndpoint endpoint) {
        if (Build.VERSION.SDK_INT >= 34) {
            currentEndpoint = endpoint;
            speaker = endpoint.getEndpointType() == CallEndpoint.TYPE_SPEAKER;
            notifyListeners();
        }
    }
    public void answer(Call call) { if (calls.contains(call) && getCallState(call) == Call.STATE_RINGING) call.answer(VideoProfile.STATE_AUDIO_ONLY); }
    public void reject(Call call) { if (calls.contains(call) && getCallState(call) == Call.STATE_RINGING) call.reject(false, null); }
    public void hangUp(Call call) { if (calls.contains(call)) call.disconnect(); }
    public void playTone(Call call, char tone) { if (calls.contains(call)) call.playDtmfTone(tone); }
    public void stopTone(Call call) { if (calls.contains(call)) call.stopDtmfTone(); }
    private void lookupContact(Call call) {
        Call.Details details = call.getDetails();
        Uri newHandle = details == null ? null : details.getHandle();
        if (!java.util.Objects.equals(contactLookups.get(call), newHandle)) { contactNames.remove(call); contactLookups.remove(call); }
        if (details == null || details.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED ||
                details.getHandle() == null || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        Uri handle = details.getHandle();
        if (!"tel".equals(handle.getScheme()) || handle.getSchemeSpecificPart() == null) return;
        if (handle.equals(contactLookups.get(call))) return;
        contactLookups.put(call, handle);
        String number = handle.getSchemeSpecificPart();
        contactsExecutor.execute(() -> {
            String found = null;
            Uri lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (Cursor cursor = getContentResolver().query(lookup, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) found = cursor.getString(0);
            } catch (RuntimeException ignored) { }
            final String name = found;
            main.post(() -> {
                if (instance != this || !calls.contains(call) || name == null || name.isEmpty()) return;
                Call.Details latest = call.getDetails();
                if (latest == null || latest.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED || !handle.equals(latest.getHandle())) return;
                contactNames.put(call, name);
                refresh(call);
            });
        });
    }
    public String displayName(Call call) {
        if (call == null || call.getDetails() == null) return "Llamada";
        Call.Details d = call.getDetails();
        if (d.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED) return "Número privado o desconocido";
        if (d.getCallerDisplayNamePresentation() == TelecomManager.PRESENTATION_ALLOWED && d.getCallerDisplayName() != null && !d.getCallerDisplayName().isEmpty()) return d.getCallerDisplayName();
        String contact = contactNames.get(call);
        if (contact != null) return contact;
        Uri handle = d.getHandle();
        return handle != null && handle.getSchemeSpecificPart() != null ? handle.getSchemeSpecificPart() : "Número desconocido";
    }
    public String callStatus(Call call) {
        switch (getCallState(call)) {
            case Call.STATE_RINGING: return "Llamada entrante";
            case Call.STATE_DIALING: case Call.STATE_CONNECTING: return "Llamando…";
            case Call.STATE_ACTIVE: return "En llamada";
            case Call.STATE_HOLDING: return "En espera";
            case Call.STATE_DISCONNECTING: return "Finalizando…";
            case Call.STATE_DISCONNECTED: return "Llamada finalizada";
            case Call.STATE_SELECT_PHONE_ACCOUNT: return "Selecciona una SIM";
            default: return "Conectando…";
        }
    }
    Call callForId(int id) { for (Map.Entry<Call,Integer> e : ids.entrySet()) if (e.getValue() == id) return e.getKey(); return null; }
    private PendingIntent action(String action, int id) {
        Intent intent = new Intent(this, CallActions.class).setAction(action).putExtra("call_id", id);
        return PendingIntent.getBroadcast(this, id * 10 + (action.equals("answer") ? 1 : action.equals("reject") ? 2 : 3), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void updateNotification(Call call) {
        Integer id = ids.get(call);
        if (id == null) return;
        int state = getCallState(call);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            nm.cancel(id); notifiedStates.remove(call); return;
        }
        boolean ringing = state == Call.STATE_RINGING;
        Integer previousState = notifiedStates.get(call);
        boolean firstRinging = ringing && (previousState == null || previousState != Call.STATE_RINGING);
        Person.Builder caller = new Person.Builder().setName(displayName(call));
        Call.Details details = call.getDetails();
        if (details != null && details.getHandlePresentation() == TelecomManager.PRESENTATION_ALLOWED) {
            Uri handle = details.getHandle();
            if (handle != null && "tel".equals(handle.getScheme())
                    && handle.getSchemeSpecificPart() != null && !handle.getSchemeSpecificPart().isEmpty()) {
                // Let Android apply its real contact/priority rules; never claim every caller is important.
                caller.setUri(handle.toString());
            }
        }
        Person person = caller.build();
        Intent ui = new Intent(this, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, id, ui, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(displayName(call)).setContentText(callStatus(call)).setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setContentIntent(content)
                .addPerson(person).setOnlyAlertOnce(!firstRinging);
        if (ringing) b.setFullScreenIntent(content, true);
        // A bound InCallService is not a foreground service. Android rejects an ongoing
        // CallStyle without an FSI; use a regular ongoing notification instead.
        boolean incomingStyle = ringing && Build.VERSION.SDK_INT >= 31
                && (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
        if (incomingStyle) {
            b.setStyle(Notification.CallStyle.forIncomingCall(person, action("reject", id), action("answer", id)));
        } else if (ringing) {
            b.addAction(new Notification.Action.Builder((Icon) null, "Rechazar", action("reject", id)).build());
            b.addAction(new Notification.Action.Builder((Icon) null, "Responder", action("answer", id)).build());
        } else b.addAction(new Notification.Action.Builder((Icon) null, "Colgar", action("hangup", id)).build());
        if (state == Call.STATE_ACTIVE && call.getDetails().getConnectTimeMillis() > 0)
            b.setWhen(call.getDetails().getConnectTimeMillis()).setUsesChronometer(true);
        try {
            nm.notify(id, b.build());
        } catch (IllegalArgumentException unsupportedStyle) {
            // An OEM may further restrict CallStyle. Keep the call controllable.
            b.setStyle(null);
            if (incomingStyle) {
                b.addAction(new Notification.Action.Builder((Icon) null, "Rechazar", action("reject", id)).build());
                b.addAction(new Notification.Action.Builder((Icon) null, "Responder", action("answer", id)).build());
            }
            nm.notify(id, b.build());
        }
        notifiedStates.put(call, state);
    }
}
