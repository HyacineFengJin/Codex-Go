package dev.codex.go.testdriver;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Xml;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;

/** Self-targeted instrumentation: never restarts Codex Go or suppresses accessibility. */
public final class UiDriver extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) throw new SecurityException("Disposable emulators only");
            if (arguments.containsKey("faultService")) {
                getContext().getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(getContext(), FaultPluginService.class),
                    "enabled".equals(arguments.getString("faultService")) ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
                result.putString("componentChanged", "true"); finish(Activity.RESULT_OK, result); return;
            }
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            try { automation.waitForIdle(100, 2000); } catch (Exception ignored) {}
            if (arguments.containsKey("setTextBase64") || "true".equals(arguments.getString("clearText"))) {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                AccessibilityNodeInfo editor = root == null ? null : editable(root, true);
                if (editor == null && root != null) editor = editable(root, false);
                if (editor == null) throw new IllegalStateException("No visible editable field");
                Bundle change = new Bundle();
                change.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "true".equals(arguments.getString("clearText")) ? "" : new String(android.util.Base64.decode(arguments.getString("setTextBase64"), 0), java.nio.charset.StandardCharsets.UTF_8));
                boolean changed = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, change);
                editor.recycle(); root.recycle();
                if (!changed) throw new IllegalStateException("Set text action rejected");
                result.putString("textSet", "true"); finish(Activity.RESULT_OK, result); return;
            }
            StringWriter output = new StringWriter();
            XmlSerializer xml = Xml.newSerializer(); xml.setOutput(output);
            xml.startTag(null, "hierarchy");
            java.util.List<AccessibilityWindowInfo> windows = automation.getWindows();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) { writeNode(xml, root, 0); root.recycle(); }
                window.recycle();
            }
            if (windows.isEmpty()) {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) { writeNode(xml, root, 0); root.recycle(); }
            }
            xml.endTag(null, "hierarchy"); xml.flush();
            result.putString("xml", output.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) { result.putString("error", error.toString()); finish(Activity.RESULT_CANCELED, result); }
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo node, boolean focused) {
        if (node.isVisibleToUser() && node.isEnabled() && node.isEditable() && (!focused || node.isFocused())) return AccessibilityNodeInfo.obtain(node);
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = editable(child, focused); child.recycle();
            if (found != null) return found;
        }
        return null;
    }
    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node, int depth) throws Exception {
        if (depth > 60 || !node.isVisibleToUser()) return;
        xml.startTag(null, "node");
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "content-desc", node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        xml.attribute(null, "state-desc", node.getStateDescription() == null ? "" : node.getStateDescription().toString());
        xml.attribute(null, "class", node.getClassName() == null ? "" : node.getClassName().toString());
        xml.attribute(null, "package", node.getPackageName() == null ? "" : node.getPackageName().toString());
        xml.attribute(null, "resource-id", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
        xml.attribute(null, "password", Boolean.toString(node.isPassword()));
        xml.attribute(null, "checkable", Boolean.toString(node.isCheckable()));
        xml.attribute(null, "checked", Boolean.toString(node.isChecked()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        xml.attribute(null, "bounds", "["+bounds.left+","+bounds.top+"]["+bounds.right+","+bounds.bottom+"]");
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { writeNode(xml, child, depth+1); child.recycle(); }
        }
        xml.endTag(null, "node");
    }
}
