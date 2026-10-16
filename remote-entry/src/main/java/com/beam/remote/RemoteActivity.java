package com.beam.remote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens Beam Mod's «Кнопки пульта» page in place of tonisaf's module (see build.gradle.kts). */
public final class RemoteActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent()
                .setClassName("com.home.tiles", "com.home.tiles.RemoteButtonsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Установите Beam Mod", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}