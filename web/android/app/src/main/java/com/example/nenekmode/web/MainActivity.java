package com.example.nenekmode.web;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // plugin custom: jembatan JS -> mesin native asli (TTS/alarm/SMS/ModeTeman)
        registerPlugin(NenekNativePlugin.class);
    }
}
