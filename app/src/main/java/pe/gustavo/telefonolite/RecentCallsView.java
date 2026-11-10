package pe.gustavo.telefonolite;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.provider.CallLog;
import android.telephony.PhoneNumberUtils;
import android.text.format.DateFormat;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Read-only, visible-only view of the system call log. */
final class RecentCallsView extends LinearLayout {
    private static final String[] PROJECTION = {
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER_PRESENTATION
    };
    private final Activity activity;
    private final Consumer<String> onSelectNumber;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TextView status;
    private final Button permissionButton;
    private final ListView list;
    private final CallsAdapter adapter = new CallsAdapter();
    private final Runnable refresh = this::load;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 200);
        }
    };
    private boolean visible;
    private boolean observing;
    private boolean destroyed;
    private int generation;
    private CancellationSignal signal;
    private java.text.DateFormat dateFormat;
    private java.text.DateFormat timeFormat;

    RecentCallsView(Activity activity, Consumer<String> onSelectNumber, Runnable onRequestPermission) {
        super(activity);
        this.activity = activity;
        this.onSelectNumber = onSelectNumber;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BLACK);
        int pad = Ui.dp(activity, 16);
        setPadding(pad, pad, pad, 0);
        TextView heading = Ui.text(activity, "Últimas 100 llamadas", 18, Ui.WHITE);
        heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(activity, 44)));
        status = Ui.text(activity, "", 16, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        permissionButton = Ui.button(activity, "Permitir historial", false);
        permissionButton.setOnClickListener(v -> onRequestPermission.run());
        addView(permissionButton, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(activity, 48)));
        list = new ListView(activity);
        list.setBackgroundColor(Ui.BLACK);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(55, 55, 55)));
        list.setDividerHeight(Ui.dp(activity, 1));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String number = adapter.rows.get(position).dialable;
            if (number != null) onSelectNumber.accept(number);
        });
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    public void onResume() {
        if (destroyed) return;
        visible = true;
        dateFormat = DateFormat.getDateFormat(activity);
        timeFormat = DateFormat.getTimeFormat(activity);
        if (hasPermission()) {
            try {
                if (!observing) {
                    activity.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer);
                    observing = true;
                }
                load();
            } catch (SecurityException e) {
                showPermission();
            } catch (RuntimeException e) {
                showError();
            }
        } else {
            showPermission();
        }
    }

    public void onPause() {
        visible = false;
        handler.removeCallbacks(refresh);
        cancelQuery();
        if (observing) {
            activity.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
    }

    public void destroy() {
        onPause();
        destroyed = true;
        executor.shutdownNow();
    }

    private boolean hasPermission() {
        return activity.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private void cancelQuery() {
        generation++;
        if (signal != null) {
            signal.cancel();
            signal = null;
        }
    }

    private void load() {
        if (!visible || destroyed) return;
        if (!hasPermission()) { showPermission(); return; }
        cancelQuery();
        final int request = generation;
        final CancellationSignal cancellation = new CancellationSignal();
        signal = cancellation;
        permissionButton.setVisibility(GONE);
        if (adapter.rows.isEmpty()) {
            status.setText("Cargando historial…");
            status.setVisibility(VISIBLE);
        }
        Uri uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "100").build();
        executor.execute(() -> {
            List<Row> rows = new ArrayList<>();
            String error = null;
            try (Cursor cursor = activity.getContentResolver().query(uri, PROJECTION, null, null,
                    CallLog.Calls.DATE + " DESC", cancellation)) {
                if (cursor == null) throw new IllegalStateException("Call log provider unavailable");
                while (cursor.moveToNext() && rows.size() < 100) {
                    rows.add(new Row(cursor));
                }
            } catch (OperationCanceledException e) {
                return;
            } catch (SecurityException e) {
                error = "permission";
            } catch (RuntimeException e) {
                error = "provider";
            }
            final String resultError = error;
            handler.post(() -> {
                if (!visible || destroyed || generation != request) return;
                signal = null;
                if ("permission".equals(resultError)) { showPermission(); return; }
                if (resultError != null) { showError(); return; }
                int first = list.getFirstVisiblePosition();
                View child = list.getChildAt(0);
                int top = child == null ? 0 : child.getTop();
                adapter.rows = rows;
                adapter.notifyDataSetChanged();
                if (!rows.isEmpty()) list.setSelectionFromTop(Math.min(first, rows.size() - 1), top);
                status.setText(rows.isEmpty() ? "Aún no hay llamadas registradas" : "");
                status.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
                list.setVisibility(VISIBLE);
            });
        });
    }

    private void showPermission() {
        cancelQuery();
        if (observing) {
            activity.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
        adapter.rows = new ArrayList<>();
        adapter.notifyDataSetChanged();
        status.setText("Permite el acceso para ver el historial de llamadas");
        status.setVisibility(VISIBLE);
        permissionButton.setVisibility(VISIBLE);
        list.setVisibility(GONE);
    }

    private void showError() {
        cancelQuery();
        status.setText("No se pudo cargar el historial de llamadas");
        status.setVisibility(VISIBLE);
        permissionButton.setVisibility(GONE);
        list.setVisibility(GONE);
    }

    private static final class Row {
        final long id, date, duration;
        final int type;
        final String title, dialable;
        Row(Cursor c) {
            id = c.getLong(0);
            String number = c.getString(1);
            type = c.getInt(2);
            date = c.getLong(3);
            duration = c.getLong(4);
            String name = c.getString(5);
            int presentation = c.getInt(6);
            String normalized = number == null ? "" : PhoneNumberUtils.normalizeNumber(number);
            String trimmed = number == null ? "" : number.trim();
            boolean legacySentinel = trimmed.equals("-1") || trimmed.equals("-2") || trimmed.equals("-3");
            boolean allowed = presentation == CallLog.Calls.PRESENTATION_ALLOWED && !legacySentinel
                    && !normalized.isEmpty() && !normalized.equals("+")
                    && PhoneNumberUtils.isGlobalPhoneNumber(number);
            dialable = allowed ? number : null;
            title = allowed ? (name != null && !name.trim().isEmpty() ? name : number)
                    : presentation == CallLog.Calls.PRESENTATION_RESTRICTED ? "Número privado" : "Número desconocido";
        }
    }

    private final class CallsAdapter extends BaseAdapter {
        List<Row> rows = new ArrayList<>();
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).id; }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean isEnabled(int position) { return rows.get(position).dialable != null; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Ui.dp(activity, 72));
                int horizontal = Ui.dp(activity, 12);
                int vertical = Ui.dp(activity, 12);
                row.setPadding(horizontal, vertical, horizontal, vertical);
                android.util.TypedValue selectable = new android.util.TypedValue();
                if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                    row.setBackgroundResource(selectable.resourceId);
                }
                TextView arrowView = Ui.text(activity, "", 20, Ui.MUTED);
                arrowView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                arrowView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(arrowView, new LayoutParams(Ui.dp(activity, 32), LayoutParams.WRAP_CONTENT));
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(VERTICAL);
                TextView title = Ui.text(activity, "", 17, Ui.WHITE);
                title.setGravity(Gravity.START);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                title.setMaxLines(1);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                TextView details = Ui.text(activity, "", 14, Ui.MUTED);
                details.setGravity(Gravity.START);
                details.setMaxLines(2);
                details.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                content.addView(title);
                content.addView(details);
                row.addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                holder = new ViewHolder(arrowView, title, details);
                row.setTag(holder);
                convertView = row;
            } else holder = (ViewHolder) convertView.getTag();
            Row item = rows.get(position);
            String kind;
            String arrow;
            switch (item.type) {
                case CallLog.Calls.INCOMING_TYPE: kind = "Entrante"; arrow = "↙"; break;
                case CallLog.Calls.OUTGOING_TYPE: kind = "Saliente"; arrow = "↗"; break;
                case CallLog.Calls.MISSED_TYPE: kind = "Perdida"; arrow = "↙"; break;
                case CallLog.Calls.REJECTED_TYPE: kind = "Rechazada"; arrow = "↙"; break;
                case CallLog.Calls.BLOCKED_TYPE: kind = "Bloqueada"; arrow = "↙"; break;
                default: kind = "Llamada"; arrow = "•";
            }
            Date date = new Date(item.date);
            String when = dateFormat.format(date) + " " + timeFormat.format(date);
            long seconds = item.duration % 60;
            String duration = item.duration > 0 ? " · " + (item.duration / 60) + ":"
                    + (seconds < 10 ? "0" : "") + seconds : "";
            holder.arrow.setText(arrow);
            holder.title.setText(item.title);
            holder.details.setText(kind + " · " + when + duration);
            convertView.setContentDescription(item.title + ", " + kind + ", " + when
                    + (item.duration > 0 ? ", duración " + item.duration + " segundos" : "")
                    + (item.dialable != null ? ", abrir en el marcador" : ""));
            return convertView;
        }
    }

    private static final class ViewHolder {
        final TextView arrow, title, details;
        ViewHolder(TextView arrow, TextView title, TextView details) {
            this.arrow = arrow;
            this.title = title;
            this.details = details;
        }
    }
}
