package pe.gustavo.telefonolite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.telecom.TelecomManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InCallActivity extends Activity {
    private static final int PANEL = 0xFF191919;
    private static final int MUTED = 0xFFAAAAAA;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable listener = this::refresh;
    private final Runnable finishEnded = this::finish;
    private final Runnable stopAccessibleTone = this::stopTone;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateStatus();
            if (visible && selected != null && CallService.getCallState(selected) == Call.STATE_ACTIVE)
                handler.postDelayed(this, 1000);
        }
    };
    private CallService service;
    private Call selected;
    private Call lastRinging;
    private final java.util.Map<Call, Integer> knownStates = new java.util.IdentityHashMap<>();
    private Call toneCall;
    private Call accountCall;
    private AlertDialog accountDialog;
    private PowerManager.WakeLock proximity;
    private boolean visible, keypadOpen, touchDelivery;
    private LinearLayout root, callsBox, actions, keypad, endActions, outer;
    private TextView name, number, status, avatar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        if (state != null) keypadOpen = state.getBoolean("keypad");
        if (getIntent() != null && getIntent().getBooleanExtra("show_dialpad", false)) keypadOpen = true;
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(Color.BLACK);
        outer.setPadding(dp(24), dp(8), dp(24), dp(8));
        root.setBackgroundColor(Color.BLACK);
        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(24) + bars.left, dp(8) + bars.top, dp(24) + bars.right, dp(8) + bars.bottom);
            } else {
                v.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(24) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(outer);
        TextView heading = label(12, MUTED);
        heading.setText("LLAMADA"); heading.setLetterSpacing(0.18f); heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);
        avatar = label(34, Color.WHITE); avatar.setPadding(0, 0, 0, 0);
        avatar.setTypeface(null, Typeface.BOLD); avatar.setBackground(shape(0xFF252525, 100));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL; avatarParams.setMargins(0, dp(20), 0, dp(18));
        root.addView(avatar, avatarParams);
        name = label(28, Color.WHITE); name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        number = label(16, MUTED); number.setSingleLine(true); number.setEllipsize(TextUtils.TruncateAt.END);
        status = label(15, MUTED);
        root.addView(name); root.addView(number); root.addView(status);
        callsBox = new LinearLayout(this); callsBox.setOrientation(LinearLayout.VERTICAL); root.addView(callsBox);
        actions = new LinearLayout(this); actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2); controlsParams.setMargins(0, dp(26), 0, 0);
        root.addView(actions, controlsParams);
        keypad = new LinearLayout(this); keypad.setOrientation(LinearLayout.VERTICAL); root.addView(keypad);
        endActions = new LinearLayout(this); endActions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(-1, -2); endParams.setMargins(0, dp(20), 0, dp(12)); outer.addView(endActions, endParams);
        buildKeypad();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("show_dialpad", false)) { keypadOpen = true; refresh(); }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true;
        service = CallService.getInstance();
        if (service != null) service.addListener(listener);
        refresh();
    }
    @Override protected void onPause() {
        visible = false; handler.removeCallbacks(tick); handler.removeCallbacks(finishEnded); handler.removeCallbacks(stopAccessibleTone); stopTone(); releaseProximity();
        if (service != null) service.removeListener(listener);
        super.onPause();
    }
    @Override protected void onDestroy() {
        stopTone(); releaseProximity();
        if (accountDialog != null) accountDialog.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("keypad", keypadOpen); super.onSaveInstanceState(out); }

    private void refresh() {
        if (!visible) return;
        CallService current = CallService.getInstance();
        if (current != service) {
            if (service != null) service.removeListener(listener);
            service = current;
            if (service != null) service.addListener(listener);
        }
        if (service == null || service.currentCalls().isEmpty()) {
            handler.removeCallbacks(tick); stopTone(); releaseProximity();
            name.setText(R.string.call_ended); number.setText(""); status.setText("");
            callsBox.removeAllViews(); actions.removeAllViews(); endActions.removeAllViews(); keypad.setVisibility(View.GONE); avatar.setText("•");
            selected = null; lastRinging = null; knownStates.clear(); handler.removeCallbacks(finishEnded); handler.postDelayed(finishEnded, 1000);
            return;
        }
        handler.removeCallbacks(finishEnded);
        List<Call> calls = service.currentCalls();
        Call ringing = null;
        for (Call c : calls) if (CallService.getCallState(c) == Call.STATE_RINGING) { ringing = c; break; }
        Call newOutgoing = null;
        for (Call c : calls) {
            int callState = CallService.getCallState(c);
            Integer previous = knownStates.get(c);
            boolean outgoing = callState == Call.STATE_SELECT_PHONE_ACCOUNT || callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING;
            boolean wasOutgoing = previous != null && (previous == Call.STATE_SELECT_PHONE_ACCOUNT || previous == Call.STATE_CONNECTING || previous == Call.STATE_DIALING);
            if (outgoing && !wasOutgoing) newOutgoing = c;
            knownStates.put(c, callState);
        }
        knownStates.keySet().retainAll(calls);
        if (ringing != null && ringing != lastRinging) selected = ringing;
        else if (ringing == null && newOutgoing != null) selected = newOutgoing;
        else if (selected == null || !calls.contains(selected) || CallService.getCallState(selected) == Call.STATE_DISCONNECTED)
            selected = service.primaryCall();
        lastRinging = ringing;
        if (selected == null) return;
        int state = CallService.getCallState(selected);
        setTurnScreenOn(state == Call.STATE_RINGING);
        name.setText(displayName(selected)); updateAvatar();
        String raw = selected.getDetails() != null && selected.getDetails().getHandlePresentation() == TelecomManager.PRESENTATION_ALLOWED && selected.getDetails().getHandle() != null ? selected.getDetails().getHandle().getSchemeSpecificPart() : "";
        number.setText(raw != null && !raw.equals(displayName(selected)) ? raw : "");
        updateStatus();
        handler.removeCallbacks(tick);
        if (state == Call.STATE_ACTIVE) handler.postDelayed(tick, 1000);
        callsBox.removeAllViews();
        if (calls.size() > 1) for (Call call : calls) {
            if (call == selected || CallService.getCallState(call) == Call.STATE_DISCONNECTED) continue;
            Button choose = button(displayName(call) + " · " + service.callStatus(call));
            choose.setOnClickListener(v -> { selected = call; refreshSelected(); });
            callsBox.addView(choose);
        }
        renderActions();
        updateProximity();
        updateAccountSelector();
    }
    // Explicit selection must remain usable while another call is ringing.
    private void refreshSelected() {
        if (service == null || selected == null) return;
        name.setText(displayName(selected)); updateAvatar();
        String raw = selected.getDetails() != null && selected.getDetails().getHandlePresentation() == TelecomManager.PRESENTATION_ALLOWED && selected.getDetails().getHandle() != null ? selected.getDetails().getHandle().getSchemeSpecificPart() : "";
        number.setText(raw != null && !raw.equals(displayName(selected)) ? raw : "");
        updateStatus(); renderActions(); updateProximity(); updateAccountSelector();
        callsBox.removeAllViews();
        for (Call c : service.currentCalls()) if (c != selected && CallService.getCallState(c) != Call.STATE_DISCONNECTED) {
            Button b = button(displayName(c) + " · " + service.callStatus(c));
            b.setOnClickListener(v -> { selected = c; refreshSelected(); }); callsBox.addView(b);
        }
        handler.removeCallbacks(tick);
        if (CallService.getCallState(selected) == Call.STATE_ACTIVE) handler.postDelayed(tick, 1000);
    }
    private void updateStatus() {
        if (selected == null || service == null) return;
        String text = service.callStatus(selected);
        if (CallService.getCallState(selected) == Call.STATE_ACTIVE && selected.getDetails() != null) {
            long started = selected.getDetails().getConnectTimeMillis();
            if (started > 0) {
                long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
                text += String.format(Locale.getDefault(), " · %02d:%02d", seconds / 60, seconds % 60);
            }
        } else if (CallService.getCallState(selected) == Call.STATE_DISCONNECTED && selected.getDetails() != null && selected.getDetails().getDisconnectCause() != null) {
            CharSequence reason = selected.getDetails().getDisconnectCause().getLabel();
            if (reason != null && reason.length() > 0) text += " · " + reason;
        }
        status.setText(text);
    }
    private String displayName(Call call) {
        if (call.getDetails() != null && call.getDetails().getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED) return "Número privado";
        return service.displayName(call);
    }
    private void updateAvatar() {
        String display = displayName(selected).trim();
        avatar.setText(display.isEmpty() ? "•" : display.substring(0, 1).toUpperCase(Locale.getDefault()));
    }
    private void renderActions() {
        actions.removeAllViews(); endActions.removeAllViews();
        int state = CallService.getCallState(selected);
        if (state == Call.STATE_RINGING) {
            addWide(endActions, "Responder", true, () -> service.answer(selected));
            addWide(endActions, "Rechazar", false, () -> service.reject(selected));
        } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
            if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
                LinearLayout row = actionRow();
                addTile(row, "Micrófono", service.isMuted() ? "Silenciado" : "Activado", service.isMuted(), () -> service.toggleMute());
                addTile(row, "Altavoz", service.isSpeaker() ? "Activado" : "Desactivado", service.isSpeaker(), () -> service.toggleSpeaker());
                LinearLayout second = actionRow();
                if (state == Call.STATE_ACTIVE) addTile(second, "Teclado", keypadOpen ? "Abierto" : "Cerrado", keypadOpen, () -> { keypadOpen = !keypadOpen; renderActions(); });
                int caps = selected.getDetails() == null ? 0 : selected.getDetails().getCallCapabilities();
                if ((caps & Call.Details.CAPABILITY_HOLD) != 0 && (caps & Call.Details.CAPABILITY_SUPPORT_HOLD) != 0) {
                    addTile(second, state == Call.STATE_HOLDING ? "Reanudar" : "En espera", state == Call.STATE_HOLDING ? "En espera" : "Pausar llamada", state == Call.STATE_HOLDING, () -> {
                        if (CallService.getCallState(selected) == Call.STATE_HOLDING) selected.unhold(); else selected.hold();
                    });
                }
                if (second.getChildCount() == 1) second.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            addWide(endActions, "Colgar", true, () -> service.hangUp(selected));
        }
        boolean show = keypadOpen && state == Call.STATE_ACTIVE;
        if (!show) stopTone();
        keypad.setVisibility(show ? View.VISIBLE : View.GONE);
        avatar.setVisibility(show ? View.GONE : View.VISIBLE);
    }
    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(row); return row;
    }
    private void addTile(LinearLayout row, String title, String detail, boolean active, Runnable action) {
        Button b = button(title + "\n" + detail); b.setTextSize(15); b.setMinHeight(dp(80));
        b.setBackground(shape(active ? Color.WHITE : PANEL, 18)); b.setTextColor(active ? Color.BLACK : Color.WHITE);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(4), dp(4), dp(4), dp(4)); row.addView(b, params);
    }
    private void addWide(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button b = button(title); b.setMinHeight(dp(58)); b.setTextSize(16); b.setTypeface(null, Typeface.BOLD);
        b.setBackground(shape(primary ? Color.WHITE : PANEL, 30)); b.setTextColor(primary ? Color.BLACK : Color.WHITE);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(dp(4), dp(5), dp(4), dp(5)); parent.addView(b, params);
    }
    // Touch delivers a continuous tone; the click listener also supports accessibility actions.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void buildKeypad() {
        String[] rows = {"123", "456", "789", "*0#"};
        for (String row : rows) {
            LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
            for (char digit : row.toCharArray()) {
                Button b = button(String.valueOf(digit));
                b.setContentDescription(String.valueOf(digit));
                b.setOnClickListener(v -> {
                    if (touchDelivery) return;
                    if (service != null && selected != null && CallService.getCallState(selected) == Call.STATE_ACTIVE) {
                        stopTone(); toneCall = selected; service.playTone(toneCall, digit);
                        handler.postDelayed(stopAccessibleTone, 150);
                    }
                });
                b.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        stopTone();
                        if (service != null && selected != null && CallService.getCallState(selected) == Call.STATE_ACTIVE) { toneCall = selected; service.playTone(toneCall, digit); }
                        return true;
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        stopTone();
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            touchDelivery = true;
                            try { v.performClick(); } finally { touchDelivery = false; }
                        }
                        return true;
                    }
                    return true;
                });
                line.addView(b, new LinearLayout.LayoutParams(0, dp(54), 1));
            }
            keypad.addView(line);
        }
    }
    private void stopTone() { handler.removeCallbacks(stopAccessibleTone); if (toneCall != null && service != null) service.stopTone(toneCall); toneCall = null; }
    private void updateAccountSelector() {
        if (selected != null && CallService.getCallState(selected) == Call.STATE_SELECT_PHONE_ACCOUNT) showAccountSelector(selected);
        else if (accountDialog != null) { accountDialog.dismiss(); accountDialog = null; accountCall = null; }
    }
    private void showAccountSelector(Call call) {
        if (accountDialog != null && accountCall == call) return;
        if (accountDialog != null) accountDialog.dismiss();
        Bundle extras = call.getDetails() == null ? null : call.getDetails().getExtras();
        ArrayList<PhoneAccountHandle> found = extras == null ? null : extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS);
        if (found == null || found.isEmpty()) {
            Bundle intentExtras = call.getDetails() == null ? null : call.getDetails().getIntentExtras();
            found = intentExtras == null ? null : intentExtras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS);
        }
        if (found == null || found.isEmpty()) return;
        final ArrayList<PhoneAccountHandle> accounts = found;
        TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
        String[] labels = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            android.telecom.PhoneAccount account = null;
            try { if (telecom != null) account = telecom.getPhoneAccount(accounts.get(i)); }
            catch (SecurityException ignored) { }
            labels[i] = account != null && account.getLabel() != null ? account.getLabel().toString() : "SIM " + (i + 1);
        }
        accountCall = call;
        accountDialog = new AlertDialog.Builder(this).setTitle("Selecciona una SIM").setItems(labels, (dialog, which) -> {
            if (CallService.getCallState(call) == Call.STATE_SELECT_PHONE_ACCOUNT) call.phoneAccountSelected(accounts.get(which), false);
        }).setNegativeButton("Cancelar", (dialog, which) -> call.disconnect()).create();
        accountDialog.setOnCancelListener(d -> { if (CallService.getCallState(call) == Call.STATE_SELECT_PHONE_ACCOUNT) call.disconnect(); });
        accountDialog.setOnDismissListener(d -> { accountDialog = null; accountCall = null; });
        accountDialog.show();
    }
    // The lock is paired with release on pause, destruction, route changes and call termination.
    @android.annotation.SuppressLint("WakelockTimeout")
    private void updateProximity() {
        boolean use = visible && service != null && selected != null && CallService.getCallState(selected) == Call.STATE_ACTIVE && !service.isSpeaker();
        if (use) {
            CallAudioState audio = service.getCallAudioState();
            use = audio != null && audio.getRoute() == CallAudioState.ROUTE_EARPIECE;
        }
        if (!use) { releaseProximity(); return; }
        if (proximity == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "TelefonoLite:proximity");
        }
        if (proximity != null && !proximity.isHeld()) proximity.acquire();
    }
    private void releaseProximity() { if (proximity != null && proximity.isHeld()) proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView label(int size, int color) { TextView v = new TextView(this); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER); v.setPadding(0, dp(4), 0, dp(4)); return v; }
    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setSoundEffectsEnabled(false); b.setHapticFeedbackEnabled(false); b.setTextColor(Color.WHITE); b.setBackground(shape(PANEL, 18)); return b; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
