#!/usr/bin/env python3
"""Verify old settings migrate into the short, tenth-second duration contract."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".test-build/settings"
JDK = Path.home() / "Library/Java/JavaVirtualMachines/temurin-17.0.18/Contents/Home/bin"
FIXTURES = {
    "android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences {
 int getInt(String key,int fallback); boolean getBoolean(String key,boolean fallback);
 void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences prefs,String key); }
}
""",
    "dev/thom/clipboardglass/SettingsContractTest.java": """package dev.thom.clipboardglass;
import java.util.HashMap;
import android.content.SharedPreferences;
public class SettingsContractTest {
 static class Prefs implements SharedPreferences {
  HashMap<String,Integer> values=new HashMap<>();
  public int getInt(String key,int fallback) { return values.getOrDefault(key,fallback); }
  public boolean getBoolean(String key,boolean fallback) { return fallback; }
  public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
 }
 static void check(boolean ok) { if (!ok) throw new AssertionError(); }
 public static void main(String[] args) {
  Prefs prefs=new Prefs(); check(ModuleSettings.durationMillis(prefs)==3000);
  prefs.values.put("duration",3); check(ModuleSettings.durationMillis(prefs)==3000);
  prefs.values.put("duration",5); check(ModuleSettings.durationMillis(prefs)==5000);
  for (int old:new int[]{0,10,12,3600}) {
   prefs.values.put("duration",old); check(ModuleSettings.durationMillis(prefs)==3000);
  }
  for (int millis=1000;millis<=5000;millis+=100) {
   prefs.values.put("duration_ms",millis); check(ModuleSettings.durationMillis(prefs)==millis);
  }
  prefs.values.put("duration_ms",2900); check(ModuleSettings.durationMillis(prefs)==2900);
  prefs.values.put("duration_ms",4100); check(ModuleSettings.durationMillis(prefs)==4100);
  prefs.values.put("duration_ms",500); check(ModuleSettings.durationMillis(prefs)==1000);
  prefs.values.put("duration_ms",3600000); check(ModuleSettings.durationMillis(prefs)==5000);
  System.out.println("Duration migration and all 41 tenth-second settings passed");
 }
}
""",
}
files = []
for name, source in FIXTURES.items():
    path = OUT / "src" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source)
    files.append(path)
classes = OUT / "classes"
classes.mkdir(parents=True, exist_ok=True)
subprocess.run([str(JDK / "javac"), "-d", str(classes),
                str(ROOT / "src/dev/thom/clipboardglass/ModuleSettings.java"),
                *map(str, files)], check=True)
subprocess.run([str(JDK / "java"), "-cp", str(classes),
                "dev.thom.clipboardglass.SettingsContractTest"], check=True)
