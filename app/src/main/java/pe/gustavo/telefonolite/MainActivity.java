package pe.gustavo.telefonolite;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telecom.Call;
import android.text.InputType;
import android.text.InputFilter;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_CALL = 10;
    private static final int REQUEST_NOTIFICATIONS = 11;
    private EditText number;
    private LinearLayout setup;
    private ScrollView setupScroll, dialerScroll;
    private RecentCallsView recentCalls;
    private Button recentTab, dialerTab;
    private boolean showingRecent = true, resumed;
    private static final int REQUEST_HISTORY = 14;
    private LinearLayout ongoing;
    private boolean callAfterPermission;
    private String pendingNumber;
    private long lastCallAt;
    private static final int REQUEST_CONTACTS = 13;
    private final Runnable callListener = this::refreshOngoing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BLACK);
        getWindow().setNavigationBarColor(Ui.BLACK);
        buildUi();
        if (state != null) {
            number.setText(state.getString("number", ""));
            int start = Math.max(0, Math.min(number.length(), state.getInt("selectionStart", number.length())));
            int end = Math.max(0, Math.min(number.length(), state.getInt("selectionEnd", start)));
            number.setSelection(start, end);
            pendingNumber = state.getString("pendingNumber");
            callAfterPermission = state.getBoolean("callAfterPermission");
            showTab(state.getBoolean("showingRecent", true));
        } else acceptIntent(getIntent());
    }

    private void buildUi() {
        LinearLayout screen = Ui.vertical(this);
        screen.setBackgroundColor(Ui.BLACK);
        int pad = Ui.dp(this, 20);
        int vertical = Ui.dp(this, 12);
        screen.setPadding(pad, vertical, pad, vertical);
        if (Build.VERSION.SDK_INT >= 35) {
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(pad + bars.left, vertical + bars.top, pad + bars.right, vertical + bars.bottom);
                return insets;
            });
        }
        setContentView(screen);

        LinearLayout header = new LinearLayout(this);
        header.setBaselineAligned(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Teléfono", 28, Ui.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
        Button contacts = Ui.button(this, "Contactos", false);
        contacts.setTextSize(14);
        Ui.click(contacts, this::openContacts);
        header.addView(contacts, Ui.params(-2, Ui.dp(this, 48)));
        screen.addView(header);

        ongoing = Ui.vertical(this);
        ongoing.setBackground(Ui.background(Ui.PANEL, 18, this));
        ongoing.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams ongoingParams = Ui.params(-1, -2);
        ongoingParams.topMargin = Ui.dp(this, 8);
        screen.addView(ongoing, ongoingParams);
        ongoing.setVisibility(View.GONE);

        setup = Ui.vertical(this);
        setupScroll = new ScrollView(this);
        setupScroll.addView(setup);
        screen.addView(setupScroll, Ui.params(-1, -2));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        recentTab = Ui.button(this, "Recientes", true);
        dialerTab = Ui.button(this, "Teclado", false);
        LinearLayout.LayoutParams tabLeft = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        tabLeft.setMarginEnd(Ui.dp(this, 5));
        LinearLayout.LayoutParams tabRight = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        tabRight.setMarginStart(Ui.dp(this, 5));
        tabs.addView(recentTab, tabLeft); tabs.addView(dialerTab, tabRight);
        recentTab.setOnClickListener(v -> showTab(true));
        dialerTab.setOnClickListener(v -> showTab(false));
        screen.addView(tabs);

        FrameLayout pages = new FrameLayout(this);
        screen.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        recentCalls = new RecentCallsView(this, value -> {
            number.setText(value); number.setSelection(number.length()); showTab(false);
        }, this::requestHistoryPermission);
        pages.addView(recentCalls, new FrameLayout.LayoutParams(-1, -1));
        dialerScroll = new ScrollView(this);
        dialerScroll.setFillViewport(true);
        dialerScroll.setVerticalScrollBarEnabled(false);
        pages.addView(dialerScroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout dialer = Ui.vertical(this);
        dialer.setGravity(Gravity.BOTTOM);
        dialer.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        dialerScroll.addView(dialer, new ScrollView.LayoutParams(-1, -2));

        number = new EditText(this);
        number.setSingleLine(true); number.setTextSize(30);
        number.setTextColor(Ui.WHITE); number.setHintTextColor(Ui.MUTED);
        number.setHint("Número de teléfono"); number.setGravity(Gravity.CENTER);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        number.setShowSoftInputOnFocus(false);
        number.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        number.setSelectAllOnFocus(false);
        number.setContentDescription("Número de teléfono, editable");
        dialer.addView(number, Ui.params(-1, Ui.dp(this, 76)));
        String[][] labels = {{"1", ""}, {"2", "ABC"}, {"3", "DEF"}, {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"}, {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"}, {"*", ""}, {"0", "+"}, {"#", ""}};
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(this);
            for (int col = 0; col < 3; col++) {
                String digit = labels[row * 3 + col][0], letters = labels[row * 3 + col][1];
                LinearLayout key = Ui.vertical(this); key.setGravity(Gravity.CENTER);
                key.setBackground(Ui.background(Ui.PANEL, 24, this));
                key.setClickable(true); key.setFocusable(true);
                key.setSoundEffectsEnabled(false); key.setHapticFeedbackEnabled(false);
                key.setContentDescription(letters.isEmpty() ? digit : digit + ", " + letters);
                TextView digitView = Ui.text(this, digit, 28, Ui.WHITE);
                TextView lettersView = Ui.text(this, letters.isEmpty() ? " " : letters, 10, Ui.MUTED);
                lettersView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                digitView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                lettersView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                key.addView(digitView); key.addView(lettersView);
                Ui.click(key, () -> insert(digit));
                if (digit.equals("0")) key.setOnLongClickListener(v -> { insert("+"); return true; });
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1);
                kp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                line.addView(key, kp);
            }
            dialer.addView(line);
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setBaselineAligned(false);
        Button call = Ui.button(this, "Llamar", true);
        call.setContentDescription("Llamar al número introducido");
        Ui.click(call, this::requestCall);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 3);
        cp.setMargins(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
        actions.addView(call, cp);
        Button delete = Ui.button(this, "⌫", false); delete.setTextSize(24);
        delete.setContentDescription("Borrar último carácter; mantener pulsado para borrar todo");
        Ui.click(delete, this::deleteCharacter);
        delete.setOnLongClickListener(v -> { number.setText(""); return true; });
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1);
        dp.setMargins(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
        actions.addView(delete, dp); dialer.addView(actions);
        showTab(true);
    }

    private void showTab(boolean recent) {
        showingRecent = recent;
        recentCalls.setVisibility(recent ? View.VISIBLE : View.GONE);
        dialerScroll.setVisibility(recent ? View.GONE : View.VISIBLE);
        recentTab.setSelected(recent); dialerTab.setSelected(!recent);
        recentTab.setTextColor(recent ? Ui.BLACK : Ui.WHITE);
        dialerTab.setTextColor(recent ? Ui.WHITE : Ui.BLACK);
        recentTab.setBackground(Ui.background(recent ? Ui.WHITE : Ui.PANEL, 20, this));
        dialerTab.setBackground(Ui.background(recent ? Ui.PANEL : Ui.WHITE, 20, this));
        if (resumed && recent) recentCalls.onResume(); else recentCalls.onPause();
    }

    private void requestHistoryPermission() {
        if (!isDefaultDialer()) { requestDefaultDialer(); return; }
        if (getPreferences(MODE_PRIVATE).getBoolean("history_requested", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
            openAppSettings();
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("history_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, REQUEST_HISTORY);
        }
    }

    private void insert(String value) {
        int start = Math.max(0, number.getSelectionStart());
        int end = Math.max(0, number.getSelectionEnd());
        number.getText().replace(Math.min(start, end), Math.max(start, end), value);
    }

    private void deleteCharacter() {
        int start = Math.max(0, number.getSelectionStart());
        int end = Math.max(0, number.getSelectionEnd());
        if (start != end) number.getText().delete(Math.min(start, end), Math.max(start, end));
        else if (start > 0) number.getText().delete(start - 1, start);
    }

    private void requestCall() {
        if (number.getText().toString().trim().isEmpty()) {
            toast("Introduce un número");
            return;
        }
        if (callAfterPermission || SystemClock.elapsedRealtime() - lastCallAt < 1000) return;
        lastCallAt = SystemClock.elapsedRealtime();
        pendingNumber = number.getText().toString().trim();
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            callAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL);
            return;
        }
        placeCall(pendingNumber);
        pendingNumber = null;
    }

    private void placeCall(String value) {
        if (value == null) return;
        if (value.isEmpty()) return;
        TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
        try {
            if (telecom == null) { toast("No se puede iniciar la llamada"); return; }
            telecom.placeCall(Uri.fromParts("tel", value, null), new Bundle());
        } catch (SecurityException | IllegalArgumentException e) {
            toast("No se pudo iniciar la llamada");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_CALL && callAfterPermission) {
            callAfterPermission = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) placeCall(pendingNumber);
            else toast("Permite las llamadas para poder llamar");
            pendingNumber = null;
        }
        refreshSetup();
        if (resumed && showingRecent) recentCalls.onResume();
    }

    private void openContacts() {
        Intent preferred = new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI);
        preferred.setPackage("pe.gustavo.contactoslite");
        try { startActivity(preferred); return; } catch (ActivityNotFoundException | SecurityException ignored) { }
        try { startActivity(new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)); }
        catch (ActivityNotFoundException | SecurityException e) { toast("No hay una aplicación de contactos disponible"); }
    }

    private boolean isDefaultDialer() {
        TelecomManager manager = (TelecomManager) getSystemService(TELECOM_SERVICE);
        return manager != null && getPackageName().equals(manager.getDefaultDialerPackage());
    }

    private void requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = (RoleManager) getSystemService(ROLE_SERVICE);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), 12);
                    return;
                }
            }
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivityForResult(intent, 12);
        } catch (ActivityNotFoundException | SecurityException e) { toast("No se pudo abrir la configuración del teléfono predeterminado"); }
    }

    private void refreshSetup() {
        setup.removeAllViews();
        setupScroll.setVisibility(View.GONE);
        boolean dialer = isDefaultDialer();
        boolean calls = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = nm == null ? null : nm.getNotificationChannel("calls_silent_v1");
        boolean notificationsEnabled = nm != null && nm.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean fullScreen = Build.VERSION.SDK_INT < 34 || (nm != null && nm.canUseFullScreenIntent());
        if (dialer && calls && notifications && notificationsEnabled && fullScreen && contacts) return;
        setupScroll.setVisibility(View.VISIBLE);
        TextView note = Ui.text(this, "Configuración de llamadas y contactos:", 14, Ui.MUTED);
        note.setGravity(Gravity.START);
        LinearLayout.LayoutParams np = Ui.params(-1, -2);
        np.topMargin = Ui.dp(this, 14);
        setup.addView(note, np);
        if (!dialer) addSetupButton("Elegir como teléfono predeterminado", this::requestDefaultDialer);
        if (!calls) addSetupButton("Permitir llamadas", () -> {
            callAfterPermission = false;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL);
        });
        if (!notifications) addSetupButton("Permitir notificaciones", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                if (getPreferences(MODE_PRIVATE).getBoolean("notifications_requested", false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    openNotificationSettings();
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
                }
            }
        });
        else if (!notificationsEnabled) addSetupButton("Activar notificaciones de llamadas", this::openNotificationSettings);
        if (!contacts) addSetupButton("Mostrar nombres de contactos (opcional)", () -> {
            if (getPreferences(MODE_PRIVATE).getBoolean("contacts_requested", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                openAppSettings();
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("contacts_requested", true).apply();
                requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS);
            }
        });
        if (!fullScreen) addSetupButton("Permitir pantalla de llamadas", () -> {
            if (Build.VERSION.SDK_INT >= 34) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()))); }
                catch (ActivityNotFoundException | SecurityException e) { toast("Abre los ajustes de la aplicación para permitir llamadas en pantalla completa"); }
            }
        });
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) { openAppSettings(); }
    }

    private void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException | SecurityException e) { toast("No se pudieron abrir los ajustes de la aplicación"); }
    }

    private void addSetupButton(String label, Runnable action) {
        android.widget.Button button = Ui.button(this, label, false);
        LinearLayout.LayoutParams p = Ui.params(-1, Ui.dp(this, 48));
        p.topMargin = Ui.dp(this, 6);
        setup.addView(button, p);
        setupScroll.getLayoutParams().height = Math.min(Ui.dp(this, 180), Ui.dp(this, 48 + Math.max(0, setup.getChildCount() - 1) * 54));
        Ui.click(button, action);
    }

    private void refreshOngoing() {
        CallService service = CallService.getInstance();
        Call ringing = null;
        if (service != null) for (Call call : service.currentCalls()) {
            if (CallService.getCallState(call) == Call.STATE_RINGING) { ringing = call; break; }
        }
        ongoing.removeAllViews();
        if (ringing != null) {
            final Call incoming = ringing;
            TextView title = Ui.text(this, "Llamada entrante", 16, Ui.WHITE);
            title.setGravity(Gravity.START); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            ongoing.addView(title);
            String identity = incoming.getDetails() != null && incoming.getDetails().getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED
                    ? "Número privado" : service.displayName(incoming);
            TextView caller = Ui.text(this, identity, 14, Ui.MUTED);
            caller.setGravity(Gravity.START); caller.setSingleLine(true);
            caller.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ongoing.addView(caller);
            LinearLayout row = new LinearLayout(this);
            Button answer = Ui.button(this, "Responder", true);
            Button reject = Ui.button(this, "Rechazar", false);
            Ui.click(answer, () -> actOnIncoming(incoming, true));
            Ui.click(reject, () -> actOnIncoming(incoming, false));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
            left.setMarginEnd(Ui.dp(this, 4));
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
            right.setMarginStart(Ui.dp(this, 4));
            row.addView(answer, left); row.addView(reject, right); ongoing.addView(row);
            ongoing.setVisibility(View.VISIBLE);
        } else if (service != null && service.primaryCall() != null
                && CallService.getCallState(service.primaryCall()) != Call.STATE_DISCONNECTED) {
            Button back = Ui.button(this, "Volver a la llamada", true);
            Ui.click(back, () -> {
                CallService current = CallService.getInstance();
                if (current != null && current.primaryCall() != null) startActivity(new Intent(this, InCallActivity.class));
                else refreshOngoing();
            });
            ongoing.addView(back, Ui.params(-1, Ui.dp(this, 48)));
            ongoing.setVisibility(View.VISIBLE);
        } else ongoing.setVisibility(View.GONE);
    }

    private void actOnIncoming(Call incoming, boolean answer) {
        CallService current = CallService.getInstance();
        if (current == null || !current.currentCalls().contains(incoming)
                || CallService.getCallState(incoming) != Call.STATE_RINGING) {
            refreshOngoing();
            return;
        }
        if (answer) {
            current.answer(incoming);
            startActivity(new Intent(this, InCallActivity.class));
        } else current.reject(incoming);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshSetup();
        if (showingRecent) recentCalls.onResume();
        refreshOngoing();
        CallService.addDialerListener(callListener);
    }

    @Override protected void onPause() {
        resumed = false;
        recentCalls.onPause();
        CallService.removeDialerListener(callListener);
        super.onPause();
    }

    @Override protected void onDestroy() {
        recentCalls.destroy();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    private void acceptIntent(Intent intent) {
        if (intent == null || !(Intent.ACTION_DIAL.equals(intent.getAction()) || Intent.ACTION_VIEW.equals(intent.getAction()))) return;
        showTab(false);
        Uri uri = intent.getData();
        if (uri != null && "tel".equalsIgnoreCase(uri.getScheme())) {
            String value = uri.getSchemeSpecificPart();
            number.setText(value == null ? "" : value);
            number.setSelection(number.length());
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("showingRecent", showingRecent);
        outState.putString("number", number.getText().toString());
        outState.putInt("selectionStart", number.getSelectionStart());
        outState.putInt("selectionEnd", number.getSelectionEnd());
        outState.putString("pendingNumber", pendingNumber);
        outState.putBoolean("callAfterPermission", callAfterPermission);
        super.onSaveInstanceState(outState);
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
