#!/usr/bin/env python3
"""Exercise the real observer with failed launches, unfocused windows and stale callbacks."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".test-build/editor-visibility"
JDK = Path.home() / "Library/Java/JavaVirtualMachines/temurin-17.0.18/Contents/Home/bin"
FIXTURES = {
    "android/os/Bundle.java": "package android.os; public class Bundle {}",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }",
    "android/os/Handler.java": """package android.os;
import java.util.LinkedHashMap;
public class Handler {
 static long now; static LinkedHashMap<Runnable,Long> queue=new LinkedHashMap<>();
 public Handler(Looper l) {}
 public boolean post(Runnable r){queue.put(r,now);return true;}
 public boolean postDelayed(Runnable r,long delay){queue.put(r,now+delay);return true;}
 public void removeCallbacks(Runnable r){queue.remove(r);}
 public static void advance(long delta){
  long target=now+delta;
  while(true){Runnable next=null;long when=Long.MAX_VALUE;
   for(var e:queue.entrySet())if(e.getValue()<when){next=e.getKey();when=e.getValue();}
   if(next==null || when>target)break;
   now=when;queue.remove(next);next.run();
  }now=target;
 }
}
""",
    "android/content/ComponentName.java": """package android.content;
public class ComponentName {
 final String value; public ComponentName(String value){this.value=value;}
 public boolean equals(Object o){return o instanceof ComponentName && value.equals(((ComponentName)o).value);}
 public int hashCode(){return value.hashCode();}
}
""",
    "android/content/Context.java": """package android.content;
public class Context { public Context application; public Context getApplicationContext(){return application==null?this:application;} }
""",
    "android/view/View.java": """package android.view;
public class View { public boolean attached=true,shown=true,focused;
 public boolean isAttachedToWindow(){return attached;} public boolean isShown(){return shown;} public boolean hasWindowFocus(){return focused;} }
""",
    "android/view/Window.java": """package android.view;
public class Window { public View decor=new View(); public View getDecorView(){return decor;} }
""",
    "android/app/Activity.java": """package android.app;
public class Activity extends android.content.Context {
 public boolean finishing,destroyed; public android.view.Window window=new android.view.Window();
 public android.content.ComponentName component;
 public Activity(android.content.ComponentName c){component=c;}
 public android.content.ComponentName getComponentName(){return component;}
 public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;}
 public android.view.Window getWindow(){return window;}
}
""",
    "android/app/Application.java": """package android.app;
public class Application extends android.content.Context {
 public ActivityLifecycleCallbacks callbacks;
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks=c;}
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a,android.os.Bundle b); void onActivityStarted(Activity a);
  void onActivityResumed(Activity a); void onActivityPaused(Activity a); void onActivityStopped(Activity a);
  void onActivitySaveInstanceState(Activity a,android.os.Bundle b); void onActivityDestroyed(Activity a);
 }
}
""",
    "dev/thom/clipboardglass/EditorVisibilityTest.java": """package dev.thom.clipboardglass;
import android.app.*;import android.content.*;import android.os.Handler;
import java.util.ArrayList;
public class EditorVisibilityTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  Application app=new Application();Context context=new Context();context.application=app;
  ComponentName editor=new ComponentName("native.editor"),home=new ComponentName("launcher");
  ArrayList<Object> closed=new ArrayList<>();EditorVisibility observer=new EditorVisibility(closed::add, s->{});
  Object first=new Object();check(observer.prepare(first,context,editor));
  check(observer.waiting(first));
  Handler.advance(500);check(closed.isEmpty()); // accepted request without an editor never closes
  Activity launcher=new Activity(home);launcher.window.decor.focused=true;
  app.callbacks.onActivityResumed(launcher);Handler.advance(100);check(closed.isEmpty());
  Activity nativeEditor=new Activity(editor);
  app.callbacks.onActivityResumed(nativeEditor);Handler.advance(100);check(closed.isEmpty());
  nativeEditor.window.decor.focused=true;nativeEditor.window.decor.attached=false;
  Handler.advance(100);check(closed.isEmpty()); // focus alone is not a shown window
  nativeEditor.window.decor.attached=true;nativeEditor.finishing=true;
  Handler.advance(100);check(closed.isEmpty()); // premature native finish keeps the card
  observer.cancel(first);check(!observer.waiting(first));nativeEditor.finishing=false;
  app.callbacks.onActivityResumed(nativeEditor);Handler.advance(100);check(closed.isEmpty());
  Object second=new Object();observer.prepare(second,context,editor);
  app.callbacks.onActivityResumed(nativeEditor);Handler.advance(0);
  check(closed.size()==1 && closed.get(0)==second);
  check(!observer.waiting(second));
  Handler.advance(500);check(closed.size()==1); // once only
  Object third=new Object();observer.prepare(third,context,editor);
  nativeEditor.window.decor.focused=false;app.callbacks.onActivityResumed(nativeEditor);
  Handler.advance(50);app.callbacks.onActivityPaused(nativeEditor);
  nativeEditor.window.decor.focused=true;Handler.advance(500);check(closed.size()==1);
  observer.cancel(third);app.callbacks.onActivityResumed(nativeEditor);
  Handler.advance(500);check(closed.size()==1); // old callbacks after expiry cannot close a later card
  System.out.println("Editor visibility: failed request, home, missing focus, early finish, expiry and single commit passed");
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
                str(ROOT / "src/dev/thom/clipboardglass/EditorVisibility.java"),
                *map(str, files)], check=True)
subprocess.run([str(JDK / "java"), "-cp", str(classes),
                "dev.thom.clipboardglass.EditorVisibilityTest"], check=True)
