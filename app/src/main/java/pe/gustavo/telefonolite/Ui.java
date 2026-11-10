package pe.gustavo.telefonolite;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native UI helpers shared by the dialer screens. */
final class Ui {
    static final int BLACK = Color.BLACK;
    static final int PANEL = Color.rgb(25, 25, 25);
    static final int WHITE = Color.WHITE;
    static final int MUTED = Color.rgb(170, 170, 170);

    private Ui() { }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable background(int color, float radius, Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    static Button button(Context context, String label, boolean accent) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(accent ? BLACK : WHITE);
        button.setBackground(background(accent ? WHITE : PANEL, 20, context));
        button.setMinHeight(dp(context, 48));
        button.setSoundEffectsEnabled(false);
        button.setHapticFeedbackEnabled(false);
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return button;
    }

    static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static void click(View view, Runnable action) {
        view.setOnClickListener(v -> action.run());
    }
}
