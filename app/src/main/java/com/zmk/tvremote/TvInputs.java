package com.zmk.tvremote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TV's inputs (HDMI, AV, tuner) through Android's TV input framework, and how to switch to one.
 * Mirrors Glass TV's Inputs menu, which is tested on Xiaomi, TCL and Sony sets: switching is ACTION_VIEW
 * on the input's channel URI, which the TV's Live TV app handles. A streaming box usually has none.
 */
final class TvInputs {
    static final String CHANNEL_TYPE = "vnd.android.cursor.item/channel";

    static final class Input {
        String id, label, kind, state;
        boolean passthrough;
    }

    private TvInputs() {
    }

    private static TvInputManager manager(Context ctx) {
        try {
            return (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Inputs a person can switch to: HDMI first, then the rest, each by its (possibly renamed) label. */
    static List<Input> list(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = manager(ctx);
        if (tim == null) return out;
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (Exception e) {
            return out;
        }
        for (TvInputInfo info : all) {
            // A child input (an HDMI-CEC device behind a port) is reached through its port.
            if (info.getParentId() != null) continue;
            Input in = new Input();
            in.id = info.getId();
            CharSequence custom = info.loadCustomLabel(ctx);
            in.label = String.valueOf(custom != null && custom.length() > 0 ? custom : info.loadLabel(ctx));
            in.kind = kind(info.getType());
            in.passthrough = info.isPassthroughInput();
            in.state = state(tim, info.getId());
            out.add(in);
        }
        Collections.sort(out, (a, b) -> {
            int ra = rank(a.kind), rb = rank(b.kind);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    static Input find(Context ctx, String id) {
        if (id == null) return null;
        for (Input in : list(ctx)) if (in.id.equals(id)) return in;
        return null;
    }

    static Uri uri(String id) {
        return TvContract.buildChannelUriForPassthroughInput(id);
    }

    /** The app that would take an input switch, as "package/activity", or null when none would. */
    static String switcher(Context ctx) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri("probe"), CHANNEL_TYPE);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        // "android/…ResolverActivity" means several apps tie and Android would ask: still switchable.
        return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    }

    /** Everything the TV reports, for the phone page's TV info panel. */
    static JSONObject info(Context ctx) throws Exception {
        JSONObject o = new JSONObject()
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("device", Build.DEVICE)
                .put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
                .put("tvInputService", manager(ctx) != null)
                .put("leanback", ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                .put("liveTv", ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LIVE_TV));
        String sw = switcher(ctx);
        o.put("switcher", sw == null ? JSONObject.NULL : sw);
        JSONArray inputs = new JSONArray();
        TvInputManager tim = manager(ctx);
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    inputs.put(new JSONObject()
                            .put("id", info.getId())
                            .put("label", String.valueOf(info.loadLabel(ctx)))
                            .put("type", kind(info.getType()))
                            .put("passthrough", info.isPassthroughInput())
                            .put("parent", info.getParentId() == null ? JSONObject.NULL : info.getParentId())
                            .put("state", state(tim, info.getId())));
                }
            } catch (Exception e) {
                o.put("error", e.toString());
            }
        }
        o.put("inputs", inputs);
        return o;
    }

    private static String state(TvInputManager tim, String id) {
        try {
            switch (tim.getInputState(id)) {
                case TvInputManager.INPUT_STATE_CONNECTED:
                    return "connected";
                case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                    return "standby";
                case TvInputManager.INPUT_STATE_DISCONNECTED:
                    return "disconnected";
                default:
                    return "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String kind(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI:
                return "hdmi";
            case TvInputInfo.TYPE_TUNER:
                return "tuner";
            case TvInputInfo.TYPE_COMPOSITE:
                return "av";
            case TvInputInfo.TYPE_COMPONENT:
                return "component";
            case TvInputInfo.TYPE_SVIDEO:
                return "s-video";
            case TvInputInfo.TYPE_SCART:
                return "scart";
            case TvInputInfo.TYPE_VGA:
                return "vga";
            case TvInputInfo.TYPE_DVI:
                return "dvi";
            case TvInputInfo.TYPE_DISPLAY_PORT:
                return "displayport";
            default:
                return "other";
        }
    }

    private static int rank(String kind) {
        switch (kind) {
            case "hdmi":
                return 0;
            case "av":
            case "component":
            case "s-video":
            case "scart":
            case "vga":
            case "dvi":
            case "displayport":
                return 1;
            case "tuner":
                return 2;
            default:
                return 3;
        }
    }
}
