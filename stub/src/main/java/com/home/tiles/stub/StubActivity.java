package com.home.tiles.stub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Forwards the shortcut key to Tiles, which runs the action assigned in its panel. */
public class StubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent()
                .setClassName("com.home.tiles", "com.home.tiles.RemoteButtonActivity")
                .putExtra("index", BuildConfig.BUTTON)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            // Tiles not installed: nothing to do.
        }
        finish();
    }
}
