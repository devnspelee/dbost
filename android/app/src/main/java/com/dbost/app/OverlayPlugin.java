package com.dbost.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Overlay")
public class OverlayPlugin extends Plugin {

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(getContext())) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName())
                );
                getActivity().startActivityForResult(intent, 1234);
                call.resolve();
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void hasPermission(PluginCall call) {
        boolean has = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            has = Settings.canDrawOverlays(getContext());
        }
        call.resolve(new com.getcapacitor.JSObject().put("value", has));
    }

    @PluginMethod
    public void show(PluginCall call) {
        Intent intent = new Intent(getContext(), OverlayService.class);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void hide(PluginCall call) {
        Intent intent = new Intent(getContext(), OverlayService.class);
        intent.setAction("STOP");
        getContext().startService(intent);
        call.resolve();
    }
}
